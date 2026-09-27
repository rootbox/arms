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
import com.arms.androidauto.MainActivity
import com.arms.androidauto.R
import com.arms.androidauto.core.data.StationRepository
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.remote.HostState
import com.arms.androidauto.core.remote.MqttRemoteTransport
import com.arms.androidauto.core.remote.Pairing
import com.arms.androidauto.core.remote.RemoteChannel
import com.arms.androidauto.core.remote.RemoteCommand
import com.arms.androidauto.core.remote.RemoteItem
import com.arms.androidauto.core.remote.RemoteMessage
import com.arms.androidauto.remote.RemoteRole
import com.arms.androidauto.remote.RemoteSettingsStore
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

// 원격 제어 호스트(벽걸이 태블릿). 플랜 §4.
//
// - 역할이 HOST이고 페어링이 있을 때만 산다(아니면 즉시 stopSelf).
// - 브로커에 상시 연결(RemoteChannel/MqttRemoteTransport), 세션(ARMSMediaLibraryService)에는
//   폰 화면과 똑같이 MediaController 하나로 붙는다(SessionAudioPlayer와 같은 경로 → 플레이어는 하나).
// - 상태 publish: 지문(StatePublishPolicy)이 바뀌면 500ms debounce 뒤, 그리고 5분마다 하트비트.
// - 명령: 모든 명령에 ack. 알 수 없는 mediaId는 거부(권한 분리).
// - 알림은 딱 하나 "리모컨 대기 중"이며 상태 변화로 다시 게시하지 않는다.
class RemoteHostService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val policy = StatePublishPolicy()

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
    private var running = false

    private val playerListener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            requestPublish()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val store = RemoteSettingsStore(this)
        val p = if (store.getRole() == RemoteRole.HOST) runCatching { store.getPairing() }.getOrNull() else null
        if (p == null) {
            Log.i("ARMS", "리모컨 호스트: 역할이 HOST가 아니거나 페어링 없음 → 종료")
            stopSelf()
            return
        }
        if (!startInForeground()) {
            stopSelf()
            return
        }
        pairing = p
        running = true
        Log.i("ARMS", "리모컨 호스트 시작 (pair ${p.pairId.take(4)}…)")

        stationRepository = StationRepository(this)
        monitor = BluetoothOutputMonitor(this)
        reconnector = BluetoothReconnector(this, monitor)
        monitor.start()
        registerBatteryReceiver()
        connectController()
        startChannel(p, store.clientId() + "-host")
        observeStations()
        observeBluetooth()
        startHeartbeat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        if (running) {
            running = false
            debounceJob?.cancel()
            batteryReceiver?.let { runCatching { unregisterReceiver(it) } }
            batteryReceiver = null
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

    private fun startInForeground(): Boolean {
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
            .setContentTitle("리모컨 대기 중")
            .setContentText("폰의 리모컨으로 이 기기를 조작할 수 있습니다")
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

    // ---- 브로커 채널 ----

    private fun startChannel(p: Pairing, clientId: String) {
        val tr = MqttRemoteTransport(p.brokerUrl, p.username, p.password, clientId = clientId)
        val ch = RemoteChannel(p, tr, scope)
        transport = tr
        channel = ch
        scope.launch {
            tr.connectionState.collect { Log.i("ARMS", "리모컨 호스트: 브로커 $it") }
        }
        scope.launch {
            try {
                ch.start(asHost = true)
                channelReady = true
                Log.i("ARMS", "리모컨 호스트: 채널 시작")
                requestPublish(forced = true)
            } catch (t: Throwable) {
                Log.w("ARMS", "리모컨 호스트: 채널 시작 실패 ${t.javaClass.simpleName}: ${t.message}")
                return@launch
            }
            try {
                ch.commands.collect { msg -> onCommand(msg) }
            } catch (t: Throwable) {
                if (t is kotlinx.coroutines.CancellationException) throw t
                Log.w("ARMS", "리모컨 호스트: 명령 수신 중단 ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    // ---- 관찰: 채널 목록·블루투스·배터리 ----

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

    private fun batteryPercent(): Int? {
        val bm = getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return null
        val v = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) }.getOrDefault(-1)
        return v.takeIf { it in 0..100 }
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
        )
    }

    private fun buildState(nowMs: Long = System.currentTimeMillis()): HostState =
        HostStateBuilder.build(snapshot(), stations, monitor.status.value, batteryPercent(), nowMs)

    // 지문이 바뀌었으면 debounce 뒤 publish, forced면 즉시. 어느 스레드에서 불려도 main으로 옮긴다.
    private fun requestPublish(forced: Boolean = false) {
        if (!running) return
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

    private fun startHeartbeat() {
        scope.launch {
            while (isActive) {
                delay(policy.heartbeatDelayMs(lastPublishedAtMs, System.currentTimeMillis()).coerceAtLeast(1_000L))
                if (policy.isHeartbeatDue(lastPublishedAtMs, System.currentTimeMillis())) publishNow()
            }
        }
    }

    // ---- 명령 처리 ----

    private suspend fun onCommand(msg: RemoteMessage.Command) {
        val cmd = msg.command
        Log.i("ARMS", "리모컨 명령 #${msg.seq} ${cmd::class.simpleName}")
        if (cmd is RemoteCommand.BtReconnect) {
            // 최대 8초 이상 걸릴 수 있으므로 다른 명령을 막지 않게 따로 돌린다.
            scope.launch {
                val result = reconnector.reconnect()
                ack(msg.seq, result.isSuccess, result.describe())
                requestPublish(forced = true)
            }
            return
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

    private suspend fun execute(cmd: RemoteCommand): Pair<Boolean, String?> {
        if (cmd is RemoteCommand.Refresh) return true to null
        if (cmd is RemoteCommand.Volume) return setVolume(cmd.percent)
        val c = controller ?: return false to "미디어 세션에 연결되지 않음"
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

        private fun intent(context: Context) = Intent(context, RemoteHostService::class.java)

        // API 34+에서 connectedDevice 포그라운드 타입은 BLUETOOTH_CONNECT(런타임) 등이 있어야 시작된다.
        fun hasForegroundPermission(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun isHostConfigured(store: RemoteSettingsStore): Boolean =
            store.getRole() == RemoteRole.HOST && runCatching { store.getPairing() }.getOrNull() != null

        fun start(context: Context) {
            if (!hasForegroundPermission(context)) {
                Log.w("ARMS", "리모컨 호스트: BLUETOOTH_CONNECT 권한 없음 → 시작하지 않음")
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

        // 역할/페어링 상태에 맞춰 켜거나 끈다. 역할 변경·페어링 저장/해제·앱 시작 시 부른다.
        // RemoteSettingsStore 생성(암호화 저장소 열기)은 느릴 수 있으니 가능하면 백그라운드에서.
        fun syncWithRole(context: Context) {
            val store = RemoteSettingsStore(context)
            if (isHostConfigured(store)) start(context) else stop(context)
        }

        // 페어링 해제 시 브로커에 남은 retained 상태를 지운다(플랜 §3). 실패해도 조용히 넘어간다.
        suspend fun clearRetainedState(context: Context, pairing: Pairing) {
            val store = RemoteSettingsStore(context)
            withContext(Dispatchers.IO) {
                withTimeoutOrNull(5_000L) {
                    val tr = MqttRemoteTransport(pairing.brokerUrl, pairing.username, pairing.password, clientId = store.clientId() + "-host-clear")
                    val ch = RemoteChannel(pairing, tr, this)
                    try {
                        ch.start(asHost = true)
                        ch.clearState()
                        Log.i("ARMS", "리모컨 호스트: retained 상태 삭제")
                    } catch (t: Throwable) {
                        if (t is kotlinx.coroutines.CancellationException) throw t
                        Log.w("ARMS", "리모컨 호스트: retained 상태 삭제 실패 ${t.javaClass.simpleName}")
                    } finally {
                        runCatching { ch.stop() }
                        runCatching { tr.disconnect() }
                    }
                }
            }
        }
    }
}
