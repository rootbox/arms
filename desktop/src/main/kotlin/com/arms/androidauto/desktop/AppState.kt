package com.arms.androidauto.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.core.network.NetworkClient
import com.arms.androidauto.core.playback.QueueTrack
import com.arms.androidauto.desktop.nas.DesktopNasRepository
import com.arms.androidauto.desktop.storage.FileCredentialStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// 데스크톱 앱의 단일 상태 보관소. 안드로이드 MainActivity의 상태/재생 함수에 대응하되,
// 재생은 AudioPlayer(DesktopAudioPlayer), 데이터는 재사용 core/network·DesktopNasRepository를 쓴다.
class AppState(private val scope: CoroutineScope) {
    private val radioApi = NetworkClient.radioApiService
    val player = DesktopAudioPlayer()
    private val credentialStore = FileCredentialStore()
    val nas = DesktopNasRepository(credentialStore)

    var nowTitle by mutableStateOf("재생 중인 항목 없음"); private set
    var nowSubtitle by mutableStateOf(""); private set
    var nowCover by mutableStateOf<String?>(null); private set
    var isPlaying by mutableStateOf(false); private set
    var currentId by mutableStateOf<String?>(null); private set

    var nasConfigured by mutableStateOf(nas.hasCredentials()); private set
    var nasAlbums by mutableStateOf<List<NasAlbum>>(emptyList()); private set
    var statusMessage by mutableStateOf<String?>(null)

    private val stations = listOf(
        Triple("1", "KBS Cool FM (89.1 MHz)", "실시간 라디오 방송"),
        Triple("2", "SBS 파워FM (107.7 MHz)", "실시간 라디오 방송"),
        Triple("3", "K-POP 24/7", "24/7 온라인 스트리밍"),
    )
    fun stationList() = stations

    init {
        player.onIsPlayingChanged = { isPlaying = it }
        player.onTrackChanged = { title, cover ->
            if (title != null) nowTitle = title
            if (cover != null) nowCover = cover
        }
        player.onPlaybackError = { statusMessage = "재생 실패: $it" }
        if (nasConfigured) loadNasAlbums()
    }

    fun playRadio(id: String) {
        val st = stations.first { it.first == id }
        currentId = id; nowTitle = st.second; nowSubtitle = st.third; nowCover = null
        scope.launch {
            val meta = withContext(Dispatchers.IO) { radioApi.getStationMetadata(id) }
            val url = withContext(Dispatchers.IO) { radioApi.getPlaybackUrl(id) } ?: meta?.streamUrl
            if (url.isNullOrBlank()) { statusMessage = "스트림 주소를 받지 못했습니다"; return@launch }
            withContext(Dispatchers.IO) { player.play(url) }
            meta?.let { nowSubtitle = it.programTitle ?: st.third; nowCover = it.imageUrl }
        }
    }

    fun playAlbum(album: NasAlbum) {
        currentId = "nas:${album.key}"; nowTitle = album.name; nowSubtitle = album.albumArtist; nowCover = null
        scope.launch {
            val tracks = withContext(Dispatchers.IO) { nas.getAlbumTracks(album) }
            if (tracks.isEmpty()) { statusMessage = "재생할 곡이 없거나 NAS에 연결할 수 없습니다"; return@launch }
            val queue = tracks.map { QueueTrack(it.song.title, it.streamUrl, it.artworkUrl, it.song.artist ?: it.song.albumArtist, it.song.album) }
            withContext(Dispatchers.IO) { player.playQueue(queue, 0, 0L) }
        }
    }

    fun togglePlayPause() { if (isPlaying) player.pause() else player.resume() }
    fun next() = player.nextTrack()
    fun previous() = player.previousTrack()

    fun saveNasCredentials(baseUrl: String, account: String, password: String) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { nas.testConnection(baseUrl, account, password) }
            if (!ok) { statusMessage = "NAS 연결 실패 — 주소/계정을 확인하세요"; return@launch }
            withContext(Dispatchers.IO) { nas.saveCredentials(baseUrl, account, password) }
            nasConfigured = true; statusMessage = "NAS 연결됨"
            loadNasAlbums()
        }
    }

    private fun loadNasAlbums() {
        scope.launch {
            nasAlbums = withContext(Dispatchers.IO) { runCatching { nas.getAlbums() }.getOrDefault(emptyList()) }
        }
    }

    fun release() = player.release()
}
