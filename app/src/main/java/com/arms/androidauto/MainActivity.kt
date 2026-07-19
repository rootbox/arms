package com.arms.androidauto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.arms.androidauto.core.data.LastPlayed
import com.arms.androidauto.core.data.NasMusicRepository
import com.arms.androidauto.core.data.PlaybackStateStore
import com.arms.androidauto.core.data.NowPlayingInfo
import com.arms.androidauto.core.data.StationRepository
import com.arms.androidauto.core.media.MediaPlayer
import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.core.data.NasPlaylistRepository
import com.arms.androidauto.core.model.NasPlaylist
import com.arms.androidauto.core.model.NasPlaylistTrack
import com.arms.androidauto.core.model.NasSong
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.model.StationType
import com.arms.androidauto.ui.nas.AddToPlaylistDialog
import com.arms.androidauto.ui.nas.NasAlbumDetailScreen
import com.arms.androidauto.ui.nas.NasPlaylistDetailScreen
import com.arms.androidauto.ui.nas.NasPlaylistListContent
import com.arms.androidauto.ui.theme.ARMSAndroidAutoTheme
import com.arms.androidauto.ui.theme.RadioBgDeep
import com.arms.androidauto.ui.theme.RadioBgMid
import com.arms.androidauto.ui.theme.RadioNeonCyan
import com.arms.androidauto.ui.theme.RadioNeonMagenta
import com.arms.androidauto.ui.theme.RadioNeonOrange
import com.arms.androidauto.ui.theme.RadioOnAirRed
import com.arms.androidauto.ui.theme.SpotifyBlackElevated
import com.arms.androidauto.ui.theme.SpotifyGreen
import com.arms.androidauto.ui.theme.Radius
import com.arms.androidauto.ui.theme.Sizes
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.SpotifySurfaceElevated
import com.arms.androidauto.ui.theme.SpotifyTextMuted
import com.arms.androidauto.ui.theme.SpotifyTextPrimary
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private lateinit var stationRepository: StationRepository
    private lateinit var mediaPlayer: MediaPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 데이터 저장소 및 미디어 플레이어 초기화
        stationRepository = StationRepository(this)
        mediaPlayer = MediaPlayer(this)

        setContent {
            ARMSAndroidAutoTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RadioPlayerScreen(stationRepository, mediaPlayer)
                }
            }
        }
    }

    override fun onDestroy() {
        mediaPlayer.release()
        super.onDestroy()
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    return powerManager.isIgnoringBatteryOptimizations(context.packageName)
}

// 재생을 멈추고 한동안 방치하면 시스템이 앱을 낮은 standby bucket으로 강등시켜, Android Auto가
// 백그라운드에서 MediaLibraryService를 다시 바인딩하지 못하고 차량 미디어 소스 목록에서
// 사라지는 문제가 있었다. 배터리 최적화 제외가 안 되어 있으면 설정 화면으로 안내한다.
// 설정 화면에서 돌아왔을 때 배너가 바로 사라지도록 ON_RESUME마다 다시 확인한다.
@Composable
private fun rememberIgnoringBatteryOptimizations(context: Context): State<Boolean> {
    val lifecycleOwner = LocalLifecycleOwner.current
    val state = remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                state.value = isIgnoringBatteryOptimizations(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    return state
}

@Composable
private fun BatteryOptimizationBanner(context: Context) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(RadioOnAirRed.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            "차량 연결 중 앱이 사라지지 않도록, 배터리 최적화에서 제외해주세요",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            context.startActivity(intent)
        }) {
            Text("설정", color = RadioNeonCyan, fontWeight = FontWeight.Bold)
        }
    }
}

