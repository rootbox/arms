package com.arms.androidauto

import android.media.audiofx.LoudnessEnhancer
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
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

    // SBS 파워FM(107.7)은 원본 스트림 자체의 라우드니스가 다른 채널보다 낮아 상대적으로
    // 작게 들린다. 다른 채널을 줄이는 대신 SBS만 게인을 올려 체감 볼륨을 맞춘다.
    private val sbsStationId = "2"
    private val sbsLoudnessBoostMillibels = 1000 // +10dB
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var loudnessEnhancerSessionId: Int = C.AUDIO_SESSION_ID_UNSET

    override fun onCreate() {
        super.onCreate()

        // 1. ExoPlayer 및 Repository 초기화
        // 오디오 속성/포커스를 명시적으로 지정하지 않으면, 차량(블루투스/Android Auto) 쪽
        // 오디오 경로가 아예 열리지 않은 채로 조용히 디코딩만 계속되는 증상이 있었다
        // (다른 음악 앱이 먼저 재생돼 포커스를 요청해야 그제서야 이 앱 소리도 들리던 문제).
        // 오디오 포커스를 명시적으로 요청하도록 설정해 재생 시작과 동시에 경로가 열리게 한다.
        player = ExoPlayer.Builder(this).build().apply {
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

        val nowPlaying = withContext(Dispatchers.IO) { stationRepository.fetchMetadata(stationId) }

        val unchanged = nowPlaying.programTitle == currentItem.mediaMetadata.subtitle?.toString() &&
            nowPlaying.currentSong == currentItem.mediaMetadata.artist?.toString()
        if (unchanged) return

        // 변경을 감지해도 바로 반영하지 않고 버퍼링 보정 지연만큼 기다린다. 대기 중 채널이
        // 바뀌었거나 재생이 멈췄다면, 이제 와서 낡은(혹은 엉뚱한 채널의) 정보를 적용하지 않는다.
        delay(nowPlayingApplyDelayMs)
        if (!player.isPlaying || player.currentMediaItem?.mediaId != stationId) return

        val artworkUri = nowPlaying.imageUrl?.let { url ->
            val bytes = withContext(Dispatchers.IO) { fetchArtworkBytes(url) }
            bytes?.let { createArtworkContentUri(it, stationId) }
        }

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
        val nowPlaying = withContext(Dispatchers.IO) { stationRepository.fetchMetadata(station.id) }

        val artworkUri = nowPlaying.imageUrl?.let { url ->
            val bytes = withContext(Dispatchers.IO) { fetchArtworkBytes(url) }
            bytes?.let { createArtworkContentUri(it, station.id) }
        }
        artworkUri?.let { grantArtworkUriToAllControllers(it) }

        val metadata = MediaMetadata.Builder()
            .setTitle(station.name)
            .setSubtitle(nowPlaying.programTitle)
            .setArtist(nowPlaying.currentSong)
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

        // 차량 연결이 완전히 끊기면(시동 OFF, 블루투스/USB 분리 등) ExoPlayer를 정지하고 큐를
        // 비운다. 그래야 다음 연결 시 세션이 idle 상태가 되어 프레임워크가 onPlaybackResumption()을
        // 호출하고, 그 안의 기존 로직이 새 서명 URL/최신 편성정보/라이브 최신 지점으로 완전히
        // 새로 시작한다. 이걸 안 하면 끊기기 직전의 낡은 MediaItem이 그대로 남아있다가, 재연결 시
        // 시스템이 "재생 중이던 걸 이어서" 취급해 오래된 상태로 되살아날 수 있다.
        override fun onDisconnected(session: MediaSession, controller: MediaSession.ControllerInfo) {
            super.onDisconnected(session, controller)
            if (mediaLibrarySession.connectedControllers.isEmpty()) {
                player.stop()
                player.clearMediaItems()
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
            // 앨범 하나를 누르면 그 앨범 전체가 큐가 되어야 하므로, 요청 1건이 여러 개의
            // 아이템으로 늘어날 수 있다 (flatMap).
            val resolved = mediaItems.flatMap { item ->
                when (val ref = MediaIdScheme.decode(item.mediaId)) {
                    is MediaIdScheme.MediaRef.Radio -> buildRadioItem(ref.stationId, controller.packageName)
                    is MediaIdScheme.MediaRef.Album -> buildNasAlbumQueue(ref.albumArtist, ref.name)
                    is MediaIdScheme.MediaRef.Playlist -> buildNasPlaylistQueue(ref.id)
                    else -> emptyList()
                }
            }
            // 하나도 못 만들면 media3가 빈 큐로 예외를 던지거나 조용히 아무 일도 안 한다.
            // 차량에 오류가 드러나도록 명시적으로 실패시킨다.
            if (resolved.isEmpty()) throw IllegalStateException("재생 가능한 항목을 찾지 못했습니다")
            resolved.toMutableList()
        }
    }

    // NAS 스트리밍 URL에는 로그인 세션이 들어있어 시간이 지나면 만료된다. 만료로 재생이
    // 끊기면 같은 앨범을 새 세션으로 다시 만들어, 듣고 있던 트랙/위치에서 이어 재생한다.
    private suspend fun retryNasPlayback() {
        val resumeIndex = player.currentMediaItemIndex
        val resumePositionMs = player.currentPosition
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

    // Now Playing 화면에 표시할 방송 프로필/앨범 이미지를 직접 내려받음.
    // 운전 중 잠깐의 신호 끊김 한 번으로 실패하면 다음 30초 주기까지 회색 플레이스홀더로
    // 남아있게 되므로, 같은 시도 안에서 한 번 더 재시도해 순간적인 네트워크 hiccup을 버틴다.
    private fun fetchArtworkBytes(url: String): ByteArray? {
        repeat(2) { attempt ->
            try {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                return connection.inputStream.use { it.readBytes() }
            } catch (e: Exception) {
                if (attempt == 1) return null
            }
        }
        return null
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
            // NAS는 ExoPlayer가 알려주는 기본 커맨드를 그대로 쓴다 (탐색/진행바 살아있음)
            if (isNasContent()) return super.getAvailableCommands()
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
