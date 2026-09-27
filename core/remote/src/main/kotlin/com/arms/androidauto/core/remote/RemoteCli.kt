package com.arms.androidauto.core.remote

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

// 실제 브로커를 상대로 한 통합 테스트용 CLI. 앱 코드 없이 호스트/게스트를 흉내낸다.
//   ./gradlew :core:remote:cli --args="gen --broker wss://host/mqtt --user u --pass p"
//   ./gradlew :core:remote:cli --args="host --pairing SR1.... [--revoke-after <sec>]"
//   ./gradlew :core:remote:cli --args="guest --pairing SR1.... [--cmd play|pause|stop|next|prev|refresh|bt_reconnect|hello|bye|select:<mediaId>|volume:<0..100>]"
// 게스트는 구독 직후 Hello("cli-guest")를, 종료 직전 Bye를 보낸다. 호스트는 Hello로 게스트 목록을 관리한다(3분 만료).
// 사용자가 넘긴 값(페어링 텍스트 등) 외의 비밀은 출력하지 않는다.
private const val CLI_GUEST_NAME = "cli-guest"
private const val GUEST_EXPIRY_MS = 3 * 60_000L
private const val HOST_PERIOD_MS = 10_000L
private const val GUEST_LISTEN_MS = 15_000L

fun main(args: Array<String>) {
    if (args.isEmpty()) usage()
    val mode = args[0]
    val opts = parseOptions(args.drop(1))
    when (mode) {
        "gen" -> runGen(opts)
        "host" -> runHost(opts)
        "guest" -> runGuest(opts)
        else -> usage()
    }
}

private fun usage(): Nothing {
    System.err.println(
        """
        usage:
          gen   --broker <url> --user <u> --pass <p>
          host  --pairing <qrtext> [--revoke-after <sec>]
          guest --pairing <qrtext> [--cmd play|pause|stop|next|prev|refresh|bt_reconnect|hello|bye|select:<mediaId>|volume:<0..100>]
        """.trimIndent(),
    )
    exitProcess(2)
}

private fun parseOptions(args: List<String>): Map<String, String> {
    val map = HashMap<String, String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (!a.startsWith("--") || i + 1 >= args.size) usage()
        map[a.removePrefix("--")] = args[i + 1]
        i += 2
    }
    return map
}

private fun requireOpt(opts: Map<String, String>, name: String): String = opts[name] ?: run {
    System.err.println("missing --$name")
    usage()
}

private fun runGen(opts: Map<String, String>) {
    val pairing = Pairing.generate(
        brokerUrl = requireOpt(opts, "broker"),
        username = requireOpt(opts, "user"),
        password = requireOpt(opts, "pass"),
    )
    val text = pairing.toQrText()
    println("pairId: ${pairing.pairId}")
    println("qr(${text.length} chars): $text")
}

private fun loadPairing(opts: Map<String, String>): Pairing =
    Pairing.fromQrText(requireOpt(opts, "pairing")) ?: run {
        System.err.println("invalid pairing text")
        exitProcess(1)
    }

private fun parseCommand(text: String): RemoteCommand? = when {
    text == "play" -> RemoteCommand.Play
    text == "pause" -> RemoteCommand.Pause
    text == "stop" -> RemoteCommand.Stop
    text == "next" -> RemoteCommand.Next
    text == "prev" -> RemoteCommand.Previous
    text == "refresh" -> RemoteCommand.Refresh
    text == "bt_reconnect" -> RemoteCommand.BtReconnect
    text == "hello" -> RemoteCommand.Hello(CLI_GUEST_NAME)
    text == "bye" -> RemoteCommand.Bye
    text.startsWith("select:") -> text.removePrefix("select:").takeIf { it.isNotBlank() }?.let { RemoteCommand.Select(it) }
    text.startsWith("volume:") -> text.removePrefix("volume:").toIntOrNull()?.takeIf { it in 0..100 }?.let { RemoteCommand.Volume(it) }
    else -> null
}

private fun describe(command: RemoteCommand): String = when (command) {
    RemoteCommand.Play -> "play"
    RemoteCommand.Pause -> "pause"
    RemoteCommand.Stop -> "stop"
    RemoteCommand.Next -> "next"
    RemoteCommand.Previous -> "prev"
    RemoteCommand.Refresh -> "refresh"
    RemoteCommand.Bye -> "bye"
    is RemoteCommand.Hello -> "hello:${command.guestName}"
    RemoteCommand.BtReconnect -> "bt_reconnect"
    is RemoteCommand.Select -> "select:${command.mediaId}"
    is RemoteCommand.Volume -> "volume:${command.percent}"
}