// 새 버전이 있을 때 케이블 연결 없이 바로 내려받아 설치할 수 있도록 안내하는 배너.
// 실제 설치는 항상 시스템 확인 다이얼로그를 한 번 거친다 (Android 정책상 완전 무음 설치는 불가능).
@Composable
private fun UpdateAvailableBanner(
    update: UpdateInfo,
    isDownloading: Boolean,
    onDownloadClick: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .background(RadioNeonCyan.copy(alpha = 0.15f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Text(
            "새 버전 ${update.versionName} 사용 가능",
            style = MaterialTheme.typography.bodySmall,
            color = Color.White,
            modifier = Modifier.weight(1f)
        )
        if (isDownloading) {
            CircularProgressIndicator(color = RadioNeonCyan, modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onDownloadClick) {
                Text("다운로드", color = RadioNeonCyan, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun nasFilterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = SpotifyGreen,
    selectedLabelColor = RadioBgDeep,
    labelColor = SpotifyTextMuted
)

@Composable
private fun navigationBarItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = SpotifyGreen,
    selectedTextColor = SpotifyGreen,
    unselectedIconColor = SpotifyTextMuted,
    unselectedTextColor = SpotifyTextMuted,
    // Spotify 하단 탭은 선택 표시용 알약 배경이 없다
    indicatorColor = Color.Transparent
)

// '내 음악' 탭 안에서의 화면 이동. Navigation 라이브러리를 쓰지 않으므로 상태로 표현한다.
// 플레이리스트는 객체가 아니라 id를 들고 있어야, 이름 변경이나 곡 삭제 후에도 화면이
// 낡은 스냅샷을 붙들지 않는다.
internal sealed class NasScreen {
    object Library : NasScreen()
    data class AlbumDetail(val album: NasAlbum) : NasScreen()
    data class PlaylistDetail(val playlistId: Long) : NasScreen()
}

// NAS 재생이 어디서 시작됐는지. 앨범이든 플레이리스트든 "곡 목록을 순서대로 재생"이라는
// 성격은 같아서, 진행바/셔플/반복이 붙는 조건도 동일하다.
internal sealed class NasPlaybackSource {
    data class Album(val album: NasAlbum) : NasPlaybackSource()
    data class Playlist(val id: Long, val name: String) : NasPlaybackSource()

    // 목록에서 "지금 재생 중" 표시를 판단할 때 쓰는 식별자
    val key: String
        get() = when (this) {
            is Album -> "album:${album.key}"
            is Playlist -> "playlist:$id"
        }
}

// 미니플레이어와 전체화면 플레이어가 같은 문구를 쓰도록 한 곳에서 만든다.
internal fun NasPlaybackSource.displaySubtitle(): String = when (this) {
    is NasPlaybackSource.Album ->
        "${album.albumArtist.ifBlank { "알 수 없는 아티스트" }} · ${album.name}"
    is NasPlaybackSource.Playlist -> "플레이리스트 · $name"
}

// 현재 무엇이 재생 중인지를 하나의 타입으로 표현 - 미니플레이어/전체화면 플레이어가
// 라디오/NAS 여부를 반복해서 분기하지 않고 이 타입 하나로 제목/부제/동작을 결정한다.
internal sealed class ActivePlayback {
    data class Radio(val station: Station, val nowPlaying: NowPlayingInfo) : ActivePlayback()
    data class Nas(val source: NasPlaybackSource, val trackTitle: String?) : ActivePlayback()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioPlayerScreen(repository: StationRepository, player: MediaPlayer) {
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val batteryUnrestricted by rememberIgnoringBatteryOptimizations(context)
    val updateChecker = remember { UpdateChecker(context) }
    var availableUpdate by remember { mutableStateOf<UpdateInfo?>(null) }
    var isDownloadingUpdate by remember { mutableStateOf(false) }
    val currentVersionName = remember { updateChecker.currentVersionName() }
    var lastCheckedAtMillis by remember { mutableStateOf(updateChecker.lastCheckedAtMillis()) }

    // 케이블/adb 없이도 새 버전을 알 수 있도록, 앱을 열 때마다 GitHub Releases를 조용히 확인한다.
    // 새 버전이 없으면 아무 것도 표시하지 않고, 있을 때만 배너로 안내한다.
    LaunchedEffect(Unit) {
        try {
            availableUpdate = updateChecker.checkForUpdate()
            lastCheckedAtMillis = updateChecker.lastCheckedAtMillis()
        } catch (e: Exception) {
            // 업데이트 확인 실패는 조용히 무시 (다음 실행 때 다시 시도)
        }
    }

    // DB의 방송국 목록 관찰 (Flow -> State)
    val stationsState = repository.getAllStations().collectAsState(initial = emptyList())
    val stations = stationsState.value

    // Synology NAS 음악 (선택 사항 - 계정 연결 전에는 목록이 비어있다)
    // 차량 서비스와 같은 인스턴스를 공유해서 세션/곡목록 캐시도 함께 쓴다.
    val nasMusicRepository = remember { NasMusicRepository.get(context) }
    val nasPlaylistRepository = remember { NasPlaylistRepository.get(context) }
    val playbackStateStore = remember { PlaybackStateStore(context) }
    val playlists by nasPlaylistRepository.observePlaylists().collectAsState(initial = emptyList())
    var nasAlbums by remember { mutableStateOf<List<NasAlbum>>(emptyList()) }
    var nasPlaybackSource by remember { mutableStateOf<NasPlaybackSource?>(null) }
    var showNasSettings by remember { mutableStateOf(false) }
    var nasConfigured by remember { mutableStateOf(nasMusicRepository.hasCredentials()) }
    var nasCurrentTrackTitle by remember { mutableStateOf<String?>(null) }
    var isNasPaused by remember { mutableStateOf(false) }
    var nasSearchQuery by remember { mutableStateOf("") }
    var manuallyExpandedArtists by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isNowPlayingExpanded by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(0) } // 0 = 라디오, 1 = 내 음악
    var nasScreen by remember { mutableStateOf<NasScreen>(NasScreen.Library) }
    var albumDetailSongs by remember { mutableStateOf<List<NasSong>>(emptyList()) }
    var isLoadingAlbumSongs by remember { mutableStateOf(false) }
    // 담기 다이얼로그를 띄울 대상 (비어있지 않으면 다이얼로그가 열린다)
    var songsPendingPlaylist by remember { mutableStateOf<List<NasSong>>(emptyList()) }
    // '내 음악' 탭 안에서 앨범 목록과 플레이리스트 목록을 전환하는 칩
    var nasLibraryMode by remember { mutableStateOf(0) } // 0 = 앨범, 1 = 플레이리스트
    var playlistDetailTracks by remember { mutableStateOf<List<NasPlaylistTrack>>(emptyList()) }
    var playbackPositionMs by remember { mutableStateOf(0L) }
    var playbackDurationMs by remember { mutableStateOf(0L) }
    var shuffleEnabled by remember { mutableStateOf(false) }
    var repeatMode by remember { mutableStateOf(Player.REPEAT_MODE_OFF) }

    LaunchedEffect(nasConfigured) {
        // 차량 서비스는 브라우징 루트를 빨리 그려야 해서 암호화 저장소를 열지 않고
        // 평문 플래그만 본다. 여기서 실제 자격증명 유무와 한 번 맞춰준다.
        playbackStateStore.setNasConfigured(nasConfigured)
        if (nasConfigured) {
            try {
                nasAlbums = nasMusicRepository.getAlbums()
            } catch (e: Exception) {
                // 무시 - 설정 화면에서 연결 테스트로 원인 확인 가능
            }
        }
    }

    var selectedStationId by remember { mutableStateOf<String?>(null) }
    var playingStationId by remember { mutableStateOf<String?>(null) }
    var nowPlaying by remember { mutableStateOf(NowPlayingInfo("채널을 선택해주세요", "", null)) }
    var isLoadingMetadata by remember { mutableStateOf(false) }

    val selectedStation = stations.find { it.id == selectedStationId }
    val isSelectedPlaying = selectedStationId != null && selectedStationId == playingStationId

    // 채널 재생 시작 (중복 재생 방지 + 마지막 재생 채널 저장)
    // 캐시된 frequencyOrUrl은 서명 토큰이 만료되었을 수 있으므로, 재생 직전에 항상 새 URL을 받아온다.
    fun playStation(station: Station) {
        selectedStationId = station.id
        if (playingStationId == station.id) return
        player.stop()
        playingStationId = station.id
        nasPlaybackSource = null
        repository.saveLastPlayedStationId(station.id)
        // 차량 자동 재개가 라디오/NAS를 구분할 수 있도록 종류까지 같이 기록한다.
        playbackStateStore.saveLastPlayed(LastPlayed.Radio(station.id))
        coroutineScope.launch {
            val playbackUrl = try {
                repository.getPlaybackUrl(station.id)
            } catch (e: Exception) {
                null
            } ?: station.frequencyOrUrl
            player.play(playbackUrl)
        }
    }

    fun stopPlayback() {
        player.stop()
        playingStationId = null
        nasPlaybackSource = null
    }

    // 라디오 재생 중 이전/다음 버튼 = 인접 채널로 순환 전환. playStation()이 URL 갱신과
    // 마지막 재생 채널 저장을 이미 다 처리하므로 여기선 대상 채널만 계산해서 넘긴다.
    fun skipToAdjacentStation(direction: Int) {
        if (stations.isEmpty()) return
        val currentIndex = stations.indexOfFirst { it.id == playingStationId }
        if (currentIndex == -1) return
        val nextIndex = ((currentIndex + direction) % stations.size + stations.size) % stations.size
        playStation(stations[nextIndex])
    }

    // NAS 재생: 실시간 라디오와 달리 유한한 곡 목록이라 진짜 트랙 순서로 재생한다.
    // 앨범 전체 / 앨범의 특정 곡부터 / 플레이리스트가 모두 이 함수를 거친다.
    fun playNasQueue(
        source: NasPlaybackSource,
        loadTracks: suspend () -> List<Pair<NasSong, String>>,
        startIndex: Int = 0
    ) {
        selectedStationId = null
        playingStationId = null
        nasPlaybackSource = source
        isNasPaused = false
        coroutineScope.launch {
            try {
                val tracks = loadTracks()
                if (tracks.isEmpty()) {
                    snackbarHostState.showSnackbar("재생할 곡이 없거나 NAS에 연결할 수 없습니다")
                    nasPlaybackSource = null
                    return@launch
                }
                player.playQueue(
                    tracks.map { (song, url) -> song.title to url },
                    startIndex.coerceIn(0, tracks.lastIndex)
                )
                when (source) {
                    is NasPlaybackSource.Album -> {
                        // 차량의 "최근 재생한 앨범" 목록과 자동 재개에 반영
                        playbackStateStore.saveLastPlayed(LastPlayed.Nas(source.album))
                        playbackStateStore.pushRecentAlbum(source.album)
                    }
                    is NasPlaybackSource.Playlist -> {
                        playbackStateStore.saveLastPlayed(
                            LastPlayed.NasPlaylist(source.id, source.name)
                        )
                    }
                }
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("NAS 재생 실패: ${e.message}")
                nasPlaybackSource = null
            }
        }
    }

    fun playNasAlbum(album: NasAlbum, startIndex: Int = 0) {
        playNasQueue(
            source = NasPlaybackSource.Album(album),
            loadTracks = { nasMusicRepository.getAlbumStreamUrls(album) },
            startIndex = startIndex
        )
    }

    fun playNasPlaylist(playlist: NasPlaylist, startIndex: Int = 0) {
        playNasQueue(
            source = NasPlaybackSource.Playlist(playlist.id, playlist.name),
            loadTracks = {
                val tracks = nasPlaylistRepository.getTracks(playlist.id)
                val resolved = nasMusicRepository.getStreamUrlsForSongIds(tracks.map { it.songId })
                // 담아둔 곡이 NAS에서 사라졌으면 그만큼 빠진다. 조용히 넘어가지 않고 알린다.
                val missing = tracks.size - resolved.size
                if (missing > 0 && resolved.isNotEmpty()) {
                    snackbarHostState.showSnackbar("${missing}곡을 재생할 수 없어 건너뜁니다")
                }
                resolved
            },
            startIndex = startIndex
        )
    }

    fun refreshNowPlayingMetadata() {
        val stationId = playingStationId ?: return
        coroutineScope.launch {
            isLoadingMetadata = true
            try {
                nowPlaying = repository.fetchMetadata(stationId)
            } catch (e: Exception) {
                // 에러 시 기존 값 유지
            } finally {
                isLoadingMetadata = false
            }
        }
    }

    fun toggleFavoriteForStation(station: Station) {
        coroutineScope.launch {
            repository.updateStationFavoriteStatus(station.id, !station.isFavorite)
        }
    }

    // 지금 재생 중인 것이 라디오인지 NAS 앨범인지를 하나의 타입으로 표현 - 미니플레이어와
    // 전체화면 플레이어가 동일한 값을 보고 제목/부제/동작을 결정한다.
    // source가 표시에 필요한 정보를 이미 들고 있으므로 앨범 목록을 되짚지 않는다.
    // (되짚던 예전 방식은 목록이 로드되기 전에 재생이 시작되면 미니플레이어가 아예 뜨지 않았고,
    //  앨범 목록에 없는 플레이리스트 재생은 표현할 수도 없었다)
    val activePlayback: ActivePlayback? = when {
        nasPlaybackSource != null -> ActivePlayback.Nas(nasPlaybackSource!!, nasCurrentTrackTitle)
        playingStationId != null -> stations.find { it.id == playingStationId }
            ?.let { ActivePlayback.Radio(it, nowPlaying) }
        else -> null
    }
    val isActivePlaybackPlaying = when (activePlayback) {
        is ActivePlayback.Nas -> !isNasPaused
        is ActivePlayback.Radio -> true
        null -> false
    }

    // 이전/다음 버튼: NAS 재생 중이면 트랙 전환, 라디오 재생 중이면 채널 전환.
    fun handlePrevious() {
        if (nasPlaybackSource != null) player.previousTrack() else skipToAdjacentStation(-1)
    }
    fun handleNext() {
        if (nasPlaybackSource != null) player.nextTrack() else skipToAdjacentStation(1)
    }
    // 재생/일시정지: NAS는 큐 위치를 유지하는 진짜 일시정지, 라디오는 실시간 스트림이라
    // 의미 있는 일시정지 지점이 없으므로 기존처럼 완전히 멈췄다가 새로 이어받는다.
    fun handlePlayPauseToggle() {
        when {
            nasPlaybackSource != null -> {
                isNasPaused = !isNasPaused
                if (isNasPaused) player.pause() else player.resume()
            }
            playingStationId != null -> stopPlayback()
        }
    }

    var hasAutoResumed by remember { mutableStateOf(false) }

    // 목록이 로드되면 최초 1회, 마지막으로 재생했던 채널을 자동 재생 (없으면 첫 채널만 선택)
    LaunchedEffect(stations.isNotEmpty()) {
        if (!hasAutoResumed && stations.isNotEmpty()) {
            hasAutoResumed = true
            val lastPlayed = stations.find { it.id == repository.getLastPlayedStationId() }
            if (lastPlayed != null) {
                playStation(lastPlayed)
            } else {
                selectedStationId = stations.first().id
            }
        }
    }

    // 재생 실패 시 사용자에게 알리고 버튼 상태를 원래대로 되돌림
    LaunchedEffect(player) {
        player.onPlaybackError = { message ->
            playingStationId = null
            coroutineScope.launch {
                snackbarHostState.showSnackbar("재생 실패: $message")
            }
        }
        player.onTrackChanged = { title -> nasCurrentTrackTitle = title }
        // 버퍼링·오디오 포커스 상실 등 플레이어가 먼저 멈추는 경우까지 상태를 맞춘다.
        player.onIsPlayingChanged = { playing ->
            if (nasPlaybackSource != null) isNasPaused = !playing
        }
    }

    // 앱 시작 시 채널 목록 새로고침
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            try {
                repository.refreshStations()
            } catch (e: Exception) {
                // 무시 (로컬 캐시로 계속 진행)
            }
        }
    }

    // 선택된 채널이 바뀔 때마다 편성 정보 갱신
    LaunchedEffect(selectedStationId) {
        val stationId = selectedStationId ?: return@LaunchedEffect
        isLoadingMetadata = true
        try {
            nowPlaying = repository.fetchMetadata(stationId)
        } catch (e: Exception) {
            // 에러 시 기존 값 유지
        } finally {
            isLoadingMetadata = false
        }
    }

    val backgroundBrush = Brush.verticalGradient(listOf(RadioBgMid, RadioBgDeep))

    // 앨범이 수백 개일 수 있어 아티스트별로 묶어 기본은 접어두고, 검색어가 있을 때만
    // 해당하는 아티스트 그룹을 펼친다.
    val albumsByArtist = remember(nasAlbums) {
        nasAlbums.groupBy { it.albumArtist }.toSortedMap()
    }
    val normalizedNasQuery = nasSearchQuery.trim()
    val isNasSearchActive = normalizedNasQuery.isNotBlank()
    val visibleArtists = remember(albumsByArtist, normalizedNasQuery) {
        if (!isNasSearchActive) {
            albumsByArtist.keys.toList()
        } else {
            albumsByArtist.filter { (artist, albums) ->
                artist.contains(normalizedNasQuery, ignoreCase = true) ||
                    albums.any { it.name.contains(normalizedNasQuery, ignoreCase = true) }
            }.keys.toList()
        }
    }

    // 재생 중인 것이 없어지면(에러/정지) 열려 있던 전체화면 플레이어도 같이 닫는다.
    LaunchedEffect(activePlayback == null) {
        if (activePlayback == null) isNowPlayingExpanded = false
    }

    // 플레이리스트 상세는 DB를 구독해서, 곡을 빼거나 이름을 바꿔도 화면이 자동으로 따라온다.
    LaunchedEffect(nasScreen) {
        val screen = nasScreen
        if (screen is NasScreen.PlaylistDetail) {
            nasPlaylistRepository.observeTracks(screen.playlistId).collect { playlistDetailTracks = it }
        } else {
            playlistDetailTracks = emptyList()
        }
    }

    // 앨범 상세로 들어가면 그 앨범의 곡 목록을 불러온다 (곡목록 캐시가 살아있으면 네트워크 0)
    LaunchedEffect(nasScreen) {
        val screen = nasScreen
        if (screen !is NasScreen.AlbumDetail) return@LaunchedEffect
        isLoadingAlbumSongs = true
        albumDetailSongs = try {
            nasMusicRepository.getAlbumSongs(screen.album)
        } catch (e: Exception) {
            emptyList()
        } finally {
            isLoadingAlbumSongs = false
        }
    }

    // 재생 위치는 ExoPlayer가 변화 이벤트를 주지 않아 주기적으로 읽어야 한다(공식 PlayerView도
    // 같은 방식). 전체화면 플레이어가 열려 있고 NAS 재생일 때만 돌려서 평소 비용을 없앤다.
    val isTrackPlayback = activePlayback is ActivePlayback.Nas
    LaunchedEffect(isNowPlayingExpanded, isTrackPlayback, nasCurrentTrackTitle, isNasPaused) {
        if (!isNowPlayingExpanded || !isTrackPlayback) return@LaunchedEffect
        while (true) {
            playbackPositionMs = player.currentPositionMs()
            playbackDurationMs = player.durationMs()
            delay(500)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                // 하단 탭이 이미 목적지를 보여주므로, 상단은 앱 이름 대신 현재 위치를 크게 띄운다.
                // 그린은 "재생 중/활성" 신호로 아껴두고 제목은 흰색으로 둔다.
                TopAppBar(
                    title = {
                        Text(
                            text = if (selectedTab == 0) "라디오" else "내 음악",
                            style = MaterialTheme.typography.headlineLarge,
                            color = SpotifyTextPrimary
                        )
                    },
                    actions = {
                        IconButton(onClick = { showNasSettings = true }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "NAS 음악 설정",
                                tint = SpotifyTextMuted
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = SpotifyTextPrimary
                    )
                )
            },
            bottomBar = {
                Column {
                    activePlayback?.let { playback ->
                        MiniPlayerBar(
                            playback = playback,
                            isPlaying = isActivePlaybackPlaying,
                            onTap = { isNowPlayingExpanded = true },
                            onPlayPauseToggle = { handlePlayPauseToggle() },
                            onNext = { handleNext() }
                        )
                    }
                    NavigationBar(containerColor = SpotifyBlackElevated) {
                        NavigationBarItem(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_radio),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = { Text("라디오", style = MaterialTheme.typography.labelMedium) },
                            colors = navigationBarItemColors()
                        )
                        NavigationBarItem(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            icon = {
                                Icon(
                                    painter = painterResource(R.drawable.ic_library_music),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp)
                                )
                            },
                            label = { Text("내 음악", style = MaterialTheme.typography.labelMedium) },
                            colors = navigationBarItemColors()
                        )
                    }
                }
            },
            snackbarHost = { SnackbarHost(snackbarHostState) }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(backgroundBrush)
                    .padding(paddingValues)
                    .padding(horizontal = Spacing.screenHorizontal)
            ) {
                if (!batteryUnrestricted) {
                    BatteryOptimizationBanner(context)
                }
                availableUpdate?.let { update ->
                    UpdateAvailableBanner(
                        update = update,
                        isDownloading = isDownloadingUpdate,
                        onDownloadClick = {
                            isDownloadingUpdate = true
                            coroutineScope.launch {
                                try {
                                    updateChecker.downloadAndInstall(update)
                                } catch (e: Exception) {
                                    snackbarHostState.showSnackbar("업데이트 다운로드 실패: ${e.message}")
                                } finally {
                                    isDownloadingUpdate = false
                                }
                            }
                        }
                    )
                }
                if (stations.isEmpty()) {
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SpotifyGreen)
                    }
                } else when (selectedTab) {
                    0 -> RadioTabContent(
                        stations = stations,
                        selectedStationId = selectedStationId,
                        playingStationId = playingStationId,
                        currentVersionName = currentVersionName,
                        lastCheckedAtMillis = lastCheckedAtMillis,
                        onStationClick = { playStation(it) }
                    )
                    else -> when (val screen = nasScreen) {
                        is NasScreen.AlbumDetail -> NasAlbumDetailScreen(
                            album = screen.album,
                            songs = albumDetailSongs,
                            isLoading = isLoadingAlbumSongs,
                            isPlayingThisAlbum =
                                (nasPlaybackSource as? NasPlaybackSource.Album)?.album?.key == screen.album.key,
                            playingTrackTitle = nasCurrentTrackTitle,
                            onBack = { nasScreen = NasScreen.Library },
                            onPlayAll = { playNasAlbum(screen.album) },
                            onPlayFrom = { index -> playNasAlbum(screen.album, startIndex = index) },
                            onAddSongToPlaylist = { song -> songsPendingPlaylist = listOf(song) },
                            onAddAllToPlaylist = { songsPendingPlaylist = albumDetailSongs }
                        )
                        is NasScreen.PlaylistDetail -> {
                            val playlist = playlists.find { it.id == screen.playlistId }
                            NasPlaylistDetailScreen(
                                playlist = playlist,
                                tracks = playlistDetailTracks,
                                isPlayingThisPlaylist =
                                    (nasPlaybackSource as? NasPlaybackSource.Playlist)?.id == screen.playlistId,
                                playingTrackTitle = nasCurrentTrackTitle,
                                onBack = { nasScreen = NasScreen.Library },
                                onPlayAll = { playlist?.let { playNasPlaylist(it) } },
                                onPlayFrom = { index -> playlist?.let { playNasPlaylist(it, index) } },
                                onRemoveTrack = { track ->
                                    coroutineScope.launch {
                                        nasPlaylistRepository.removeTrack(screen.playlistId, track.songId)
                                    }
                                },
                                onRename = { name ->
                                    coroutineScope.launch {
                                        nasPlaylistRepository.rename(screen.playlistId, name)
                                    }
                                },
                                onDelete = {
                                    coroutineScope.launch {
                                        nasPlaylistRepository.delete(screen.playlistId)
                                        nasScreen = NasScreen.Library
                                    }
                                }
                            )
                        }
                        else -> NasTabContent(
                            nasConfigured = nasConfigured,
                            nasSearchQuery = nasSearchQuery,
                            onSearchChange = { nasSearchQuery = it },
                            visibleArtists = visibleArtists,
                            albumsByArtist = albumsByArtist,
                            isSearchActive = isNasSearchActive,
                            manuallyExpandedArtists = manuallyExpandedArtists,
                            onToggleArtist = { artist ->
                                manuallyExpandedArtists = if (artist in manuallyExpandedArtists) {
                                    manuallyExpandedArtists - artist
                                } else {
                                    manuallyExpandedArtists + artist
                                }
                            },
                            playingSource = nasPlaybackSource,
                            onAlbumClick = { nasScreen = NasScreen.AlbumDetail(it) },
                            onOpenSettings = { showNasSettings = true },
                            libraryMode = nasLibraryMode,
                            onLibraryModeChange = { nasLibraryMode = it },
                            playlists = playlists,
                            onPlaylistClick = { nasScreen = NasScreen.PlaylistDetail(it) }
                        )
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = isNowPlayingExpanded && activePlayback != null,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it })
        ) {
            activePlayback?.let { playback ->
                NowPlayingDetailScreen(
                    playback = playback,
                    isPlaying = isActivePlaybackPlaying,
                    isLoadingMetadata = isLoadingMetadata,
                    positionMs = playbackPositionMs,
                    durationMs = playbackDurationMs,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    onCollapse = { isNowPlayingExpanded = false },
                    onFavoriteToggle = (playback as? ActivePlayback.Radio)?.let {
                        { toggleFavoriteForStation(it.station) }
                    },
                    onRefreshMetadata = if (playback is ActivePlayback.Radio) ::refreshNowPlayingMetadata else null,
                    onSeek = if (playback is ActivePlayback.Nas) {
                        { positionMs ->
                            player.seekTo(positionMs)
                            playbackPositionMs = positionMs
                        }
                    } else null,
                    onToggleShuffle = if (playback is ActivePlayback.Nas) {
                        {
                            shuffleEnabled = !shuffleEnabled
                            player.shuffleEnabled = shuffleEnabled
                        }
                    } else null,
                    onCycleRepeat = if (playback is ActivePlayback.Nas) {
                        {
                            // 끄기 -> 전체 반복 -> 한 곡 반복 -> 끄기
                            repeatMode = when (repeatMode) {
                                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                                else -> Player.REPEAT_MODE_OFF
                            }
                            player.repeatMode = repeatMode
                        }
                    } else null,
                    onPrevious = { handlePrevious() },
                    onPlayPauseToggle = { handlePlayPauseToggle() },
                    onNext = { handleNext() }
                )
            }
        }

        // 두 핸들러의 조건을 서로 배타적으로 둔다 (등록 순서에 기대지 않기 위해).
        BackHandler(enabled = isNowPlayingExpanded) { isNowPlayingExpanded = false }
        BackHandler(
            enabled = !isNowPlayingExpanded && selectedTab == 1 && nasScreen != NasScreen.Library
        ) {
            nasScreen = NasScreen.Library
        }
    }

    if (songsPendingPlaylist.isNotEmpty()) {
        val pending = songsPendingPlaylist
        AddToPlaylistDialog(
            songCount = pending.size,
            playlists = playlists,
            onDismiss = { songsPendingPlaylist = emptyList() },
            onSelect = { playlistId ->
                songsPendingPlaylist = emptyList()
                coroutineScope.launch {
                    val added = nasPlaylistRepository.addSongs(playlistId, pending)
                    val name = playlists.find { it.id == playlistId }?.name ?: "플레이리스트"
                    snackbarHostState.showSnackbar(
                        when {
                            added == 0 -> "이미 담겨 있는 곡입니다"
                            added < pending.size -> "$name 에 ${added}곡 담김 (중복 제외)"
                            else -> "$name 에 ${added}곡 담았습니다"
                        }
                    )
                }
            },
            onCreateAndSelect = { name ->
                songsPendingPlaylist = emptyList()
                coroutineScope.launch {
                    val playlistId = nasPlaylistRepository.createPlaylist(name)
                    val added = nasPlaylistRepository.addSongs(playlistId, pending)
                    snackbarHostState.showSnackbar("${name.trim()} 에 ${added}곡 담았습니다")
                }
            }
        )
    }

    if (showNasSettings) {
        NasSettingsDialog(
            nasMusicRepository = nasMusicRepository,
            onDismiss = { showNasSettings = false },
            onSaved = {
                showNasSettings = false
                nasConfigured = true
            }
        )
    }
}

