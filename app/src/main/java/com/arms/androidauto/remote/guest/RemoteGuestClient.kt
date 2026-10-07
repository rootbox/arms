package com.arms.androidauto.remote.guest

import android.content.Context
import android.os.Build
import com.arms.androidauto.BuildConfig
import com.arms.androidauto.core.remote.BrokerUrlPolicy
import com.arms.androidauto.core.remote.ConnectionState
import com.arms.androidauto.core.remote.FailureKind
import com.arms.androidauto.core.remote.HostState
import com.arms.androidauto.core.remote.MqttRemoteTransport
import com.arms.androidauto.core.remote.RemoteChannel
import com.arms.androidauto.core.remote.Pairing
import com.arms.androidauto.core.remote.RemoteCommand
import com.arms.androidauto.core.remote.RemoteFailure
import com.arms.androidauto.core.remote.RemoteMessage
import com.arms.androidauto.remote.RemoteRole
import com.arms.androidauto.remote.RemoteSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    // 마지막 브로커 연결 실패 원인. 연결에 성공하면 null로 돌아간다. 화면은 이것으로 "연결 중…" 대신 원인을 보여준다.
    private val _lastFailure = MutableStateFlow<FailureKind?>(null)
    val lastFailure: StateFlow<FailureKind?> = _lastFailure.asStateFlow()

    // 저장된 페어링의 브로커 주소가 BrokerUrlPolicy를 통과하지 못함(예전에 저장된 127.0.0.1 페어링).
    // 이 상태에서는 연결을 시도하지 않는다 — 재시도해도 절대 붙지 않으므로 "페어링 다시 하기"만 의미가 있다.
    private val _configProblem = MutableStateFlow<BrokerUrlPolicy.Problem?>(null)
    val configProblem: StateFlow<BrokerUrlPolicy.Problem?> = _configProblem.asStateFlow()

    // 구독을 마친 시각과 마지막 ack를 받은 시각(이 폰의 시계). "태블릿 응답 없음" 판단에 쓴다.
    private val _subscribedAtMs = MutableStateFlow<Long?>(null)
    val subscribedAtMs: StateFlow<Long?> = _subscribedAtMs.asStateFlow()
    private val _lastAckAtMs = MutableStateFlow<Long?>(null)
    val lastAckAtMs: StateFlow<Long?> = _lastAckAtMs.asStateFlow()

    private var channel: RemoteChannel? = null
    // 재시도 대기 중 "다시 연결"을 누르면 기다리지 않고 바로 시도하게 깨운다.
    private var retryWake: Channel<Unit>? = null
    // 연결 루프가 어디쯤인지. "다시 연결"이 무엇을 할지 정한다.
    private enum class Phase { ATTEMPTING, WAITING, SUBSCRIBED }
    @Volatile private var phase = Phase.ATTEMPTING
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
        if (sessionJob != null) return
        val pairing = store.getPairing()
        _paired.value = pairing != null
        _subscribedAtMs.value = null
        // 새 세션은 이전 실패를 끌고 오지 않는다(첫 시도 동안은 "연결 중…", 실패하면 그 원인).
        _lastFailure.value = null
        if (pairing == null) {
            _configProblem.value = null
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        // 새 페어링이 들어왔으면 "호스트가 종료했다"는 안내는 더 이상 맞지 않는다.
        _revokedNotice.value = false

        // 예전 빌드가 검사 없이 저장한 페어링(예: ws://127.0.0.1:9001)은 붙어 볼 필요도 없다.
        val check = BrokerUrlPolicy.check(pairing.brokerUrl, allowLoopback = BuildConfig.DEBUG)
        if (check is BrokerUrlPolicy.Result.Invalid) {
            android.util.Log.w("ARMS", "리모컨 게스트: 저장된 브로커 주소가 올바르지 않아 연결하지 않음 (${check.problem})")
            _configProblem.value = check.problem
            _connectionState.value = ConnectionState.DISCONNECTED
            return
        }
        _configProblem.value = null

        val job = SupervisorJob(scope.coroutineContext[Job])
        val session = CoroutineScope(scope.coroutineContext + job)
        sessionJob = job
        sessionScope = session
        val wake = Channel<Unit>(Channel.CONFLATED)
        retryWake = wake
        session.launch { connectLoop(pairing, session, wake) }
    }

    // 브로커 연결 시도를 성공할 때까지 되풀이한다(화면이 떠 있는 동안만 — 세션이 취소되면 끝).
    // 매 시도마다 새 전송/채널을 만든다: HiveMQ는 첫 연결이 실패한 클라이언트에 구독을 다시 걸어 주지 않으므로
    // 실패한 채널을 살려 두면 "연결됨인데 상태가 안 오는" 반쪽 연결이 된다.
    private suspend fun connectLoop(pairing: Pairing, session: CoroutineScope, wake: Channel<Unit>) {
        var attempt = 0
        while (session.isActive) {
            phase = Phase.ATTEMPTING
            val attemptJob = SupervisorJob(session.coroutineContext[Job])
            val attemptScope = CoroutineScope(session.coroutineContext + attemptJob)
            val ch = openChannel(pairing, attemptScope)
            // 응답 없는 주소(패킷이 버려지는 경우)에서 "연결 중…"이 끝없이 이어지지 않게 시도마다 상한을 둔다.
            val result = withContext(Dispatchers.IO) {
                runCatching { withTimeoutOrNull(CONNECT_TIMEOUT_MS) { ch.start(asHost = false); true } }
            }
            val error = result.exceptionOrNull()
            if (error == null && result.getOrNull() == true) {
                phase = Phase.SUBSCRIBED
                onSubscribed(attemptScope)
                // 자동 재접속을 쓰지 않으므로 끊기면 여기서 정리하고 새 연결을 연다.
                ch.connectionState.first { it != ConnectionState.CONNECTED }
                val dropped = ch.lastFailure.value
                android.util.Log.w("ARMS", "리모컨 게스트: 연결 끊김 (${dropped ?: "UNKNOWN"}) → 다시 연결")
                if (dropped != null) _lastFailure.value = dropped
                failPending(GuestStatusPolicy.NO_ACK_MESSAGE)
                attemptJob.cancel()
                if (channel === ch) channel = null
                withContext(Dispatchers.IO) { withTimeoutOrNull(STOP_TIMEOUT_MS) { runCatching { ch.stop() } } }
                _connectionState.value = ConnectionState.DISCONNECTED
                _subscribedAtMs.value = null
                attempt = 0
                phase = Phase.WAITING
                withTimeoutOrNull(GuestStatusPolicy.retryDelayMs(0)) { wake.receive() }
                continue
            }
            if (error is kotlinx.coroutines.CancellationException && error !is kotlinx.coroutines.TimeoutCancellationException) throw error
            val kind = if (error == null) FailureKind.TIMEOUT else ch.lastFailure.value ?: RemoteFailure.classify(error)
            _lastFailure.value = kind
            // 원인 분류만 남긴다. 주소·계정·키는 로그에 남기지 않는다.
            android.util.Log.w("ARMS", "리모컨 게스트: 브로커 연결 실패 (${kind})")
            attemptJob.cancel()
            if (channel === ch) channel = null
            // 실패한 클라이언트의 자동 재접속을 멈춘다(같은 clientId 두 개가 서로를 밀어내지 않게).
            withContext(Dispatchers.IO) { withTimeoutOrNull(STOP_TIMEOUT_MS) { runCatching { ch.stop() } } }
            _connectionState.value = ConnectionState.DISCONNECTED
            val backoff = GuestStatusPolicy.retryDelayMs(attempt)
            attempt++
            phase = Phase.WAITING
            // "다시 연결"을 누르면 대기를 건너뛰고 백오프도 처음부터.
            if (withTimeoutOrNull(backoff) { wake.receive() } != null) attempt = 0
        }
    }

    // 전송·채널을 만들고 화면용 StateFlow로 옮기는 수집기를 붙인다. 수집기는 attemptScope에 묶여
    // 실패한 시도와 함께 정리된다.
    private fun openChannel(pairing: Pairing, attemptScope: CoroutineScope): RemoteChannel {
        val transport = MqttRemoteTransport(
            brokerUrl = pairing.brokerUrl,
            username = pairing.username,
            password = pairing.password,
            clientId = store.clientId() + "-guest",
            // HiveMQ 자동 재접속은 첫 연결 실패 때 예외 없이 무한 대기하고, disconnect()로도 멈추지 않는다
            // (1.3.3 바이트코드 확인). 끊고 새로 만들면 같은 clientId 둘이 서로 밀어낸다 → 재접속은 이 루프가 맡는다.
            autoReconnect = false,
        )
        val ch = RemoteChannel(pairing, transport, attemptScope)
        channel = ch

        // 전송 연결 상태를 화면용 StateFlow로 옮기고, 재접속될 때마다 최신 상태를 요청한다.
        // 첫 연결의 Refresh는 구독까지 마친 뒤에 보낸다(onSubscribed). 연결 직후 CONNECTED 전이에서
        // 보내면 ack 토픽 구독이 끝나기 전이라 ack를 놓쳐 "응답 없음"이 떴다(S22 rc1 검증). 재접속은 전이로 잡는다.
        attemptScope.launch {
            var previous = ConnectionState.DISCONNECTED
            ch.connectionState.collect { state ->
                _connectionState.value = state
                android.util.Log.i("ARMS", "리모컨 연결 상태: $state")
                if (state == ConnectionState.CONNECTED) _lastFailure.value = null
                if (_subscribedAtMs.value != null && state == ConnectionState.CONNECTED && previous != ConnectionState.CONNECTED) {
                    // 재접속: 구독은 라이브러리가 되살린다. "응답 없음" 유예를 이 시점부터 다시 센다.
                    _subscribedAtMs.value = System.currentTimeMillis()
                    delay(500L)
                    send(RemoteCommand.Refresh)
                    send(RemoteCommand.Hello(guestName))
                }
                if (state != ConnectionState.CONNECTED && previous == ConnectionState.CONNECTED) {
                    // 끊기면 기다리던 ack는 오지 않는다.
                    failPending(GuestStatusPolicy.NO_ACK_MESSAGE)
                }
                previous = state
            }
        }
        // 연결 후 끊김(자동 재접속 중)의 원인도 화면에 올린다. null(시도 시작)은 무시해 시도마다 깜빡이지 않게 한다.
        attemptScope.launch {
            ch.lastFailure.collect { kind -> if (kind != null) _lastFailure.value = kind }
        }
        attemptScope.launch {
            ch.states.collect { state ->
                if (state.revoked) {
                    onRevoked()
                } else {
                    _hostState.value = state
                }
            }
        }
        attemptScope.launch {
            ch.acks.collect { onAck(it) }
        }
        return ch
    }

    private fun onSubscribed(attemptScope: CoroutineScope) {
        _lastFailure.value = null
        _subscribedAtMs.value = System.currentTimeMillis()
        android.util.Log.i("ARMS", "리모컨 구독 완료, 상태 요청")
        send(RemoteCommand.Refresh)
        send(RemoteCommand.Hello(guestName))
        // 화면이 열려 있는 동안 주기적으로 "붙어 있음"을 알린다. 연결이 끊긴 동안은 send가 조용히 실패한다.
        // 이 Hello의 ack가 "태블릿이 살아 있다"는 신호이기도 하다(GuestStatus.HostSilent).
        attemptScope.launch {
            while (true) {
                delay(GuestStatusPolicy.HELLO_INTERVAL_MS)
                if (_connectionState.value == ConnectionState.CONNECTED) send(RemoteCommand.Hello(guestName))
            }
        }
    }

    // "다시 연결": 재시도 대기 중이면 바로 시도한다. 세션이 없으면(설정 오류였거나 멈춰 있음) 페어링을 다시 읽어 시작한다.
    fun retryNow() {
        _error.value = null
        if (sessionJob == null) {
            start()
            return
        }
        if (_connectionState.value == ConnectionState.CONNECTED) return
        when (phase) {
            // 백오프 대기 중: 깨워서 바로 시도.
            Phase.WAITING -> retryWake?.trySend(Unit)
            // 시도가 진행 중: 그 결과를 기다린다(끝나면 바로 다시 시도하도록 깨움만 남긴다).
            Phase.ATTEMPTING -> retryWake?.trySend(Unit)
            // 한 번 붙었다가 끊겨 라이브러리가 재접속 중: 세션을 새로 연다.
            Phase.SUBSCRIBED -> {
                stop(sendBye = false)
                start()
            }
        }
    }

    fun stop() = stop(sendBye = true)

    // 저장된 페어링이 바뀌었을 때: 이전 연결·설정 오류 상태를 버리고 새 페어링으로 다시 시작한다.
    fun restart() {
        stop(sendBye = false)
        start()
    }

    private fun stop(sendBye: Boolean) {
        val job = sessionJob ?: return
        val ch = channel
        channel = null
        retryWake = null
        _subscribedAtMs.value = null
        ackTimeoutJob?.cancel()
        ackTimeoutJob = null
        job.cancel()
        sessionJob = null
        sessionScope = null
        _pendingSeq.value = null
        pendingCommand = null
        queuedVolume = null
        val wasConnected = _connectionState.value == ConnectionState.CONNECTED
        _connectionState.value = ConnectionState.DISCONNECTED
        if (ch == null) return
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

    // "연결 허용 (10분)": 태블릿의 스마트싱스 연결 허용 창을 연다. ack(ok/메시지)는 lastAckResult로, 카운트다운은 hostState로 온다.
    fun openSmartThingsPairing() = send(RemoteCommand.OpenSmartThingsPairing)

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
        _lastAckAtMs.value = System.currentTimeMillis()
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
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val BYE_TIMEOUT_MS = 1_000L

        // ack를 기다려 컨트롤을 잠그는 명령인지. Refresh/Hello/Bye는 보내고 잊는다.
        fun awaitsAck(command: RemoteCommand): Boolean = when (command) {
            RemoteCommand.Refresh, RemoteCommand.Bye, is RemoteCommand.Hello -> false
            else -> true
        }
    }
}
