package com.arms.androidauto.remote.guest

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.arms.androidauto.R
import com.arms.androidauto.core.data.StationRepository
import com.arms.androidauto.core.remote.ConnectionState
import com.arms.androidauto.core.remote.HostState
import com.arms.androidauto.core.remote.RemoteCommand
import com.arms.androidauto.core.remote.RemoteItem
import com.arms.androidauto.remote.RemoteSettingsStore
import com.arms.androidauto.ui.theme.RadioBgDeep
import com.arms.androidauto.ui.theme.RadioOnAirRed
import com.arms.androidauto.ui.theme.Radius
import com.arms.androidauto.ui.theme.Sizes
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.SpotifyGreen
import com.arms.androidauto.ui.theme.SpotifySurface
import com.arms.androidauto.ui.theme.SpotifySurfaceElevated
import com.arms.androidauto.ui.theme.SpotifyTextMuted
import com.arms.androidauto.ui.theme.SpotifyTextPrimary
import kotlinx.coroutines.delay

// 리모컨 탭. 태블릿(호스트)의 재생 상태를 보여주고 명령을 보낸다.
// 화면이 보이는 동안(ON_RESUME~ON_PAUSE)만 브로커에 연결한다 — 플랜 §5.
@Composable
fun RemoteControlScreen(
    store: RemoteSettingsStore,
    onOpenPairing: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val client = remember(store) { RemoteGuestClient(context, store, scope) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner, client) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> client.start()
                Lifecycle.Event.ON_PAUSE -> client.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            client.stop()
        }
    }

    val paired by client.paired.collectAsState()
    val connectionState by client.connectionState.collectAsState()
    val hostState by client.hostState.collectAsState()
    val pendingSeq by client.pendingSeq.collectAsState()
    val error by client.error.collectAsState()
    val lastAckResult by client.lastAckResult.collectAsState()

    // "n분 전" 계산용 현재 시각. 30초마다 갱신해 상태 줄이 저절로 늙는다.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    // 상태가 새로 오면 즉시 "지금"으로 맞춘다.
    LaunchedEffect(hostState?.updatedAtMs) { nowMs = System.currentTimeMillis() }

    val status = GuestStatusPolicy.compute(paired, connectionState, hostState, nowMs)
    val canSend = GuestStatusPolicy.canSend(connectionState, pendingSeq)

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(error) {
        val message = error ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message)
        client.clearError()
    }
    // 블루투스 재연결처럼 결과 문구가 의미 있는 명령은 ack 메시지를 그대로 보여준다.
    var btAckMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(lastAckResult) {
        val result = lastAckResult ?: return@LaunchedEffect
        if (result.command is RemoteCommand.BtReconnect) {
            btAckMessage = result.ack.message ?: if (result.ack.ok) "재연결 요청을 보냈습니다" else "재연결 실패"
        }
    }

    Scaffold(
        containerColor = RadioBgDeep,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (!paired) {
            NotPairedContent(
                modifier = Modifier.fillMaxSize().padding(padding),
                onOpenPairing = onOpenPairing,
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(horizontal = Spacing.screenHorizontal, vertical = Spacing.lg),
        ) {
            item(key = "status") {
                StatusLine(status = status, isBusy = pendingSeq != null, onOpenPairing = onOpenPairing)
                Spacer(Modifier.height(Spacing.lg))
            }
            item(key = "nowplaying") {
                NowPlayingHeader(state = hostState, status = status)
                Spacer(Modifier.height(Spacing.xl))
            }
            item(key = "controls") {
                TransportControls(
                    isPlaying = hostState?.isPlaying == true,
                    enabled = canSend,
                    onPrevious = { client.send(RemoteCommand.Previous) },
                    onPlayPause = {
                        client.send(if (hostState?.isPlaying == true) RemoteCommand.Pause else RemoteCommand.Play)
                    },
                    onNext = { client.send(RemoteCommand.Next) },
                    onStop = { client.send(RemoteCommand.Stop) },
                )
                Spacer(Modifier.height(Spacing.xl))
            }
            item(key = "bluetooth") {
                BluetoothCard(
                    state = hostState,
                    nowMs = nowMs,
                    enabled = canSend,
                    ackMessage = btAckMessage,
                    onReconnect = {
                        btAckMessage = null
                        client.send(RemoteCommand.BtReconnect)
                    },
                )
                Spacer(Modifier.height(Spacing.xl))
            }
            val items = hostState?.items.orEmpty()
            if (items.isNotEmpty()) {
                item(key = "items-header") {
                    Text(
                        text = "채널",
                        style = MaterialTheme.typography.titleMedium,
                        color = SpotifyTextPrimary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                    )
                }
                items(items, key = { "item-" + it.mediaId }) { item ->
                    RemoteItemRow(
                        item = item,
                        isCurrent = item.mediaId == hostState?.mediaId,
                        isOnAir = item.mediaId == hostState?.mediaId && hostState?.isPlaying == true,
                        enabled = canSend,
                        onClick = { client.send(RemoteCommand.Select(item.mediaId)) },
                    )
                }
            }
            item(key = "refresh") {
                Spacer(Modifier.height(Spacing.lg))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextButton(onClick = { client.send(RemoteCommand.Refresh) }, enabled = canSend) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            tint = SpotifyTextMuted,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(Spacing.xs))
                        Text("새로고침", color = SpotifyTextMuted)
                    }
                }
                Spacer(Modifier.height(Spacing.huge))
            }
        }
    }
}

