package com.arms.androidauto.core.remote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

// 호스트/게스트가 쓰는 상위 API. 봉인·개봉·재전송 검사·직렬화를 모두 안에서 처리한다.
// - 호스트: start(asHost = true) → commands 수집, publishState(), sendAck()
// - 게스트: start(asHost = false) → sendCommand(), states 수집, acks 수집
// 들어오는 페이로드: open(aad = 토픽) → decode → 재전송 검사 → 흐름에 방출. 어느 단계든 실패하면
// rejectedCount만 올리고 내용은 남기지 않는다.
class RemoteChannel(
    private val pairing: Pairing,
    private val transport: RemoteTransport,
    private val scope: CoroutineScope,
    private val codec: RemoteCodec = JsonRemoteCodec,
    private val box: SealedBox = AesGcmSealedBox(pairing.key),
    private val replayGuard: ReplayGuard = ReplayGuard(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val topics: RemoteTopics = pairing.topics

    // states는 최신값 1개를 replay 한다: retained state가 게스트의 collect보다 먼저 도착해도 잃지 않는다.
    // commands/acks는 replay 없음(한 번 처리한 명령/응답을 늦게 붙은 수집자가 다시 받으면 안 된다).
    private val _states = MutableSharedFlow<HostState>(replay = 1, extraBufferCapacity = 16)
    private val _commands = MutableSharedFlow<RemoteMessage.Command>(extraBufferCapacity = 16)
    private val _acks = MutableSharedFlow<RemoteMessage.Ack>(extraBufferCapacity = 16)
    private val _rejectedCount = MutableStateFlow(0)

    // retained state는 호스트가 오래전에 publish 했을 수 있다(게스트가 한 시간 뒤에 화면을 열어도
    // 받아야 한다). 그래서 state는 시간 창 없이 seq 단조성만 검사한다. cmd/ack는 보통 창(replayGuard).
    private val stateGuard = ReplayGuard(windowMs = Long.MAX_VALUE)

    // 재시작해도 seq가 계속 증가하도록 현재 시각(ms)에서 시작한다.
    private val nextSeq = AtomicLong(clock())

    val connectionState: StateFlow<ConnectionState> get() = transport.connectionState
    val lastFailure: StateFlow<FailureKind?> get() = transport.lastFailure
    val states: Flow<HostState> get() = _states
    val commands: Flow<RemoteMessage.Command> get() = _commands
    val acks: Flow<RemoteMessage.Ack> get() = _acks
    // 개봉 실패·재전송 거부 횟수(진단용, 내용은 남기지 않는다).
    val rejectedCount: StateFlow<Int> get() = _rejectedCount

    suspend fun start(asHost: Boolean) {
        transport.connect()
        if (asHost) {
            transport.subscribe(topics.cmd) { payload -> onIncoming(topics.cmd, payload) }
        } else {
            transport.subscribe(topics.state) { payload -> onIncoming(topics.state, payload) }
            transport.subscribe(topics.ack) { payload -> onIncoming(topics.ack, payload) }
        }
    }

    suspend fun stop() {
        transport.disconnect()
    }

    suspend fun publishState(state: HostState) {
        val seq = nextSeq.incrementAndGet()
        send(topics.state, RemoteMessage.State(seq = seq, sentAtMs = clock(), state = state), retain = true)
    }

    // retained 상태 삭제(페어링 해제 시). MQTT는 빈 페이로드의 retained publish로 지운다.
    suspend fun clearState() {
        transport.publish(topics.state, ByteArray(0), retain = true)
    }

    // 보낸 seq를 돌려준다(ack 대조용).
    suspend fun sendCommand(command: RemoteCommand): Long {
        val seq = nextSeq.incrementAndGet()
        send(topics.cmd, RemoteMessage.Command(seq = seq, sentAtMs = clock(), command = command), retain = false)
        return seq
    }

    suspend fun sendAck(ackSeq: Long, ok: Boolean, message: String? = null) {
        val seq = nextSeq.incrementAndGet()
        send(
            topics.ack,
            RemoteMessage.Ack(seq = seq, sentAtMs = clock(), ackSeq = ackSeq, ok = ok, message = message),
            retain = false,
        )
    }

    private suspend fun send(topic: String, message: RemoteMessage, retain: Boolean) {
        val plain = codec.encode(message).toByteArray(Charsets.UTF_8)
        val sealed = box.seal(plain, topic.toByteArray(Charsets.UTF_8))
        transport.publish(topic, sealed, retain)
    }

    // 전송 스레드에서 불린다. 예외를 내지 않는다.
    private fun onIncoming(topic: String, payload: ByteArray) {
        try {
            // 빈 페이로드 = retained 삭제 알림. 메시지가 아니므로 조용히 무시한다.
            if (payload.isEmpty()) return
            val plain = box.open(payload, topic.toByteArray(Charsets.UTF_8)) ?: return reject()
            val message = codec.decode(String(plain, Charsets.UTF_8)) ?: return reject()
            when (message) {
                is RemoteMessage.State -> {
                    if (topic != topics.state) return reject()
                    if (!stateGuard.accept(message.seq, message.sentAtMs, clock())) return reject()
                    scope.launch { _states.emit(message.state) }
                }
                is RemoteMessage.Command -> {
                    if (topic != topics.cmd) return reject()
                    if (!replayGuard.accept(message.seq, message.sentAtMs, clock())) return reject()
                    scope.launch { _commands.emit(message) }
                }
                is RemoteMessage.Ack -> {
                    if (topic != topics.ack) return reject()
                    if (!replayGuard.accept(message.seq, message.sentAtMs, clock())) return reject()
                    scope.launch { _acks.emit(message) }
                }
            }
        } catch (_: Throwable) {
            reject()
        }
    }

    private fun reject() {
        _rejectedCount.update { it + 1 }
    }
}
