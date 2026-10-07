package com.arms.androidauto.remote.host

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arms.androidauto.local.LocalControl
import com.arms.androidauto.remote.RemoteRole
import com.arms.androidauto.remote.RemoteSettingsStore
import com.arms.androidauto.ui.theme.RadioOnAirRed
import com.arms.androidauto.ui.theme.RadioOnDark
import com.arms.androidauto.ui.theme.RadioOnDarkMuted
import com.arms.androidauto.ui.theme.RadioSurfaceVariant
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.SpotifyGreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// 상단바용 호스트 상태 칩. 역할이 HOST가 아니거나 호스트 서비스가 꺼져 있으면 아무것도 그리지 않는다.
// RemoteHostService.status(HostStatus)를 그대로 보여 준다:
// - InvalidConfig: 빨간 점 "리모컨 설정 오류" (저장된 브로커 주소를 쓸 수 없음, 예: 127.0.0.1)
// - Connecting: 주황 점 / Offline: 빨간 점 — 둘 다 "리모컨 오프라인"
// - Ready·게스트 없음: "리모컨 대기 중"(흐리게) — 브로커에 붙어 있어도 게스트가 없으면 "연결됨"이라 하지 않는다.
// - Ready·게스트 있음: "<이름> 연결됨"(강조), 여럿이면 "<첫 이름> 외 n대 연결됨".
// 탭하면 메뉴: 첫 줄은 상태 설명(누를 수 없음), "지금 다시 연결"(Ready가 아닐 때), "페어링 QR 보기/만들기",
// "연결 종료"(확인 후 RemoteHostService.revokeAndUnpair), "스마트싱스 연결 허용"(10분 창, LocalControl — 폰 리모컨의 st_pair도 같은 창을 연다).
// - NoPairing(폰 리모컨 페어링 없이 스마트싱스 로컬 제어만): "리모컨 미연결"(흐리게, 오류 아님). "연결 종료"는 숨긴다.
// - 스마트싱스 연결 허용 창이 열려 있으면 "스마트싱스 연결 허용 m:ss"가 우선한다.
// 서비스 상태는 companion StateFlow만 본다(바인드 없음).
@Composable
fun HostStatusChip(store: RemoteSettingsStore, onOpenPairing: () -> Unit) {
    val running by RemoteHostService.isRunning.collectAsState()
    if (!running) return
    // 서비스는 역할이 HOST일 때만 뜨지만, 역할이 바뀐 직후의 한 프레임까지 걸러 둔다.
    val isHost = remember(running) { runCatching { store.getRole() }.getOrNull() == RemoteRole.HOST }
    if (!isHost) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by RemoteHostService.status.collectAsState()
    val localEnabled by LocalControl.enabled.collectAsState()
    val pairingDeadline by LocalControl.pairingWindow.deadline.collectAsState()
    var pairingLeftMs by remember { mutableStateOf(0L) }
    LaunchedEffect(pairingDeadline) {
        while (pairingDeadline != null) {
            pairingLeftMs = LocalControl.pairingRemainingMs()
            if (pairingLeftMs <= 0L) break
            delay(500L)
        }
        pairingLeftMs = 0L
    }
    val look = if (pairingLeftMs > 0L) {
        ChipLook("스마트싱스 연결 허용 ${formatCountdown(pairingLeftMs)}", SpotifyGreen, emphasized = true)
    } else {
        chipLook(status)
    }

    var menuOpen by remember { mutableStateOf(false) }
    var confirmRevoke by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    Box {
        Row(
            modifier = Modifier
                .clip(CircleShape)
                .background(RadioSurfaceVariant)
                .clickable(enabled = !busy) { menuOpen = true }
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(look.dot),
            )
            Text(
                text = if (busy) "연결 종료 중…" else look.label,
                color = if (look.emphasized) RadioOnDark else RadioOnDarkMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = {
                    Text(
                        HostStatusPolicy.detail(status),
                        color = if (status is HostStatus.Ready) RadioOnDarkMuted else look.dot,
                        style = MaterialTheme.typography.bodySmall,
                    )
                },
                onClick = {},
                enabled = false,
            )
            if (status is HostStatus.Connecting || status is HostStatus.Offline) {
                DropdownMenuItem(
                    text = { Text("지금 다시 연결") },
                    onClick = { menuOpen = false; RemoteHostService.retryNow(context) },
                )
            }
            DropdownMenuItem(
                text = { Text("페어링 QR 보기/만들기") },
                onClick = { menuOpen = false; onOpenPairing() },
            )
            if (localEnabled) {
                DropdownMenuItem(
                    text = { Text("스마트싱스 연결 허용") },
                    onClick = {
                        menuOpen = false
                        LocalControl.openPairingWindow()
                        Toast.makeText(context, "${pairingWindowMinutes()}분 동안 스마트싱스 연결을 허용합니다", Toast.LENGTH_SHORT).show()
                    },
                )
            }
            if (status !is HostStatus.NoPairing) {
                DropdownMenuItem(
                    text = { Text("연결 종료", color = RadioOnAirRed) },
                    onClick = { menuOpen = false; confirmRevoke = true },
                )
            }
        }
    }

    if (confirmRevoke) {
        AlertDialog(
            onDismissRequest = { confirmRevoke = false },
            title = { Text("연결 종료") },
            text = { Text("리모컨 연결을 끊고 페어링을 지웁니다. 계속할까요?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRevoke = false
                        busy = true
                        scope.launch {
                            RemoteHostService.revokeAndUnpair(context)
                            busy = false
                        }
                    },
                ) { Text("연결 종료", color = RadioOnAirRed) }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = false }) { Text("취소") } },
        )
    }
}

private val HostAmber = Color(0xFFFFB300)

private data class ChipLook(val label: String, val dot: Color, val emphasized: Boolean)

private fun chipLook(status: HostStatus): ChipLook = when (status) {
    // NotRunning은 칩 자체가 안 그려지지만(isRunning=false), 서비스 시작 직후 한 프레임을 위해 둔다.
    HostStatus.NotRunning -> ChipLook("리모컨 대기 중", RadioOnDarkMuted, emphasized = false)
    HostStatus.NoPairing -> ChipLook("리모컨 미연결", RadioOnDarkMuted, emphasized = false)
    is HostStatus.InvalidConfig -> ChipLook("리모컨 설정 오류", RadioOnAirRed, emphasized = true)
    is HostStatus.Connecting -> ChipLook("리모컨 오프라인", HostAmber, emphasized = false)
    is HostStatus.Offline -> ChipLook("리모컨 오프라인", RadioOnAirRed, emphasized = true)
    is HostStatus.Ready -> {
        val label = guestPresenceLabel(status.guests)
        if (label != null) ChipLook(label, SpotifyGreen, emphasized = true)
        else ChipLook("리모컨 대기 중", RadioOnDarkMuted, emphasized = false)
    }
}

// 스마트싱스 연결 허용 창 길이(분). 버튼·토스트 문구가 PairingWindow.DEFAULT_DURATION_MS를 따라가게 한다.
internal fun pairingWindowMinutes(): Long = LocalControl.pairingWindow.durationMs / 60_000L

// 남은 시간 "m:ss". 초는 올림(남아 있는 동안 0:00이 보이지 않게).
internal fun formatCountdown(ms: Long): String {
    val totalSec = ((ms + 999L) / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(totalSec / 60L, totalSec % 60L)
}