@Composable
private fun NotPairedContent(modifier: Modifier, onOpenPairing: () -> Unit) {
    Column(
        modifier = modifier.padding(Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(RoundedCornerShape(Radius.lg))
                .background(SpotifySurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            EqualizerBarsGuest(isAnimating = false, color = SpotifyTextMuted, barCount = 5)
        }
        Spacer(Modifier.height(Spacing.xl))
        Text(
            text = "리모컨이 아직 연결되지 않았습니다",
            style = MaterialTheme.typography.titleMedium,
            color = SpotifyTextPrimary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.sm))
        Text(
            text = "태블릿의 설정 화면에서 QR을 띄운 뒤 이 폰으로 스캔하면 됩니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = SpotifyTextMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(Spacing.xl))
        Button(
            onClick = onOpenPairing,
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = RadioBgDeep),
        ) {
            Text("태블릿과 페어링", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun StatusLine(status: GuestStatus, isBusy: Boolean, onOpenPairing: () -> Unit) {
    val dotColor = when (status) {
        GuestStatus.Live -> SpotifyGreen
        is GuestStatus.Stale, GuestStatus.NoStateYet -> Color(0xFFE0B341)
        GuestStatus.Connecting -> SpotifyTextMuted
        GuestStatus.Offline, GuestStatus.NotPaired -> RadioOnAirRed
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.sm)
                .clip(CircleShape)
                .background(dotColor),
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = GuestStatusPolicy.label(status),
            style = MaterialTheme.typography.labelLarge,
            color = SpotifyTextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (isBusy || status == GuestStatus.Connecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                color = SpotifyGreen,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(Spacing.sm))
        }
        TextButton(onClick = onOpenPairing, contentPadding = PaddingValues(horizontal = Spacing.sm)) {
            Text("페어링", color = SpotifyTextMuted, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun NowPlayingHeader(state: HostState?, status: GuestStatus) {
    val context = LocalContext.current
    val stationRepository = remember(context) { StationRepository(context) }
    val artworkModel: Any? = remember(state?.artworkRef) {
        when (val art = GuestStatusPolicy.parseArtworkRef(state?.artworkRef)) {
            is GuestStatusPolicy.Artwork.Station -> stationRepository.defaultArtworkUri(art.stationId)
            is GuestStatusPolicy.Artwork.Url -> art.url
            null -> null
        }
    }
    val isPlaying = state?.isPlaying == true
    val dimmed = status is GuestStatus.Stale || status == GuestStatus.Offline

    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.72f)
                .widthIn(max = 320.dp)
                .aspectRatio(1f)
                .clip(RoundedCornerShape(Radius.md))
                .background(SpotifySurfaceElevated)
                .alpha(if (dimmed) 0.55f else 1f),
            contentAlignment = Alignment.Center,
        ) {
            if (artworkModel != null) {
                AsyncImage(
                    model = ImageRequest.Builder(context).data(artworkModel).crossfade(true).build(),
                    contentDescription = "커버 이미지",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                EqualizerBarsGuest(isAnimating = isPlaying && !dimmed, color = SpotifyGreen, barCount = 5)
            }
        }
        Spacer(Modifier.height(Spacing.xl))
        Column(modifier = Modifier.fillMaxWidth()) {
            OnAirBadgeGuest(isOnAir = isPlaying && !dimmed)
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = state?.title ?: if (state == null) "태블릿 상태를 기다리는 중" else "재생 중인 항목 없음",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val artist = state?.artist
            if (!artist.isNullOrBlank()) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val battery = state?.batteryPercent
            if (battery != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "태블릿 배터리 ${battery}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = SpotifyTextMuted,
                )
            }
        }
    }
}

@Composable
private fun TransportControls(
    isPlaying: Boolean,
    enabled: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onStop: () -> Unit,
) {
    val alpha = if (enabled) 1f else 0.4f
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onPrevious, enabled = enabled, modifier = Modifier.size(Sizes.playerSecondaryButton)) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_previous),
                    contentDescription = "이전",
                    tint = SpotifyTextPrimary.copy(alpha = alpha),
                    modifier = Modifier.size(Sizes.playerIcon),
                )
            }
            IconButton(
                onClick = onPlayPause,
                enabled = enabled,
                modifier = Modifier
                    .size(Sizes.playerPrimaryButton)
                    .clip(CircleShape)
                    .background(SpotifyGreen.copy(alpha = alpha)),
            ) {
                Icon(
                    painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play_arrow_filled),
                    contentDescription = if (isPlaying) "일시정지" else "재생",
                    tint = RadioBgDeep,
                    modifier = Modifier.size(Sizes.playerIcon),
                )
            }
            IconButton(onClick = onNext, enabled = enabled, modifier = Modifier.size(Sizes.playerSecondaryButton)) {
                Icon(
                    painter = painterResource(R.drawable.ic_skip_next),
                    contentDescription = "다음",
                    tint = SpotifyTextPrimary.copy(alpha = alpha),
                    modifier = Modifier.size(Sizes.playerIcon),
                )
            }
        }
        Spacer(Modifier.height(Spacing.md))
        OutlinedButton(
            onClick = onStop,
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyTextMuted),
        ) {
            Text("정지")
        }
    }
}