// 라디오 탭: 전체 채널 목록 + 하단 버전/업데이트 정보.
@Composable
private fun RadioTabContent(
    stations: List<Station>,
    selectedStationId: String?,
    playingStationId: String?,
    currentVersionName: String,
    lastCheckedAtMillis: Long?,
    onStationClick: (Station) -> Unit
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.xl)
    ) {
        items(stations, key = { it.id }) { station ->
            ChannelRow(
                station = station,
                isSelected = station.id == selectedStationId,
                isOnAir = station.id == playingStationId,
                onClick = { onStationClick(station) }
            )
        }
        item {
            val lastCheckedText = lastCheckedAtMillis?.let {
                val fmt = java.text.SimpleDateFormat("M월 d일 HH:mm", java.util.Locale.KOREA)
                "마지막 업데이트 확인: ${fmt.format(java.util.Date(it))}"
            } ?: "업데이트 확인 안 됨"

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text(
                    "Simple Radio v$currentVersionName · made by 1319.space",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyTextMuted.copy(alpha = 0.5f),
                    textAlign = TextAlign.Center
                )
                Text(
                    lastCheckedText,
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyTextMuted.copy(alpha = 0.35f),
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

// 내 음악 탭: NAS 앨범을 아티스트별로 접어서 보여주고, 상단 검색으로 필터링.
// NAS 미설정 시에는 연결을 유도하는 빈 상태를 보여준다.
@Composable
private fun NasTabContent(
    nasConfigured: Boolean,
    nasSearchQuery: String,
    onSearchChange: (String) -> Unit,
    visibleArtists: List<String>,
    albumsByArtist: Map<String, List<NasAlbum>>,
    isSearchActive: Boolean,
    manuallyExpandedArtists: Set<String>,
    onToggleArtist: (String) -> Unit,
    playingSource: NasPlaybackSource?,
    onAlbumClick: (NasAlbum) -> Unit,
    onOpenSettings: () -> Unit,
    libraryMode: Int,
    onLibraryModeChange: (Int) -> Unit,
    playlists: List<NasPlaylist>,
    onPlaylistClick: (Long) -> Unit
) {
    if (!nasConfigured) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_library_music),
                contentDescription = null,
                tint = SpotifyTextMuted,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(Spacing.lg))
            Text(
                "내 NAS 음악을 연결해보세요",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                "Synology Audio Station의 앨범을\n이 앱에서 바로 재생할 수 있어요",
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyTextMuted,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(Spacing.xl))
            Button(
                onClick = onOpenSettings,
                shape = RoundedCornerShape(Radius.xxl),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SpotifyGreen,
                    contentColor = RadioBgDeep
                )
            ) {
                Text("NAS 연결", style = MaterialTheme.typography.labelLarge)
            }
        }
        return
    }

    // 앨범 / 플레이리스트 전환
    Row(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        modifier = Modifier.padding(top = Spacing.sm, bottom = Spacing.xs)
    ) {
        FilterChip(
            selected = libraryMode == 0,
            onClick = { onLibraryModeChange(0) },
            label = { Text("앨범", style = MaterialTheme.typography.labelLarge) },
            colors = nasFilterChipColors()
        )
        FilterChip(
            selected = libraryMode == 1,
            onClick = { onLibraryModeChange(1) },
            label = { Text("플레이리스트", style = MaterialTheme.typography.labelLarge) },
            colors = nasFilterChipColors()
        )
    }

    if (libraryMode == 1) {
        NasPlaylistListContent(
            playlists = playlists,
            playingPlaylistId = (playingSource as? NasPlaybackSource.Playlist)?.id,
            onPlaylistClick = onPlaylistClick
        )
        return
    }

    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
        contentPadding = PaddingValues(top = Spacing.sm, bottom = Spacing.xl)
    ) {
        item {
            OutlinedTextField(
                value = nasSearchQuery,
                onValueChange = onSearchChange,
                placeholder = {
                    Text("앨범/아티스트 검색", style = MaterialTheme.typography.bodyMedium)
                },
                leadingIcon = {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = SpotifyTextMuted)
                },
                singleLine = true,
                shape = RoundedCornerShape(Radius.md),
                textStyle = MaterialTheme.typography.bodyMedium,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = SpotifyGreen,
                    unfocusedBorderColor = SpotifyTextMuted.copy(alpha = 0.3f),
                    focusedTextColor = SpotifyTextPrimary,
                    unfocusedTextColor = SpotifyTextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.sm)
            )
        }
        visibleArtists.forEach { artist ->
            val albums = albumsByArtist[artist].orEmpty()
            val isExpanded = isSearchActive || artist in manuallyExpandedArtists
            item(key = "artist:$artist") {
                NasArtistHeaderRow(
                    artistName = artist.ifBlank { "알 수 없는 아티스트" },
                    albumCount = albums.size,
                    isExpanded = isExpanded,
                    onClick = { onToggleArtist(artist) }
                )
            }
            if (isExpanded) {
                items(albums, key = { "album:${it.key}" }) { album ->
                    NasAlbumRow(
                        album = album,
                        isPlaying = (playingSource as? NasPlaybackSource.Album)?.album?.key == album.key,
                        onClick = { onAlbumClick(album) }
                    )
                }
            }
        }
    }
}

