package com.arms.androidauto.core.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

// 실제 브로커를 상대로 한 통합 테스트용 CLI. 앱 코드 없이 호스트/게스트를 흉내낸다.
//   ./gradlew :core:remote:cli --args="gen --broker wss://host/mqtt --user u --pass p"
//   ./gradlew :core:remote:cli --args="host --pairing SR1...."
//   ./gradlew :core:remote:cli --args="guest --pairing SR1.... [--cmd play|pause|stop|next|prev|refresh|bt_reconnect|select:<mediaId>|volume:<0..100>]"
// 사용자가 넘긴 값(페어링 텍스트 등) 외의 비밀은 출력하지 않는다.
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
          host  --pairing <qrtext>
          guest --pairing <qrtext> [--cmd play|pause|stop|next|prev|refresh|bt_reconnect|select:<mediaId>|volume:<0..100>]
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
    RemoteCommand.BtReconnect -> "bt_reconnect"
    is RemoteCommand.Select -> "select:${command.mediaId}"
    is RemoteCommand.Volume -> "volume:${command.percent}"
}

private fun fakeItems() = listOf(
    RemoteItem("station:1", "Fake FM 1", "news"),
    RemoteItem("station:2", "Fake FM 2", "jazz"),
    RemoteItem("station:3", "Fake FM 3", "classic"),
)

private fun runHost(opts: Map<String, String>) {
    val pairing = loadPairing(opts)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val transport = MqttRemoteTransport(pairing.brokerUrl, pairing.username, pairing.password, "sr-cli-host-${pairing.pairId}")
    val channel = RemoteChannel(pairing, transport, scope)
    val items = fakeItems()
    var index = 0
    var playing = false
    var volume = 50
    var btConnected = true

    fun currentState() = HostState(
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
    )

    val stopping = AtomicBoolean(false)
    Runtime.getRuntime().addShutdownHook(
        Thread {
            stopping.set(true)
            runBlocking { runCatching { channel.stop() } }
        },
    )

    runBlocking {
        println("host: connecting to ${pairing.brokerUrl} (pairId ${pairing.pairId})")
        channel.start(asHost = true)
        println("host: connected, waiting for commands (Ctrl-C to quit)")
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
                when (val c = msg.command) {
                    RemoteCommand.Play -> playing = true
                    RemoteCommand.Pause, RemoteCommand.Stop -> playing = false
                    RemoteCommand.Next -> index = (index + 1) % items.size
                    RemoteCommand.Previous -> index = (index + items.size - 1) % items.size
                    RemoteCommand.Refresh -> Unit
                    RemoteCommand.BtReconnect -> btConnected = true
                    is RemoteCommand.Select -> {
                        val i = items.indexOfFirst { it.mediaId == c.mediaId }
                        if (i >= 0) index = i else { ok = false; note = "unknown mediaId" }
                    }
                    is RemoteCommand.Volume -> volume = c.percent
                }
                channel.sendAck(msg.seq, ok, note)
                channel.publishState(currentState())
                println("host: acked seq=${msg.seq} ok=$ok, state published (volume=$volume)")
            }
        }
        while (!stopping.get()) {
            channel.publishState(currentState())
            println("host: periodic state published (${items[index].name}, playing=$playing)")
            delay(10_000)
        }
    }
    scope.cancel()
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

    runBlocking {
        println("guest: connecting to ${pairing.brokerUrl} (pairId ${pairing.pairId})")
        channel.start(asHost = false)
        println("guest: connected, listening for 15 s")
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
                        "battery=${s.batteryPercent} items=${s.items.size} updatedAt=${s.updatedAtMs}",
                )
            }
        }
        scope.launch {
            channel.acks.collect { a -> println("guest: ack for seq=${a.ackSeq} ok=${a.ok} message=${a.message}") }
        }
        if (command != null) {
            delay(500)
            val seq = channel.sendCommand(command)
            println("guest: sent ${describe(command)} seq=$seq")
        }
        delay(15_000)
        channel.stop()
        println("guest: done")
    }
    scope.cancel()
    exitProcess(0)
}
