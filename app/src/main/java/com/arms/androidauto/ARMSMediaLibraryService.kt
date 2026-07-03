package com.arms.androidauto

import android.os.Bundle
import androidx.annotation.OptIn
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

    override fun onCreate() {
        super.onCreate()

        // 1. ExoPlayer 및 Repository 초기화
        player = ExoPlayer.Builder(this).build()
        stationRepository = StationRepository(this)

        // 2. MediaLibrarySession 초기화 및 콜백 바인딩
        // 실시간 라디오는 탐색(seek)이 의미가 없으므로, 재생 구간 바가 뜨지 않도록
        // duration/seek 관련 정보를 감추는 래퍼를 통해 세션에 플레이어를 연결한다.
        mediaLibrarySession = MediaLibrarySession.Builder(
            this,
            LiveRadioPlayer(player),
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
        player.release()
        mediaLibrarySession.release()
        super.onDestroy()
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
            bytes?.let { createArtworkContentUri(it, stationId, packageName) }
        }

        artworkUri?.let { uri ->
            mediaLibrarySession.connectedControllers.forEach { controller ->
                try {
                    grantUriPermission(controller.packageName, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } catch (e: Exception) {
                    // 해당 컨트롤러에 권한 부여 실패는 무시
                }
            }
        }

        val updatedMetadata = currentItem.mediaMetadata.buildUpon()
            .setSubtitle(nowPlaying.programTitle)
            .setArtist(nowPlaying.currentSong)
            .apply { artworkUri?.let { setArtworkUri(it) } }
            .build()

        val updatedItem = currentItem.buildUpon().setMediaMetadata(updatedMetadata).build()
        player.replaceMediaItem(player.currentMediaItemIndex, updatedItem)
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
                .setTitle("ARMS Android Auto")
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
                        val freshUrl = runBlocking { repo.getPlaybackUrl(station.id) } ?: station.frequencyOrUrl
                        val nowPlaying = runBlocking { repo.fetchMetadata(station.id) }

                        val metadata = MediaMetadata.Builder()
                            .setTitle(station.name)
                            .setSubtitle(nowPlaying.programTitle)
                            .setArtist(nowPlaying.currentSong)
                            .setIsBrowsable(false)
                            .setIsPlayable(true)
                            .setMediaType(MediaMetadata.MEDIA_TYPE_RADIO_STATION)
                            .apply {
                                // 카미디어 UI는 원격 https URI나 임베드 바이트를 직접 로드하지 않으므로,
                                // 이미지를 내려받아 파일로 저장한 뒤 FileProvider의 content:// URI로 노출한다
                                // (안드로이드 앱과 동일한 이미지 노출).
                                nowPlaying.imageUrl?.let { url ->
                                    val bytes = runBlocking { withContext(Dispatchers.IO) { fetchArtworkBytes(url) } }
                                    if (bytes != null) {
                                        createArtworkContentUri(bytes, station.id, controller.packageName)?.let { uri ->
                                            setArtworkUri(uri)
                                        }
                                    }
                                }
                            }
                            .build()

                        MediaItem.Builder()
                            .setMediaId(station.id)
                            .setUri(android.net.Uri.parse(freshUrl))
                            .setMediaMetadata(metadata)
                            .build()
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
    // FileProvider의 content:// URI로 노출(권한 부여)한다.
    private fun createArtworkContentUri(bytes: ByteArray, stationId: String, granteePackage: String): android.net.Uri? {
        return try {
            val artworkDir = java.io.File(cacheDir, "artwork").apply { mkdirs() }
            val file = java.io.File(artworkDir, "$stationId.jpg")
            file.writeBytes(bytes)
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this@ARMSMediaLibraryService,
                "$packageName.artworkprovider",
                file
            )
            grantUriPermission(granteePackage, uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            uri
        } catch (e: Exception) {
            null
        }
    }

    // 실시간 라디오 스트림은 재생 구간(탐색 바)이 의미가 없으므로, duration/seek 관련 정보를
    // 차량 UI에 노출하지 않도록 감싸는 래퍼. 실제 재생/일시정지는 그대로 위임한다.
    private class LiveRadioPlayer(player: ExoPlayer) : ForwardingPlayer(player) {
        override fun getDuration(): Long = C.TIME_UNSET
        override fun getContentDuration(): Long = C.TIME_UNSET
        override fun isCurrentMediaItemSeekable(): Boolean = false

        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands().buildUpon()
                .remove(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
                .remove(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
                .remove(Player.COMMAND_SEEK_BACK)
                .remove(Player.COMMAND_SEEK_FORWARD)
                .build()
        }
    }
}