// 스포티파이 스타일 하단 고정 미니플레이어. 라디오/NAS 어느 쪽이 재생 중이든 이 하나로
// 통일하고, 탭하면 NowPlayingDetailScreen으로 확장된다.
@Composable
private fun MiniPlayerBar(
    playback: ActivePlayback,
    isPlaying: Boolean,
    onTap: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNext: () -> Unit
) {
    val title: String
    val subtitle: String
    val imageUrl: String?
    when (playback) {
        is ActivePlayback.Radio -> {
            title = playback.nowPlaying.currentSong.ifBlank { playback.station.name }
            subtitle = playback.station.name
            imageUrl = playback.nowPlaying.imageUrl
        }
        is ActivePlayback.Nas -> {
            title = playback.trackTitle ?: "재생 중"
            subtitle = playback.source.displaySubtitle()
            imageUrl = null
        }
    }
    Surface(
        color = SpotifySurfaceElevated,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.06f))
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTap() }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm)
            ) {
                Box(
                    modifier = Modifier
                        .size(Sizes.miniPlayerThumbnail)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(SpotifyBlackElevated),
                    contentAlignment = Alignment.Center
                ) {
                    if (imageUrl != null) {
                        AsyncImage(
                            model = imageUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            painter = painterResource(R.drawable.ic_album),
                            contentDescription = null,
                            tint = SpotifyTextMuted,
                            modifier = Modifier.size(Sizes.miniPlayerIcon)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(Spacing.md))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.labelMedium,
                        color = SpotifyTextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                // 미니플레이어에는 이전 버튼을 두지 않는다. 좁은 폭에 버튼 3개를 넣으면
                // 제목이 밀리고, 탭하면 전체화면에서 모든 조작이 가능하다.
                IconButton(
                    onClick = onPlayPauseToggle,
                    modifier = Modifier.size(Sizes.miniPlayerButton)
                ) {
                    Icon(
                        painter = painterResource(
                            if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_filled
                        ),
                        contentDescription = if (isPlaying) "일시정지" else "재생",
                        tint = SpotifyTextPrimary,
                        modifier = Modifier.size(Sizes.miniPlayerIcon)
                    )
                }
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(Sizes.miniPlayerButton)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_skip_next),
                        contentDescription = "다음",
                        tint = SpotifyTextPrimary,
                        modifier = Modifier.size(Sizes.miniPlayerIcon)
                    )
                }
            }
        }
    }
}

