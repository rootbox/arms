package com.arms.androidauto

import android.content.Context
import android.media.AudioManager
import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.car.app.connection.CarConnection
import androidx.lifecycle.Observer
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.arms.androidauto.auto.MediaIdScheme
import com.arms.androidauto.auto.NasBrowseTree
import com.arms.androidauto.core.data.LastPlayed
import com.arms.androidauto.core.data.NasMusicRepository
import com.arms.androidauto.core.data.NasPlaylistRepository
import com.arms.androidauto.core.data.PlaybackStateStore
import com.arms.androidauto.core.data.StationRepository
import com.arms.androidauto.core.model.Station
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// 차량 호스트 앱의 패키지명. 이 목록에 있는 컨트롤러가 모두 빠지면 "차량 연결이 끝났다"로 본다.
internal val CAR_CONTROLLER_PACKAGES = setOf(
    "com.google.android.projection.gearhead", // Android Auto (폰 투영)
    "com.android.car.media", // Automotive OS 미디어 센터
    "com.android.car.carlauncher" // Automotive OS 런처
)

// 차량 화면의 셔플/반복 버튼. Android Auto는 셔플·반복을 기본 버튼으로 그려주지 않고
// "커스텀 액션"으로 올린 것만 표시하기 때문에, 세션 커스텀 커맨드로 직접 올려야 한다.
private const val ACTION_TOGGLE_SHUFFLE = "com.arms.androidauto.TOGGLE_SHUFFLE"
private const val ACTION_CYCLE_REPEAT = "com.arms.androidauto.CYCLE_REPEAT"

// 커버 이미지 재시도 간격. 편성/곡 정보는 그대로인데 커버만 못 받은 경우, 매 8초 주기마다
// 두드리지 않고 8s -> 16s -> 32s ... 최대 2분 간격으로 다시 받아본다. 포기하지는 않는다 -
// 최악이라도 2분에 한 번 작은 요청 하나이고, 회색 화면이 편성이 바뀔 때까지 남는 것보다 낫다.
internal object ArtworkRetryPolicy {
    private const val BASE_MS = 8_000L
    private const val MAX_MS = 120_000L
    fun delayMs(attempt: Int): Long {
        val shift = attempt.coerceIn(0, 10)
        return (BASE_MS shl shift).coerceAtMost(MAX_MS)
    }
}

// 커버 응답을 이미지로 받아들일지 판단한다. 예전에는 상태코드도 Content-Type도 보지 않고
// 본문을 통째로 .jpg로 저장해서, CDN 오류 페이지(HTML)가 이미지로 저장돼 회색으로 보였다.
// Content-Type이 없는 서버도 있으므로 그 경우는 통과시키고 디코딩 검사에 맡긴다.
internal object ArtworkResponsePolicy {
    fun isAcceptable(statusCode: Int, contentType: String?): Boolean {
        if (statusCode !in 200..299) return false
        if (contentType == null) return true
        return contentType.trim().lowercase().startsWith("image/")
    }
}

// 재생이 멈춘 이유별로 "우리가 다시 살려야 하는가"를 정한다.
// 규칙을 한 곳에 모아둬야, 나중에 새 사유가 생겼을 때 되살리면 안 되는 것(이어폰 분리 등)을
// 실수로 포함시키지 않는다.
internal object AudioFocusResumePolicy {
    fun shouldAutoResume(playWhenReadyChangeReason: Int): Boolean =
        when (playWhenReadyChangeReason) {
            // 내비 음성인식/어시스턴트 등이 포커스를 가져가 멈춘 경우
            Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS,
            // 포커스 억제가 너무 오래 지속돼 ExoPlayer가 재생을 내려놓은 경우
            Player.PLAY_WHEN_READY_CHANGE_REASON_SUPPRESSED_TOO_LONG -> true
            // 사용자가 직접 멈췄거나(USER_REQUEST/REMOTE), 이어폰이 빠졌거나
            // (AUDIO_BECOMING_NOISY), 재생이 끝난 경우는 의도된 정지다.
            else -> false
        }
}

// 블루투스 모드(Android Auto 없이 폰이 재생을 시작해 차량 스피커로 내보내는 경우) 정책.
// 이 서비스의 정지/재개 로직은 원래 "플레이어는 차량(Android Auto) 전용"을 전제로 짜였다.
// 폰 화면의 재생도 이 세션을 타게 되면서, 차량이 시작한 재생과 폰이 시작한 재생을 구분해야
// 폰 재생이 Android Auto 연결 상태에 휘둘리지 않는다. 규칙을 한 곳에 모아 단위 테스트한다.
internal object BluetoothModePolicy {
    // Android Auto가 끊겼을 때 정지·큐 폐기는 차량이 시작한 재생에만 적용한다. 폰이 시작한
    // 재생(블루투스/폰 스피커)은 차량 링크와 무관하다.
    fun shouldStopOnCarDisconnect(initiatedByCar: Boolean): Boolean = initiatedByCar

    // 포커스 상실 후 자동 재개를 "차가 없으면 포기"하는 규칙도 차량 시작 재생에만. 폰이 시작한
    // 재생은 내비 음성안내 뒤에 되살아나야 한다(그게 블루투스 모드의 핵심 기대 동작).
    fun shouldSkipFocusResume(initiatedByCar: Boolean, carDefinitelyDisconnected: Boolean): Boolean =
        initiatedByCar && carDefinitelyDisconnected

    // 출력 경로 소실(블루투스 끊김·이어폰 분리): 라디오는 실시간이라 의미 있는 일시정지 지점이
    // 없으므로 완전히 멈추고 큐를 비운다(다음 연결에서 onPlaybackResumption이 새 URL로 재개).
    // NAS 음악은 ExoPlayer의 기본 일시정지로 두어 위치를 유지한다.
    fun shouldStopOnBecomingNoisy(isNasContent: Boolean): Boolean = !isNasContent
}

// 재생 요청의 시작 위치 결정. 컨트롤러가 준 startIndex(요청 목록 기준)보다 요청 메타데이터에
// 실린 값(확장된 큐 기준)을 우선하고, 확장된 큐 크기로 안전하게 자른다.
internal object StartPositionPolicy {
    fun resolve(
        requestedIndex: Int, requestedPositionMs: Long,
        extraIndex: Int?, extraPositionMs: Long?, queueSize: Int
    ): Pair<Int, Long> {
        val index = (extraIndex ?: requestedIndex.takeIf { it != C.INDEX_UNSET } ?: 0)
            .coerceIn(0, (queueSize - 1).coerceAtLeast(0))
        val position = extraPositionMs ?: requestedPositionMs
        return index to position
    }
}

