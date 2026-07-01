package com.arms.androidauto

import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
class ARMSMediaLibraryService : MediaLibraryService() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaLibrarySession: MediaLibrarySession
    private lateinit var stationRepository: StationRepository
    private val serviceScope = CoroutineScope(Dispatchers.Main)

    private val rootId = "[ROOT]"
    private val favoritesId = "[FAVORITES]"
    private val allRadioId = "[ALL_RADIO]"

    override fun onCreate() {
        super.onCreate()
        
        // 1. ExoPlayer 및 Repository 초기화
        player = ExoPlayer.Builder(this).build()
        stationRepository = StationRepository(this)

        // 2. MediaLibrarySession 초기화 및 콜백 바인딩
        mediaLibrarySession = MediaLibrarySession.Builder(
            this, 
            player, 
            LibraryCallback()
        ).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaLibrarySession
    }

    override fun onDestroy() {
        player.release()
        mediaLibrarySession.release()
        super.onDestroy()
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
            return super.onGetItem(session, browser, mediaId)
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
}
