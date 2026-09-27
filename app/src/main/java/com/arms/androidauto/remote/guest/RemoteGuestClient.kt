package com.arms.androidauto.remote.guest

import android.content.Context
import android.os.Build
import com.arms.androidauto.core.remote.ConnectionState
import com.arms.androidauto.core.remote.HostState
import com.arms.androidauto.core.remote.MqttRemoteTransport
import com.arms.androidauto.core.remote.RemoteChannel
import com.arms.androidauto.core.remote.RemoteCommand
import com.arms.androidauto.core.remote.RemoteMessage
import com.arms.androidauto.remote.RemoteRole
import com.arms.androidauto.remote.RemoteSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

// 게스트(폰) 쪽 원격 제어 클라이언트. 리모컨 화면이 보이는 동안만 살아 있다(백그라운드 상시 연결 없음).
// start()/stop()은 화면의 ON_RESUME/ON_PAUSE에 맞춰 부른다. 화면은 여기의 StateFlow만 읽는다.
class RemoteGuestClient(
    context: Context,
    private val store: RemoteSettingsStore,
    private val scope: CoroutineScope,
) {
    @Suppress("unused")
    private val appContext: Context = context.applicationContext

    private val _connectionState = MutableStateFlow(ConnectionState.DISCONNECTED)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _hostState = MutableStateFlow<HostState?>(null)
    val hostState: StateFlow<HostState?> = _hostState.asStateFlow()

    private val _lastAck = MutableStateFlow<RemoteMessage.Ack?>(null)
    val lastAck: StateFlow<RemoteMessage.Ack?> = _lastAck.asStateFlow()

    // 마지막으로 ack가 온 명령과 그 ack. 화면이 "재연결" 같은 특정 명령의 결과 문구를 보여줄 때 쓴다.
    data class AckResult(val command: RemoteCommand, val ack: RemoteMessage.Ack)
    private val _lastAckResult = MutableStateFlow<AckResult?>(null)
    val lastAckResult: StateFlow<AckResult?> = _lastAckResult.asStateFlow()

    // 보냈지만 아직 ack를 못 받은 명령의 seq. null이면 보낼 수 있다.
    private val _pendingSeq = MutableStateFlow<Long?>(null)
    val pendingSeq: StateFlow<Long?> = _pendingSeq.asStateFlow()

    // 사용자에게 보여줄 한 줄 오류("태블릿 응답 없음", 호스트가 돌려준 실패 메시지). clearError()로 지운다.
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // 저장된 페어링이 있는지. start() 때마다 다시 읽는다(페어링 화면에서 돌아온 직후 반영).
    private val _paired = MutableStateFlow(false)
    val paired: StateFlow<Boolean> = _paired.asStateFlow()

    // 호스트가 "연결 종료"를 눌러 이 페어링이 지워졌다. 사용자가 새 QR로 페어링할 때까지 안내를 띄운다.
    private val _revokedNotice = MutableStateFlow(false)
    val revokedNotice: StateFlow<Boolean> = _revokedNotice.asStateFlow()

    private var channel: RemoteChannel? = null
    private var sessionJob: Job? = null
    private var sessionScope: CoroutineScope? = null
    private var ackTimeoutJob: Job? = null
    private var pendingCommand: RemoteCommand? = null
    // 볼륨은 "동시에 하나만" 보낸다. 앞선 명령의 ack를 기다리는 동안 손을 뗀 값은 여기 모아 뒀다가 마지막 것만 보낸다.
    private var queuedVolume: Int? = null

    // Hello에 담는 기기 이름. 모델명만(사용자 이름·계정·설정의 기기 이름은 쓰지 않는다).
    private val guestName: String = GuestStatusPolicy.guestName(Build.MODEL, Build.MANUFACTURER)

    // stop()은 화면이 사라지는 순간(스코프가 이미 취소됐을 수 있음)에도 브로커 연결을 끊어야 하므로
    // 화면 스코프와 독립된 곳에서 끝낸다.
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun start() {
        if (channel != null) return
        val pairing = store.getPairing()
        _paired.value = pairing != null
        if (pairing == null) {
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        // 새 페어링이 들어왔으면 "호스트가 종료했다"는 안내는 더 이상 맞지 않는다.
        _revokedNotice.value = false

        val job = SupervisorJob(scope.coroutineContext[Job])
        val session = CoroutineScope(scope.coroutineContext + job)
        sessionJob = job
        sessionScope = session

        val transport = MqttRemoteTransport(
            brokerUrl = pairing.brokerUrl,
            username = pairing.username,
            password = pairing.password,
            clientId = store.clientId() + "-guest",
        )
        val ch = RemoteChannel(pairing, transport, session)
        channel = ch

        // 전송 연결 상태를 화면용 StateFlow로 옮기고, 연결될 때마다 최신 상태를 요청한다.
        // 첫 연결의 Refresh는 start()가 구독까지 마친 뒤에 보낸다(아래). 연결 직후 CONNECTED 전이에서
        // 보내면 ack 토픽 구독이 끝나기 전이라 ack를 놓쳐 "응답 없음"이 떴다(S22 rc1 검증). 재접속은 전이로 잡는다.
        var started = false
        session.launch {
            var previous = ConnectionState.DISCONNECTED
            ch.connectionState.collect { state ->
                _connectionState.value = state
                android.util.Log.i("ARMS", "리모컨 연결 상태: $state")
                if (started && state == ConnectionState.CONNECTED && previous != ConnectionState.CONNECTED) {
                    delay(500L)
                    send(RemoteCommand.Refresh)
                    send(RemoteCommand.Hello(guestName))
                }
                if (state == ConnectionState.DISCONNECTED && previous != ConnectionState.DISCONNECTED) {
                    // 끊기면 기다리던 ack는 오지 않는다.
                    failPending(GuestStatusPolicy.NO_ACK_MESSAGE)
                }
                previous = state
            }
        }
        session.launch {
            ch.states.collect { state ->
                if (state.revoked) {
                    onRevoked()
                } else {
                    _hostState.value = state
                }
            }
        }
        session.launch {
            ch.acks.collect { onAck(it) }
        }
        session.launch(Dispatchers.IO) {
            runCatching { ch.start(asHost = false) }
                .onSuccess {
                    started = true
                    android.util.Log.i("ARMS", "리모컨 구독 완료, 상태 요청")
                    send(RemoteCommand.Refresh)
                    send(RemoteCommand.Hello(guestName))
                    // 화면이 열려 있는 동안 주기적으로 "붙어 있음"을 알린다. 연결이 끊긴 동안은 send가 조용히 실패한다.
                    session.launch {
                        while (true) {
                            delay(GuestStatusPolicy.HELLO_INTERVAL_MS)
                            if (_connectionState.value == ConnectionState.CONNECTED) send(RemoteCommand.Hello(guestName))
                        }
                    }
                }
                .onFailure {
                    android.util.Log.w("ARMS", "리모컨 브로커 연결 실패: ${it.javaClass.simpleName}")
                    _error.value = "브로커에 연결할 수 없습니다"
                }
        }
    }

    fun stop() = stop(sendBye = true)

    private fun stop(sendBye: Boolean) {
        val ch = channel ?: return
        channel = null
        ackTimeoutJob?.cancel()
        ackTimeoutJob = null
        sessionJob?.cancel()
        sessionJob = null
        sessionScope = null
        _pendingSeq.value = null
        pendingCommand = null
        queuedVolume = null
        val wasConnected = _connectionState.value == ConnectionState.CONNECTED
        _connectionState.value = ConnectionState.DISCONNECTED
        teardownScope.launch {
            // Bye는 "보내고 잊는" 인사다. 못 보내도 호스트가 lastSeen 만료로 정리하므로 짧게만 기다린다.
            if (sendBye && wasConnected) {
                withTimeoutOrNull(BYE_TIMEOUT_MS) { runCatching { ch.sendCommand(RemoteCommand.Bye) } }
            }
            withTimeoutOrNull(STOP_TIMEOUT_MS) { runCatching { ch.stop() } }
        }
    }

    // 명령 하나를 보내고 ack를 기다린다. 5초 안에 ack가 없으면 "태블릿 응답 없음".
    fun send(command: RemoteCommand) {
        val ch = channel ?: run {
            _error.value = "연결되지 않았습니다"
            return
        }
        val session = sessionScope ?: return
        // 새로고침·인사(Hello/Bye)는 "보내고 잊는" 요청이다. ack를 기다려 버튼을 잠그면 화면을 열 때마다 5초씩 굳는다.
        val awaitAck = awaitsAck(command)
        if (awaitAck && _pendingSeq.value != null) return
        session.launch(Dispatchers.IO) {
            val seq = runCatching { ch.sendCommand(command) }.getOrElse {
                android.util.Log.w("ARMS", "리모컨 명령 전송 실패: ${it.javaClass.simpleName}")
                // 인사가 실패한 건 사용자에게 알릴 일이 아니다.
                if (awaitAck) _error.value = "명령을 보내지 못했습니다"
                return@launch
            }
            if (!awaitAck) return@launch
            pendingCommand = command
            _pendingSeq.value = seq
            ackTimeoutJob?.cancel()
            ackTimeoutJob = session.launch {
                delay(GuestStatusPolicy.ACK_TIMEOUT_MS)
                if (_pendingSeq.value == seq) failPending(GuestStatusPolicy.NO_ACK_MESSAGE)
            }
        }
    }

    // 볼륨 슬라이더에서 손을 뗐을 때. 앞선 명령의 ack를 기다리는 중이면 값만 기억해 두었다가 ack가 오면 마지막 값을 보낸다.
    fun setVolume(percent: Int) {
        val value = GuestStatusPolicy.clampVolume(percent)
        if (channel == null) return
        if (_pendingSeq.value != null) {
            queuedVolume = value
            return
        }
        send(RemoteCommand.Volume(value))
    }

    fun clearError() {
        _error.value = null
    }

    private fun onAck(ack: RemoteMessage.Ack) {
        _lastAck.value = ack
        android.util.Log.i("ARMS", "리모컨 ack seq=${ack.ackSeq} ok=${ack.ok}")
        if (ack.ackSeq == _pendingSeq.value) {
            ackTimeoutJob?.cancel()
            ackTimeoutJob = null
            pendingCommand?.let { _lastAckResult.value = AckResult(it, ack) }
            pendingCommand = null
            _pendingSeq.value = null
            flushQueuedVolume()
        }
        if (!ack.ok) _error.value = ack.message ?: "태블릿이 명령을 거부했습니다"
    }

    private fun flushQueuedVolume() {
        val value = queuedVolume ?: return
        queuedVolume = null
        send(RemoteCommand.Volume(value))
    }

    private fun failPending(message: String) {
        // 응답이 없는 호스트에 볼륨을 또 보내 봐야 같은 결과다. 슬라이더는 호스트 값으로 되돌아간다.
        queuedVolume = null
        if (_pendingSeq.value == null) return
        _pendingSeq.value = null
        pendingCommand = null
        _error.value = message
    }

    // 호스트가 "연결 종료"를 눌렀다. 페어링을 지우고, 브로커 연결을 끊고, 화면에 안내를 띄운다.
    // 이 페어링의 키는 더 이상 쓸 수 없으므로 retained 상태가 다시 와도 붙지 않게 저장소부터 비운다.
    private fun onRevoked() {
        android.util.Log.i("ARMS", "리모컨 게스트: 호스트가 연결을 종료해 페어링을 지움")
        store.clearPairing()
        store.setRole(RemoteRole.NONE)
        _paired.value = false
        _hostState.value = null
        _revokedNotice.value = true
        // 이미 끊긴 상대에게 Bye를 보낼 필요는 없다. (이 호출은 세션 코루틴 안에서 세션을 취소하지만,
        // 취소는 협력적이라 아래에 더 이상의 suspend 지점이 없으므로 안전하다.)
        stop(sendBye = false)
    }

    private companion object {
        const val STOP_TIMEOUT_MS = 3_000L
        const val BYE_TIMEOUT_MS = 1_000L

        // ack를 기다려 컨트롤을 잠그는 명령인지. Refresh/Hello/Bye는 보내고 잊는다.
        fun awaitsAck(command: RemoteCommand): Boolean = when (command) {
            RemoteCommand.Refresh, RemoteCommand.Bye, is RemoteCommand.Hello -> false
            else -> true
        }
    }
}