// 전체화면으로 확장된 Now Playing 화면. 라디오는 즐겨찾기/편성 갱신을, NAS는 곡/앨범 정보를 보여준다.
@Composable
private fun NowPlayingDetailScreen(
    playback: ActivePlayback,
    isPlaying: Boolean,
    isLoadingMetadata: Boolean,
    positionMs: Long,
    durationMs: Long,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    onCollapse: () -> Unit,
    onFavoriteToggle: (() -> Unit)?,
    onRefreshMetadata: (() -> Unit)?,
    onSeek: ((Long) -> Unit)?,
    onToggleShuffle: (() -> Unit)?,
    onCycleRepeat: (() -> Unit)?,
    onPrevious: () -> Unit,
    onPlayPauseToggle: () -> Unit,
    onNext: () -> Unit
) {
    val backgroundBrush = Brush.verticalGradient(listOf(RadioBgMid, RadioBgDeep))
    val imageUrl = (playback as? ActivePlayback.Radio)?.nowPlaying?.imageUrl
    val isFavorite = (playback as? ActivePlayback.Radio)?.station?.isFavorite == true
    // 실시간 라디오는 진행바/셔플/반복이 의미가 없다 (끝이 없고 큐도 없다)
    val isTrackPlayback = playback is ActivePlayback.Nas

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundBrush)
            .padding(horizontal = Spacing.xl)
    ) {
        Spacer(modifier = Modifier.height(Spacing.lg))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onCollapse) {
                Icon(
                    imageVector = Icons.Filled.KeyboardArrowDown,
                    contentDescription = "닫기",
                    tint = SpotifyTextPrimary,
                    modifier = Modifier.size(Sizes.playerIcon)
                )
            }
            if (onFavoriteToggle != null) {
                IconButton(onClick = onFavoriteToggle) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "즐겨찾기 토글",
                        tint = if (isFavorite) SpotifyGreen else SpotifyTextMuted,
                        modifier = Modifier.size(28.dp)
                    )
                }
            } else {
                Spacer(modifier = Modifier.size(Sizes.playerSecondaryButton))
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        Box(
            modifier = Modifier
                .fillMaxWidth(0.86f)
                .align(Alignment.CenterHorizontally)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Radius.md))
                .background(SpotifySurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = "커버 이미지",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                EqualizerBars(isAnimating = isPlaying, color = SpotifyGreen, barCount = 5)
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xl))

        when (playback) {
            is ActivePlayback.Radio -> {
                OnAirBadge(isPlaying)
                Spacer(modifier = Modifier.height(Spacing.md))
                Text(
                    text = playback.station.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = playback.nowPlaying.programTitle,
                            style = MaterialTheme.typography.titleSmall,
                            color = SpotifyTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = playback.nowPlaying.currentSong,
                            style = MaterialTheme.typography.bodySmall,
                            color = SpotifyTextMuted,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (onRefreshMetadata != null) {
                        IconButton(onClick = onRefreshMetadata, enabled = !isLoadingMetadata) {
                            if (isLoadingMetadata) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(Sizes.miniPlayerIcon),
                                    color = SpotifyGreen
                                )
                            } else {
                                Icon(
                                    Icons.Default.Refresh,
                                    contentDescription = "편성 갱신",
                                    tint = SpotifyTextMuted
                                )
                            }
                        }
                    }
                }
            }
            is ActivePlayback.Nas -> {
                Text(
                    text = playback.trackTitle ?: "재생 중",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = playback.source.displaySubtitle(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        // 진행바는 끝이 있는 재생(NAS)에서만 의미가 있다
        if (isTrackPlayback && onSeek != null) {
            PlaybackProgressBar(
                positionMs = positionMs,
                durationMs = durationMs,
                onSeek = onSeek
            )
            Spacer(modifier = Modifier.height(Spacing.md))
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 셔플/반복도 큐가 있는 재생에서만 노출한다
            if (isTrackPlayback && onToggleShuffle != null) {
                PlayerToggleButton(
                    iconRes = R.drawable.ic_shuffle,
                    contentDescription = "셔플",
                    isActive = shuffleEnabled,
                    onClick = onToggleShuffle
                )
            } else {
                Spacer(modifier = Modifier.size(Sizes.playerSecondaryButton))
            }

            IconButton(onClick = onPrevious, modifier = Modifier.size(Sizes.playerSecondaryButton)) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = "이전",
                    tint = SpotifyTextPrimary,
                    modifier = Modifier.size(Sizes.playerIcon)
                )
            }

            IconButton(
                onClick = onPlayPauseToggle,
                modifier = Modifier
                    .size(Sizes.playerPrimaryButton)
                    .clip(CircleShape)
                    .background(SpotifyGreen)
            ) {
                Icon(
                    painter = painterResource(
                        if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_filled
                    ),
                    contentDescription = if (isPlaying) "일시정지" else "재생",
                    tint = RadioBgDeep,
                    modifier = Modifier.size(Sizes.playerIcon)
                )
            }

            IconButton(onClick = onNext, modifier = Modifier.size(Sizes.playerSecondaryButton)) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = "다음",
                    tint = SpotifyTextPrimary,
                    modifier = Modifier.size(Sizes.playerIcon)
                )
            }

            if (isTrackPlayback && onCycleRepeat != null) {
                PlayerToggleButton(
                    iconRes = if (repeatMode == Player.REPEAT_MODE_ONE) {
                        R.drawable.ic_repeat_one
                    } else {
                        R.drawable.ic_repeat
                    },
                    contentDescription = "반복",
                    isActive = repeatMode != Player.REPEAT_MODE_OFF,
                    onClick = onCycleRepeat
                )
            } else {
                Spacer(modifier = Modifier.size(Sizes.playerSecondaryButton))
            }
        }

        Spacer(modifier = Modifier.height(Spacing.huge))
    }
}

