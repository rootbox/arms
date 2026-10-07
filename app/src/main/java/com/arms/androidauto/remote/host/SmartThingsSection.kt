package com.arms.androidauto.remote.host

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import com.arms.androidauto.local.LocalControl
import com.arms.androidauto.local.LocalServerStatus
import com.arms.androidauto.local.PairedClient
import com.arms.androidauto.ui.theme.RadioBgDeep
import com.arms.androidauto.ui.theme.RadioOnAirRed
import com.arms.androidauto.ui.theme.RadioOnDark
import com.arms.androidauto.ui.theme.RadioOnDarkMuted
import com.arms.androidauto.ui.theme.RadioSurfaceVariant
import com.arms.androidauto.ui.theme.Radius
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.SpotifyGreen
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 호스트 설정 화면의 "스마트싱스 연결" 섹션(로컬 제어 API v1, smartthings/LAN_API.md).
// - 켜기/끄기(기본 켬). 끄면 서버·mDNS를 내리고, 폰 리모컨 페어링도 없으면 호스트 서비스도 내려간다.
// - "연결 허용 (10분)": 이 동안만 SmartThings Edge 드라이버가 토큰을 받는다(첫 연결 뒤 자동으로 닫힘).
//   폰 리모컨의 "스마트싱스" 카드(st_pair)도 같은 창을 연다 — 태블릿을 만지지 않아도 된다.
// - 연결된 클라이언트 목록과 "해제"(토큰 삭제 + 열린 SSE 연결 끊기).
@Composable
fun SmartThingsSection() {
    val context = LocalContext.current
    // 암호화 저장소에서 설정·클라이언트 목록을 읽어 둔다(한 번만).
    remember { runCatching { LocalControl.init(context) } }
    val enabled by LocalControl.enabled.collectAsState()
    val clients by LocalControl.pairedClients.collectAsState()
    val server by LocalControl.serverStatus.collectAsState()
    val hostRunning by RemoteHostService.isRunning.collectAsState()
    val deadline by LocalControl.pairingWindow.deadline.collectAsState()
    var leftMs by remember { mutableStateOf(0L) }
    LaunchedEffect(deadline) {
        while (deadline != null) {
            leftMs = LocalControl.pairingRemainingMs()
            if (leftMs <= 0L) break
            delay(500L)
        }
        leftMs = 0L
    }
    var confirmRevoke by remember { mutableStateOf<PairedClient?>(null) }

    confirmRevoke?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmRevoke = null },
            title = { Text("스마트싱스 연결 해제") },
            text = { Text("'${target.label}'의 연결을 끊습니다. 다시 쓰려면 SmartThings 앱에서 기기를 다시 추가해야 합니다.") },
            confirmButton = {
                TextButton(onClick = { LocalControl.revoke(target.id); confirmRevoke = null }) { Text("해제", color = RadioOnAirRed) }
            },
            dismissButton = { TextButton(onClick = { confirmRevoke = null }) { Text("취소") } },
        )
    }

    Spacer(Modifier.height(Spacing.md))
    Text("스마트싱스 연결", style = MaterialTheme.typography.titleMedium, color = RadioOnDark, modifier = Modifier.fillMaxWidth())
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(RadioSurfaceVariant)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "같은 와이파이의 스마트싱스에서 조작",
                color = RadioOnDark, style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Switch(
                checked = enabled,
                onCheckedChange = { LocalControl.setEnabled(context, it) },
                colors = SwitchDefaults.colors(checkedTrackColor = SpotifyGreen),
            )
        }
        if (!enabled) return@Column

        val (statusText, statusBad) = when (val st = server) {
            is LocalServerStatus.Running -> (st.address?.let { "이 기기 주소: $it" } ?: "와이파이 주소를 알 수 없습니다") to (st.address == null)
            LocalServerStatus.Starting -> "로컬 제어 서버 시작 중…" to false
            is LocalServerStatus.Failed -> st.reason to true
            LocalServerStatus.Stopped ->
                (if (hostRunning) "로컬 제어 서버가 꺼져 있습니다" else "홈 플레이어 서비스가 꺼져 있습니다") to true
        }
        Text(statusText, color = if (statusBad) RadioOnAirRed else RadioOnDarkMuted, style = MaterialTheme.typography.bodySmall)

        if (leftMs > 0L) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "연결 허용 중 · ${formatCountdown(leftMs)}",
                    color = SpotifyGreen, style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { LocalControl.closePairingWindow() }) { Text("그만 허용", color = RadioOnDarkMuted) }
            }
        } else {
            Button(
                onClick = { LocalControl.openPairingWindow() },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = RadioBgDeep),
                modifier = Modifier.fillMaxWidth(),
            ) { Text("연결 허용 (${pairingWindowMinutes()}분)") }
        }
        Text(
            "SmartThings 앱 → 기기 추가 → 주변 기기 검색 중에 이 버튼을 누르세요",
            color = RadioOnDarkMuted, style = MaterialTheme.typography.bodySmall,
        )

        if (clients.isEmpty()) {
            Text("연결된 스마트싱스가 없습니다", color = RadioOnDarkMuted, style = MaterialTheme.typography.bodySmall)
        } else {
            clients.forEach { c ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(c.label, color = RadioOnDark, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${formatDate(c.createdAtMs)} 연결", color = RadioOnDarkMuted, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(
                        onClick = { confirmRevoke = c },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = RadioOnAirRed),
                    ) { Text("해제") }
                }
            }
        }
    }
}

private fun formatDate(ms: Long): String =
    runCatching { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.KOREA).format(Date(ms)) }.getOrDefault("")
