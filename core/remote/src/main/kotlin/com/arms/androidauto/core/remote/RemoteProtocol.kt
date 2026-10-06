package com.arms.androidauto.core.remote

// 원격 제어 프로토콜의 "계약". 호스트(태블릿)와 게스트(폰)가 주고받을 수 있는 정보는 이 파일에
// 선언된 것이 전부다. 여기에 없는 정보(위치·네트워크·계정·NAS 자격증명·파일 목록)는 스키마에
// 존재하지 않으므로 오갈 수 없다. 필드를 추가할 때는 docs/REMOTE_CONTROL_PLAN.md §3 화이트리스트를
// 먼저 고친다.

const val REMOTE_PROTOCOL_VERSION = 1

// 게스트 → 호스트 명령. 호스트의 재생 세션과 블루투스 출력만 다룬다.
sealed class RemoteCommand {
    object Play : RemoteCommand()
    object Pause : RemoteCommand()
    object Stop : RemoteCommand()
    object Next : RemoteCommand()
    object Previous : RemoteCommand()
    // 호스트에게 최신 상태를 다시 publish 하라는 요청(게스트 화면을 열 때).
    object Refresh : RemoteCommand()
    // 블루투스 출력 재연결 시도. 실제 가능한 방법은 호스트가 결정한다(플랜 §4).
    object BtReconnect : RemoteCommand()
    // 호스트가 알고 있는 mediaId(라디오 채널 id 또는 NAS 앨범/플레이리스트 mediaId)만 받는다.
    data class Select(val mediaId: String) : RemoteCommand()
    data class Volume(val percent: Int) : RemoteCommand()
    // 게스트가 리모컨 화면을 열 때(그리고 60초마다) 보내는 인사. 호스트는 이걸로 "누가 붙어 있는지"를 안다.
    // guestName은 기기 모델명 정도(예: "Galaxy S25"), 그 이상은 담지 않는다.
    data class Hello(val guestName: String) : RemoteCommand()
    // 리모컨 화면을 닫을 때. 못 보내도 호스트는 lastSeen 만료(3분)로 정리한다.
    object Bye : RemoteCommand()
}

// 호스트에 붙어 있는 게스트(게스트 이름 + 마지막 인사 시각).
data class RemoteGuest(val name: String, val lastSeenMs: Long)

data class BluetoothStatus(
    val connected: Boolean,
    val deviceName: String?,
    // 마지막으로 연결 상태가 바뀐 시각(epoch ms). 게스트가 "n분 전 끊김"을 보여준다.
    val changedAtMs: Long?,
)

// 게스트가 고를 수 있는 항목. id는 호스트 세션의 mediaId 그대로.
data class RemoteItem(val mediaId: String, val name: String, val subtitle: String)

// 호스트 → 게스트 상태(retained). 재생 화면을 그리는 데 필요한 것만.
data class HostState(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
    // androidx.media3.common.Player 상태값(1 IDLE, 2 BUFFERING, 3 READY, 4 ENDED)
    val playbackState: Int,
    // 채널 아트 식별자("station:4") 또는 공개 커버 URL. NAS 커버는 URL/sid 대신 null.
    val artworkRef: String?,
    val bluetooth: BluetoothStatus?,
    val batteryPercent: Int?,
    val updatedAtMs: Long,
    // 게스트가 고를 수 있는 채널/앨범 목록. 라디오 채널은 항상, NAS는 이름만.
    val items: List<RemoteItem> = emptyList(),
    // 호스트 미디어 볼륨(0..100). 게스트 슬라이더의 현재값.
    val volumePercent: Int? = null,
    // 현재 붙어 있는 게스트들(호스트 상단바·QR 화면 자동 닫힘에 쓴다).
    val guests: List<RemoteGuest> = emptyList(),
    // 호스트가 "연결 종료"를 눌렀다: 게스트는 이 상태를 받으면 페어링을 지우고 안내한다.
    val revoked: Boolean = false,
)