// 셔플/반복처럼 켜짐 상태가 있는 버튼. 켜지면 그린으로 바뀌고 아래에 작은 점이 찍힌다.
@Composable
private fun PlayerToggleButton(
    iconRes: Int,
    contentDescription: String,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.size(Sizes.playerSecondaryButton)
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(Sizes.playerSecondaryButton - 8.dp)) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = contentDescription,
                tint = if (isActive) SpotifyGreen else SpotifyTextMuted,
                modifier = Modifier.size(Sizes.miniPlayerIcon + 2.dp)
            )
        }
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(3.dp)
                    .clip(CircleShape)
                    .background(SpotifyGreen)
            )
        }
    }
}

// 재생 위치 슬라이더 + 경과/총 시간.
// 드래그하는 동안에는 폴링으로 들어오는 실제 위치가 슬라이더를 되돌리지 않도록
// 로컬 상태(scrubMs)를 우선해서 보여준다.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackProgressBar(
    positionMs: Long,
    durationMs: Long,
    onSeek: (Long) -> Unit
) {
    var scrubMs by remember { mutableStateOf<Long?>(null) }
    val hasDuration = durationMs > 0L
    val shownMs = scrubMs ?: positionMs

    Column(modifier = Modifier.fillMaxWidth()) {
        Slider(
            value = if (hasDuration) (shownMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f,
            onValueChange = { fraction -> if (hasDuration) scrubMs = (fraction * durationMs).toLong() },
            onValueChangeFinished = {
                scrubMs?.let { onSeek(it) }
                scrubMs = null
            },
            enabled = hasDuration,
            colors = SliderDefaults.colors(
                thumbColor = SpotifyTextPrimary,
                activeTrackColor = SpotifyTextPrimary,
                inactiveTrackColor = SpotifyTextMuted.copy(alpha = 0.3f)
            ),
            // Material 기본 슬라이더는 손잡이가 크고 트랙이 두꺼워 음악 앱에 비해 투박하다.
            // 얇은 트랙 + 작은 원형 손잡이로 바꾼다.
            thumb = {
                Box(
                    modifier = Modifier
                        .size(Spacing.md)
                        .clip(CircleShape)
                        .background(SpotifyTextPrimary)
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(Spacing.xs),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                    colors = SliderDefaults.colors(
                        activeTrackColor = SpotifyTextPrimary,
                        inactiveTrackColor = SpotifyTextMuted.copy(alpha = 0.3f)
                    )
                )
            },
            modifier = Modifier.fillMaxWidth()
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = formatClock(shownMs),
                style = MaterialTheme.typography.labelMedium,
                color = SpotifyTextMuted
            )
            Text(
                text = if (hasDuration) formatClock(durationMs) else "--:--",
                style = MaterialTheme.typography.labelMedium,
                color = SpotifyTextMuted
            )
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@Composable
private fun OnAirBadge(isOnAir: Boolean) {
    val color = if (isOnAir) RadioOnAirRed else Color.Gray
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.sm)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(Spacing.xs + 2.dp))
        Text(
            text = if (isOnAir) "ON AIR" else "STANDBY",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun EqualizerBars(isAnimating: Boolean, color: Color, barCount: Int = 4) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer")
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(28.dp)
    ) {
        repeat(barCount) { index ->
            val heightFraction by if (isAnimating) {
                infiniteTransition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 420 + index * 130, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "bar$index"
                )
            } else {
                remember { mutableStateOf(0.2f) }
            }
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight(heightFraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
        }
    }
}

@Composable
private fun ChannelRow(
    station: Station,
    isSelected: Boolean,
    isOnAir: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable { onClick() }
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(Sizes.listThumbnail)
                .clip(RoundedCornerShape(Radius.sm))
                .background(SpotifySurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    if (station.type == StationType.STREAMING) R.drawable.ic_album else R.drawable.ic_radio
                ),
                contentDescription = null,
                tint = if (isOnAir) SpotifyGreen else SpotifyTextMuted,
                modifier = Modifier.size(Sizes.miniPlayerIcon + 2.dp)
            )
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = station.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (isOnAir) SpotifyGreen else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = channelSubtitle(station),
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (station.isFavorite) {
            Icon(
                imageVector = Icons.Default.Star,
                contentDescription = "즐겨찾는 채널",
                tint = SpotifyGreen,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
        }

        if (isOnAir) {
            EqualizerBars(isAnimating = true, color = SpotifyGreen, barCount = 3)
        }
    }
}

