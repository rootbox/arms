package com.arms.androidauto

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.arms.androidauto.core.data.NowPlayingInfo
import com.arms.androidauto.core.data.StationRepository
import com.arms.androidauto.core.media.MediaPlayer
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.model.StationType
import com.arms.androidauto.ui.theme.ARMSAndroidAutoTheme
import com.arms.androidauto.ui.theme.RadioBgDeep
import com.arms.androidauto.ui.theme.RadioBgMid
import com.arms.androidauto.ui.theme.RadioNeonCyan
import com.arms.androidauto.ui.theme.RadioNeonMagenta
import com.arms.androidauto.ui.theme.RadioNeonOrange
import com.arms.androidauto.ui.theme.RadioOnAirRed
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

// 채널 아이콘/포인트 컬러를 순환 배정하기 위한 팔레트
private val channelAccentColors = listOf(RadioNeonCyan, RadioNeonMagenta, RadioNeonOrange)

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
        repository.saveLastPlayedStationId(station.id)
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

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Text(
                        "S I M P L E   R A D I O",
                        fontWeight = FontWeight.Black,
                        letterSpacing = 3.sp,
                        color = RadioNeonCyan
                    )
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = RadioNeonCyan
                )
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundBrush)
                .padding(paddingValues)
                .padding(horizontal = 20.dp)
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
                    CircularProgressIndicator(color = RadioNeonCyan)
                }
            } else {
                NowPlayingHero(
                    station = selectedStation,
                    nowPlaying = nowPlaying,
                    isLoadingMetadata = isLoadingMetadata,
                    isPlaying = isSelectedPlaying,
                    onFavoriteToggle = {
                        selectedStation?.let {
                            coroutineScope.launch {
                                repository.updateStationFavoriteStatus(it.id, !it.isFavorite)
                            }
                        }
                    },
                    onRefreshMetadata = {
                        val stationId = selectedStationId ?: return@NowPlayingHero
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
                    },
                    onPlayToggle = {
                        val station = selectedStation ?: return@NowPlayingHero
                        if (isSelectedPlaying) {
                            stopPlayback()
                        } else {
                            playStation(station)
                        }
                    }
                )

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "전체 채널",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    color = RadioNeonCyan.copy(alpha = 0.8f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 20.dp)
                ) {
                    items(stations, key = { it.id }) { station ->
                        val accent = channelAccentColors[stations.indexOf(station) % channelAccentColors.size]
                        ChannelRow(
                            station = station,
                            accentColor = accent,
                            isSelected = station.id == selectedStationId,
                            isOnAir = station.id == playingStationId,
                            onClick = { playStation(station) }
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
                                color = RadioNeonCyan.copy(alpha = 0.4f),
                                textAlign = TextAlign.Center
                            )
                            Text(
                                lastCheckedText,
                                style = MaterialTheme.typography.labelSmall,
                                color = RadioNeonCyan.copy(alpha = 0.25f),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NowPlayingHero(
    station: Station?,
    nowPlaying: NowPlayingInfo,
    isLoadingMetadata: Boolean,
    isPlaying: Boolean,
    onFavoriteToggle: () -> Unit,
    onRefreshMetadata: () -> Unit,
    onPlayToggle: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, RadioNeonCyan.copy(alpha = 0.25f))
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OnAirBadge(isPlaying)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = station?.let { channelSubtitle(it) } ?: "채널 없음",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onFavoriteToggle, enabled = station != null) {
                    Icon(
                        imageVector = Icons.Default.Star,
                        contentDescription = "즐겨찾기 토글",
                        tint = if (station?.isFavorite == true) RadioNeonOrange else Color.Gray,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = station?.name ?: "-",
                fontSize = 26.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(18.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (nowPlaying.imageUrl != null) {
                    AsyncImage(
                        model = nowPlaying.imageUrl,
                        contentDescription = "방송 프로필 이미지",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(14.dp))
                    )
                } else {
                    Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                        EqualizerBars(isAnimating = isPlaying, color = RadioNeonCyan)
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = nowPlaying.programTitle,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = nowPlaying.currentSong,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onRefreshMetadata, enabled = !isLoadingMetadata) {
                    if (isLoadingMetadata) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), color = RadioNeonCyan)
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "편성 갱신",
                            tint = RadioNeonCyan
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Button(
                onClick = onPlayToggle,
                enabled = station != null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                shape = RoundedCornerShape(32.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isPlaying) RadioOnAirRed else RadioNeonCyan,
                    contentColor = RadioBgDeep
                )
            ) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Warning else Icons.Default.PlayArrow,
                    contentDescription = "재생 토글",
                    modifier = Modifier.size(28.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isPlaying) "청취 중지 (STOP)" else "실시간 청취 (PLAY)",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
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
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = if (isOnAir) "ON AIR" else "STANDBY",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
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
    accentColor: Color,
    isSelected: Boolean,
    isOnAir: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) accentColor.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surface
        ),
        border = if (isSelected) BorderStroke(1.5.dp, accentColor) else null
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (station.type == StationType.STREAMING) "♪" else "FM",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Black,
                    color = accentColor
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = channelSubtitle(station),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (station.isFavorite) {
                Icon(
                    imageVector = Icons.Default.Star,
                    contentDescription = "즐겨찾는 채널",
                    tint = RadioNeonOrange,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (isOnAir) {
                Box(
                    modifier = Modifier
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(RadioOnAirRed)
                )
            }
        }
    }
}

private fun channelSubtitle(station: Station): String {
    return if (station.type == StationType.STREAMING) "24/7 온라인 스트리밍" else "실시간 라디오 방송"
}
