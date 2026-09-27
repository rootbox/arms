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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.arms.androidauto.remote.RemoteRole
import com.arms.androidauto.remote.RemoteSettingsStore
import com.arms.androidauto.ui.theme.RadioOnAirRed
import com.arms.androidauto.ui.theme.RadioOnDark
import com.arms.androidauto.ui.theme.RadioOnDarkMuted
import com.arms.androidauto.ui.theme.RadioSurfaceVariant
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.SpotifyGreen
import kotlinx.coroutines.launch

// 상단바용 호스트 상태 칩. 역할이 HOST가 아니거나 호스트 서비스가 꺼져 있으면 아무것도 그리지 않는다.
// - 게스트 없음: "리모컨 대기 중"(흐리게) — 브로커에 붙어 있어도 게스트가 없으면 "연결됨"이라 하지 않는다.
// - 게스트 있음: "<이름> 연결됨"(강조), 여럿이면 "<첫 이름> 외 n대 연결됨".
// 탭하면 메뉴: "페어링 QR 보기/만들기"(onOpenPairing), "연결 종료"(확인 후 RemoteHostService.revokeAndUnpair).
// 서비스 상태는 RemoteHostService의 companion StateFlow만 본다(바인드 없음).
@Composable
fun HostStatusChip(store: RemoteSettingsStore, onOpenPairing: () -> Unit) {
    val running by RemoteHostService.isRunning.collectAsState()
    if (!running) return
    // 서비스는 역할이 HOST일 때만 뜨지만, 역할이 바뀐 직후의 한 프레임까지 걸러 둔다.
    val isHost = remember(running) { runCatching { store.getRole() }.getOrNull() == RemoteRole.HOST }
    if (!isHost) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val guests by RemoteHostService.guests.collectAsState()
    val label = guestPresenceLabel(guests)
    val connected = label != null

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
                    .background(if (connected) SpotifyGreen else RadioOnDarkMuted),
            )
            Text(
                text = if (busy) "연결 종료 중…" else (label ?: "리모컨 대기 중"),
                color = if (connected) RadioOnDark else RadioOnDarkMuted,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Spacing.sm),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("페어링 QR 보기/만들기") },
                onClick = { menuOpen = false; onOpenPairing() },
            )
            DropdownMenuItem(
                text = { Text("연결 종료", color = RadioOnAirRed) },
                onClick = { menuOpen = false; confirmRevoke = true },
            )
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