@Composable
private fun BluetoothCard(
    state: HostState?,
    nowMs: Long,
    enabled: Boolean,
    ackMessage: String?,
    onReconnect: () -> Unit,
) {
    val bt = state?.bluetooth
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Radius.lg),
        colors = CardDefaults.cardColors(containerColor = SpotifySurface),
    ) {
        Column(modifier = Modifier.padding(Spacing.lg)) {
            val label = if (bt == null) {
                "블루투스 스피커: 정보 없음"
            } else {
                GuestStatusPolicy.bluetoothLabel(bt.connected, bt.deviceName, bt.changedAtMs, nowMs)
            }
            val connected = bt?.connected == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(Spacing.sm)
                        .clip(CircleShape)
                        .background(if (connected) SpotifyGreen else RadioOnAirRed),
                )
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyTextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (ackMessage != null) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    text = ackMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyTextMuted,
                )
            }
            Spacer(Modifier.height(Spacing.md))
            OutlinedButton(
                onClick = onReconnect,
                enabled = enabled && bt != null,
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = if (connected) SpotifyTextMuted else SpotifyGreen,
                ),
            ) {
                Text("재연결")
            }
        }
    }
}

@Composable
private fun RemoteItemRow(
    item: RemoteItem,
    isCurrent: Boolean,
    isOnAir: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.md))
            .background(if (isCurrent) SpotifySurface else Color.Transparent)
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(Sizes.listThumbnail)
                .clip(RoundedCornerShape(Radius.sm))
                .background(SpotifySurfaceElevated),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_radio),
                contentDescription = null,
                tint = if (isCurrent) SpotifyGreen else SpotifyTextMuted,
                modifier = Modifier.size(Sizes.miniPlayerIcon + 2.dp),
            )
        }
        Spacer(Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.name,
                style = MaterialTheme.typography.titleMedium,
                color = if (isCurrent) SpotifyGreen else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    text = item.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyTextMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (isOnAir) {
            EqualizerBarsGuest(isAnimating = true, color = SpotifyGreen, barCount = 3)
        }
    }
}

// MainActivity의 OnAirBadge와 같은 모양(그쪽은 private).
@Composable
private fun OnAirBadgeGuest(isOnAir: Boolean) {
    val color = if (isOnAir) RadioOnAirRed else Color.Gray
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(Spacing.sm)
                .clip(CircleShape)
                .background(color),
        )
        Spacer(modifier = Modifier.width(Spacing.xs + 2.dp))
        Text(
            text = if (isOnAir) "ON AIR" else "STANDBY",
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}

// MainActivity의 EqualizerBars와 같은 모양(그쪽은 private).
@Composable
internal fun EqualizerBarsGuest(isAnimating: Boolean, color: Color, barCount: Int = 4) {
    val infiniteTransition = rememberInfiniteTransition(label = "equalizer-guest")
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.height(28.dp),
    ) {
        repeat(barCount) { index ->
            val heightFraction by if (isAnimating) {
                infiniteTransition.animateFloat(
                    initialValue = 0.25f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 420 + index * 130, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "bar$index",
                )
            } else {
                remember { mutableStateOf(0.2f) }
            }
            Box(
                modifier = Modifier
                    .width(5.dp)
                    .fillMaxHeight(heightFraction)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color),
            )
        }
    }
}
