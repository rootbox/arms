package com.arms.androidauto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arms.androidauto.core.data.StationRepository
import com.arms.androidauto.core.media.MediaPlayer
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.ui.theme.ARMSAndroidAutoTheme
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
        mediaPlayer.stop()
        super.onDestroy()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RadioPlayerScreen(repository: StationRepository, player: MediaPlayer) {
    val coroutineScope = rememberCoroutineScope()
    
    // DB의 방송국 목록 관찰 (Flow -> State)
    val stationsState = repository.getAllStations().collectAsState(initial = emptyList())
    val stations = stationsState.value

    var currentProgram by remember { mutableStateOf("KBS Cool FM (89.1 MHz)") }
    var currentSong by remember { mutableStateOf("실시간 방송 중") }
    var isPlaying by remember { mutableStateOf(false) }
    var isLoadingMetadata by remember { mutableStateOf(false) }
    var isRefreshingStations by remember { mutableStateOf(false) }

    // 앱 시작 시 데이터 로드 및 초기화
    LaunchedEffect(Unit) {
        coroutineScope.launch {
            isRefreshingStations = true
            try {
                repository.refreshStations()
            } catch (e: Exception) {
                // 무시
            } finally {
                isRefreshingStations = false
            }
        }
        coroutineScope.launch {
            isLoadingMetadata = true
            try {
                val metadata = repository.fetchMetadata("1")
                // 정규식으로 수집한 결과를 파싱하여 상태에 동기화
                val parts = metadata.split(" | ")
                if (parts.size == 2) {
                    currentProgram = parts[0].replace("현재 방송: ", "")
                    currentSong = parts[1].replace("음악: ", "")
                }
            } catch (e: Exception) {
                currentProgram = "KBS Cool FM (89.1 MHz)"
                currentSong = "로컬 편성표 수신 완료"
            } finally {
                isLoadingMetadata = false
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("ARMS CAR PLAYER", fontWeight = FontWeight.Bold, letterSpacing = 2.sp) },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(24.dp),
            verticalArrangement = ColumnCorner.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (stations.isEmpty()) {
                // 로딩 중 표시
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                val station = stations[0] // 89.1 단일 채널 집중 대응

                // 1. 상단 카드: 방송 채널 정보 및 즐겨찾기 토글
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "FM 89.1 MHz",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            IconButton(
                                onClick = {
                                    coroutineScope.launch {
                                        repository.updateStationFavoriteStatus(station.id, !station.isFavorite)
                                    }
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = "즐겨찾기 토글",
                                    tint = if (station.isFavorite) Color(0xFFFFD700) else Color.LightGray,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        Text(
                            text = station.name,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (station.isFavorite) "★ 나의 즐겨찾는 채널" else "일반 라디오 주파수",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // 2. 중단 카드: 실시간 재생 메타데이터 영역 (편성표 명, 현재 선곡 제목)
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(vertical = 8.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "CURRENTLY ON AIR",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            letterSpacing = 1.5.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = currentProgram,
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = currentSong,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        
                        Spacer(modifier = Modifier.height(24.dp))
                        
                        // 실시간 선곡 수집 정보 수동 동기화 갱신 버튼
                        IconButton(
                            onClick = {
                                coroutineScope.launch {
                                    isLoadingMetadata = true
                                    try {
                                        val metadata = repository.fetchMetadata(station.id)
                                        val parts = metadata.split(" | ")
                                        if (parts.size == 2) {
                                            currentProgram = parts[0].replace("현재 방송: ", "")
                                            currentSong = parts[1].replace("음악: ", "")
                                        }
                                    } catch (e: Exception) {
                                        // 에러 시 로컬 폴백 유지
                                    } finally {
                                        isLoadingMetadata = false
                                    }
                                }
                            },
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.1f))
                        ) {
                            if (isLoadingMetadata) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "편성 갱신",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 3. 하단 카드: 물리 버튼 조작이 쉬운 거대 차량 친화적 재생 컨트롤 영역
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 24.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (isPlaying) {
                                player.stop()
                                isPlaying = false
                            } else {
                                player.play(station.frequencyOrUrl)
                                isPlaying = true
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.8f)
                            .height(80.dp),
                        shape = RoundedCornerShape(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPlaying) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Warning else Icons.Default.PlayArrow,
                            contentDescription = "재생 토글",
                            modifier = Modifier.size(36.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = if (isPlaying) "청취 중지 (STOP)" else "실시간 청취 (PLAY)",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

// Arrangment 유연성을 위한 컬럼 헬퍼 객체
object ColumnCorner {
    val SpaceBetween = Arrangement.SpaceBetween
}
