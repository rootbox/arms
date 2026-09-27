package com.arms.androidauto.remote.host

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arms.androidauto.core.remote.Pairing
import com.arms.androidauto.core.remote.RemoteGuest
import com.arms.androidauto.remote.RemoteSettingsStore
import com.arms.androidauto.ui.theme.Radius
import com.arms.androidauto.ui.theme.RadioBgDeep
import com.arms.androidauto.ui.theme.RadioOnAirRed
import com.arms.androidauto.ui.theme.RadioOnDark
import com.arms.androidauto.ui.theme.RadioOnDarkMuted
import com.arms.androidauto.ui.theme.RadioSurfaceVariant
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.SpotifyGreen
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

// 호스트(태블릿) 페어링 화면.
// - 브로커 설정(URL/계정/비밀번호)은 한 번만 입력해 저장한다(페어링을 해제해도 남는다).
// - 그 뒤로는 "페어링 QR 만들기" 한 번으로 키 생성·저장·서비스 시작·QR 표시까지 간다.
// - QR을 띄운 뒤 게스트가 Hello를 보내면(RemoteHostService.guests) QR을 즉시 걷고 "페어링 완료"를 보여준다.
//   QR은 키를 담고 있으므로 게스트가 붙은 뒤에는 절대 화면에 남기지 않는다.
// - 이전 페어링이 있는 상태에서 QR을 새로 만들면 이전 게스트에게 연결 종료를 알리고(revoked) 새 키로 바꾼다.
@Composable
fun HostPairingScreen(store: RemoteSettingsStore, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var broker by remember { mutableStateOf(runCatching { store.getBrokerSettings() }.getOrNull()) }
    var stored by remember { mutableStateOf(runCatching { store.getPairing() }.getOrNull()) }
    // 이 화면에서 방금 만든 페어링. 화면을 떠나면 사라지고 QR은 다시 볼 수 없다.
    var fresh by remember { mutableStateOf<Pairing?>(null) }
    // QR을 만든 시각. 그 전에 인사한(이전 페어링의) 게스트 항목이 남아 있어도 "완료"로 오인하지 않는다.
    var freshAtMs by remember { mutableStateOf(0L) }
    // QR을 띄운 뒤 처음 붙은 게스트. 이게 생기면 QR 대신 완료 화면.
    var pairedGuest by remember { mutableStateOf<RemoteGuest?>(null) }
    var showBrokerForm by remember { mutableStateOf(broker == null) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val guests by RemoteHostService.guests.collectAsState()
    LaunchedEffect(fresh, guests) {
        if (fresh != null && pairedGuest == null) {
            val g = guests.firstOrNull { it.lastSeenMs >= freshAtMs } ?: return@LaunchedEffect
            pairedGuest = g
            Log.i("ARMS", "리모컨 호스트: 페어링 완료 (게스트 1대)")
        }
    }
    // 완료 화면은 2초 뒤 자동으로 닫힌다.
    LaunchedEffect(pairedGuest) {
        if (pairedGuest != null) {
            delay(PAIRED_AUTO_CLOSE_MS)
            onDone()
        }
    }

    fun generate() {
        val b = broker ?: run { showBrokerForm = true; return }
        busy = true
        error = null
        scope.launch {
            // 이전 페어링이 있으면 그 게스트에게 알리고 지운다(새 키로 바뀌므로 이전 QR은 무효).
            if (stored != null) {
                RemoteHostService.revokeAndUnpair(context)
                stored = null
            }
            val result = withContext(Dispatchers.Default) {
                runCatching { Pairing.generate(b.url, b.username, b.password) }
            }
            result.onSuccess { p ->
                runCatching { store.savePairing(p) }
                    .onSuccess {
                        freshAtMs = System.currentTimeMillis()
                        fresh = p
                        pairedGuest = null
                        // 서비스는 onCreate에서만 페어링을 읽는다 → 새 페어링으로 재시작.
                        RemoteHostService.restart(context)
                    }
                    .onFailure { error = "저장 실패: ${it.javaClass.simpleName}" }
            }.onFailure { error = "페어링 생성 실패: ${it.message ?: it.javaClass.simpleName}" }
            busy = false
        }
    }

    fun unpair() {
        busy = true
        scope.launch {
            Log.i("ARMS", "리모컨 호스트: 페어링 해제")
            RemoteHostService.revokeAndUnpair(context)
            fresh = null
            stored = null
            pairedGuest = null
            busy = false
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("페어링 해제") },
            text = { Text("리모컨 연결을 끊고 페어링을 지웁니다. 폰에서 다시 쓰려면 QR을 새로 만들어 스캔해야 합니다. 계속할까요?") },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; unpair() }) { Text("해제", color = RadioOnAirRed) }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("취소") } },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RadioBgDeep)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        Text("리모컨 페어링 (홈 플레이어)", style = MaterialTheme.typography.titleLarge, color = RadioOnDark)

        when {
            showBrokerForm -> BrokerSettingsForm(
                initial = broker,
                onSave = { settings ->
                    runCatching { store.saveBrokerSettings(settings) }
                        .onSuccess {
                            broker = settings
                            showBrokerForm = false
                            error = null
                        }
                        .onFailure { error = "저장 실패: ${it.javaClass.simpleName}" }
                },
                onCancel = if (broker != null) ({ showBrokerForm = false }) else null,
                error = error,
            )
            pairedGuest != null -> PairedView(guest = pairedGuest!!, onClose = onDone)
            fresh != null -> FreshPairingView(pairing = fresh!!, onDone = onDone)
            else -> ReadyView(
                broker = broker!!,
                stored = stored,
                busy = busy,
                error = error,
                onGenerate = ::generate,
                onUnpair = { confirmUnpair = true },
                onEditBroker = { showBrokerForm = true },
                onDone = onDone,
            )
        }
    }
}