sealed class RemoteMessage {
    abstract val seq: Long
    abstract val sentAtMs: Long

    data class Command(override val seq: Long, override val sentAtMs: Long, val command: RemoteCommand) : RemoteMessage()
    data class State(override val seq: Long, override val sentAtMs: Long, val state: HostState) : RemoteMessage()
    data class Ack(
        override val seq: Long,
        override val sentAtMs: Long,
        val ackSeq: Long,
        val ok: Boolean,
        val message: String?,
    ) : RemoteMessage()
}

// 한 페어링(호스트 1대 ↔ 게스트 1대)의 토픽. 브로커에는 pairId만 보인다.
data class RemoteTopics(val state: String, val cmd: String, val ack: String) {
    companion object {
        fun forPair(pairId: String) = RemoteTopics(
            state = "sr/$pairId/state",
            cmd = "sr/$pairId/cmd",
            ack = "sr/$pairId/ack",
        )
    }
}

// QR로 한 번 전달되는 페어링 정보. 키가 들어 있으므로 저장은 암호화 저장소에만, 로그·문서 금지.
data class Pairing(
    val pairId: String,
    val key: ByteArray, // 32바이트(AES-256)
    val brokerUrl: String, // 예: wss://host.example/mqtt
    val username: String,
    val password: String,
) {
    val topics: RemoteTopics get() = RemoteTopics.forPair(pairId)

    // 사람이 읽을 필요는 없지만 QR 용량(≤ 300자 목표)에 맞춘 압축 표현. 구현: Pairing.kt
    fun toQrText(): String = PairingCodec.encode(this)

    override fun equals(other: Any?): Boolean =
        other is Pairing && pairId == other.pairId && key.contentEquals(other.key) &&
            brokerUrl == other.brokerUrl && username == other.username && password == other.password

    override fun hashCode(): Int = pairId.hashCode() * 31 + key.contentHashCode()

    companion object {
        fun fromQrText(text: String): Pairing? = PairingCodec.decode(text)
        // 호스트가 새 페어링을 만들 때(키·pairId 무작위). 구현: Pairing.kt
        fun generate(brokerUrl: String, username: String, password: String): Pairing = PairingCodec.generate(brokerUrl, username, password)
    }
}

// 메시지 ↔ JSON(평문). 알 수 없는 type/필드는 거부한다(화이트리스트).
interface RemoteCodec {
    fun encode(message: RemoteMessage): String
    // 형식이 틀리거나 허용되지 않은 값이면 null.
    fun decode(json: String): RemoteMessage?
}

// 봉인/개봉(AES-256-GCM). aad에는 토픽 이름을 넣어 cmd를 state로 재생하는 식의 바꿔치기를 막는다.
interface SealedBox {
    fun seal(plain: ByteArray, aad: ByteArray): ByteArray
    // 인증 실패·손상이면 null (예외를 밖으로 내지 않는다).
    fun open(sealed: ByteArray, aad: ByteArray): ByteArray?
}

enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED }

// 브로커 전송. 구현: MqttRemoteTransport(HiveMQ). 테스트용 인메모리 구현도 이 인터페이스로.
interface RemoteTransport {
    val connectionState: kotlinx.coroutines.flow.StateFlow<ConnectionState>
    // 마지막 연결 실패 원인(연결되면 null). 화면·로그에 "왜 안 붙는지"를 보여주는 데 쓴다.
    val lastFailure: kotlinx.coroutines.flow.StateFlow<FailureKind?> get() = NO_FAILURE
    suspend fun connect()
    suspend fun publish(topic: String, payload: ByteArray, retain: Boolean)
    // 구독 콜백은 전송 스레드에서 불릴 수 있다. 호출자가 자기 스코프로 옮긴다.
    suspend fun subscribe(topic: String, onMessage: (ByteArray) -> Unit)
    suspend fun disconnect()
}

private val NO_FAILURE: kotlinx.coroutines.flow.StateFlow<FailureKind?> =
    kotlinx.coroutines.flow.MutableStateFlow<FailureKind?>(null)
