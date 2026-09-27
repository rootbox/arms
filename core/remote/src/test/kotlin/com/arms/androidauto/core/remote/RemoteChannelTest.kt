package com.arms.androidauto.core.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteChannelTest {
    private val pairing = Pairing(
        pairId = "0123456789ab",
        key = ByteArray(32) { (it * 7).toByte() },
        brokerUrl = "wss://broker.test/mqtt",
        username = "u",
        password = "p",
    )
    private val topics = pairing.topics
    private val broker = InMemoryBroker()

    // 가짜 시계: 테스트가 시간을 직접 움직인다.
    private var now = 1_000_000L
    private val clock: () -> Long = { now }

    private val sampleState = HostState(
        mediaId = "station:2",
        title = "CBS 음악FM",
        artist = "93.9",
        isPlaying = true,
        playbackState = 3,
        artworkRef = "station:2",
        bluetooth = BluetoothStatus(connected = true, deviceName = "JBL", changedAtMs = 999_000L),
        batteryPercent = 100,
        updatedAtMs = 1_000_000L,
        items = listOf(RemoteItem("station:1", "A", "a"), RemoteItem("station:2", "B", "b")),
    )

    private fun TestScope.host(): Pair<RemoteChannel, InMemoryRemoteTransport> {
        val t = InMemoryRemoteTransport(broker)
        return RemoteChannel(pairing, t, this, clock = clock) to t
    }

    private fun TestScope.guest(): Pair<RemoteChannel, InMemoryRemoteTransport> {
        val t = InMemoryRemoteTransport(broker)
        return RemoteChannel(pairing, t, this, clock = clock) to t
    }

    private fun <T> TestScope.collectInto(flow: kotlinx.coroutines.flow.Flow<T>, into: MutableList<T>): Job {
        val job = launch { flow.collect { into.add(it) } }
        runCurrent() // 구독이 시작된 뒤에 publish 해야 SharedFlow가 놓치지 않는다.
        return job
    }

    @Test
    fun `guest command reaches host, host ack reaches guest`() = runTest {
        val (host, _) = host()
        val (guest, _) = guest()
        host.start(asHost = true)
        guest.start(asHost = false)

        val commands = mutableListOf<RemoteMessage.Command>()
        val acks = mutableListOf<RemoteMessage.Ack>()
        val j1 = collectInto(host.commands, commands)
        val j2 = collectInto(guest.acks, acks)

        val seq = guest.sendCommand(RemoteCommand.Select("station:2"))
        runCurrent()
        assertEquals(1, commands.size)
        assertEquals(RemoteCommand.Select("station:2"), commands[0].command)
        assertEquals(seq, commands[0].seq)

        host.sendAck(commands[0].seq, ok = true, message = null)
        runCurrent()
        assertEquals(1, acks.size)
        assertEquals(seq, acks[0].ackSeq)
        assertTrue(acks[0].ok)

        assertEquals(0, host.rejectedCount.value)
        assertEquals(0, guest.rejectedCount.value)
        j1.cancel(); j2.cancel()
    }

    @Test
    fun `host state reaches guest and is retained on the broker`() = runTest {
        val (host, _) = host()
        val (guest, _) = guest()
        host.start(asHost = true)
        guest.start(asHost = false)
        val states = mutableListOf<HostState>()
        val job = collectInto(guest.states, states)

        host.publishState(sampleState)
        runCurrent()
        assertEquals(listOf(sampleState), states)

        val retained = broker.retained(topics.state)
        assertTrue("state must be retained", retained != null && retained.isNotEmpty())
        assertNull("cmd/ack are never retained", broker.retained(topics.cmd))
        // 브로커에는 암호문만 있다.
        assertTrue(!String(retained!!, Charsets.ISO_8859_1).contains("CBS"))
        job.cancel()
    }

    @Test
    fun `guest joining an hour later still gets the retained state`() = runTest {
        val (host, _) = host()
        host.start(asHost = true)
        host.publishState(sampleState)

        now += 60L * 60L * 1000L // 한 시간 뒤
        val (guest, _) = guest()
        val states = mutableListOf<HostState>()
        val job = collectInto(guest.states, states)
        guest.start(asHost = false)
        runCurrent()
        assertEquals(listOf(sampleState), states)
        assertEquals(0, guest.rejectedCount.value)
        job.cancel()
    }

    @Test
    fun `states replays the latest value to a late collector, commands and acks do not`() = runTest {
        val (host, _) = host()
        host.start(asHost = true)
        host.publishState(sampleState)
        val (guest, _) = guest()
        guest.start(asHost = false) // retained state가 지금 도착하지만 아직 아무도 collect 하지 않는다.
        runCurrent()
        val states = mutableListOf<HostState>()
        val job = collectInto(guest.states, states)
        assertEquals(listOf(sampleState), states)

        // 명령/응답은 replay 되지 않는다.
        guest.sendCommand(RemoteCommand.Play)
        host.sendAck(1, true)
        runCurrent()
        val lateCommands = mutableListOf<RemoteMessage.Command>()
        val lateAcks = mutableListOf<RemoteMessage.Ack>()
        val j2 = collectInto(host.commands, lateCommands)
        val j3 = collectInto(guest.acks, lateAcks)
        assertEquals(0, lateCommands.size)
        assertEquals(0, lateAcks.size)
        job.cancel(); j2.cancel(); j3.cancel()
    }

    @Test
    fun `start and sendCommand propagate transport failures`() = runTest {
        val failing = object : RemoteTransport {
            override val connectionState = kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.DISCONNECTED)
            override suspend fun connect() { throw java.io.IOException("connect refused") }
            override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean) { throw java.io.IOException("not connected") }
            override suspend fun subscribe(topic: String, onMessage: (ByteArray) -> Unit) = Unit
            override suspend fun disconnect() = Unit
        }
        val channel = RemoteChannel(pairing, failing, this, clock = clock)
        assertTrue(runCatching { channel.start(asHost = false) }.exceptionOrNull() is java.io.IOException)
        assertTrue(runCatching { channel.sendCommand(RemoteCommand.Play) }.exceptionOrNull() is java.io.IOException)
    }

    @Test
    fun `stale command outside the window is rejected but state is not`() = runTest {
        val (host, _) = host()
        val (guest, _) = guest()
        host.start(asHost = true)
        guest.start(asHost = false)
        val commands = mutableListOf<RemoteMessage.Command>()
        val states = mutableListOf<HostState>()
        val j1 = collectInto(host.commands, commands)
        val j2 = collectInto(guest.states, states)

        // 게스트 시계는 그대로, 호스트 시계만 10분 앞으로: 게스트가 보낸 명령은 호스트 창 밖.
        val guestClock = now
        val (guest2, _) = run {
            val t = InMemoryRemoteTransport(broker)
            RemoteChannel(pairing, t, this, clock = { guestClock }) to t
        }
        guest2.start(asHost = false)
        now += 10L * 60L * 1000L
        guest2.sendCommand(RemoteCommand.Play)
        runCurrent()
        assertEquals(0, commands.size)
        assertEquals(1, host.rejectedCount.value)

        // 같은 시계 차이라도 state는 통과한다.
        host.publishState(sampleState)
        runCurrent()
        assertEquals(1, states.size)
        assertEquals(0, guest.rejectedCount.value)
        j1.cancel(); j2.cancel()
    }

    @Test
    fun `replayed command is rejected`() = runTest {
        val (host, _) = host()
        val (guest, _) = guest()
        host.start(asHost = true)
        guest.start(asHost = false)
        val commands = mutableListOf<RemoteMessage.Command>()
        val job = collectInto(host.commands, commands)

        guest.sendCommand(RemoteCommand.Next)
        runCurrent()
        assertEquals(1, commands.size)

        // 브로커/중간자가 같은 암호문을 다시 보낸다.
        val (topic, payload, _) = broker.published.last { it.first == topics.cmd }
        broker.publish(topic, payload, false)
        runCurrent()
        assertEquals(1, commands.size)
        assertEquals(1, host.rejectedCount.value)
        job.cancel()
    }

    @Test
    fun `tampered payload only bumps rejectedCount`() = runTest {
        val (host, _) = host()
        val (guest, _) = guest()
        host.start(asHost = true)
        guest.start(asHost = false)
        val commands = mutableListOf<RemoteMessage.Command>()
        val states = mutableListOf<HostState>()
        val j1 = collectInto(host.commands, commands)
        val j2 = collectInto(guest.states, states)

        broker.publish(topics.cmd, "garbage".toByteArray(), false)
        broker.publish(topics.cmd, ByteArray(64) { 0x41 }, false)
        runCurrent()
        assertEquals(0, commands.size)
        assertEquals(2, host.rejectedCount.value)

        // 정상 암호문을 한 비트 뒤집어서.
        guest.sendCommand(RemoteCommand.Pause)
        runCurrent()
        assertEquals(1, commands.size)
        val (_, payload, _) = broker.published.last { it.first == topics.cmd }
        val flipped = payload.copyOf().also { it[20] = (it[20].toInt() xor 1).toByte() }
        broker.publish(topics.cmd, flipped, false)
        runCurrent()
        assertEquals(1, commands.size)
        assertEquals(3, host.rejectedCount.value)

        // 다른 토픽으로 재생(aad 불일치): cmd 암호문을 state 토픽에.
        broker.publish(topics.state, payload, true)
        runCurrent()
        assertEquals(0, states.size)
        assertEquals(1, guest.rejectedCount.value)
        j1.cancel(); j2.cancel()
    }

    @Test
    fun `wrong key cannot inject commands`() = runTest {
        val (host, _) = host()
        host.start(asHost = true)
        val commands = mutableListOf<RemoteMessage.Command>()
        val job = collectInto(host.commands, commands)

        val attacker = RemoteChannel(
            pairing.copy(key = ByteArray(32) { 1 }),
            InMemoryRemoteTransport(broker),
            this,
            clock = clock,
        )
        attacker.start(asHost = false)
        attacker.sendCommand(RemoteCommand.Stop)
        runCurrent()
        assertEquals(0, commands.size)
        assertEquals(1, host.rejectedCount.value)
        job.cancel()
    }

    @Test
    fun `clearState removes the retained state`() = runTest {
        val (host, _) = host()
        host.start(asHost = true)
        host.publishState(sampleState)
        assertTrue(broker.retained(topics.state) != null)
        host.clearState()
        assertNull(broker.retained(topics.state))

        // 늦게 들어온 게스트는 아무것도 받지 않고 거부 카운트도 오르지 않는다.
        val (guest, _) = guest()
        val states = mutableListOf<HostState>()
        val job = collectInto(guest.states, states)
        guest.start(asHost = false)
        runCurrent()
        assertEquals(0, states.size)
        assertEquals(0, guest.rejectedCount.value)
        job.cancel()
    }

    @Test
    fun `host only subscribes to cmd and guest only to state and ack`() = runTest {
        val (host, _) = host()
        val (guest, _) = guest()
        host.start(asHost = true)
        guest.start(asHost = false)
        val hostCmds = mutableListOf<RemoteMessage.Command>()
        val hostAcks = mutableListOf<RemoteMessage.Ack>()
        val guestCmds = mutableListOf<RemoteMessage.Command>()
        val jobs = listOf(
            collectInto(host.commands, hostCmds),
            collectInto(host.acks, hostAcks),
            collectInto(guest.commands, guestCmds),
        )
        guest.sendCommand(RemoteCommand.Refresh)
        host.sendAck(1, true)
        runCurrent()
        assertEquals(1, hostCmds.size)
        assertEquals(0, hostAcks.size)
        assertEquals(0, guestCmds.size)
        jobs.forEach { it.cancel() }
    }

    @Test
    fun `seq keeps increasing across restarts`() = runTest {
        val (guest1, _) = guest()
        guest1.start(asHost = false)
        val s1 = guest1.sendCommand(RemoteCommand.Play)
        val s2 = guest1.sendCommand(RemoteCommand.Play)
        assertTrue(s2 > s1)
        guest1.stop()

        now += 5_000
        val (guest2, _) = guest()
        guest2.start(asHost = false)
        val s3 = guest2.sendCommand(RemoteCommand.Play)
        assertTrue("restarted guest must not reuse old seq", s3 > s2)
    }

    @Test
    fun `start connects and stop disconnects the transport`() = runTest {
        val (host, transport) = host()
        assertEquals(ConnectionState.DISCONNECTED, host.connectionState.value)
        host.start(asHost = true)
        assertEquals(ConnectionState.CONNECTED, host.connectionState.value)
        assertEquals(1, transport.connectCalls)
        host.stop()
        assertEquals(ConnectionState.DISCONNECTED, host.connectionState.value)
        assertEquals(1, transport.disconnectCalls)
    }
}