private const val PAIRED_AUTO_CLOSE_MS = 2_000L

// 브로커 설정 입력(최초 1회, 또는 "브로커 설정 변경"). 저장된 비밀번호는 다시 보여주지 않는다 —
// 비워 두고 저장하면 기존 비밀번호를 유지한다.
@Composable
private fun BrokerSettingsForm(
    initial: RemoteSettingsStore.BrokerSettings?,
    onSave: (RemoteSettingsStore.BrokerSettings) -> Unit,
    onCancel: (() -> Unit)?,
    error: String?,
) {
    var brokerUrl by remember { mutableStateOf(initial?.url ?: "wss://") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf("") }
    val hasSavedPassword = !initial?.password.isNullOrEmpty()

    Text(
        if (initial == null) "NAS의 MQTT 브로커 주소와 계정을 한 번만 넣어 두면, 그 뒤로는 버튼 하나로 QR을 만듭니다."
        else "브로커 설정을 바꿉니다. 비밀번호를 비워 두면 저장된 값을 그대로 씁니다.",
        style = MaterialTheme.typography.bodyMedium, color = RadioOnDarkMuted,
    )
    OutlinedTextField(
        value = brokerUrl,
        onValueChange = { brokerUrl = it },
        label = { Text("브로커 URL") },
        placeholder = { Text("wss://호스트/mqtt") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = username,
        onValueChange = { username = it },
        label = { Text("계정") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text(if (hasSavedPassword) "비밀번호 (변경 시에만 입력)" else "비밀번호") },
        placeholder = { if (hasSavedPassword) Text("••••••••") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = RadioOnAirRed, style = MaterialTheme.typography.bodySmall) }

    val effectivePassword = if (password.isNotEmpty()) password else initial?.password ?: ""
    val canSave = isValidBrokerUrl(brokerUrl) && username.isNotBlank() && effectivePassword.isNotEmpty()
    Spacer(Modifier.height(Spacing.sm))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (onCancel != null) {
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("취소") }
        }
        Button(
            enabled = canSave,
            onClick = { onSave(RemoteSettingsStore.BrokerSettings(brokerUrl.trim(), username.trim(), effectivePassword)) },
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
            modifier = Modifier.weight(1f),
        ) { Text("저장") }
    }
}