@OptIn(UnstableApi::class)
class ARMSMediaLibraryService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var stationRepository: StationRepository
    private lateinit var playbackStateStore: PlaybackStateStore
    private lateinit var nasBrowseTree: NasBrowseTree
    // SupervisorJob: 브라우징 요청 하나가 실패해도 나머지 코루틴(주기 갱신 등)이 같이
    // 죽지 않도록. onDestroy에서 통째로 취소해 누수를 막는다.
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var nowPlayingRefreshJob: Job? = null

    private val rootId = "[ROOT]"
    private val favoritesId = "[FAVORITES]"
    private val allRadioId = "[ALL_RADIO]"

    // 페이징을 무시하는 헤드유닛이 있어, 한 번에 돌려주는 개수에 상한을 둔다
    // (바인더 트랜잭션 크기 한계 방어).
    private val MAX_CHILDREN_PER_PAGE = 500

    // 재생 에러 자동 재시도 폭주 방지
    private val MAX_ERROR_RETRIES = 2
    private val ERROR_RETRY_WINDOW_MS = 30_000L
    private var errorRetryCount = 0
    private var lastErrorRetryAtMs = 0L

    // 편성/곡 정보를 다시 확인할 주기. 방송 전환은 보통 정시/30분 단위지만,
    // K-POP은 곡이 3~4분마다 바뀌므로 그보다 짧게 잡아 갱신 지연을 최소화한다.
    private val nowPlayingRefreshIntervalMs = 8_000L

    // 변경을 감지한 시점과 실제로 화면에 반영하는 시점 사이에 두는 지연. LISTEN.moe의 SSE
    // 편성정보는 인코더 기준 "지금" 곡을 알려주지만, 클라이언트는 네트워크/버퍼링만큼 뒤늦게
    // 그 소리를 듣는다. 감지 즉시 반영하면 오히려 화면(새 곡)과 소리(이전 곡 꼬리)가 어긋나므로,
    // 전형적인 버퍼링 지연만큼 늦춰서 반영해 체감 싱크를 맞춘다.
    private val nowPlayingApplyDelayMs = 4_000L

    // 커버 재시도 상태. 편성/곡 정보가 그대로여도 커버만 못 받은 경우가 있어서(이동 중 순간 끊김
    // 등), 그때는 커버만 따로 다시 받는다. 예전에는 정보가 안 바뀌면 그대로 return해버려서
    // 한 번 실패한 커버가 편성/곡이 바뀔 때까지 회색으로 남았다.
    private var artworkRetryStationId: String? = null
    private var artworkRetryAttempt = 0
    private var artworkNextRetryAtMs = 0L

    // 커버 다운로드 전용. HttpURLConnection은 http<->https로 스킴이 바뀌는 리다이렉트를 따라가지
    // 않아 그 경우 리다이렉트 안내 HTML을 이미지로 저장했다. OkHttp는 따라간다.
    private val artworkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(5, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    // SBS 파워FM(107.7)은 원본 스트림 자체의 라우드니스가 다른 채널보다 낮아 상대적으로
    // 작게 들린다. 다른 채널을 줄이는 대신 SBS만 게인을 올려 체감 볼륨을 맞춘다.
    private val sbsStationId = "2"
    private val sbsLoudnessBoostMillibels = 1000 // +10dB
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessEnhancerSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    // 차량 연결이 끊기면 재생을 멈추기까지 두는 짧은 유예. 헤드유닛에 따라 브라우징 갱신 등으로
    // 컨트롤러가 순간적으로 끊겼다 곧바로 다시 붙는 경우가 있어, 그때 재생이 죽지 않도록 한다.
    // 사람이 체감하기엔 사실상 즉시이고, 다시 연결되면 아래 onPostConnect에서 취소한다.
    private val CAR_DISCONNECT_STOP_DELAY_MS = 1_500L
    private var carDisconnectStopJob: Job? = null

    // 내비 음성인식 등으로 오디오 포커스를 뺏겨 멈춘 뒤, 포커스가 풀렸을 때 스스로 재생을
    // 되살리기 위한 상태. ExoPlayer는 일시적 상실이면 알아서 재개하지만, 영구 상실
    // (AUDIOFOCUS_LOSS)이나 너무 오래 눌린 경우에는 재개하지 않고 그대로 멈춰 있는다.
    private val AUDIO_FOCUS_RESUME_RETRY_INTERVAL_MS = 2_000L
    private val AUDIO_FOCUS_RESUME_TIMEOUT_MS = 90_000L

    // 재생이 멈추면 미디어 포그라운드 서비스가 내려가고, 그 상태(백그라운드)의 포커스 요청은
    // 시스템이 아예 무시한다("AudioHardening focus request ignored"). 이때는 몇 번을 더 눌러도
    // 통하지 않으므로 몇 번 시도해보고 접는다. 예전에는 90초 내내 2초 간격으로 두드려서
    // 거부 로그와 알림 재게시만 수십 건씩 쌓였다.
    private val MAX_AUDIO_FOCUS_RESUME_ATTEMPTS = 3
    // play() 요청이 거부되면 ExoPlayer가 곧바로 playWhenReady를 되돌린다. 그걸 확인할 짧은 여유.
    private val AUDIO_FOCUS_RESUME_VERIFY_DELAY_MS = 400L

    private var audioFocusResumeJob: Job? = null
    private var pausedByAudioFocusLoss = false
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    // 차량 연결 상태. Android Auto는 차량 링크가 끊겨도 우리 서비스 바인딩과 미디어 컨트롤러를
    // 그대로 유지하기 때문에, onDisconnected만으로는 "차에서 내렸다"를 알 수 없다.
    private var carConnection: CarConnection? = null
    private var carConnectionObserver: Observer<Int>? = null
    // null = 아직 상태를 받지 못함. 상태를 못 읽는 기기에서 자동 재개가 통째로 막히지 않도록
    // "연결 안 됨"을 실제로 확인했을 때만 차단하려고 nullable로 둔다.
    private var carConnectionType: Int? = null

    private fun isCarDefinitelyDisconnected(): Boolean =
        carConnectionType == CarConnection.CONNECTION_TYPE_NOT_CONNECTED

    // 현재 큐를 차량 컨트롤러(Android Auto)가 시작했는지. 폰 화면이 시작했으면 false.
    // 차량 연결 해제 시 정지/큐 폐기와 포커스 재개 포기는 이 값이 true일 때만 적용한다.
    private var playbackInitiatedByCar = false

    override fun onCreate() {
        super.onCreate()

        // 1. ExoPlayer 및 Repository 초기화
        // 오디오 속성/포커스를 명시적으로 지정하지 않으면, 차량(블루투스/Android Auto) 쪽
        // 오디오 경로가 아예 열리지 않은 채로 조용히 디코딩만 계속되는 증상이 있었다
        // (다른 음악 앱이 먼저 재생돼 포커스를 요청해야 그제서야 이 앱 소리도 들리던 문제).
        // 오디오 포커스를 명시적으로 요청하도록 설정해 재생 시작과 동시에 경로가 열리게 한다.
        player = ExoPlayer.Builder(this).build().apply {
            // 블루투스가 끊기거나 이어폰이 빠지면(ACTION_AUDIO_BECOMING_NOISY) 폰 스피커로
            // 계속 흘러나오지 않도록 멈춘다. 라디오는 아래 onPlayWhenReadyChanged에서 완전 정지.
            setHandleAudioBecomingNoisy(true)
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                /* handleAudioFocus= */ true
            )
        }
        stationRepository = StationRepository(this)
        playbackStateStore = PlaybackStateStore(this)
        // 폰 화면과 같은 인스턴스를 공유해 세션/곡목록 캐시도 함께 쓴다.
        nasBrowseTree = NasBrowseTree(
            NasMusicRepository.get(this),
            NasPlaylistRepository.get(this),
            playbackStateStore
        )

        // 운전 중에는 터널/음영지역 등으로 스트림 연결이 잠깐씩 끊기는 일이 흔한데, ExoPlayer는
        // 에러가 나면 자동으로 재시도하지 않고 그대로 멈춰있는다. 그러면 "재생되다가 중간에
        // 끊기고 다시 시작되지 않는" 증상으로 이어진다. 에러 발생 시 잠시 후 같은 채널을
        // 새로 서명된 URL로 다시 재생 시도하도록 한다.
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                val mediaId = player.currentMediaItem?.mediaId ?: return
                // 재시도가 무한히 반복되지 않도록 짧은 시간 안의 연속 실패는 끊는다.
                val now = System.currentTimeMillis()
                if (now - lastErrorRetryAtMs > ERROR_RETRY_WINDOW_MS) errorRetryCount = 0
                if (errorRetryCount >= MAX_ERROR_RETRIES) return
                errorRetryCount++
                lastErrorRetryAtMs = now

                serviceScope.launch {
                    delay(3000L)
                    try {
                        if (MediaIdScheme.isNas(mediaId)) {
                            // NAS는 스트리밍 URL에 세션(sid)이 박혀 있어, 세션이 만료되면
                            // 큐 중간부터 실패한다. 같은 앨범을 새 세션으로 다시 만들어
                            // 듣던 위치에서 이어 재생한다.
                            retryNasPlayback()
                        } else {
                            val station = stationRepository.getAllStations().first().find { it.id == mediaId }
                                ?: return@launch
                            val newItem = buildEnrichedMediaItem(station)
                            player.setMediaItem(newItem)
                            player.prepare()
                            player.play()
                        }
                    } catch (e: Exception) {
                        // 이번 재시도가 실패해도, 다시 에러가 나면 onPlayerError가 또 호출되어
                        // 재시도가 이어진다 (위 카운터가 폭주를 막는다).
                    }
                }
            }

            // 내비 음성인식(티맵 등)이나 어시스턴트가 오디오 포커스를 가져가면 ExoPlayer가
            // 재생을 멈춘다. 일시적 상실이면 ExoPlayer가 스스로 재개하지만, 상대 앱이 영구
            // 포커스(AUDIOFOCUS_LOSS)를 요청했거나 상실이 너무 오래 지속돼 억제가 풀린 경우
            // (SUPPRESSED_TOO_LONG)에는 재개하지 않고 멈춘 채로 남는다 - 음성인식이 끝나도
            // 라디오가 다시 시작되지 않던 원인. 이 경우 포커스가 풀리는 것을 지켜보다 되살린다.
            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_BECOMING_NOISY) {
                    val isNas = MediaIdScheme.isNas(player.currentMediaItem?.mediaId)
                    if (BluetoothModePolicy.shouldStopOnBecomingNoisy(isNas)) stopPlaybackAndClearBuffer()
                    return
                }
                if (playWhenReady) {
                    // 스스로 재개했든 아래 재시도로 살아났든, 대기 상태를 푼다.
                    pausedByAudioFocusLoss = false
                    audioFocusResumeJob?.cancel()
                    return
                }
                if (AudioFocusResumePolicy.shouldAutoResume(reason)) {
                    pausedByAudioFocusLoss = true
                    startAudioFocusResumeLoop()
                } else {
                    // 의도된 정지 - 되살리지 않는다.
                    pausedByAudioFocusLoss = false
                    audioFocusResumeJob?.cancel()
                }
            }

            // 셔플/반복 버튼은 현재 상태를 이름과 아이콘으로 보여주므로, 상태가 바뀌면
            // 차량 화면의 버튼도 새로 올려야 한다. 라디오 <-> NAS 전환 시에는 버튼 자체가
            // 붙었다 떨어져야 하므로 트랙 전환도 함께 본다.
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                updateCustomLayout()
            }

            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                updateCustomLayout()
            }

            override fun onRepeatModeChanged(repeatMode: Int) {
                updateCustomLayout()
            }
        })

        // 2. MediaLibrarySession 초기화 및 콜백 바인딩
        // 실시간 라디오는 탐색(seek)이 의미가 없으므로, 재생 구간 바가 뜨지 않도록
        // duration/seek 관련 정보를 감추는 래퍼를 통해 세션에 플레이어를 연결한다.
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            LiveRadioPlayer(player) { direction -> skipToAdjacentStation(direction) },
            LibraryCallback()
        ).build()

        // 3. 재생 중인 채널의 편성정보/이미지를 주기적으로 다시 확인해 Now Playing 화면에 반영.
        // (재생 시작 시점 한 번만 값을 채우던 기존 방식으로는 방송이 바뀌거나, K-POP처럼
        //  네트워크 호출이 여러 단계라 간헐적으로 유실되는 경우를 따라잡을 수 없었다.)
        startNowPlayingRefreshLoop()

        // 4. 차량 연결/해제를 직접 관찰 (차에서 내린 뒤 폰으로 재생이 새는 것을 막는다)
        observeCarConnection()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    // MediaSessionService의 기본 onTaskRemoved()는, 재생 중이 아닐 때 앱 태스크가 최근 목록에서
    // 제거되면 stopSelf()로 서비스 자체를 완전히 종료시킨다. 이 앱은 재생 중이 아니어도 차량이
    // 언제든 다시 연결해서 미디어 소스로 찾아야 하는데, 서비스가 내려가면 그 순간부터 Android
    // Auto가 이 앱을 찾지 못해 "사라지는" 현상으로 이어진다(로그의 "remove task" 강제종료가
    // 정확히 이 경로). 기본 동작을 건너뛰어 태스크가 제거돼도 서비스가 계속 살아있게 한다.
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        // super.onTaskRemoved()를 의도적으로 호출하지 않는다.
    }

    override fun onDestroy() {
        nowPlayingRefreshJob?.cancel()
        carConnectionObserver?.let { carConnection?.type?.removeObserver(it) }
        carConnectionObserver = null
        carConnection = null
        serviceScope.cancel()
        loudnessEnhancer?.release()
        player.release()
        mediaLibrarySession.release()
        super.onDestroy()
    }

    // 브라우징/재생 콜백은 ListenableFuture를 돌려줘야 하는데, 안에서 하는 일은 전부
    // 코루틴(네트워크/DB)이다. 예전에는 runBlocking으로 호출 스레드를 그대로 막았는데,
    // 라디오는 최대 3번, NAS는 로그인까지 붙는 네트워크라 그대로 두면 ANR 위험이 있다.
    // 차량이 화면을 벗어나면 프레임워크가 future를 취소하므로, 그때 코루틴도 같이 끊어
    // 불필요한 재로그인이 유령처럼 남지 않게 한다.
    private fun <T> asyncResult(block: suspend () -> T): ListenableFuture<T> {
        val future = SettableFuture.create<T>()
        val job = serviceScope.launch {
            try {
                future.set(block())
            } catch (e: CancellationException) {
                future.cancel(false)
                throw e
            } catch (e: Exception) {
                future.setException(e)
            }
        }
        future.addListener({ if (future.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        return future
    }

    // 브라우징 실패를 예외로 흘려보내면 차량에는 원인 불명 오류로만 보인다.
    // LibraryResult 에러로 감싸서 헤드유닛이 "불러올 수 없음"을 제대로 표시하게 한다.
    private fun <T : Any> asyncLibraryResult(
        block: suspend () -> LibraryResult<T>
    ): ListenableFuture<LibraryResult<T>> = asyncResult {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LibraryResult.ofError(LibraryResult.RESULT_ERROR_IO)
        }
    }

    // 차량 헤드유닛은 목록을 페이지 단위로 요청한다. 지금까지는 이 값을 무시하고 전체를
    // 돌려줬는데, NAS 아티스트/앨범은 수백 개가 될 수 있어 바인더 트랜잭션 한계에 걸린다.
    private fun <T> List<T>.pageOf(page: Int, pageSize: Int): List<T> {
        // 일부 헤드유닛은 pageSize로 0이나 Int.MAX_VALUE를 보낸다 = 전체 요청
        if (pageSize <= 0 || pageSize == Int.MAX_VALUE) return take(MAX_CHILDREN_PER_PAGE)
        val from = page * pageSize
        if (from >= size) return emptyList()
        return subList(from, minOf(from + pageSize, size)).take(MAX_CHILDREN_PER_PAGE)
    }

    // 채널이 바뀔 때마다 호출: SBS만 게인을 올리고 나머지 채널은 원본 그대로 재생한다.
    // ExoPlayer의 audioSessionId는 인스턴스 생애주기 동안 보통 고정되지만, 혹시 바뀌더라도
    // 안전하게 다시 붙이도록 매번 현재 세션ID와 비교해서 필요할 때만 새로 생성한다.
    // stationId가 null이면 라디오가 아닌 재생(NAS 음악)이므로 보정을 끈다.
    private fun applyLoudnessCompensation(stationId: String?) {
        try {
            val sessionId = player.audioSessionId
            if (sessionId == C.AUDIO_SESSION_ID_UNSET) return
            if (loudnessEnhancer == null || loudnessEnhancerSessionId != sessionId) {
                loudnessEnhancer?.release()
                loudnessEnhancer = LoudnessEnhancer(sessionId)
                loudnessEnhancerSessionId = sessionId
            }
            val enhancer = loudnessEnhancer ?: return
            if (stationId == sbsStationId) {
                enhancer.setTargetGain(sbsLoudnessBoostMillibels)
                enhancer.enabled = true
            } else {
                enhancer.enabled = false
            }
        } catch (e: Exception) {
            // 라우드니스 보정 실패는 무시하고 원본 볼륨으로 재생
        }
    }

    // 차량 연결 상태를 직접 관찰한다.
    //
    // 예전에는 미디어 컨트롤러가 끊기는 것(onDisconnected)을 차량 이탈 신호로 삼았는데,
    // Android Auto는 차량 링크가 끊어져도 우리 서비스를 계속 바인딩하고 있어서 그 신호가
    // 오지 않는 경우가 있다. 실제 주행 로그에서, 차량이 사라진 뒤에도 자동 재개 루프가 살아남아
    // 폰 스피커로 라디오가 다시 흘러나온 사례가 있었다. 그래서 연결 상태를 직접 본다.
    private fun observeCarConnection() {
        try {
            val connection = CarConnection(this)
            val observer = Observer<Int> { type ->
                val wasConnected = carConnectionType?.let {
                    it != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                } ?: false
                carConnectionType = type
                val connected = type != CarConnection.CONNECTION_TYPE_NOT_CONNECTED
                if (wasConnected && !connected) {
                    // 차에서 내렸다. 예약된 정지가 있으면 기다리지 말고 즉시 정리한다.
                    // (폰이 시작한 재생은 차량 링크와 무관하므로 건드리지 않는다)
                    carDisconnectStopJob?.cancel()
                    if (BluetoothModePolicy.shouldStopOnCarDisconnect(playbackInitiatedByCar)) {
                        stopPlaybackAndClearBuffer()
                    }
                } else if (!wasConnected && connected) {
                    // 폰이 방금 일시정지해 둔 큐는 "낡은 큐"가 아니다. 지난 주행의 차량 큐만 폐기.
                    if (playbackInitiatedByCar) discardStaleQueueIfIdle()
                }
            }
            connection.type.observeForever(observer)
            carConnection = connection
            carConnectionObserver = observer
        } catch (e: Exception) {
            // 연결 상태를 못 읽어도 앱의 나머지 동작은 그대로 유지한다
            // (기존 onDisconnected 경로가 폴백으로 남아 있다).
        }
    }

    // 포커스를 되찾을 때까지 짧은 주기로 재생을 시도한다. 포커스 반환 콜백을 직접 받으려면
    // ExoPlayer의 포커스 관리를 꺼야 하는데, 그건 차량 오디오 경로가 열리지 않던 예전 문제를
    // 되살릴 위험이 있어(위 setAudioAttributes 주석) 건드리지 않고 폴링으로 처리한다.
    private fun startAudioFocusResumeLoop() {
        if (audioFocusResumeJob?.isActive == true) return
        audioFocusResumeJob = serviceScope.launch {
            val deadline = System.currentTimeMillis() + AUDIO_FOCUS_RESUME_TIMEOUT_MS
            var rejectedAttempts = 0
            while (System.currentTimeMillis() < deadline) {
                delay(AUDIO_FOCUS_RESUME_RETRY_INTERVAL_MS)
                if (!pausedByAudioFocusLoss) return@launch
                if (player.playWhenReady) return@launch // ExoPlayer가 스스로 재개함
                if (player.currentMediaItem == null) return@launch // 재생할 것이 없음
                // 차량이 시작한 재생인데 차가 없는 것이 확인되면 되살릴 이유가 없다
                // (폰 스피커로 갑자기 켜지면 안 된다). 폰이 시작한 재생은 되살린다.
                if (BluetoothModePolicy.shouldSkipFocusResume(playbackInitiatedByCar, isCarDefinitelyDisconnected())) return@launch
                // 통화 중이거나 다른 앱이 소리를 내는 중이면 시도 자체를 하지 않는다.
                // 이건 거부가 아니라 "지금은 때가 아님"이므로 실패로 세지 않는다.
                if (isAudioBusy()) continue

                resumeAfterAudioFocusLoss()
                delay(AUDIO_FOCUS_RESUME_VERIFY_DELAY_MS)
                if (player.playWhenReady) return@launch // 재개 성공

                // 요청이 통하지 않았다. 대개 백그라운드라 시스템이 막는 경우이고,
                // 그 상태는 계속 두드려도 바뀌지 않는다.
                rejectedAttempts++
                if (rejectedAttempts >= MAX_AUDIO_FOCUS_RESUME_ATTEMPTS) return@launch
            }
        }
    }

    // 통화 중이거나 다른 앱이 실제로 소리를 내고 있으면 포커스를 다투지 않는다.
    // (사용자가 의도적으로 다른 음악 앱으로 넘어간 경우까지 빼앗아오면 안 된다)
    private fun isAudioBusy(): Boolean = try {
        audioManager.mode != AudioManager.MODE_NORMAL || audioManager.isMusicActive
    } catch (e: Exception) {
        false
    }

    private fun resumeAfterAudioFocusLoss() {
        try {
            // 라디오는 멈춰 있는 동안 버퍼가 낡는다. 그대로 재개하면 지난 구간이 흘러나오거나
            // 곧 끊기므로, 라이브 최신 지점으로 옮긴 뒤 재생한다. NAS 음악은 유한한 트랙이라
            // 멈췄던 위치 그대로 이어야 한다.
            if (!MediaIdScheme.isNas(player.currentMediaItem?.mediaId)) {
                player.seekToDefaultPosition()
            }
            player.play()
        } catch (e: Exception) {
            // 이번 시도가 실패해도 다음 주기에 다시 시도한다.
        }
    }

    // 차량 화면에 올릴 셔플/반복 버튼을 현재 상태에 맞춰 만든다.
    //
    // 라디오는 실시간 방송이라 셔플/반복이 의미가 없으므로 NAS 음악일 때만 올린다.
    // Media3의 CommandButton에는 "켜짐" 표시가 따로 없어서, 아이콘과 이름으로 현재 상태를
    // 알려준다(운전 중에 버튼만 보고 지금 상태를 알 수 있어야 한다).
    private fun buildCustomLayout(): List<CommandButton> {
        if (!MediaIdScheme.isNas(player.currentMediaItem?.mediaId)) return emptyList()

        val shuffleOn = player.shuffleModeEnabled
        val shuffleButton = CommandButton.Builder()
            .setSessionCommand(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
            .setIconResId(R.drawable.ic_shuffle)
            .setDisplayName(if (shuffleOn) "셔플 켜짐" else "셔플 꺼짐")
            .setEnabled(true)
            .build()

        val repeatButton = CommandButton.Builder()
            .setSessionCommand(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
            .setIconResId(
                if (player.repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one
                else R.drawable.ic_repeat
            )
            .setDisplayName(
                when (player.repeatMode) {
                    Player.REPEAT_MODE_ONE -> "한 곡 반복"
                    Player.REPEAT_MODE_ALL -> "전체 반복"
                    else -> "반복 꺼짐"
                }
            )
            .setEnabled(true)
            .build()

        return listOf(shuffleButton, repeatButton)
    }

    // 상태가 바뀔 때마다 차량 화면의 버튼을 새 상태로 교체한다.
    // 플레이어 리스너는 세션이 만들어지기 전에도 붙어 있으므로 초기화 여부를 확인한다.
    private fun updateCustomLayout() {
        if (!::mediaLibrarySession.isInitialized) return
        mediaLibrarySession.setCustomLayout(buildCustomLayout())
    }

    // Android Auto(폰 투영) / Automotive(차량 내장) 컨트롤러인지 판별한다.
    // 미디어 알림 컨트롤러처럼 세션에 늘 붙어있는 내부 컨트롤러와 구분하기 위해 필요하다.
    //
    // Media3의 두 판별 함수는 "레거시 컨트롤러(controllerVersion == 0)"일 때만 true를 돌려준다.
    // Android Auto는 레거시로 접속하므로 정상 동작하지만, 차량 호스트가 Media3 컨트롤러로 붙는
    // 구성에서는 놓치게 되므로 패키지명으로도 한 번 더 확인한다. 아래 패키지들은 차량 호스트
    // 전용이라 폰 UI나 시스템 UI가 잘못 걸릴 여지가 없다.
    private fun isCarController(controller: MediaSession.ControllerInfo): Boolean =
        mediaLibrarySession.isAutoCompanionController(controller) ||
            mediaLibrarySession.isAutomotiveController(controller) ||
            controller.packageName in CAR_CONTROLLER_PACKAGES

    private fun hasCarControllerConnected(excluding: MediaSession.ControllerInfo? = null): Boolean =
        mediaLibrarySession.connectedControllers.any { it !== excluding && isCarController(it) }

    // 차량 연결이 끊겼을 때의 정리. 재생을 즉시 멈추고 버퍼와 큐를 모두 버린다.
    // stop()은 ExoPlayer를 IDLE로 되돌리며 버퍼링해 둔 스트림 데이터를 폐기하고(이 앱은 디스크
    // 캐시를 쓰지 않으므로 이것이 캐시 제거의 전부다), clearMediaItems()까지 해야 다음 연결에서
    // 세션이 빈 상태가 되어 프레임워크가 onPlaybackResumption()을 호출한다. 그 경로에서
    // 새로 서명된 URL과 라이브 최신 지점으로 다시 시작하므로, 낡은 구간이 되살아나지 않는다.
    // 차량이 다시 붙는 시점의 안전망.
    //
    // 이탈을 놓치면(예전에 onDisconnected가 오지 않아 실제로 그랬다) 지난 주행의 큐가 그대로
    // 남는다. 프레임워크는 큐가 비어있을 때만 onPlaybackResumption을 부르기 때문에, 그 상태로
    // 재연결하면 낡은 아이템과 버퍼가 그대로 재생된다 - 스트리밍 URL도 이미 만료됐을 수 있다.
    // 재생할 의사가 없는데 큐만 남아 있으면 비워서, 재개가 항상 새 URL과 라이브 최신 지점에서
    // 시작하도록 강제한다. playWhenReady로 판단하는 이유는, 버퍼링 중이라 isPlaying이 잠깐
    // false인 정상 재생을 끊지 않기 위해서다.
    private fun discardStaleQueueIfIdle() {
        if (player.mediaItemCount > 0 && !player.playWhenReady) {
            stopPlaybackAndClearBuffer()
        }
    }

    private fun stopPlaybackAndClearBuffer() {
        // 차량이 없는데 뒤늦게 폰에서 소리가 나면 안 되므로 자동 재개 대기도 함께 끊는다.
        audioFocusResumeJob?.cancel()
        pausedByAudioFocusLoss = false
        player.stop()
        player.clearMediaItems()
        // 다음 연결은 새 세션이므로 에러 재시도 카운터도 초기화한다.
        errorRetryCount = 0
        lastErrorRetryAtMs = 0L
    }

    private fun startNowPlayingRefreshLoop() {
        nowPlayingRefreshJob = serviceScope.launch {
            while (true) {
                delay(nowPlayingRefreshIntervalMs)
                try {
                    refreshCurrentNowPlaying()
                } catch (e: Exception) {
                    // 이번 주기 갱신 실패는 무시하고 다음 주기에 재시도
                }
            }
        }
    }

    // 현재 재생 중인 미디어 아이템의 편성정보/곡정보/이미지를 다시 가져와,
    // 실제로 바뀐 경우에만 세션의 MediaItem을 교체해 Now Playing 화면을 갱신한다.
    // player.replaceMediaItem은 동일 URI에 대해서는 재생을 끊지 않고 메타데이터만 반영한다.
    private suspend fun refreshCurrentNowPlaying() {
        if (!player.isPlaying) return
        val currentItem = player.currentMediaItem ?: return
        // NAS 음악은 편성 정보라는 개념이 없다. 이 가드가 없으면 NAS 재생 중에도
        // NAS mediaId를 방송국 ID로 착각해 라디오 메타데이터 API를 주기적으로 호출한다.
        if (MediaIdScheme.isNas(currentItem.mediaId)) return
        val stationId = currentItem.mediaId

        // 조회가 실패하면 아무것도 바꾸지 않는다. 예전에는 실패가 "정보 없음"이라는 값으로 돌아와
        // "정보가 바뀌었다"로 판정되고, 그 결과 멀쩡한 커버까지 지워졌다.
        val nowPlaying = withContext(Dispatchers.IO) { stationRepository.fetchMetadata(stationId) }
            ?: return

        val unchanged = nowPlaying.programTitle == currentItem.mediaMetadata.subtitle?.toString() &&
            nowPlaying.currentSong == currentItem.mediaMetadata.artist?.toString()
        val imageUrl = nowPlaying.imageUrl

        if (unchanged) {
            // 정보가 그대로면 커버가 비어 있는 경우에만 할 일이 있다.
            if (imageUrl == null || currentItem.mediaMetadata.artworkUri != null) return
            // 정보는 그대로인데 커버만 비어 있다: 백오프 간격에 맞춰 커버만 다시 받는다.
            if (artworkRetryStationId == stationId && System.currentTimeMillis() < artworkNextRetryAtMs) return
            val artworkUri = loadArtwork(imageUrl, stationId)
            if (artworkUri == null) {
                scheduleArtworkRetry(stationId)
                return
            }
            clearArtworkRetry()
            // 받는 사이 채널이 바뀌었으면 엉뚱한 채널에 붙이지 않는다.
            if (!player.isPlaying || player.currentMediaItem?.mediaId != stationId) return
            val latest = player.currentMediaItem ?: return
            grantArtworkUriToAllControllers(artworkUri)
            val withArtwork = latest.buildUpon()
                .setMediaMetadata(latest.mediaMetadata.buildUpon().setArtworkUri(artworkUri).build())
                .build()
            player.replaceMediaItem(player.currentMediaItemIndex, withArtwork)
            return
        }

        // 정보가 바뀌었다 = 새 곡/프로그램. 이전 재시도 상태는 의미가 없다.
        clearArtworkRetry()

        // 변경을 감지해도 바로 반영하지 않고 버퍼링 보정 지연만큼 기다린다. 대기 중 채널이
        // 바뀌었거나 재생이 멈췄다면, 이제 와서 낡은(혹은 엉뚱한 채널의) 정보를 적용하지 않는다.
        delay(nowPlayingApplyDelayMs)
        if (!player.isPlaying || player.currentMediaItem?.mediaId != stationId) return

        val artworkUri = imageUrl?.let { loadArtwork(it, stationId) }
        // 커버가 있어야 하는데 못 받았다면 다음 주기부터 커버만 다시 시도한다.
        if (artworkUri == null && imageUrl != null) scheduleArtworkRetry(stationId)

        artworkUri?.let { grantArtworkUriToAllControllers(it) }

        // 이 시점은 이미 프로그램/곡 정보가 실제로 바뀐 경우이므로, 새 이미지를 못 찾았다면
        // (예: K-POP 곡이 DB에 커버가 없는 경우) 이전 곡의 이미지를 계속 보여주지 않고 지운다.
        // 그렇지 않으면 "곡 제목은 바뀌었는데 표지는 이전 곡 그대로"인 잘못된 정보가 노출된다.
        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
            .setSubtitle(nowPlaying.programTitle)
            .setArtist(nowPlaying.currentSong)
            .setArtworkUri(artworkUri)
            .build()

        val updatedItem = currentItem.buildUpon().setMediaMetadata(updatedMetadata).build()
        player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
    }

    // < / > (이전/다음) 버튼 입력을 실제 트랙이 아닌 '채널 전환'으로 처리한다.
    // 전체 라디오 채널 목록(모든 라디오 탭과 동일한 순서) 상에서 현재 채널의 다음/이전
    // 채널로 넘어가, 새로 서명된 URL과 최신 편성/곡 정보를 받아와 바로 재생한다.
    private fun skipToAdjacentStation(direction: Int) {
        serviceScope.launch {
            try {
                val currentItem = player.currentMediaItem ?: return@launch
                // NAS 재생 중에는 채널 전환이 아니라 트랙 전환이어야 하며, 그 처리는
                // LiveRadioPlayer가 ExoPlayer 기본 동작으로 위임한다.
                if (MediaIdScheme.isNas(currentItem.mediaId)) return@launch
                val allStations = stationRepository.getAllStations().first()
                if (allStations.isEmpty()) return@launch
                val currentIndex = allStations.indexOfFirst { it.id == currentItem.mediaId }
                if (currentIndex == -1) return@launch
                val nextIndex = ((currentIndex + direction) % allStations.size + allStations.size) % allStations.size
                val nextStation = allStations[nextIndex]

                val newItem = buildEnrichedMediaItem(nextStation)
                player.setMediaItem(newItem)
                player.prepare()
                player.play()
                stationRepository.saveLastPlayedStationId(nextStation.id)
            } catch (e: Exception) {
                // 채널 전환 실패 시 현재 채널 재생을 그대로 유지
            }
        }
    }

    // 방송국의 최신 재생 URL/편성/곡/이미지 정보를 모두 채운 재생 가능한 MediaItem을 생성.
    // onAddMediaItems(최초 재생)와 skipToAdjacentStation(채널 전환) 양쪽에서 공통으로 사용한다.
    private suspend fun buildEnrichedMediaItem(station: Station): MediaItem {
        applyLoudnessCompensation(station.id)
        val freshUrl = withContext(Dispatchers.IO) { stationRepository.getPlaybackUrl(station.id) } ?: station.frequencyOrUrl
        // 편성 조회에 실패해도 재생은 시작한다. 정보와 커버는 곧 도는 갱신 루프가 채운다.
        val nowPlaying = withContext(Dispatchers.IO) { stationRepository.fetchMetadata(station.id) }
        val imageUrl = nowPlaying?.imageUrl

        val artworkUri = imageUrl?.let { loadArtwork(it, station.id) }
        if (artworkUri == null && imageUrl != null) scheduleArtworkRetry(station.id) else clearArtworkRetry()
        artworkUri?.let { grantArtworkUriToAllControllers(it) }

        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setSubtitle(nowPlaying?.programTitle ?: "정보 없음")
            .setArtist(nowPlaying?.currentSong ?: "정보 없음")
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .apply { artworkUri?.let { setArtworkUri(it) } }
            .build()

        return MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(android.net.Uri.parse(freshUrl))
            .setMediaMetadata(metadata)
            .build()
    }

    // 아트워크 content:// URI를 현재 연결된 모든 컨트롤러(카미디어 프로세스 등)에게 읽기 권한 부여
    private fun grantArtworkUriToAllControllers(uri: android.net.Uri) {
        mediaLibrarySession.connectedControllers.forEach { controller ->
            grantArtworkUriTo(controller.packageName, uri)
        }
    }

    private fun grantArtworkUriTo(packageName: String, uri: android.net.Uri) {
        try {
            grantUriPermission(packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: Exception) {
            // 해당 컨트롤러에 권한 부여 실패는 무시
        }
    }

    // Android Auto 미디어 카탈로그 탐색을 위한 콜백 구현
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        // 셔플/반복을 커스텀 액션으로 쓰려면 그 커맨드를 컨트롤러에게 허용해줘야 한다.
        // 기본 커맨드 묶음에 두 개를 더하고, 접속 시점의 버튼 구성도 함께 내려준다.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo
        ): MediaSession.ConnectionResult {
            val sessionCommands =
                MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
                    .add(SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY))
                    .add(SessionCommand(ACTION_CYCLE_REPEAT, Bundle.EMPTY))
                    .build()
            return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(sessionCommands)
                .setCustomLayout(buildCustomLayout())
                .build()
        }

        // 차량에서 셔플/반복 버튼을 눌렀을 때. 폰 화면의 동작과 같은 순서로 순환시킨다
        // (반복: 끄기 -> 전체 -> 한 곡).
        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_TOGGLE_SHUFFLE ->
                    player.shuffleModeEnabled = !player.shuffleModeEnabled
                ACTION_CYCLE_REPEAT ->
                    player.repeatMode = when (player.repeatMode) {
                        Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                        Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                        else -> Player.REPEAT_MODE_OFF
                    }
                else -> return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED)
                )
            }
            // 위 변경은 플레이어 리스너(onShuffleModeEnabledChanged/onRepeatModeChanged)를
            // 통해 버튼 갱신으로 이어진다.
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }

        // 미디어 브라우징의 루트 노드 정의
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> {
            val rootMetadata = MediaMetadata.Builder()
                .setTitle("Simple Radio")
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .build()

            val rootItem = MediaItem.Builder()
                .setMediaId(rootId)
                .setMediaMetadata(rootMetadata)
                .build()

            return Futures.immediateFuture(LibraryResult.ofItem(rootItem, params))
        }

        // 각 폴더(루트, 즐겨찾기, 전체목록)의 하위 아이템(Children) 로드 및 리턴
        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> = asyncLibraryResult {
            val children: List<MediaItem> = when {
                parentId == rootId -> buildRootChildren()
                parentId == favoritesId ->
                    stationRepository.getFavoriteStations().first().map { createPlayableItem(it) }
                parentId == allRadioId ->
                    stationRepository.getAllStations().first().map { createPlayableItem(it) }
                parentId == MediaIdScheme.RECENT_FOLDER -> nasBrowseTree.recentAlbumItems()
                parentId == MediaIdScheme.ARTISTS_FOLDER -> nasBrowseTree.artistFolderItems()
                parentId == MediaIdScheme.PLAYLISTS_FOLDER -> nasBrowseTree.playlistItems()
                else -> when (val ref = MediaIdScheme.decode(parentId)) {
                    is MediaIdScheme.MediaRef.Artist -> nasBrowseTree.albumItemsOfArtist(ref.name)
                    else -> emptyList()
                }
            }
            LibraryResult.ofItemList(ImmutableList.copyOf(children.pageOf(page, pageSize)), params)
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> = asyncLibraryResult {
            val station = stationRepository.getAllStations().first().find { it.id == mediaId }
            if (station != null) LibraryResult.ofItem(createPlayableItem(station), null)
            else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
        }

        // 차량 시동을 끄면 Android Auto/서비스가 종료됐다가, 다시 연결될 때 시스템이 이 콜백으로
        // "마지막에 뭘 재생 중이었는지" 물어본다. 이걸 구현하지 않으면 세션이 빈 상태로 새로
        // 시작돼서, 사용자가 직접 채널을 다시 눌러야만 재생이 시작된다 (자동 재개 안 됨).
        //
        // buildEnrichedMediaItem은 URL/편성정보/이미지까지 순차적으로 최대 3번의 네트워크
        // 호출을 거치는데, 이 콜백이 너무 오래 걸리면 시스템이 응답을 기다리다 포기하고
        // "재개할 것 없음"으로 처리해버린다 (자동 재개가 안 되던 원인). 일정 시간 안에 못
        // 끝나면 캐시된 URL/기본 정보만으로 즉시 재생을 시작하는 가벼운 아이템으로 대신하고,
        // 실제 최신 정보는 곧이어 도는 주기적 갱신 루프가 채워 넣는다.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = asyncResult {
            // 시동 시 Android Auto가 부르면 차량 재생, 블루투스 헤드유닛의 PLAY나 알림이 부르면 폰 재생.
            playbackInitiatedByCar = isCarController(controller)
            // 마지막에 듣던 것이 NAS 앨범이면 그걸 복원한다. NAS는 로그인이 필요해 라디오보다
            // 느릴 수 있으므로, 제한 시간 안에 못 끝내면 라디오로 폴백한다.
            // (여기서 아무 것도 못 돌려주면 "시동을 걸었는데 아무 소리도 안 나는" 상태가 된다)
            val nasItems = when (val last = playbackStateStore.getLastPlayed()) {
                is LastPlayed.Nas ->
                    withTimeoutOrNull(4000L) { nasBrowseTree.albumQueue(last.album) }
                is LastPlayed.NasPlaylist ->
                    withTimeoutOrNull(4000L) { nasBrowseTree.playlistQueue(last.playlistId) }
                else -> null
            }?.takeIf { it.isNotEmpty() }
            if (nasItems != null) {
                applyLoudnessCompensation(null)
                // NAS는 유한한 곡이라 처음부터 재생하는 것이 자연스럽다.
                return@asyncResult MediaSession.MediaItemsWithStartPosition(
                    ImmutableList.copyOf(nasItems), 0, 0L
                )
            }

            val lastStationId = stationRepository.getLastPlayedStationId()
            val allStations = stationRepository.getAllStations().first()
            val station = allStations.find { it.id == lastStationId } ?: allStations.firstOrNull()
            val items = station?.let {
                val item = withTimeoutOrNull(4000L) { buildEnrichedMediaItem(it) } ?: createPlayableItem(it)
                ImmutableList.of(item)
            } ?: ImmutableList.of()
            // startPositionMs를 0으로 고정하면, 방송사 스트림이 되감기 가능한 DVR 버퍼를
            // 제공하는 경우 라이브 최신 지점이 아니라 그 버퍼의 맨 앞부분부터 재생을
            // 시작해버린다. 시동을 끄고 다시 켰을 때 예전 구간이 재생되다가 결국 멈추던
            // 버그가 바로 이 때문이었다. TIME_UNSET으로 두면 라이브 스트림은 최신 지점부터
            // 재생을 시작한다.
            MediaSession.MediaItemsWithStartPosition(items, 0, C.TIME_UNSET)
        }

        // 차량이 다시 붙으면 아래 onDisconnected가 예약해 둔 정지를 취소한다.
        // (헤드유닛이 잠깐 끊었다 다시 연결하는 경우 재생이 끊기지 않도록)
        override fun onPostConnect(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onPostConnect(session, controller)
            if (!isCarController(controller)) return
            carDisconnectStopJob?.cancel()
            // CarConnection 상태를 못 받는 기기에서도 낡은 큐가 되살아나지 않도록 여기서도 확인한다.
            if (playbackInitiatedByCar) discardStaleQueueIfIdle()
        }

        // 차량 연결이 끊기면(시동 OFF, USB/블루투스 분리, Android Auto 종료) 재생을 즉시 멈추고
        // 버퍼와 큐를 비운다. 이 서비스의 플레이어는 차량 전용이라(폰 화면은 자체 플레이어를
        // 따로 쓴다) 차량이 사라지면 계속 재생할 이유가 없고, 그대로 두면 폰 스피커로 라디오가
        // 계속 나온다.
        //
        // 예전에는 "연결된 컨트롤러가 하나도 없을 때"를 조건으로 삼았는데, 미디어 알림
        // 컨트롤러처럼 세션에 늘 붙어 있는 내부 컨트롤러가 있어 그 조건이 사실상 참이 되지
        // 않았다. 그래서 정지 코드가 아예 실행되지 않았다. 이제는 차량 컨트롤러만 세어
        // 마지막 차량이 빠지는 순간을 정확히 잡는다.
        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onDisconnected(session, controller)
            if (!isCarController(controller)) return
            if (!BluetoothModePolicy.shouldStopOnCarDisconnect(playbackInitiatedByCar)) return
            // 끊긴 본인이 목록에서 아직 안 빠졌을 수 있으므로 명시적으로 제외하고 센다.
            if (hasCarControllerConnected(excluding = controller)) return

            carDisconnectStopJob?.cancel()
            carDisconnectStopJob = serviceScope.launch {
                delay(CAR_DISCONNECT_STOP_DELAY_MS)
                if (hasCarControllerConnected()) return@launch
                stopPlaybackAndClearBuffer()
            }
        }

        // 차량이 mediaId로 재생을 요청할 때(playFromMediaId), 캐시된 URL이 만료됐을 수 있으므로
        // 실제 재생 직전에 항상 새로 서명된 스트림 URL을 받아오고, 실시간 편성/곡 정보와 이미지를
        // 채워 Now Playing 화면에 실제 방송 프로필/앨범 아트가 보이도록 한다.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> = asyncResult {
            resolveMediaItems(controller, mediaItems).toMutableList()
        }

        // 폰 화면이 "앨범의 n번째 곡부터 / 이어듣기 위치"로 재생을 요청할 때. 기본 구현도
        // onAddMediaItems로 위임하지만, 요청 1건이 여러 아이템으로 늘어나는 이 앱에서
        // 시작 인덱스/위치가 확실히 보존되도록 명시적으로 처리한다.
        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = asyncResult {
            val resolved = resolveMediaItems(controller, mediaItems)
            // 폰 화면은 "n번째 곡부터/이어듣기 위치"를 요청 메타데이터로 보낸다(SessionAudioPlayer 참고).
            val extras = mediaItems.firstOrNull()?.requestMetadata?.extras
            val (index, position) = StartPositionPolicy.resolve(
                requestedIndex = startIndex, requestedPositionMs = startPositionMs,
                extraIndex = extras?.takeIf { it.containsKey(SessionAudioPlayer.EXTRA_START_INDEX) }?.getInt(SessionAudioPlayer.EXTRA_START_INDEX),
                extraPositionMs = extras?.takeIf { it.containsKey(SessionAudioPlayer.EXTRA_START_POSITION_MS) }?.getLong(SessionAudioPlayer.EXTRA_START_POSITION_MS),
                queueSize = resolved.size
            )
            MediaSession.MediaItemsWithStartPosition(ImmutableList.copyOf(resolved), index, position)
        }

        // 컨트롤러(차량 또는 폰 화면)가 넘긴 항목을 실제 재생 항목으로. 앨범 하나를 누르면
        // 그 앨범 전체가 큐가 되어야 하므로, 요청 1건이 여러 개의 아이템으로 늘어날 수 있다.
        // 차량이 시작했는지 여부를 여기서 기록해, 이후 정지/재개 판단(BluetoothModePolicy)에 쓴다.
        private suspend fun resolveMediaItems(
            controller: MediaSession.ControllerInfo,
            mediaItems: List<MediaItem>
        ): List<MediaItem> {
            playbackInitiatedByCar = isCarController(controller)
            val resolved = mediaItems.flatMap { item ->
                // 이미 스트림 URI를 갖고 온 항목은 그대로 쓴다(폰 화면의 AudioPlayer 계약 호환용).
                if (item.localConfiguration != null) return@flatMap listOf(item)
                when (val ref = MediaIdScheme.decode(item.mediaId)) {
                    is MediaIdScheme.MediaRef.Radio -> buildRadioItem(ref.stationId, controller.packageName)
                    is MediaIdScheme.MediaRef.Album -> buildNasAlbumQueue(ref.albumArtist, ref.name)
                    is MediaIdScheme.MediaRef.Playlist -> buildNasPlaylistQueue(ref.id)
                    else -> emptyList()
                }
            }
            // 하나도 못 만들면 media3가 빈 큐로 예외를 던지거나 조용히 아무 일도 안 한다.
            // 호출한 쪽에 오류가 드러나도록 명시적으로 실패시킨다.
            if (resolved.isEmpty()) throw IllegalStateException("재생 가능한 항목을 찾지 못했습니다")
            return resolved
        }
    }

    // NAS 스트리밍 URL에는 로그인 세션이 들어있어 시간이 지나면 만료된다. 만료로 재생이
    // 끊기면 같은 앨범을 새 세션으로 다시 만들어, 듣고 있던 트랙/위치에서 이어 재생한다.
    private suspend fun retryNasPlayback() {
        val resumeIndex = player.currentMediaItemIndex
        val resumePositionMs = player.currentPosition
        // 스트리밍 URL에 박힌 sid가 만료된 것이 재생 실패의 가장 흔한 원인이다.
        // 세션을 버려야 아래에서 큐를 다시 만들 때 새로 로그인한다. 버리지 않으면
        // 같은 낡은 sid로 URL을 다시 만들어 똑같이 실패한다.
        NasMusicRepository.get(this@ARMSMediaLibraryService).invalidateSession()
        // 무엇을 듣고 있었는지에 따라 큐를 다시 만든다. 종류를 구분하지 않으면
        // 플레이리스트를 듣던 중 세션이 만료됐을 때 엉뚱한 앨범으로 갈아탄다.
        val queue = when (val last = playbackStateStore.getLastPlayed()) {
            is LastPlayed.Nas -> nasBrowseTree.albumQueue(last.album)
            is LastPlayed.NasPlaylist -> nasBrowseTree.playlistQueue(last.playlistId)
            else -> return
        }
        if (queue.isEmpty()) return
        val safeIndex = resumeIndex.coerceIn(0, queue.lastIndex)
        player.setMediaItems(queue, safeIndex, resumePositionMs)
        player.prepare()
        player.play()
    }

    // 라디오 채널 하나를 재생 가능한 아이템으로. 차량 목록에서 직접 고른 경우에도
    // "마지막 재생"을 저장해야 다음 시동 때 자동 재개가 정확히 이어진다.
    private suspend fun buildRadioItem(stationId: String, controllerPackage: String): List<MediaItem> {
        val station = stationRepository.getAllStations().first().find { it.id == stationId }
            ?: return emptyList()
        stationRepository.saveLastPlayedStationId(station.id)
        playbackStateStore.saveLastPlayed(LastPlayed.Radio(station.id))
        val enrichedItem = buildEnrichedMediaItem(station)
        enrichedItem.mediaMetadata.artworkUri?.let { grantArtworkUriTo(controllerPackage, it) }
        return listOf(enrichedItem)
    }

    // NAS 앨범 전체를 재생 큐로. 라우드니스 보정은 라디오(SBS) 전용이므로 여기서 꺼준다.
    private suspend fun buildNasAlbumQueue(albumArtist: String, albumName: String): List<MediaItem> {
        val album = nasBrowseTree.findAlbum(albumArtist, albumName) ?: return emptyList()
        val queue = nasBrowseTree.albumQueue(album)
        if (queue.isEmpty()) return emptyList()
        applyLoudnessCompensation(null)
        playbackStateStore.saveLastPlayed(LastPlayed.Nas(album))
        playbackStateStore.pushRecentAlbum(album)
        return queue
    }

    private suspend fun buildNasPlaylistQueue(playlistId: Long): List<MediaItem> {
        val playlist = nasBrowseTree.findPlaylist(playlistId) ?: return emptyList()
        val queue = nasBrowseTree.playlistQueue(playlistId)
        if (queue.isEmpty()) return emptyList()
        applyLoudnessCompensation(null)
        playbackStateStore.saveLastPlayed(LastPlayed.NasPlaylist(playlist.id, playlist.name))
        return queue
    }

    // 차량 브라우징 루트 구성.
    // NAS는 설정돼 있을 때만 노출한다. 설정 방법이 폰에만 있어서, 미설정 상태에서 안내 항목을
    // 띄워봐야 운전 중에는 아무 것도 할 수 없는 죽은 행이 되기 때문이다.
    // 자격증명 확인(hasCredentials)은 암호화 저장소를 열어야 해서 느리므로, 여기서는 폰이
    // 저장해둔 평문 플래그만 본다.
    private suspend fun buildRootChildren(): List<MediaItem> {
        return buildList {
            add(createFolderItem(favoritesId, "즐겨찾는 채널", "★ 자주 듣는 라디오 목록"))
            add(createFolderItem(allRadioId, "모든 라디오 채널", "📻 대한민국 실시간 방송 목록"))
            if (playbackStateStore.isNasConfigured()) {
                if (playbackStateStore.getRecentAlbums().isNotEmpty()) {
                    add(createFolderItem(MediaIdScheme.RECENT_FOLDER, "최근 재생한 앨범", "♪ 방금 듣던 음악"))
                }
                // 하나도 없으면 눌러도 아무 것도 없는 빈 폴더가 되므로 숨긴다
                if (nasBrowseTree.hasPlaylists()) {
                    add(createFolderItem(MediaIdScheme.PLAYLISTS_FOLDER, "플레이리스트", "♪ 내가 담은 곡"))
                }
                add(createFolderItem(MediaIdScheme.ARTISTS_FOLDER, "아티스트", "♪ 내 NAS 음악"))
            }
        }
    }

    // Android Auto 미디어 카탈로그 상에서 '폴더' 노드를 생성하는 헬퍼 함수
    private fun createFolderItem(id: String, title: String, subtitle: String): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setFolderType(MediaMetadata.FOLDER_TYPE_MIXED)
            .build()

        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(metadata)
            .build()
    }

    // Android Auto 미디어 카탈로그 상에서 실제로 '재생 가능한 라디오 채널' 노드를 생성하는 헬퍼 함수
    private fun createPlayableItem(station: Station): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setSubtitle(if (station.isFavorite) "★ 즐겨찾는 채널" else "일반 주파수")
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
            .build()

        return MediaItem.Builder()
            .setMediaId(station.id)
            .setUri(android.net.Uri.parse(station.frequencyOrUrl))
            .setMediaMetadata(metadata)
            .build()
    }

    // 커버 URL을 내려받아 다른 프로세스가 읽을 수 있는 content:// URI로 만든다. 실패하면 null.
    private suspend fun loadArtwork(url: String, stationId: String): android.net.Uri? {
        val bytes = withContext(Dispatchers.IO) {
            // 번들된 채널 아트(android.resource://)는 네트워크가 아니라 리소스에서 읽는다.
            if (url.startsWith("android.resource://")) {
                runCatching { contentResolver.openInputStream(android.net.Uri.parse(url))?.use { it.readBytes() } }.getOrNull()
            } else fetchArtworkBytes(url)
        } ?: return null
        return createArtworkContentUri(bytes, stationId)
    }

    private fun scheduleArtworkRetry(stationId: String) {
        if (artworkRetryStationId != stationId) {
            artworkRetryStationId = stationId
            artworkRetryAttempt = 0
        }
        artworkNextRetryAtMs = System.currentTimeMillis() + ArtworkRetryPolicy.delayMs(artworkRetryAttempt)
        artworkRetryAttempt++
    }

    private fun clearArtworkRetry() {
        artworkRetryStationId = null
        artworkRetryAttempt = 0
        artworkNextRetryAtMs = 0L
    }

    // Now Playing 화면에 표시할 방송 프로필/앨범 이미지를 직접 내려받는다.
    // 순간적인 신호 끊김을 위해 짧은 간격으로 한 번 더 시도하고, 그래도 안 되면 호출부의
    // 백오프 재시도(ArtworkRetryPolicy)에 맡긴다. 받은 본문은 상태코드·Content-Type을 확인하고
    // 실제로 디코딩되는지까지 본다 - 오류 페이지를 이미지라고 저장하지 않기 위해서다.
    private suspend fun fetchArtworkBytes(url: String): ByteArray? {
        repeat(2) { attempt ->
            try {
                val request = okhttp3.Request.Builder()
                    .url(url)
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                artworkHttpClient.newCall(request).execute().use { response ->
                    if (ArtworkResponsePolicy.isAcceptable(response.code, response.header("Content-Type"))) {
                        val bytes = response.body?.bytes()
                        if (bytes != null && bytes.isNotEmpty() && looksLikeDecodableImage(bytes)) return bytes
                    }
                }
            } catch (e: Exception) {
                // 아래에서 한 번 더 시도
            }
            if (attempt == 0) delay(700L)
        }
        return null
    }

    // 헤더만 읽어 이미지인지 확인한다(비트맵을 실제로 만들지는 않는다).
    private fun looksLikeDecodableImage(bytes: ByteArray): Boolean {
        val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        return options.outWidth > 0 && options.outHeight > 0
    }

    // 파일명으로 안전한 고정 길이 토큰. 어떤 mediaId가 와도 경로를 깨뜨리지 않는다.
    private fun artworkCacheToken(mediaId: String): String {
        return try {
            val digest = java.security.MessageDigest.getInstance("SHA-1").digest(mediaId.toByteArray())
            digest.joinToString("") { "%02x".format(it) }.take(16)
        } catch (e: Exception) {
            mediaId.filter { it.isLetterOrDigit() }.take(16).ifEmpty { "artwork" }
        }
    }

    // 내려받은 아트워크를 캐시 파일로 저장하고, 카미디어 프로세스가 읽을 수 있도록
    // FileProvider의 content:// URI로 노출한다.
    // 파일명에 항상 같은 이름(예: "$stationId.jpg")을 쓰면 content:// URI도 매번 동일해져,
    // 차량 미디어 UI의 이미지 로더가 URI 기준으로 캐싱할 경우 내용이 바뀌어도 이전 이미지를
    // 계속 보여준다 (K-POP 커버가 곡이 바뀌어도 갱신되지 않던 원인). 매번 고유한 파일명을 써서
    // URI 자체가 바뀌도록 하고, 이전 캐시 파일은 지운다.
    private fun createArtworkContentUri(bytes: ByteArray, mediaId: String): android.net.Uri? {
        return try {
            val artworkDir = java.io.File(cacheDir, "artwork").apply { mkdirs() }
            // mediaId를 그대로 파일명에 쓰면 NAS 앨범명처럼 `/`나 `-`가 들어간 값에서
            // 경로가 깨지거나 이전 파일 정리(prefix 매칭)가 엉뚱한 파일을 지운다.
            val token = artworkCacheToken(mediaId)
            artworkDir.listFiles { f -> f.name.startsWith("$token-") }?.forEach { it.delete() }
            val file = java.io.File(artworkDir, "$token-${System.currentTimeMillis()}.jpg")
            file.writeBytes(bytes)
            androidx.core.content.FileProvider.getUriForFile(
                this@ARMSMediaLibraryService,
                "$packageName.artworkprovider",
                file
            )
        } catch (e: Exception) {
            null
        }
    }

    // 실시간 라디오 스트림은 재생 구간(탐색 바)이 의미가 없으므로, duration/seek 관련 정보를
    // 차량 UI에 노출하지 않도록 감싸는 래퍼. 실제 재생/일시정지는 그대로 위임한다.
    // 또한 실시간 스트림에는 '다음/이전 트랙' 개념이 없으므로, Now Playing 화면의 < / > 버튼을
    // 다음/이전 채널로 전환하는 키로 재정의한다.
    //
    // 단, NAS 음악은 유한한 곡 목록이라 진짜 트랙 탐색이 의미가 있다. 그래서 지금 재생 중인
    // 것이 NAS인지 라디오인지에 따라 동작을 나눈다. 판별은 현재 MediaItem의 mediaId 접두사로
    // 하는데, 별도 플래그를 두면 갱신해야 할 지점(재생 시작/에러 재시도/자동 재개/트랙 전환)이
    // 여러 곳이라 언젠가 반드시 어긋나기 때문이다.
    private class LiveRadioPlayer(
        player: ExoPlayer,
        private val onSkipToAdjacentStation: (direction: Int) -> Unit
    ) : ForwardingPlayer(player) {

        private fun isNasContent(): Boolean = MediaIdScheme.isNas(wrappedPlayer.currentMediaItem?.mediaId)

        override fun getDuration(): Long =
            if (isNasContent()) super.getDuration() else C.TIME_UNSET

        override fun getContentDuration(): Long =
            if (isNasContent()) super.getContentDuration() else C.TIME_UNSET

        override fun isCurrentMediaItemSeekable(): Boolean =
            if (isNasContent()) super.isCurrentMediaItemSeekable() else false

        override fun hasNextMediaItem(): Boolean =
            if (isNasContent()) super.hasNextMediaItem() else true

        override fun hasPreviousMediaItem(): Boolean =
            if (isNasContent()) super.hasPreviousMediaItem() else true

        override fun seekToNext() {
            if (isNasContent()) super.seekToNext() else onSkipToAdjacentStation(1)
        }

        override fun seekToPrevious() {
            if (isNasContent()) super.seekToPrevious() else onSkipToAdjacentStation(-1)
        }

        override fun seekToNextMediaItem() {
            if (isNasContent()) super.seekToNextMediaItem() else onSkipToAdjacentStation(1)
        }

        override fun seekToPreviousMediaItem() {
            if (isNasContent()) super.seekToPreviousMediaItem() else onSkipToAdjacentStation(-1)
        }

        override fun getAvailableCommands(): Player.Commands {
            // NAS는 ExoPlayer가 알려주는 기본 커맨드를 그대로 쓴다 (탐색/진행바 살아있음).
            // 다만 곡이 여러 개인 큐에서는 이전/다음 커맨드를 명시적으로 올려준다. ExoPlayer는
            // 큐의 처음/끝에서 해당 커맨드를 내리는데, 그러면 차량 화면에서 이전/다음 버튼이
            // 통째로 사라져 트랙 이동 수단이 없어진다(라디오 쪽도 같은 이유로 명시해 두었다).
            // 경계에서 눌러도 ExoPlayer가 안전하게 무시하거나 현재 곡을 다시 재생한다.
            if (isNasContent()) {
                val commands = super.getAvailableCommands()
                if (wrappedPlayer.mediaItemCount <= 1) return commands
                return commands.buildUpon()
                    .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                    .add(Player.COMMAND_SEEK_TO_NEXT)
                    .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                    .build()
            }
            return super.getAvailableCommands().buildUpon()
                .remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_BACK)
                .remove(Player.COMMAND_SEEK_FORWARD)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .build()
        }

        // ForwardingPlayer는 오버라이드한 값을 리스너 이벤트에까지 반영해주지 않는다.
        // MediaSession은 감싼 플레이어가 알려준 커맨드를 그대로 차량에 전달하기 때문에,
        // 라디오 <-> NAS로 바뀌어도 차량 UI(진행바/이전다음 버튼)가 갱신되지 않는다.
        // 그래서 리스너를 한 겹 감싸서, 커맨드 변경 이벤트를 이 래퍼 기준 값으로 바꿔 전달하고
        // 재생 종류가 바뀌는 순간에도 한 번 더 알려준다.
        private val listenerWrappers = java.util.IdentityHashMap<Player.Listener, Player.Listener>()

        override fun addListener(listener: Player.Listener) {
            val wrapper = object : Player.Listener {
                override fun onAvailableCommandsChanged(availableCommands: Player.Commands) {
                    listener.onAvailableCommandsChanged(this@LiveRadioPlayer.getAvailableCommands())
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    listener.onMediaItemTransition(mediaItem, reason)
                    // 라디오 <-> NAS 전환이면 커맨드 구성 자체가 달라지므로 강제로 다시 알린다.
                    listener.onAvailableCommandsChanged(this@LiveRadioPlayer.getAvailableCommands())
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    listener.onEvents(this@LiveRadioPlayer, events)
                }

                override fun onTimelineChanged(timeline: androidx.media3.common.Timeline, reason: Int) {
                    listener.onTimelineChanged(timeline, reason)
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    listener.onIsPlayingChanged(isPlaying)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    listener.onPlaybackStateChanged(playbackState)
                }

                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    listener.onPlayWhenReadyChanged(playWhenReady, reason)
                }

                // 이 래퍼는 여기 적어둔 콜백만 전달한다. 빠뜨리면 세션이 그 변화를 아예 모른다.
                // 셔플/반복은 차량 화면의 버튼 상태와 직결되므로 반드시 넘겨야 한다.
                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    listener.onShuffleModeEnabledChanged(shuffleModeEnabled)
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    listener.onRepeatModeChanged(repeatMode)
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    listener.onPlayerError(error)
                }

                override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                    listener.onMediaMetadataChanged(mediaMetadata)
                }

                override fun onPositionDiscontinuity(
                    oldPosition: Player.PositionInfo,
                    newPosition: Player.PositionInfo,
                    reason: Int
                ) {
                    listener.onPositionDiscontinuity(oldPosition, newPosition, reason)
                }
            }
            listenerWrappers[listener] = wrapper
            super.addListener(wrapper)
        }

        override fun removeListener(listener: Player.Listener) {
            val wrapper = listenerWrappers.remove(listener)
            if (wrapper != null) super.removeListener(wrapper) else super.removeListener(listener)
        }
    }
}
