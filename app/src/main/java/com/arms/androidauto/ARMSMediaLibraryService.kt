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
import com.arms.androidauto.core.data.StationRepository
import com.arms.androidauto.core.model.Station
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(UnstableApi::class)
class ARMSMediaLibraryService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var stationRepository: StationRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var nowPlayingRefreshJob: Job? = null

    private val rootId = "[ROOT]"
    private val favoritesId = "[FAVORITES]"
    private val allRadioId = "[ALL_RADIO]"

    // 편성/곡 정보를 다시 확인할 주기. 방송 전환은 보통 정시/30분 단위지만,
    // K-POP은 곡이 3~4분마다 바뀌므로 그보다 짧게 잡아 갱신 지연을 최소화한다.
    private val nowPlayingRefreshIntervalMs = 30_000L

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

    override fun onDestroy() {
        nowPlayingRefreshJob?.cancel()
        loudnessEnhancer?.release()
        player.release()
        mediaLibrarySession.release()
        super.onDestroy()
    }

    // 채널이 바뀔 때마다 호출: SBS만 게인을 올리고 나머지 채널은 원본 그대로 재생한다.
    // ExoPlayer의 audioSessionId는 인스턴스 생애주기 동안 보통 고정되지만, 혹시 바뀌더라도
    // 안전하게 다시 붙이도록 매번 현재 세션ID와 비교해서 필요할 때만 새로 생성한다.
    private fun applyLoudnessCompensation(stationId: String) {
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
        val stationId = currentItem.mediaId

        val nowPlaying = withContext(Dispatchers.IO) { stationRepository.fetchMetadata(stationId) }

        val unchanged = nowPlaying.programTitle == currentItem.mediaMetadata.subtitle?.toString() &&
            nowPlaying.currentSong == currentItem.mediaMetadata.artist?.toString()
        if (unchanged) return

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
            try {
                grantUriPermission(controller.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (e: Exception) {
                // 해당 컨트롤러에 권한 부여 실패는 무시
            }
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
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val children = mutableListOf<MediaItem>()

            when (parentId) {
                rootId -> {
                    // 루트 메뉴: 즐겨찾기 및 모든 채널 대분류 노드 추가
                    children.add(createFolderItem(favoritesId, "즐겨찾는 채널", "★ 자주 듣는 라디오 목록"))
                    children.add(createFolderItem(allRadioId, "모든 라디오 채널", "📻 대한민국 실시간 방송 목록"))
                    return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
                }
                favoritesId -> {
                    // 즐겨찾기 폴더 브라우징: DB에서 즐겨찾기 상태인 방송만 필터링하여 노출
                    val future = Futures.transform(
                        Futures.immediateFuture(stationRepository),
                        { repo ->
                            val favoriteStations = runBlocking { repo.getFavoriteStations().first() }
                            ImmutableList.copyOf(favoriteStations.map { createPlayableItem(it) })
                        },
                        MoreExecutors.directExecutor()
                    )
                    return Futures.transform(
                        future,
                        { items -> LibraryResult.ofItemList(items, params) },
                        MoreExecutors.directExecutor()
                    )
                }
                allRadioId -> {
                    // 모든 라디오 채널 브라우징: DB의 모든 수신 가능 주파수 목록 노출
                    val future = Futures.transform(
                        Futures.immediateFuture(stationRepository),
                        { repo ->
                            val allStations = runBlocking { repo.getAllStations().first() }
                            ImmutableList.copyOf(allStations.map { createPlayableItem(it) })
                        },
                        MoreExecutors.directExecutor()
                    )
                    return Futures.transform(
                        future,
                        { items -> LibraryResult.ofItemList(items, params) },
                        MoreExecutors.directExecutor()
                    )
                }
            }
            return Futures.immediateFuture(LibraryResult.ofItemList(ImmutableList.of(), params))
        }

        override fun onGetItem(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            mediaId: String
        ): ListenableFuture<LibraryResult<MediaItem>> {
            // 특정 ID의 단일 미디어 정보 로드 및 반환
            val future = Futures.transform(
                Futures.immediateFuture(stationRepository),
                { repo ->
                    val station = runBlocking { repo.getAllStations().first() }.find { it.id == mediaId }
                    station?.let { createPlayableItem(it) }
                },
                MoreExecutors.directExecutor()
            )
            return Futures.transform(
                future,
                { item ->
                    if (item != null) LibraryResult.ofItem(item, null)
                    else LibraryResult.ofError(LibraryResult.RESULT_ERROR_BAD_VALUE)
                },
                MoreExecutors.directExecutor()
            )
        }

        // 차량 시동을 끄면 Android Auto/서비스가 종료됐다가, 다시 연결될 때 시스템이 이 콜백으로
        // "마지막에 뭘 재생 중이었는지" 물어본다. 이걸 구현하지 않으면 세션이 빈 상태로 새로
        // 시작돼서, 사용자가 직접 채널을 다시 눌러야만 재생이 시작된다 (자동 재개 안 됨).
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            return Futures.transform(
                Futures.immediateFuture(stationRepository),
                { repo ->
                    val lastStationId = repo.getLastPlayedStationId()
                    val allStations = runBlocking { repo.getAllStations().first() }
                    val station = allStations.find { it.id == lastStationId } ?: allStations.firstOrNull()
                    val items = station?.let { ImmutableList.of(runBlocking { buildEnrichedMediaItem(it) }) }
                        ?: ImmutableList.of()
                    // startPositionMs를 0으로 고정하면, 방송사 스트림이 되감기 가능한 DVR 버퍼를
                    // 제공하는 경우 라이브 최신 지점이 아니라 그 버퍼의 맨 앞부분부터 재생을
                    // 시작해버린다. 시동을 끄고 다시 켰을 때 예전 구간이 재생되다가 결국 멈추던
                    // 버그가 바로 이 때문이었다. TIME_UNSET으로 두면 라이브 스트림은 최신 지점부터
                    // 재생을 시작한다.
                    MediaSession.MediaItemsWithStartPosition(items, 0, C.TIME_UNSET)
                },
                MoreExecutors.directExecutor()
            )
        }

        // 차량이 mediaId로 재생을 요청할 때(playFromMediaId), 캐시된 URL이 만료됐을 수 있으므로
        // 실제 재생 직전에 항상 새로 서명된 스트림 URL을 받아오고, 실시간 편성/곡 정보와 이미지를
        // 채워 Now Playing 화면에 실제 방송 프로필/앨범 아트가 보이도록 한다.
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            return Futures.transform(
                Futures.immediateFuture(stationRepository),
                { repo ->
                    val allStations = runBlocking { repo.getAllStations().first() }
                    mediaItems.mapNotNull { item ->
                        val station = allStations.find { it.id == item.mediaId } ?: return@mapNotNull null
                        // 차량 브라우징 목록에서 채널을 직접 선택한 경우에도 "마지막 재생 채널"을
                        // 저장해야, 다음에 시동을 걸었을 때 onPlaybackResumption이 이 채널을
                        // 정확히 자동 재개할 수 있다.
                        repo.saveLastPlayedStationId(station.id)
                        val enrichedItem = runBlocking { buildEnrichedMediaItem(station) }
                        // 최초 재생 요청을 보낸 컨트롤러에도 확실히 아트워크 열람 권한을 부여
                        enrichedItem.mediaMetadata.artworkUri?.let { uri ->
                            try {
                                grantUriPermission(controller.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            } catch (e: Exception) {
                                // 무시
                            }
                        }
                        enrichedItem
                    }.toMutableList()
                },
                MoreExecutors.directExecutor()
            )
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

    // 코루틴 동기 브라우징 블로킹 브릿지 헬퍼
    private fun <T> runBlocking(block: suspend () -> T): T {
        return kotlinx.coroutines.runBlocking { block() }
    }

    // Now Playing 화면에 표시할 방송 프로필/앨범 이미지를 직접 내려받음
    private fun fetchArtworkBytes(url: String): ByteArray? {
        return try {
            val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        }
    }

    // 내려받은 아트워크를 캐시 파일로 저장하고, 카미디어 프로세스가 읽을 수 있도록
    // FileProvider의 content:// URI로 노출한다.
    // 파일명에 항상 같은 이름(예: "$stationId.jpg")을 쓰면 content:// URI도 매번 동일해져,
    // 차량 미디어 UI의 이미지 로더가 URI 기준으로 캐싱할 경우 내용이 바뀌어도 이전 이미지를
    // 계속 보여준다 (K-POP 커버가 곡이 바뀌어도 갱신되지 않던 원인). 매번 고유한 파일명을 써서
    // URI 자체가 바뀌도록 하고, 이전 캐시 파일은 지운다.
    private fun createArtworkContentUri(bytes: ByteArray, stationId: String): android.net.Uri? {
        return try {
            val artworkDir = java.io.File(cacheDir, "artwork").apply { mkdirs() }
            artworkDir.listFiles { f -> f.name.startsWith("$stationId-") }?.forEach { it.delete() }
            val file = java.io.File(artworkDir, "$stationId-${System.currentTimeMillis()}.jpg")
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
    private class LiveRadioPlayer(
        player: ExoPlayer,
        private val onSkipToAdjacentStation: (direction: Int) -> Unit
    ) : ForwardingPlayer(player) {
        override fun getDuration(): Long = C.TIME_UNSET
        override fun getContentDuration(): Long = C.TIME_UNSET
        override fun isCurrentMediaItemSeekable(): Boolean = false

        override fun hasNextMediaItem(): Boolean = true
        override fun hasPreviousMediaItem(): Boolean = true
        override fun seekToNext() = onSkipToAdjacentStation(1)
        override fun seekToPrevious() = onSkipToAdjacentStation(-1)
        override fun seekToNextMediaItem() = onSkipToAdjacentStation(1)
        override fun seekToPreviousMediaItem() = onSkipToAdjacentStation(-1)

        override fun getAvailableCommands(): Player.Commands {
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
    }
}