private fun channelSubtitle(station: Station): String {
    return if (station.type == StationType.STREAMING) "24/7 온라인 스트리밍" else "실시간 라디오 방송"
}

@Composable
private fun NasAlbumRow(album: NasAlbum, isPlaying: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .clickable { onClick() }
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm)
    ) {
        // 아티스트 그룹 안에 속한 항목이라는 걸 들여쓰기로 표현한다
        Spacer(modifier = Modifier.width(Spacing.md))
        Box(
            modifier = Modifier
                .size(Sizes.listThumbnail - 8.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(SpotifySurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_album),
                contentDescription = null,
                tint = if (isPlaying) SpotifyGreen else SpotifyTextMuted,
                modifier = Modifier.size(Sizes.miniPlayerIcon)
            )
        }
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                album.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (isPlaying) SpotifyGreen else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${album.songCount}곡",
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyTextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (isPlaying) {
            EqualizerBars(isAnimating = true, color = SpotifyGreen, barCount = 3)
        }
    }
}

// 앨범이 수백 개일 수 있어 아티스트 단위로 접어두는 그룹 헤더.
@Composable
private fun NasArtistHeaderRow(
    artistName: String,
    albumCount: Int,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    // 배경 블록 대신 여백과 얇은 구분선으로 그룹을 나눈다 (Spotify식).
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.md))
                .clickable { onClick() }
                .padding(horizontal = Spacing.sm, vertical = Spacing.md)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    artistName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${albumCount}개 앨범",
                    style = MaterialTheme.typography.labelMedium,
                    color = SpotifyTextMuted
                )
            }
            Icon(
                imageVector = Icons.Filled.KeyboardArrowDown,
                contentDescription = if (isExpanded) "접기" else "펼치기",
                tint = SpotifyTextMuted,
                modifier = Modifier.graphicsLayer { rotationZ = if (isExpanded) 180f else 0f }
            )
        }
        if (!isExpanded) {
            HorizontalDivider(
                color = Color.White.copy(alpha = 0.06f),
                modifier = Modifier.padding(horizontal = Spacing.sm)
            )
        }
    }
}

