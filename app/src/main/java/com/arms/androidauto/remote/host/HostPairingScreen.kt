package com.arms.androidauto.remote.host

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.arms.androidauto.core.remote.Pairing
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URI

// 호스트(태블릿) 페어링 화면.
// - 페어링 없음: 브로커 URL/계정/비밀번호 입력 → "페어링 QR 만들기" → 키 생성·저장 → QR 표시(이 화면에서만).
// - 페어링 있음(이전에 만든 것): 마스킹된 요약만. 키·비밀번호는 생성 이후 어디에도 다시 보여 주지 않는다.
// 페어링 저장/해제 뒤에는 RemoteHostService.syncWithRole로 서비스를 맞춘다(역할이 HOST일 때만 시작됨).
@Composable
fun HostPairingScreen(store: RemoteSettingsStore, onDone: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var stored by remember { mutableStateOf(runCatching { store.getPairing() }.getOrNull()) }
    // 이 화면에서 방금 만든 페어링. 화면을 떠나면 사라지고 QR은 다시 볼 수 없다.
    var fresh by remember { mutableStateOf<Pairing?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun unpair() {
        val current = fresh ?: stored ?: return
        busy = true
        scope.launch {
            RemoteHostService.clearRetainedState(context, current)
            store.clearPairing()
            RemoteHostService.stop(context)
            fresh = null
            stored = null
            busy = false
        }
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
            fresh != null -> FreshPairingView(
                pairing = fresh!!,
                busy = busy,
                onDone = onDone,
                onUnpair = ::unpair,
            )
            stored != null -> StoredPairingView(
                pairing = stored!!,
                busy = busy,
                onDone = onDone,
                onUnpair = ::unpair,
            )
            else -> NewPairingForm(
                busy = busy,
                error = error,
                onGenerate = { url, user, pw ->
                    busy = true
                    error = null
                    scope.launch {
                        val result = withContext(Dispatchers.Default) {
                            runCatching { Pairing.generate(url, user, pw) }
                        }
                        result.onSuccess { p ->
                            runCatching { store.savePairing(p) }
                                .onSuccess {
                                    fresh = p
                                    RemoteHostService.syncWithRole(context)
                                }
                                .onFailure { error = "저장 실패: ${it.javaClass.simpleName}" }
                        }.onFailure { error = "페어링 생성 실패: ${it.message ?: it.javaClass.simpleName}" }
                        busy = false
                    }
                },
            )
        }
    }
}

@Composable
private fun NewPairingForm(
    busy: Boolean,
    error: String?,
    onGenerate: (brokerUrl: String, username: String, password: String) -> Unit,
) {
    val context = LocalContext.current
    var brokerUrl by remember { mutableStateOf("wss://") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

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
        onGenerate(brokerUrl.trim(), username.trim(), password)
    }

    Text(
        "NAS의 MQTT 브로커 주소와 계정을 넣고 QR을 만드세요. 폰이 QR을 스캔하면 페어링됩니다.",
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
        label = { Text("비밀번호") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    if (needsBtPermission && !btGranted) {
        Text(
            "블루투스 연결 권한이 있어야 호스트 서비스가 켜지고 스피커 상태를 볼 수 있습니다. QR을 만들 때 요청합니다.",
            style = MaterialTheme.typography.bodySmall, color = RadioOnDarkMuted,
        )
    }
    error?.let { Text(it, color = RadioOnAirRed, style = MaterialTheme.typography.bodySmall) }

    val urlOk = isValidBrokerUrl(brokerUrl)
    Button(
        enabled = !busy && urlOk,
        onClick = {
            if (needsBtPermission && !btGranted) permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            else onGenerate(brokerUrl.trim(), username.trim(), password)
        },
        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (busy) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = RadioBgDeep, strokeWidth = 2.dp)
        else Text("페어링 QR 만들기")
    }
}

@Composable
private fun FreshPairingView(pairing: Pairing, busy: Boolean, onDone: () -> Unit, onUnpair: () -> Unit) {
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
        "폰의 리모컨 설정에서 이 QR을 스캔하세요. 이 화면을 닫으면 QR은 다시 볼 수 없습니다(키 노출 방지).",
        style = MaterialTheme.typography.bodyMedium, color = RadioOnDark, textAlign = TextAlign.Center,
    )
    PairingSummary(pairing)
    ActionRow(busy = busy, onDone = onDone, onUnpair = onUnpair)
}

@Composable
private fun StoredPairingView(pairing: Pairing, busy: Boolean, onDone: () -> Unit, onUnpair: () -> Unit) {
    Text(
        "이 기기는 이미 페어링되어 있습니다. QR은 만들 때 한 번만 표시됩니다. 폰을 새로 연결하려면 페어링을 해제하고 다시 만드세요.",
        style = MaterialTheme.typography.bodyMedium, color = RadioOnDarkMuted, textAlign = TextAlign.Center,
    )
    PairingSummary(pairing)
    ActionRow(busy = busy, onDone = onDone, onUnpair = onUnpair)
}

// 키·비밀번호·계정은 절대 표시하지 않는다. 브로커 호스트명과 pairId 앞 4자만.
@Composable
private fun PairingSummary(pairing: Pairing) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(RadioSurfaceVariant)
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        Text("브로커: ${brokerHost(pairing.brokerUrl)}", color = RadioOnDark, style = MaterialTheme.typography.bodyMedium)
        Text("페어링 ID: ${maskPairId(pairing.pairId)}", color = RadioOnDarkMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ActionRow(busy: Boolean, onDone: () -> Unit, onUnpair: () -> Unit) {
    Spacer(Modifier.height(Spacing.sm))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        OutlinedButton(
            enabled = !busy,
            onClick = onUnpair,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = RadioOnAirRed),
            modifier = Modifier.weight(1f),
        ) { Text("페어링 해제") }
        Button(
            enabled = !busy,
            onClick = onDone,
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
            modifier = Modifier.weight(1f),
        ) { Text("완료") }
    }
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