private fun fakeItems() = listOf(
    RemoteItem("station:1", "Fake FM 1", "news"),
    RemoteItem("station:2", "Fake FM 2", "jazz"),
    RemoteItem("station:3", "Fake FM 3", "classic"),
)

// 호스트 시뮬레이터의 게스트 목록. 명령 수집 코루틴과 주기 루프가 다른 스레드에서 만지므로 잠근다.
private class GuestBook {
    private val lastSeen = LinkedHashMap<String, Long>()

    fun hello(name: String, nowMs: Long): Boolean = synchronized(this) {
        val isNew = name !in lastSeen
        lastSeen[name] = nowMs
        isNew
    }

    fun bye(name: String): Boolean = synchronized(this) { lastSeen.remove(name) != null }

    fun expire(nowMs: Long): Boolean = synchronized(this) {
        lastSeen.entries.removeIf { nowMs - it.value > GUEST_EXPIRY_MS }
    }

    fun snapshot(): List<RemoteGuest> = synchronized(this) {
        lastSeen.map { (name, seen) -> RemoteGuest(name = name, lastSeenMs = seen) }
    }

    fun describe(nowMs: Long): String = snapshot().joinToString(prefix = "[", postfix = "]") { g ->
        "${g.name}(${(nowMs - g.lastSeenMs) / 1000}s ago)"
    }
}

private fun runHost(opts: Map<String, String>) {
    val pairing = loadPairing(opts)
    val revokeAfterMs: Long? = opts["revoke-after"]?.let { text ->
        text.toLongOrNull()?.takeIf { it >= 0 }?.times(1000) ?: run {
            System.err.println("invalid --revoke-after: $text")
            usage()
        }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = MqttRemoteTransport(pairing.brokerUrl, pairing.username, pairing.password, "sr-cli-host-${pairing.pairId}")
    val channel = RemoteChannel(pairing, transport, scope)
    val items = fakeItems()
    val guests = GuestBook()
    var index = 0
    var playing = false
    var volume = 50
    var btConnected = true

    fun currentState(revoked: Boolean = false) = HostState(
        mediaId = items[index].mediaId,
        title = items[index].name,
        artist = items[index].subtitle,
        isPlaying = playing,
        playbackState = if (playing) 3 else 1,
        artworkRef = items[index].mediaId,
        bluetooth = BluetoothStatus(connected = btConnected, deviceName = "Fake Speaker", changedAtMs = System.currentTimeMillis()),
        batteryPercent = 100,
        updatedAtMs = System.currentTimeMillis(),
        items = items,
        volumePercent = volume,
        guests = guests.snapshot(),
        revoked = revoked,
    )

    fun printGuests() = println("host: guests=${guests.describe(System.currentTimeMillis())}")

    val stopping = AtomicBoolean(false)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            if (stopping.compareAndSet(false, true)) runBlocking { runCatching { channel.stop() } }
        },
    )

    runBlocking {
        println("host: connecting to ${pairing.brokerUrl} (pairId ${pairing.pairId})")
        channel.start(asHost = true)
        println(
            "host: connected, waiting for commands (Ctrl-C to quit)" +
                (revokeAfterMs?.let { ", revoking in ${it / 1000} s" } ?: ""),
        )
        scope.launch {
            channel.connectionState.collect { println("host: connection $it") }
        }
        scope.launch {
            channel.rejectedCount.collect { if (it > 0) println("host: rejected messages = $it") }
        }
        scope.launch {
            channel.commands.collect { msg ->
                println("host: command seq=${msg.seq} ${describe(msg.command)}")
                var ok = true
                var note: String? = null
                var guestsChanged = false
                when (val c = msg.command) {
                    RemoteCommand.Play -> playing = true
                    RemoteCommand.Pause, RemoteCommand.Stop -> playing = false
                    RemoteCommand.Next -> index = (index + 1) % items.size
                    RemoteCommand.Previous -> index = (index + items.size - 1) % items.size
                    RemoteCommand.Refresh -> Unit
                    is RemoteCommand.Hello -> guestsChanged = guests.hello(c.guestName, System.currentTimeMillis())
                    // Bye에는 이름이 없다(계약). CLI 호스트는 게스트가 하나뿐이라고 보고 cli-guest를 지운다.
                    RemoteCommand.Bye -> guestsChanged = guests.bye(CLI_GUEST_NAME)
                    RemoteCommand.BtReconnect -> btConnected = true
                    is RemoteCommand.Select -> {
                        val i = items.indexOfFirst { it.mediaId == c.mediaId }
                        if (i >= 0) index = i else { ok = false; note = "unknown mediaId" }
                    }
                    is RemoteCommand.Volume -> volume = c.percent
                }
                if (guestsChanged) printGuests()
                channel.sendAck(msg.seq, ok, note)
                channel.publishState(currentState())
                println("host: acked seq=${msg.seq} ok=$ok, state published (volume=$volume)")
            }
        }
        val startedAt = System.currentTimeMillis()
        while (!stopping.get()) {
            val now = System.currentTimeMillis()
            if (guests.expire(now)) {
                print("host: guest expired -> ")
                printGuests()
            }
            if (revokeAfterMs != null && now - startedAt >= revokeAfterMs) {
                channel.publishState(currentState(revoked = true))
                println("host: revoked state published, exiting")
                break
            }
            channel.publishState(currentState())
            println("host: periodic state published (${items[index].name}, playing=$playing, volume=$volume, guests=${guests.snapshot().size})")
            val wait = revokeAfterMs?.let { (startedAt + it - System.currentTimeMillis()).coerceIn(0, HOST_PERIOD_MS) } ?: HOST_PERIOD_MS
            delay(wait)
        }
        if (stopping.compareAndSet(false, true)) channel.stop()
    }
    scope.cancel()
    exitProcess(0)
}