// 브로커 설정이 있는 기본 화면: QR 만들기 버튼 하나. 이전 페어링이 있으면 요약과 해제 버튼도.
@Composable
private fun ReadyView(
    broker: RemoteSettingsStore.BrokerSettings,
    stored: Pairing?,
    busy: Boolean,
    error: String?,
    onGenerate: () -> Unit,
    onUnpair: () -> Unit,
    onEditBroker: () -> Unit,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    val needsBtPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    var btGranted by remember {
        mutableStateOf(
            !needsBtPermission ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        btGranted = granted
        // 권한을 거부해도 페어링 자체는 만든다(서비스 시작은 권한 있을 때만).
        onGenerate()
    }

    Text(
        if (stored == null) "폰이 QR을 스캔하면 페어링됩니다. QR은 만들 때 한 번만 표시됩니다."
        else "이 기기는 이미 페어링되어 있습니다. QR을 새로 만들면 이전 폰의 연결은 끊기고 새 키로 바뀝니다.",
        style = MaterialTheme.typography.bodyMedium, color = RadioOnDarkMuted, textAlign = TextAlign.Center,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(RadioSurfaceVariant)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text("브로커: ${brokerHost(broker.url)}", color = RadioOnDark, style = MaterialTheme.typography.bodyMedium)
        Text("계정: ${broker.username}", color = RadioOnDarkMuted, style = MaterialTheme.typography.bodyMedium)
        if (stored != null) {
            Text("페어링 ID: ${maskPairId(stored.pairId)}", color = RadioOnDarkMuted, style = MaterialTheme.typography.bodyMedium)
        }
    }
    if (needsBtPermission && !btGranted) {
        Text(
            "블루투스 연결 권한이 있어야 호스트 서비스가 켜지고 스피커 상태를 볼 수 있습니다. QR을 만들 때 요청합니다.",
            style = MaterialTheme.typography.bodySmall, color = RadioOnDarkMuted,
        )
    }
    error?.let { Text(it, color = RadioOnAirRed, style = MaterialTheme.typography.bodySmall) }

    Button(
        enabled = !busy,
        onClick = {
            if (needsBtPermission && !btGranted) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else onGenerate()
        },
        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = RadioBgDeep, strokeWidth = 2.dp)
        else Text("페어링 QR 만들기")
    }
    TextButton(enabled = !busy, onClick = onEditBroker) { Text("브로커 설정 변경", color = RadioOnDarkMuted) }

    Spacer(Modifier.height(Spacing.sm))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (stored != null) {
            OutlinedButton(
                enabled = !busy,
                onClick = onUnpair,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = RadioOnAirRed),
                modifier = Modifier.weight(1f),
            ) { Text("페어링 해제") }
        }
        OutlinedButton(enabled = !busy, onClick = onDone, modifier = Modifier.weight(1f)) { Text("닫기") }
    }
}

@Composable
private fun FreshPairingView(pairing: Pairing, onDone: () -> Unit) {
    val qrSizeDp = 320.dp
    val qrSizePx = with(LocalDensity.current) { qrSizeDp.roundToPx() }
    val bitmap by produceState<Bitmap?>(initialValue = null, pairing) {
        value = withContext(Dispatchers.Default) { runCatching { qrBitmap(pairing.toQrText(), qrSizePx) }.getOrNull() }
    }

    Column(
        modifier = Modifier
            .size(qrSizeDp)
            .clip(RoundedCornerShape(Radius.lg))
            .background(Color.White),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(bitmap = bmp.asImageBitmap(), contentDescription = "페어링 QR", modifier = Modifier.size(qrSizeDp))
        } else {
            CircularProgressIndicator(color = SpotifyGreen)
        }
    }
    Text(
        "폰의 리모컨 설정에서 이 QR을 스캔하세요. 폰이 연결되면 QR은 자동으로 사라집니다.",
        style = MaterialTheme.typography.bodyMedium, color = RadioOnDark, textAlign = TextAlign.Center,
    )
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = RadioOnDarkMuted, strokeWidth = 2.dp)
        Text("폰 연결 기다리는 중 · 페어링 ID ${maskPairId(pairing.pairId)}", color = RadioOnDarkMuted, style = MaterialTheme.typography.bodySmall)
    }
    Spacer(Modifier.height(Spacing.sm))
    OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth()) { Text("닫기") }
}

@Composable
private fun PairedView(guest: RemoteGuest, onClose: () -> Unit) {
    Spacer(Modifier.height(Spacing.xl))
    Text("페어링 완료 · ${guest.name}", style = MaterialTheme.typography.headlineSmall, color = SpotifyGreen, textAlign = TextAlign.Center)
    Text(
        "폰의 리모컨 탭에서 이 기기를 조작할 수 있습니다. 상단바의 리모컨 칩에서 연결 상태를 볼 수 있습니다.",
        style = MaterialTheme.typography.bodyMedium, color = RadioOnDarkMuted, textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(Spacing.sm))
    Button(
        onClick = onClose,
        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("닫기") }
}

internal fun isValidBrokerUrl(url: String): Boolean {
    val t = url.trim()
    if (!(t.startsWith("wss://") || t.startsWith("ws://"))) return false
    val host = runCatching { URI(t).host }.getOrNull()
    return !host.isNullOrBlank()
}

internal fun brokerHost(url: String): String = runCatching { URI(url.trim()).host }.getOrNull()?.takeIf { it.isNotBlank() } ?: "(알 수 없음)"

internal fun maskPairId(pairId: String): String = pairId.take(4) + "…"

// zxing QRCodeWriter → BitMatrix → Bitmap. 오류정정 M, 여백 1모듈. 흰 바탕에 검은 모듈(스캔 호환).
internal fun qrBitmap(text: String, sizePx: Int): Bitmap {
    val hints = mapOf(
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
    val w = matrix.width
    val h = matrix.height
    val pixels = IntArray(w * h)
    for (y in 0 until h) {
        val row = y * w
        for (x in 0 until w) pixels[row + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
    }
    return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { setPixels(pixels, 0, w, 0, 0, w, h) }
}
