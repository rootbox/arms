package com.arms.androidauto.core.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ===== 아래는 계약만 고정한 자리표시자. core/remote 구현 작업에서 각자 파일로 옮기며 채운다. =====

object PairingCodec {
    fun encode(pairing: Pairing): String = TODO("Pairing.kt")
    fun decode(text: String): Pairing? = TODO("Pairing.kt")
    fun generate(brokerUrl: String, username: String, password: String): Pairing = TODO("Pairing.kt")
}

object JsonRemoteCodec : RemoteCodec {
    override fun encode(message: RemoteMessage): String = TODO("JsonRemoteCodec.kt")
    override fun decode(json: String): RemoteMessage? = TODO("JsonRemoteCodec.kt")
}

class AesGcmSealedBox(private val key: ByteArray) : SealedBox {
    override fun seal(plain: ByteArray, aad: ByteArray): ByteArray = TODO("AesGcmSealedBox.kt")
    override fun open(sealed: ByteArray, aad: ByteArray): ByteArray? = TODO("AesGcmSealedBox.kt")
}

// 재전송 방지: seq는 단조 증가, sentAtMs는 now ± windowMs 안이어야 한다.
class ReplayGuard(private val windowMs: Long = 120_000L) {
    fun accept(seq: Long, sentAtMs: Long, nowMs: Long): Boolean = TODO("ReplayGuard.kt")
}

class MqttRemoteTransport(
    private val brokerUrl: String,
    private val username: String,
    private val password: String,
    private val clientId: String,
) : RemoteTransport {
    override val connectionState: StateFlow<ConnectionState> = MutableStateFlow(ConnectionState.DISCONNECTED)
    override suspend fun connect() = TODO("MqttRemoteTransport.kt")
    override suspend fun publish(topic: String, payload: ByteArray, retain: Boolean) = TODO("MqttRemoteTransport.kt")
    override suspend fun subscribe(topic: String, onMessage: (ByteArray) -> Unit) = TODO("MqttRemoteTransport.kt")
    override suspend fun disconnect() = TODO("MqttRemoteTransport.kt")
}

// 호스트/게스트가 쓰는 상위 API. 봉인·개봉·재전송 검사·직렬화를 모두 안에서 처리한다.
// - 호스트: publishState(), commands 수집, sendAck()
// - 게스트: sendCommand(), states 수집, acks 수집
class RemoteChannel(
    private val pairing: Pairing,
    private val transport: RemoteTransport,
    private val scope: CoroutineScope,
    private val codec: RemoteCodec = JsonRemoteCodec,
    private val box: SealedBox = AesGcmSealedBox(pairing.key),
    private val replayGuard: ReplayGuard = ReplayGuard(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val connectionState: StateFlow<ConnectionState> get() = transport.connectionState
    val states: Flow<HostState> get() = TODO("RemoteChannel.kt")
    val commands: Flow<RemoteMessage.Command> get() = TODO("RemoteChannel.kt")
    val acks: Flow<RemoteMessage.Ack> get() = TODO("RemoteChannel.kt")
    // 개봉 실패·재전송 거부 횟수(진단용, 내용은 남기지 않는다).
    val rejectedCount: StateFlow<Int> get() = TODO("RemoteChannel.kt")

    suspend fun start(asHost: Boolean): Unit = TODO("RemoteChannel.kt")
    suspend fun stop(): Unit = TODO("RemoteChannel.kt")
    suspend fun publishState(state: HostState): Unit = TODO("RemoteChannel.kt")
    // retained 상태 삭제(페어링 해제 시).
    suspend fun clearState(): Unit = TODO("RemoteChannel.kt")
    // 보낸 seq를 돌려준다(ack 대조용).
    suspend fun sendCommand(command: RemoteCommand): Long = TODO("RemoteChannel.kt")
    suspend fun sendAck(ackSeq: Long, ok: Boolean, message: String? = null): Unit = TODO("RemoteChannel.kt")
}