@Composable
private fun NasSettingsDialog(
    nasMusicRepository: NasMusicRepository,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    var baseUrl by remember { mutableStateOf("") }
    var account by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Boolean?>(null) }
    val coroutineScope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Synology NAS 연결") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "Audio Station 라이브러리의 앨범을 채널로 추가합니다. 관리자 계정 대신 Audio Station " +
                        "읽기 권한만 있는 전용 계정 사용을 권장합니다.",
                    style = MaterialTheme.typography.bodySmall
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it; testResult = null },
                    label = { Text("주소 (예: https://xxxxx.quickconnect.to)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = account,
                    onValueChange = { account = it; testResult = null },
                    label = { Text("계정") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; testResult = null },
                    label = { Text("비밀번호") },
                    singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                if (isTesting) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), color = RadioNeonCyan)
                }
                testResult?.let { success ->
                    Text(
                        if (success) "연결 성공" else "연결 실패 - 주소/계정/비밀번호를 확인해주세요",
                        color = if (success) RadioNeonCyan else RadioOnAirRed,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = baseUrl.isNotBlank() && account.isNotBlank() && password.isNotBlank() && !isTesting,
                onClick = {
                    isTesting = true
                    coroutineScope.launch {
                        val success = nasMusicRepository.testConnection(baseUrl, account, password)
                        testResult = success
                        isTesting = false
                        if (success) {
                            nasMusicRepository.saveCredentials(baseUrl, account, password)
                            onSaved()
                        }
                    }
                }
            ) {
                Text("연결 테스트 후 저장")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("취소") }
        }
    )
}
