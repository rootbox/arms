package com.arms.androidauto.remote.host

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.arms.androidauto.ARMSMediaLibraryService
import com.arms.androidauto.BuildConfig
import com.arms.androidauto.MainActivity
import com.arms.androidauto.R
import com.arms.androidauto.core.data.StationRepository
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.remote.BrokerUrlPolicy
import com.arms.androidauto.core.remote.ConnectionState
import com.arms.androidauto.core.remote.FailureKind
import com.arms.androidauto.core.remote.HostState
import com.arms.androidauto.core.remote.MqttRemoteTransport
import com.arms.androidauto.core.remote.Pairing
import com.arms.androidauto.core.remote.RemoteChannel
import com.arms.androidauto.core.remote.RemoteCommand
import com.arms.androidauto.core.remote.RemoteFailure
import com.arms.androidauto.core.remote.RemoteGuest
import com.arms.androidauto.core.remote.RemoteItem
import com.arms.androidauto.core.remote.RemoteMessage
import com.arms.androidauto.local.CommandOutcome
import com.arms.androidauto.local.DeviceInfo
import com.arms.androidauto.local.LocalBackend
import com.arms.androidauto.local.LocalCommand
import com.arms.androidauto.local.LocalControl
import com.arms.androidauto.local.LocalControlHost
import com.arms.androidauto.local.LocalState
import com.arms.androidauto.local.LocalStateMapper
import com.arms.androidauto.remote.RemoteRole
import com.arms.androidauto.remote.RemoteSettingsStore
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// 원격 제어 호스트(벽걸이 태블릿). 플랜 §4.
//
// - 역할이 HOST일 때 산다: 폰 리모컨 페어링(MQTT)이 있거나 스마트싱스 로컬 제어가 켜져 있으면(기본 켬).
//   둘 다 아니면 즉시 stopSelf. 페어링이 없으면 브로커에 붙지 않고 status=NoPairing(오류 아님).
// - 로컬 제어 API v1(smartthings/LAN_API.md): LocalControlHost가 포트 8765 HTTP 서버·mDNS를 띄우고,
//   명령은 MQTT와 같은 execute 경로(세션 MediaController)로 실행한다. 상태는 200ms 디바운스로 SSE에 나간다.
// - 브로커에 상시 연결(RemoteChannel/MqttRemoteTransport), 세션(ARMSMediaLibraryService)에는
//   폰 화면과 똑같이 MediaController 하나로 붙는다(SessionAudioPlayer와 같은 경로 → 플레이어는 하나).
// - 상태 publish: 지문(StatePublishPolicy)이 바뀌면 500ms debounce 뒤, 그리고 5분마다 하트비트.
// - 명령: 모든 명령에 ack. 알 수 없는 mediaId는 거부(권한 분리).
// - "브로커에 붙어 있음"(brokerState)과 "게스트가 붙어 있음"(guests)은 다르다. 게스트는 Hello/Bye와
//   lastSeen 만료(3분, GuestRegistry)로 센다. 화면(상단바 칩·QR 화면)은 companion의 StateFlow만 본다.
// - 알림은 딱 하나 "리모컨 대기 중"이며 상태 변화로 다시 게시하지 않는다.
// - 브로커 연결은 감독 루프(superviseChannel)가 책임진다(2026-10-06 사고: 부팅 때 한 번 실패하면 영원히
//   구독하지 않았다). 실패하면 5→10→20→40→60s 백오프로 서비스가 사는 동안 계속 재시도, 붙은 뒤 끊기면
//   채널을 새로 만들어 다시 붙는다. 네트워크 복구·"지금 다시 연결"은 남은 대기를 건너뛴다(최소 5s 간격).
// - 저장된 브로커 주소가 정책상 쓸 수 없으면(127.0.0.1 등, release) 연결하지 않고 status=InvalidConfig.
// - 화면용 요약 상태는 status(HostStatus) 하나로 본다.
class RemoteHostService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = StatePublishPolicy()
    private val registry = GuestRegistry()

    private var pairing: Pairing? = null
    private var channel: RemoteChannel? = null
    private var transport: MqttRemoteTransport? = null

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null

    private lateinit var stationRepository: StationRepository
    private lateinit var monitor: BluetoothOutputMonitor
    private lateinit var reconnector: BluetoothReconnector

    private var stations: List<Station> = emptyList()
    private var lastFingerprint: StateFingerprint? = null
    private var lastPublishedAtMs: Long? = null
    private var lastItems: List<RemoteItem> = emptyList()
    private var debounceJob: Job? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private var running = false

    // ---- 브로커 감독 상태(모두 main에서만 바뀐다) ----
    private var configProblem: BrokerUrlPolicy.Problem? = null
    // 마지막 정상 연결 이후 연속 시작 시도 횟수(1부터)와 연속 실패 횟수.
    private var startAttempt = 0
    private var consecutiveFailures = 0
    // 마지막 정상 연결 이후 처음 실패/끊긴 시각. 정상이면 null.
    private var failingSinceMs: Long? = null
    private var lastFailure: FailureKind? = null
    // "명령 토픽 구독까지 끝남"을 포함한 실효 상태(status 계산용). 전송 자체 상태는 brokerState.
    private var effectiveState = ConnectionState.DISCONNECTED
    private var lastAttemptStartMs = 0L
    // 즉시 재시도 요청(네트워크 복구·retryNow) 카운터. 어느 스레드에서 올려도 된다.
    private val retryRequests = MutableStateFlow(0L)
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    // 폰 리모컨 페어링(MQTT)이 있는지. 없으면 로컬 제어만 한다.
    private var hasPairing = false

    // ---- 로컬 제어(스마트싱스) ----
    private var localHost: LocalControlHost? = null
    private val localState = MutableStateFlow(LocalState.EMPTY)
    private var localDebounceJob: Job? = null
    private val stationArtCache = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            requestPublish()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val store = RemoteSettingsStore(this)
        val isHost = store.getRole() == RemoteRole.HOST
        val p = if (isHost) runCatching { store.getPairing() }.getOrNull() else null
        val localOn = isHost && store.smartThingsLocalEnabled
        if (!isHost || (p == null && !localOn)) {
            Log.i("ARMS", "리모컨 호스트: 역할이 HOST가 아니거나 페어링·스마트싱스 연결 모두 없음 → 종료")
            stopSelf()
            return
        }
        // 개발용 주소(127.0.0.1, adb reverse 전용)는 debug 빌드에서만 허용한다.
        val problem = p?.let { (BrokerUrlPolicy.check(it.brokerUrl, allowLoopback = BuildConfig.DEBUG) as? BrokerUrlPolicy.Result.Invalid)?.problem }
        if (!startInForeground(configError = problem != null, hasPairing = p != null)) {
            stopSelf()
            return
        }
        pairing = p
        hasPairing = p != null
        configProblem = problem
        running = true
        instance = this
        _guests.value = emptyList()
        _brokerState.value = ConnectionState.DISCONNECTED
        _isRunning.value = true
        updateStatus()
        Log.i("ARMS", if (p != null) "리모컨 호스트 시작 (pair ${p.pairId.take(4)}…)" else "리모컨 호스트 시작 (페어링 없음 · 스마트싱스 로컬 제어만)")

        stationRepository = StationRepository(this)
        monitor = BluetoothOutputMonitor(this)
        reconnector = BluetoothReconnector(this, monitor)
        monitor.start()
        registerBatteryReceiver()
        registerVolumeReceiver()
        connectController()
        if (p == null) {
            // 폰 리모컨 페어링 없음: 브로커는 건드리지 않는다(로컬 제어만).
        } else if (problem != null) {
            // 서비스는 살려 둔다(상단바 칩이 "리모컨 설정 오류"를 보여야 한다). 연결은 시도하지 않는다.
            Log.w("ARMS", "리모컨 호스트: 브로커 주소 사용 불가 ($problem)")
        } else {
            superviseChannel(p, store.clientId() + "-host")
            registerNetworkCallback()
            startBrokenLog()
        }
        observeStations()
        observeBluetooth()
        startHeartbeat()
        startLocalControl(store.localDeviceId())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (running) {
            running = false
            debounceJob?.cancel()
            localDebounceJob?.cancel()
            localHost?.stop()
            localHost = null
            batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
            batteryReceiver = null
            volumeReceiver?.let { runCatching { unregisterReceiver(it) } }
            volumeReceiver = null
            networkCallback?.let { cb ->
                runCatching { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb) }
            }
            networkCallback = null
            monitor.stop()
            controller?.removeListener(playerListener)
            controller?.release()
            controller = null
            controllerFuture?.let { f -> if (!f.isDone) f.cancel(false) }
            controllerFuture = null
            val ch = channel
            val tr = transport
            channel = null
            transport = null
            channelReady = false
            registry.clear()
            // 새 인스턴스가 이미 떠 있으면(재시작) 그쪽 값을 건드리지 않는다.
            if (instance === this) {
                instance = null
                _guests.value = emptyList()
                _brokerState.value = ConnectionState.DISCONNECTED
                _isRunning.value = false
                _status.value = HostStatus.NotRunning
            }
            // 브로커 연결 정리는 서비스 스코프 밖에서(취소돼도 끝까지) 짧게.
            CoroutineScope(Dispatchers.IO).launch {
                withTimeoutOrNull(3_000L) {
                    runCatching { ch?.stop() }
                    runCatching { tr?.disconnect() }
                }
            }
            Log.i("ARMS", "리모컨 호스트 종료")
        }
        scope.cancel()
        super.onDestroy()
    }

    // ---- 포그라운드/알림 ----

    private fun startInForeground(configError: Boolean, hasPairing: Boolean): Boolean {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "리모컨 호스트", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "폰 리모컨의 명령을 기다리는 동안 표시됩니다"
                    setShowBadge(false)
                },
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_radio)
            .setContentTitle(
                when {
                    configError -> "리모컨 설정 오류"
                    hasPairing -> "리모컨 대기 중"
                    else -> "홈 플레이어 대기 중"
                },
            )
            .setContentText(
                when {
                    configError -> "앱의 리모컨 페어링 화면에서 브로커 주소를 다시 설정하세요"
                    hasPairing -> "폰의 리모컨으로 이 기기를 조작할 수 있습니다"
                    else -> "같은 와이파이의 스마트싱스에서 이 기기를 조작할 수 있습니다"
                },
            )
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (e: Exception) {
            // API 34+: connectedDevice 타입은 BLUETOOTH_CONNECT 등 권한이 있어야 한다(없으면 SecurityException).
            // 백그라운드에서 시작하면 ForegroundServiceStartNotAllowedException.
            Log.w("ARMS", "리모컨 호스트: 포그라운드 전환 실패 ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    // ---- 세션 연결 ----

    private fun connectController() {
        val token = SessionToken(this, ComponentName(this, ARMSMediaLibraryService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        controllerFuture = future
        future.addListener({
            if (!running) { runCatching { future.get().release() }; return@addListener }
            val c = runCatching { future.get() }.getOrElse {
                Log.w("ARMS", "리모컨 호스트: 미디어 세션 연결 실패 ${it.javaClass.simpleName}")
                return@addListener
            }
            c.addListener(playerListener)
            controller = c
            Log.i("ARMS", "리모컨 호스트: 미디어 세션 연결")
            requestPublish()
        }, MoreExecutors.directExecutor())
    }

    // ---- 브로커 채널(감독 루프) ----

    // 서비스가 사는 동안 끝나지 않는다. 한 바퀴 = 새 전송·채널로 시작 시도 → 성공하면 끊길 때까지 명령 수신
    // → 정리 → (백오프) → 다음 바퀴. 전송은 autoReconnect=false로 만든다: HiveMQ 1.3.3은 자동 재접속이 켜져
    // 있으면 첫 connect가 실패해도 future가 끝나지 않고(재접속 성공까지 대기), disconnect()로도 그 재접속을
    // 멈출 수 없다 → 새 전송을 만들 때마다 같은 clientId의 클라이언트가 뒤에서 계속 붙으려 해 서로 쫓아낸다.
    // 그래서 재접속은 이 루프만 한다(끊김 즉시 감지·재구독·상태 재게시까지 한 경로).
    private fun superviseChannel(p: Pairing, clientId: String) {
        scope.launch {
            while (isActive) {
                val tick = retryRequests.value
                lastAttemptStartMs = System.currentTimeMillis()
                startAttempt++
                effectiveState = ConnectionState.CONNECTING
                updateStatus()

                val tr = MqttRemoteTransport(p.brokerUrl, p.username, p.password, clientId = clientId, autoReconnect = false)
                val ch = RemoteChannel(p, tr, scope)
                transport = tr
                channel = ch
                val stateJob = launch { tr.connectionState.collect { st -> onTransportState(st) } }

                val failure: Throwable? = try {
                    withTimeout(START_TIMEOUT_MS) { ch.start(asHost = true) }
                    null
                } catch (e: TimeoutCancellationException) {
                    e
                } catch (e: kotlinx.coroutines.CancellationException) {
                    stateJob.cancel()
                    throw e
                } catch (t: Throwable) {
                    t
                }

                if (failure == null) {
                    runConnected(ch, tr)
                    // 끊김: 바로 다음 바퀴(직전 시도 시작에서 최소 5s는 띄운다 — 붙자마자 끊기는 경우의 폭주 방지).
                    stateJob.cancel()
                    teardown(ch, tr)
                    waitForRetry(0L, tick)
                } else {
                    stateJob.cancel()
                    teardown(ch, tr)
                    val kind = failureKindOf(failure, tr)
                    lastFailure = kind
                    if (failingSinceMs == null) failingSinceMs = System.currentTimeMillis()
                    consecutiveFailures++
                    effectiveState = ConnectionState.DISCONNECTED
                    updateStatus()
                    val delayMs = StartRetryPolicy.nextDelayMs(consecutiveFailures)
                    Log.w("ARMS", "리모컨 호스트: 브로커 연결 실패 ($kind) — ${delayMs / 1000}s 후 재시도")
                    waitForRetry(delayMs, tick)
                }
            }
        }
    }

    // 채널이 구독까지 마친 상태. 전송이 끊길 때까지 명령을 받는다.
    private suspend fun runConnected(ch: RemoteChannel, tr: MqttRemoteTransport) {
        channelReady = true
        startAttempt = 0
        consecutiveFailures = 0
        val wasFailingSince = failingSinceMs
        failingSinceMs = null
        lastFailure = null
        effectiveState = ConnectionState.CONNECTED
        updateStatus()
        if (wasFailingSince != null) {
            Log.i("ARMS", "리모컨 호스트: 채널 시작 (미연결 ${(System.currentTimeMillis() - wasFailingSince) / 1000}s 만에 복구)")
        } else {
            Log.i("ARMS", "리모컨 호스트: 채널 시작")
        }
        requestPublish(forced = true)
        val commandJob = scope.launch {
            try {
                ch.commands.collect { msg -> onCommand(msg) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("ARMS", "리모컨 호스트: 명령 수신 중단 ${t.javaClass.simpleName}: ${t.message}")
            }
        }
        try {
            // 자동 재접속을 끈 전송이라 한 번 끊기면 다시 붙지 않는다 → 끊기는 즉시 이 바퀴를 끝낸다.
            tr.connectionState.first { it != ConnectionState.CONNECTED }
        } finally {
            commandJob.cancel()
            channelReady = false
        }
        val kind = tr.lastFailure.value ?: FailureKind.CLOSED
        lastFailure = kind
        failingSinceMs = System.currentTimeMillis()
        effectiveState = ConnectionState.DISCONNECTED
        updateStatus()
        Log.w("ARMS", "리모컨 호스트: 브로커 연결 끊김 ($kind) → 채널 다시 시작")
    }

    // delayMs 동안 기다리되, 그 사이(또는 이번 시도 중에) 즉시 재시도 요청이 오면 일찍 깬다.
    // 어떤 경우에도 직전 시도 시작으로부터 StartRetryPolicy.MIN_GAP_MS 안에는 다시 시도하지 않는다.
    private suspend fun waitForRetry(delayMs: Long, tickAtAttempt: Long) {
        val early = if (delayMs <= 0L) {
            false
        } else {
            withTimeoutOrNull(delayMs) { retryRequests.first { it != tickAtAttempt } } != null
        }
        if (early) Log.i("ARMS", "리모컨 호스트: 즉시 재시도 (남은 대기 건너뜀)")
        val gap = StartRetryPolicy.minGapRemainingMs(lastAttemptStartMs, System.currentTimeMillis())
        if (gap > 0L) delay(gap)
    }

    private fun failureKindOf(t: Throwable, tr: MqttRemoteTransport): FailureKind {
        if (t is TimeoutCancellationException) return tr.lastFailure.value ?: FailureKind.TIMEOUT
        val kind = RemoteFailure.classify(t)
        return if (kind == FailureKind.UNKNOWN) tr.lastFailure.value ?: kind else kind
    }

    private suspend fun teardown(ch: RemoteChannel, tr: MqttRemoteTransport) {
        if (channel === ch) channel = null
        if (transport === tr) transport = null
        channelReady = false
        withContext(NonCancellable) {
            withTimeoutOrNull(3_000L) { runCatching { ch.stop() } }
        }
    }

    private fun onTransportState(st: ConnectionState) {
        if (instance !== this) return
        if (_brokerState.value != st && (st == ConnectionState.CONNECTED || consecutiveFailures == 0)) {
            // 실패가 이어지는 동안의 CONNECTING/DISCONNECTED 반복은 "연결 실패" 한 줄로 충분하다.
            Log.i("ARMS", "리모컨 호스트: 브로커 $st")
        }
        _brokerState.value = st
    }

    // 네트워크(기본 네트워크)가 새로 잡히면 남은 백오프를 건너뛴다. 등록 직후 한 번 불리는 것은 무해하다.
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (running && effectiveState != ConnectionState.CONNECTED) {
                    retryRequests.update { it + 1 }
                }
            }
        }
        try {
            cm.registerDefaultNetworkCallback(cb)
            networkCallback = cb
        } catch (e: Exception) {
            Log.w("ARMS", "리모컨 호스트: 네트워크 콜백 등록 실패 ${e.javaClass.simpleName}")
        }
    }

    // 끊겨 있는 동안 10분마다 한 줄(사고 때는 상태가 바뀔 때만 로그가 남아 4일간 흔적이 없었다).
    private fun startBrokenLog() {
        scope.launch {
            var nextMinutes = BROKEN_LOG_EVERY_MIN
            while (isActive) {
                delay(BROKEN_LOG_CHECK_MS)
                val since = failingSinceMs
                if (since == null || effectiveState == ConnectionState.CONNECTED) {
                    nextMinutes = BROKEN_LOG_EVERY_MIN
                    continue
                }
                val minutes = (System.currentTimeMillis() - since) / 60_000L
                if (minutes >= nextMinutes) {
                    Log.w("ARMS", "리모컨 호스트: 브로커 미연결 ${minutes}분째 (${lastFailure ?: FailureKind.UNKNOWN})")
                    nextMinutes = (minutes / BROKEN_LOG_EVERY_MIN + 1) * BROKEN_LOG_EVERY_MIN
                }
            }
        }
    }

    private fun requestRetry() {
        retryRequests.update { it + 1 }
    }

    private fun updateStatus() {
        if (instance !== this) return
        _status.value = HostStatusPolicy.compute(
            running = running,
            configProblem = configProblem,
            brokerState = effectiveState,
            lastFailure = lastFailure,
            failingSinceMs = failingSinceMs,
            guests = registry.guests,
            attempt = startAttempt,
            hasPairing = hasPairing,
        )
    }

    // ---- 관찰: 채널 목록·블루투스·배터리·볼륨 ----

    private fun observeStations() {
        scope.launch {
            try {
                if (stationRepository.getAllStations().first().isEmpty()) {
                    runCatching { stationRepository.refreshStations() }
                }
                stationRepository.getAllStations().collectLatest { list ->
                    stations = list
                    requestPublish()
                }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("ARMS", "리모컨 호스트: 채널 목록 관찰 실패 ${t.javaClass.simpleName}")
            }
        }
    }

    private fun observeBluetooth() {
        scope.launch { monitor.status.collect { requestPublish() } }
    }

    private fun registerBatteryReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { requestPublish() }
        }
        ContextCompat.registerReceiver(this, r, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        batteryReceiver = r
    }

    // 미디어 볼륨이 바뀌면(하드웨어 키·시스템 UI·게스트 Volume 명령) 게스트 슬라이더가 따라오게 publish.
    // VOLUME_CHANGED_ACTION은 시스템만 보내는 보호 브로드캐스트(공개 상수는 없어 문자열 그대로).
    private fun registerVolumeReceiver() {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val stream = intent.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
                if (stream == -1 || stream == AudioManager.STREAM_MUSIC) requestPublish()
            }
        }
        val filter = IntentFilter(VOLUME_CHANGED_ACTION).apply { addAction(STREAM_MUTE_CHANGED_ACTION) }
        ContextCompat.registerReceiver(this, r, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        volumeReceiver = r
    }

    private fun batteryPercent(): Int? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val v = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)
        return v.takeIf { it in 0..100 }
    }

    private fun volumePercent(): Int? {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return null
        return runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max <= 0) null else (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100.0 / max).roundToInt().coerceIn(0, 100)
        }.getOrNull()
    }

    // ---- 상태 publish ----

    private fun snapshot(): SessionSnapshot {
        val c = controller ?: return SessionSnapshot.EMPTY
        val md = c.mediaMetadata
        return SessionSnapshot(
            mediaId = c.currentMediaItem?.mediaId,
            title = md.title?.toString(),
            artist = md.artist?.toString(),
            albumTitle = md.albumTitle?.toString(),
            isPlaying = c.isPlaying,
            playbackState = c.playbackState,
            artworkUri = md.artworkUri?.toString(),
            playWhenReady = c.playWhenReady,
            hasError = c.playerError != null,
        )
    }

    private fun buildState(nowMs: Long = System.currentTimeMillis(), revoked: Boolean = false): HostState =
        HostStateBuilder.build(
            snapshot(), stations, monitor.status.value, batteryPercent(), nowMs,
            volumePercent = volumePercent(),
            guests = registry.guests,
            revoked = revoked,
        )

    // 지문이 바뀌었으면 debounce 뒤 publish, forced면 즉시. 어느 스레드에서 불려도 main으로 옮긴다.
    private fun requestPublish(forced: Boolean = false) {
        if (!running) return
        requestLocalUpdate()
        scope.launch {
            val now = System.currentTimeMillis()
            val state = buildState(now)
            val fp = StatePublishPolicy.fingerprintOf(state)
            when (policy.decide(lastFingerprint, fp, lastPublishedAtMs, now, forced)) {
                PublishDecision.Skip -> Unit
                PublishDecision.Now -> { debounceJob?.cancel(); publishNow() }
                PublishDecision.Debounced -> {
                    debounceJob?.cancel()
                    debounceJob = launch {
                        delay(policy.debounceMs)
                        publishNow()
                    }
                }
            }
        }
    }

    // 채널이 구독까지 마치기 전(플레이어·BT·배터리 이벤트가 먼저 올 수 있다)에는 publish하지 않는다.
    // 시작 완료 시 forced publish가 한 번 나가므로 놓치는 상태는 없다. (S22 rc2: 시작 24ms 뒤 실패 로그 2건)
    @Volatile private var channelReady = false

    private suspend fun publishNow() {
        val ch = channel ?: return
        if (!channelReady) return
        val state = buildState()
        try {
            ch.publishState(state)
            lastFingerprint = StatePublishPolicy.fingerprintOf(state)
            lastPublishedAtMs = state.updatedAtMs
            lastItems = state.items
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w("ARMS", "리모컨 호스트: 상태 publish 실패 ${t.javaClass.simpleName}")
        }
    }

    // "연결 종료": revoked=true 상태를 한 번 publish 하고(게스트가 페어링을 지우게), retained 상태를 비운다.
    // 각 단계는 짧게 제한하고 실패해도 예외를 내지 않는다(어차피 이어서 페어링을 지우고 서비스를 끈다).
    private suspend fun publishRevokedAndClear() {
        val ch = channel ?: return
        if (!channelReady) return
        withTimeoutOrNull(REVOKE_TIMEOUT_MS) {
            runCatching { ch.publishState(withContext(Dispatchers.Main.immediate) { buildState(revoked = true) }) }
                .onFailure { Log.w("ARMS", "리모컨 호스트: 연결 종료 publish 실패 ${it.javaClass.simpleName}") }
                .onSuccess { Log.i("ARMS", "리모컨 호스트: 연결 종료 알림 publish") }
        }
        // 온라인 게스트가 revoked 상태를 받을 시간을 잠깐 주고 지운다(retained라 나중에 붙는 게스트에겐 안 남긴다).
        delay(REVOKE_SETTLE_MS)
        withTimeoutOrNull(REVOKE_TIMEOUT_MS) {
            runCatching { ch.clearState() }
                .onFailure { Log.w("ARMS", "리모컨 호스트: retained 상태 삭제 실패 ${it.javaClass.simpleName}") }
                .onSuccess { Log.i("ARMS", "리모컨 호스트: retained 상태 삭제") }
        }
    }

    // 하트비트(5분)와 게스트 만료 정리(30초 간격). 만료로 목록이 바뀌면 그것도 publish.
    private fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                delay(policy.heartbeatDelayMs(lastPublishedAtMs, now).coerceIn(1_000L, GUEST_SWEEP_MS))
                val tick = System.currentTimeMillis()
                if (registry.sweep(tick)) {
                    Log.i("ARMS", "리모컨 호스트: 게스트 응답 없음 → 목록에서 제거 (남은 ${registry.guests.size}대)")
                    publishGuests()
                    requestPublish()
                }
                if (policy.isHeartbeatDue(lastPublishedAtMs, tick)) publishNow()
            }
        }
    }

    private fun publishGuests() {
        if (instance === this) _guests.value = registry.guests
        updateStatus()
    }

    // ---- 명령 처리 ----

    private suspend fun onCommand(msg: RemoteMessage.Command) {
        val cmd = msg.command
        Log.i("ARMS", "리모컨 명령 #${msg.seq} ${cmd::class.simpleName}")
        when (cmd) {
            is RemoteCommand.Hello -> {
                val changed = registry.hello(cmd.guestName, System.currentTimeMillis())
                publishGuests()
                if (changed) Log.i("ARMS", "리모컨 호스트: 게스트 연결 (${registry.guests.size}대)")
                ack(msg.seq, true, null)
                // 이름 목록이 바뀌면 지문이 달라져 publish 된다(게스트 자신도 상태에서 자기를 본다).
                requestPublish()
                return
            }
            RemoteCommand.Bye -> {
                val name = oldestGuestName()
                val changed = name != null && registry.bye(name)
                publishGuests()
                if (changed) Log.i("ARMS", "리모컨 호스트: 게스트 연결 해제 (${registry.guests.size}대)")
                ack(msg.seq, true, null)
                requestPublish()
                return
            }
            RemoteCommand.BtReconnect -> {
                // 최대 8초 이상 걸릴 수 있으므로 다른 명령을 막지 않게 따로 돌린다.
                scope.launch {
                    val result = reconnector.reconnect()
                    ack(msg.seq, result.isSuccess, result.describe())
                    requestPublish(forced = true)
                }
                return
            }
            else -> Unit
        }
        val (ok, message) = try {
            execute(cmd)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            false to (t.message ?: t.javaClass.simpleName)
        }
        ack(msg.seq, ok, message)
        if (cmd is RemoteCommand.Refresh) requestPublish(forced = true) else requestPublish()
    }

    // Bye에는 이름이 없다(프로토콜). 페어링 1회 = 게스트 1대(플랜 §5)이므로 붙어 있는 게스트가 하나면
    // 그 게스트로, 여럿이면 가장 오래 인사가 없던 게스트로 본다(어차피 3분 뒤 만료된다).
    private fun oldestGuestName(): String? = registry.guests.minByOrNull { it.lastSeenMs }?.name

    private suspend fun execute(cmd: RemoteCommand): Pair<Boolean, String?> {
        if (cmd is RemoteCommand.Refresh) return true to null
        if (cmd is RemoteCommand.Volume) return setVolume(cmd.percent)
        val c = controller ?: return false to NO_SESSION
        return when (cmd) {
            RemoteCommand.Play -> play(c)
            RemoteCommand.Pause -> { c.pause(); true to null }
            RemoteCommand.Stop -> { c.stop(); true to null }
            RemoteCommand.Next -> { c.seekToNext(); true to null }
            RemoteCommand.Previous -> { c.seekToPrevious(); true to null }
            is RemoteCommand.Select -> select(c, cmd.mediaId)
            else -> false to "지원하지 않는 명령"
        }
    }

    private suspend fun play(c: MediaController): Pair<Boolean, String?> {
        if (c.mediaItemCount == 0) {
            // 큐가 비어 있으면(앱 재시작 직후) 마지막 채널부터. 서비스가 mediaId를 해석한다.
            val last = withContext(Dispatchers.IO) { runCatching { stationRepository.getLastPlayedStationId() }.getOrNull() }
                ?: stations.firstOrNull()?.id
                ?: return false to "재생할 항목 없음"
            c.setMediaItem(MediaItem.Builder().setMediaId(last).build())
            c.prepare(); c.play()
            return true to null
        }
        // 외부 STOP으로 IDLE이 된 뒤에는 prepare()가 있어야 다시 재생된다(SessionAudioPlayer.resume과 동일).
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
        return true to null
    }

    private fun select(c: MediaController, mediaId: String): Pair<Boolean, String?> {
        val known = HostStateBuilder.items(stations, snapshot())
        if (!HostStateBuilder.isKnownMediaId(mediaId, known)) return false to "알 수 없는 항목"
        if (c.currentMediaItem?.mediaId == mediaId && c.playbackState != Player.STATE_IDLE) {
            // 같은 항목이면 재개만(NAS 앨범을 처음부터 다시 틀지 않는다).
            c.play()
            return true to null
        }
        // SessionAudioPlayer.playStation과 같은 경로: 서비스의 onAddMediaItems가 URL·메타데이터를 채운다.
        c.setMediaItem(MediaItem.Builder().setMediaId(mediaId).build())
        c.prepare(); c.play()
        return true to null
    }

    private fun setVolume(percent: Int): Pair<Boolean, String?> {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false to "오디오 서비스 없음"
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val index = (max * percent.coerceIn(0, 100) / 100.0).roundToInt().coerceIn(0, max)
        return try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
            true to null
        } catch (e: SecurityException) {
            false to "볼륨 변경 권한 없음(방해 금지 모드)"
        }
    }

    // ---- 로컬 제어(스마트싱스) ----

    private fun startLocalControl(deviceId: String) {
        localDeviceId = deviceId
        val host = LocalControlHost(this, localBackend, scope, deviceId)
        localHost = host
        LocalControl.init(this)
        scope.launch {
            LocalControl.enabled.collect { on ->
                if (on) {
                    host.start()
                    requestLocalUpdate()
                } else {
                    host.stop()
                }
            }
        }
    }

    private fun isMuted(): Boolean {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return false
        return runCatching { am.isStreamMute(AudioManager.STREAM_MUSIC) }.getOrDefault(false)
    }

    private fun buildLocalState(nowMs: Long = System.currentTimeMillis()): LocalState =
        LocalStateMapper.map(
            snapshot(), stations, volumePercent(), isMuted(),
            hasStationArt = { StationRepository.channelArtRes(it) != null },
            nowMs = nowMs,
        )

    // 플레이어·채널 목록·볼륨·음소거가 바뀌면 200ms 모아서 상태를 갱신한다(SSE로 나감). 내용이 같으면 그대로 둔다.
    private fun requestLocalUpdate() {
        if (!running || localHost?.isStarted != true) return
        scope.launch {
            localDebounceJob?.cancel()
            localDebounceJob = launch {
                delay(LOCAL_DEBOUNCE_MS)
                val next = buildLocalState()
                if (!next.sameContentAs(localState.value)) localState.value = next
            }
        }
    }

    private val localBackend = object : LocalBackend {
        override val states: StateFlow<LocalState> = localState.asStateFlow()

        override suspend fun currentState(): LocalState = withContext(Dispatchers.Main.immediate) {
            buildLocalState().also { if (!it.sameContentAs(localState.value)) localState.value = it }
        }

        override fun deviceInfo(): DeviceInfo = DeviceInfo(
            id = localDeviceId,
            name = LocalControl.displayName(),
            model = Build.MODEL,
            appVersion = BuildConfig.VERSION_NAME,
        )

        override suspend fun execute(command: LocalCommand): CommandOutcome = withContext(Dispatchers.Main.immediate) {
            val outcome = executeLocal(command)
            requestPublish()
            outcome
        }

        override fun lanBaseUrl(): String? = localHost?.baseUrl()

        override fun stationArtPng(stationId: String): ByteArray? {
            stationArtCache[stationId]?.let { return it }
            val res = StationRepository.channelArtRes(stationId) ?: return null
            val bytes = runCatching { resources.openRawResource(res).use { it.readBytes() } }.getOrNull() ?: return null
            stationArtCache[stationId] = bytes
            return bytes
        }
    }

    private var localDeviceId: String = ""

    // 로컬 명령 → MQTT와 같은 실행 경로(execute/play/select/setVolume). main에서 부른다.
    private suspend fun executeLocal(cmd: LocalCommand): CommandOutcome {
        fun of(r: Pair<Boolean, String?>): CommandOutcome =
            if (r.first) CommandOutcome.OK
            else if (r.second == NO_SESSION) CommandOutcome.unavailable(NO_SESSION)
            else CommandOutcome.failed(r.second ?: "실패")
        return when (cmd) {
            LocalCommand.On -> {
                val c = controller ?: return CommandOutcome.unavailable(NO_SESSION)
                if (c.isPlaying) CommandOutcome.OK else of(play(c))
            }
            // 라디오(플레이어)만 멈춘다. 화면·다른 기능은 건드리지 않는다.
            LocalCommand.Off -> of(execute(RemoteCommand.Stop))
            LocalCommand.Play -> of(execute(RemoteCommand.Play))
            LocalCommand.Pause -> of(execute(RemoteCommand.Pause))
            LocalCommand.Stop -> of(execute(RemoteCommand.Stop))
            LocalCommand.Next -> of(execute(RemoteCommand.Next))
            LocalCommand.Previous -> of(execute(RemoteCommand.Previous))
            is LocalCommand.SetVolume -> of(execute(RemoteCommand.Volume(cmd.percent)))
            LocalCommand.VolumeUp -> adjustVolume(AudioManager.ADJUST_RAISE)
            LocalCommand.VolumeDown -> adjustVolume(AudioManager.ADJUST_LOWER)
            LocalCommand.Mute -> adjustVolume(AudioManager.ADJUST_MUTE)
            LocalCommand.Unmute -> adjustVolume(AudioManager.ADJUST_UNMUTE)
            is LocalCommand.PlayPreset -> {
                if (stations.none { it.id == cmd.id }) return CommandOutcome.badRequest("unknown preset")
                of(execute(RemoteCommand.Select(cmd.id)))
            }
        }
    }

    private fun adjustVolume(direction: Int): CommandOutcome {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return CommandOutcome.failed("오디오 서비스 없음")
        return try {
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
            CommandOutcome.OK
        } catch (e: SecurityException) {
            CommandOutcome.failed("볼륨 변경 권한 없음(방해 금지 모드)")
        }
    }

    private suspend fun ack(seq: Long, ok: Boolean, message: String?) {
        val ch = channel ?: return
        try {
            ch.sendAck(seq, ok, message)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            Log.w("ARMS", "리모컨 호스트: ack 전송 실패 ${t.javaClass.simpleName}")
        }
        if (!ok) Log.i("ARMS", "리모컨 명령 #$seq 거부: $message")
    }

    companion object {
        const val CHANNEL_ID = "remote_host"
        private const val NOTIFICATION_ID = 0x5248 // "RH"
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"
        // 음소거 변경(시스템 보호 브로드캐스트, 공개 상수 없음)
        private const val STREAM_MUTE_CHANGED_ACTION = "android.media.STREAM_MUTE_CHANGED_ACTION"
        private const val LOCAL_DEBOUNCE_MS = 200L
        private const val NO_SESSION = "미디어 세션에 연결되지 않음"
        private const val GUEST_SWEEP_MS = 30_000L
        private const val REVOKE_TIMEOUT_MS = 2_000L
        private const val REVOKE_SETTLE_MS = 500L
        // 한 번의 시작 시도(connect+subscribe) 상한. HiveMQ 자체 소켓 타임아웃(10s)보다 넉넉히.
        private const val START_TIMEOUT_MS = 30_000L
        private const val BROKEN_LOG_EVERY_MIN = 10L
        private const val BROKEN_LOG_CHECK_MS = 30_000L

        // 서비스는 프로세스당 하나. 화면(상단바 칩·페어링 화면)이 서비스에 바인드하지 않고 이 흐름만 본다.
        // 서비스가 꺼져 있으면 각각 false / 빈 목록 / DISCONNECTED.
        @Volatile private var instance: RemoteHostService? = null
        private val _isRunning = MutableStateFlow(false)
        private val _guests = MutableStateFlow<List<RemoteGuest>>(emptyList())
        private val _brokerState = MutableStateFlow(ConnectionState.DISCONNECTED)
        private val _status = MutableStateFlow<HostStatus>(HostStatus.NotRunning)

        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()
        // 지금 붙어 있는 게스트(Hello 수신 순). 브로커 연결과 무관하게 "누가 리모컨을 열고 있나"만 뜻한다.
        val guests: StateFlow<List<RemoteGuest>> = _guests.asStateFlow()
        // 브로커 연결 상태. "연결됨" 표시의 근거가 아니라 진단용.
        val brokerState: StateFlow<ConnectionState> = _brokerState.asStateFlow()
        // 화면용 요약 상태(설정 오류/연결 중/오프라인/준비됨+게스트). 서비스가 꺼져 있으면 NotRunning.
        val status: StateFlow<HostStatus> = _status.asStateFlow()

        private fun intent(context: Context) = Intent(context, RemoteHostService::class.java)

        // API 34+에서 connectedDevice 포그라운드 타입은 전제 권한이 하나 있어야 시작된다: BLUETOOTH_CONNECT(런타임)
        // 또는 CHANGE_WIFI_MULTICAST_STATE 같은 일반 권한(매니페스트 선언만으로 허용). 후자 덕에 블루투스 권한을
        // 아직 안 준 태블릿도 스마트싱스 로컬 제어용으로 서비스를 띄울 수 있다.
        fun hasForegroundPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.CHANGE_WIFI_MULTICAST_STATE) == PackageManager.PERMISSION_GRANTED

        // 서비스가 떠 있어야 하는지: 호스트 역할이고, 폰 리모컨 페어링이 있거나 스마트싱스 로컬 제어가 켜져 있으면.
        fun shouldRun(store: RemoteSettingsStore): Boolean =
            store.getRole() == RemoteRole.HOST &&
                (runCatching { store.getPairing() }.getOrNull() != null || store.smartThingsLocalEnabled)

        fun start(context: Context) {
            if (!hasForegroundPermission(context)) {
                Log.w("ARMS", "리모컨 호스트: 포그라운드 전제 권한 없음 → 시작하지 않음")
                return
            }
            try {
                ContextCompat.startForegroundService(context, intent(context))
            } catch (e: Exception) {
                Log.w("ARMS", "리모컨 호스트: 시작 실패 ${e.javaClass.simpleName}")
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(intent(context)) }
        }

        // "지금 다시 연결": 서비스가 떠 있으면 남은 백오프를 건너뛰고 바로 다시 붙는다(최소 5s 간격은 지킨다).
        // 꺼져 있으면 역할/페어링에 맞춰 켠다(암호화 저장소 열기는 백그라운드에서). 어느 스레드에서 불러도 된다.
        fun retryNow(context: Context) {
            val live = instance
            if (live != null && live.running) {
                Log.i("ARMS", "리모컨 호스트: 지금 다시 연결 요청")
                live.requestRetry()
                return
            }
            val app = context.applicationContext
            CoroutineScope(Dispatchers.IO).launch { runCatching { syncWithRole(app) } }
        }

        // 페어링을 새로 만들었을 때: 서비스는 onCreate에서만 페어링을 읽으므로 껐다 켠다(둘 다 main 큐에서 순서대로).
        fun restart(context: Context) {
            stop(context)
            start(context)
        }

        // 역할/페어링/스마트싱스 설정에 맞춰 켜거나 끈다. 역할 변경·페어링 저장/해제·앱 시작 시 부른다.
        // RemoteSettingsStore 생성(암호화 저장소 열기)은 느릴 수 있으니 가능하면 백그라운드에서.
        fun syncWithRole(context: Context) {
            val store = RemoteSettingsStore(context)
            if (shouldRun(store)) start(context) else stop(context)
        }

        // "연결 종료"/"페어링 해제": 게스트에게 revoked 상태를 한 번 알리고(≤2s), retained 상태를 지우고,
        // 페어링을 삭제(브로커 설정은 남김)한 뒤 서비스를 끈다. 서비스가 안 떠 있으면 임시 채널로 같은 일을 한다.
        // 전체가 몇 초 안에 끝나며 예외를 내지 않는다.
        suspend fun revokeAndUnpair(context: Context) {
            val store = RemoteSettingsStore(context)
            val pairing = runCatching { store.getPairing() }.getOrNull()
            val live = instance
            if (live != null && live.running) {
                live.publishRevokedAndClear()
            } else if (pairing != null) {
                revokeWithTemporaryChannel(store, pairing)
            }
            store.clearPairing()
            // 서비스는 onCreate에서만 페어링을 읽는다 → 끄고, 스마트싱스 로컬 제어가 켜져 있으면 페어링 없이 다시 켠다.
            stop(context)
            if (shouldRun(store)) start(context)
            Log.i("ARMS", "리모컨 호스트: 연결 종료 · 페어링 삭제")
        }

        // 페어링 해제 시 브로커에 남은 retained 상태를 지운다(플랜 §3). 실패해도 조용히 넘어간다.
        suspend fun clearRetainedState(context: Context, pairing: Pairing) {
            val store = RemoteSettingsStore(context)
            withTemporaryChannel(store, pairing, "-host-clear") { ch ->
                ch.clearState()
                Log.i("ARMS", "리모컨 호스트: retained 상태 삭제")
            }
        }

        private suspend fun revokeWithTemporaryChannel(store: RemoteSettingsStore, pairing: Pairing) {
            withTemporaryChannel(store, pairing, "-host-revoke") { ch ->
                val now = System.currentTimeMillis()
                val state = HostStateBuilder.build(SessionSnapshot.EMPTY, emptyList(), null, null, now, revoked = true)
                withTimeoutOrNull(REVOKE_TIMEOUT_MS) { ch.publishState(state) }
                delay(REVOKE_SETTLE_MS)
                ch.clearState()
                Log.i("ARMS", "리모컨 호스트: 연결 종료 알림 publish · retained 상태 삭제")
            }
        }

        private suspend fun withTemporaryChannel(
            store: RemoteSettingsStore,
            pairing: Pairing,
            clientSuffix: String,
            block: suspend (RemoteChannel) -> Unit,
        ) {
            if (BrokerUrlPolicy.check(pairing.brokerUrl, allowLoopback = BuildConfig.DEBUG) is BrokerUrlPolicy.Result.Invalid) return
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(5_000L) {
                    // 일회성 연결: 자동 재접속을 켜면 실패 시 connect가 끝나지 않고 클라이언트가 뒤에서 계속 붙으려 한다.
                    val tr = MqttRemoteTransport(
                        pairing.brokerUrl, pairing.username, pairing.password,
                        clientId = store.clientId() + clientSuffix, autoReconnect = false,
                    )
                    val ch = RemoteChannel(pairing, tr, this)
                    try {
                        ch.start(asHost = true)
                        block(ch)
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.w("ARMS", "리모컨 호스트: 브로커 정리 실패 ${t.javaClass.simpleName}")
                    } finally {
                        runCatching { ch.stop() }
                        runCatching { tr.disconnect() }
                    }
                }
            }
        }
    }
}