private fun runGuest(opts: Map<String, String>) {
    val pairing = loadPairing(opts)
    val command = opts["cmd"]?.let { text ->
        parseCommand(text) ?: run {
            System.err.println("unknown command: $text")
            usage()
        }
    }
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = MqttRemoteTransport(pairing.brokerUrl, pairing.username, pairing.password, "sr-cli-guest-${pairing.pairId}")
    val channel = RemoteChannel(pairing, transport, scope)
    val revoked = CompletableDeferred<Unit>()

    runBlocking {
        println("guest: connecting to ${pairing.brokerUrl} (pairId ${pairing.pairId})")
        channel.start(asHost = false)
        println("guest: connected, listening for ${GUEST_LISTEN_MS / 1000} s")
        scope.launch {
            channel.connectionState.collect { println("guest: connection $it") }
        }
        scope.launch {
            channel.rejectedCount.collect { if (it > 0) println("guest: rejected messages = $it") }
        }
        scope.launch {
            channel.states.collect { s ->
                println(
                    "guest: state title=${s.title} artist=${s.artist} playing=${s.isPlaying} " +
                        "pb=${s.playbackState} bt=${s.bluetooth?.connected}/${s.bluetooth?.deviceName} " +
                        "battery=${s.batteryPercent} volume=${s.volumePercent} items=${s.items.size} " +
                        "guests=${s.guests.map { it.name }} revoked=${s.revoked} updatedAt=${s.updatedAtMs}",
                )
                if (s.revoked) revoked.complete(Unit)
            }
        }
        scope.launch {
            channel.acks.collect { a -> println("guest: ack for seq=${a.ackSeq} ok=${a.ok} message=${a.message}") }
        }
        // 구독 직후 인사. 실제 앱도 리모컨 화면을 열 때 이렇게 한다.
        val helloSeq = channel.sendCommand(RemoteCommand.Hello(CLI_GUEST_NAME))
        println("guest: sent hello:$CLI_GUEST_NAME seq=$helloSeq")
        if (command != null) {
            delay(500)
            val seq = channel.sendCommand(command)
            println("guest: sent ${describe(command)} seq=$seq")
        }
        val wasRevoked = withTimeoutOrNull(GUEST_LISTEN_MS) { revoked.await() } != null
        if (wasRevoked) {
            // 실제 앱은 여기서 페어링을 지우고 안내한다. 끊긴 상대에게 Bye는 보내지 않는다.
            println("guest: host revoked this pairing -> would clear pairing and exit")
        } else if (command != RemoteCommand.Bye) {
            val byeSeq = channel.sendCommand(RemoteCommand.Bye)
            println("guest: sent bye seq=$byeSeq")
            delay(300)
        }
        channel.stop()
        println("guest: done")
    }
    scope.cancel()
    exitProcess(0)
}
