package com.arms.androidauto.remote.guest

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.arms.androidauto.BuildConfig
import com.arms.androidauto.core.remote.BrokerProbe
import com.arms.androidauto.core.remote.BrokerUrlPolicy
import com.arms.androidauto.core.remote.Pairing
import com.arms.androidauto.core.remote.RemoteFailure
import com.arms.androidauto.remote.RemoteRole
import com.arms.androidauto.remote.RemoteSettingsStore
import com.arms.androidauto.ui.theme.RadioBgDeep
import com.arms.androidauto.ui.theme.RadioOnAirRed
import com.arms.androidauto.ui.theme.Radius
import com.arms.androidauto.ui.theme.Spacing
import com.arms.androidauto.ui.theme.SpotifyGreen
import com.arms.androidauto.ui.theme.SpotifySurface
import com.arms.androidauto.ui.theme.SpotifyTextMuted
import com.arms.androidauto.ui.theme.SpotifyTextPrimary
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import kotlinx.coroutines.launch
import java.net.URI

// 게스트(폰) 페어링 화면. 태블릿이 띄운 QR을 스캔해 페어링(키 포함)을 암호화 저장소에 넣는다.
// 키는 어디에도 표시하지 않는다 — 요약에는 브로커 호스트와 pairId 앞 4자만.
@Composable
fun GuestPairingScreen(
    store: RemoteSettingsStore,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var pairing by remember { mutableStateOf(store.getPairing()) }
    var errorText by remember { mutableStateOf<String?>(null) }
    var hintText by remember { mutableStateOf<String?>(null) }
    var manualCode by remember { mutableStateOf("") }
    // 스캐너는 사용자가 "QR 스캔"을 눌렀을 때만 연다(첫 진입에서 카메라가 저절로 켜지지 않는다).
    var showScanner by remember { mutableStateOf(false) }
    var confirmUnpair by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    // QR/코드 검증 진행 상태. verifying: 브로커에 실제로 붙어 보는 중. checkError: 저장하지 않은 이유(빨간 글씨).
    // probeFailedPairing: 주소는 맞지만 지금 브로커에 못 붙은 페어링 — "그래도 저장"/"다시 시도" 대상.
    var verifying by remember { mutableStateOf(false) }
    var checkError by remember { mutableStateOf<String?>(null) }
    var probeFailedPairing by remember { mutableStateOf<Pairing?>(null) }

    fun save(parsed: Pairing) {
        store.savePairing(parsed)
        store.setRole(RemoteRole.GUEST)
        // pairId 앞 4자만. 키·브로커 URL·계정은 로그에 남기지 않는다.
        Log.i("ARMS", "리모컨 게스트: 페어링 저장 (pair ${parsed.pairId.take(4)}…)")
        pairing = parsed
        errorText = null
        hintText = null
        checkError = null
        probeFailedPairing = null
        manualCode = ""
        onDone()
    }

    // 저장 전에 한 번 실제로 붙어 본다. 성공하면 저장, 실패하면 원인과 함께 "그래도 저장"/"다시 시도"를 띄운다.
    fun probeAndSave(parsed: Pairing) {
        verifying = true
        checkError = null
        probeFailedPairing = null
        scope.launch {
            val result = BrokerProbe.probe(
                url = parsed.brokerUrl,
                username = parsed.username,
                password = parsed.password,
                clientId = store.clientId() + "-probe",
            )
            verifying = false
            when (result) {
                is BrokerProbe.Result.Ok -> save(parsed)
                is BrokerProbe.Result.Failed -> {
                    Log.w("ARMS", "리모컨 게스트: 페어링 전 브로커 확인 실패 (${result.kind})")
                    checkError = RemoteFailure.message(result.kind)
                    probeFailedPairing = parsed
                }
            }
        }
    }

    // QR 스캔 결과·직접 입력 코드 공통. 형식 → 브로커 주소 규칙 → 실제 연결 순으로 확인한다.
    // 2026-10-06 사고: 태블릿 QR에 ws://127.0.0.1(개발용 주소)이 들어 있었는데 그대로 저장돼 영영 붙지 못했다.
    fun accept(text: String?) {
        if (verifying) return
        val parsed = text?.trim()?.takeIf { it.isNotEmpty() }?.let { Pairing.fromQrText(it) }
        if (parsed == null) {
            errorText = "올바른 페어링 QR이 아닙니다"
            return
        }
        errorText = null
        val check = BrokerUrlPolicy.check(parsed.brokerUrl, allowLoopback = BuildConfig.DEBUG)
        if (check is BrokerUrlPolicy.Result.Invalid) {
            Log.w("ARMS", "리모컨 게스트: QR의 브로커 주소가 올바르지 않아 저장하지 않음 (${check.problem})")
            checkError = GuestStatusPolicy.configMessage(check.problem)
            probeFailedPairing = null
            return
        }
        probeAndSave(parsed)
    }

    // 카메라 권한은 여기서 직접 묻는다. 라이브러리의 CaptureActivity(가로 고정)를 쓰지 않고 이 화면 안에
    // 세로 스캐너를 띄우기 때문.
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            showScanner = true
        } else {
            hintText = "카메라 권한이 없습니다. 설정에서 카메라 권한을 허용하거나 아래에 코드를 직접 입력하세요."
        }
    }
    fun openScanner() {
        errorText = null
        hintText = null
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (granted) showScanner = true else permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (showScanner) {
        InlineQrScanner(
            onResult = { text ->
                showScanner = false
                accept(text)
            },
            onClose = {
                showScanner = false
                hintText = "스캔을 취소했습니다. 다시 스캔하거나 아래에 코드를 직접 입력하세요."
            },
        )
        return
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            title = { Text("페어링 해제") },
            text = { Text("이 폰의 리모컨 페어링을 지웁니다. 계속할까요?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmUnpair = false
                        store.clearPairing()
                        store.setRole(RemoteRole.NONE)
                        Log.i("ARMS", "리모컨 게스트: 페어링 해제")
                        pairing = null
                        errorText = null
                        hintText = null
                    },
                ) {
                    Text("해제", color = RadioOnAirRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmUnpair = false }) { Text("취소") }
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(RadioBgDeep)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xl),
    ) {
        Text(
            text = "태블릿과 페어링",
            style = MaterialTheme.typography.headlineSmall,
            color = SpotifyTextPrimary,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(Spacing.sm))

        val current = pairing
        if (current != null) {
            Text(
                text = "이 폰은 리모컨으로 페어링되어 있습니다.",
                style = MaterialTheme.typography.bodyMedium,
                color = SpotifyTextMuted,
            )
            Spacer(Modifier.height(Spacing.lg))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(Radius.lg),
                colors = CardDefaults.cardColors(containerColor = SpotifySurface),
            ) {
                Column(modifier = Modifier.padding(Spacing.lg)) {
                    Text(
                        text = "브로커",
                        style = MaterialTheme.typography.labelMedium,
                        color = SpotifyTextMuted,
                    )
                    Text(
                        text = PairingSummary.brokerHost(current.brokerUrl),
                        style = MaterialTheme.typography.bodyLarge,
                        color = SpotifyTextPrimary,
                    )
                    Spacer(Modifier.height(Spacing.md))
                    Text(
                        text = "페어링 ID",
                        style = MaterialTheme.typography.labelMedium,
                        color = SpotifyTextMuted,
                    )
                    Text(
                        text = PairingSummary.maskedPairId(current.pairId),
                        style = MaterialTheme.typography.bodyLarge,
                        color = SpotifyTextPrimary,
                    )
                }
            }
            // 예전 빌드가 검사 없이 저장한 페어링(예: 127.0.0.1)은 절대 붙지 않는다. 지우고 다시 스캔하게 한다.
            val storedProblem = (BrokerUrlPolicy.check(current.brokerUrl, allowLoopback = BuildConfig.DEBUG)
                as? BrokerUrlPolicy.Result.Invalid)?.problem
            if (storedProblem != null) {
                Spacer(Modifier.height(Spacing.md))
                Text(
                    text = GuestStatusPolicy.configMessage(storedProblem),
                    style = MaterialTheme.typography.bodyMedium,
                    color = RadioOnAirRed,
                )
                Spacer(Modifier.height(Spacing.md))
                Button(
                    onClick = {
                        store.clearPairing()
                        store.setRole(RemoteRole.NONE)
                        Log.i("ARMS", "리모컨 게스트: 잘못된 페어링 해제 후 다시 스캔")
                        pairing = null
                        errorText = null
                        hintText = null
                        checkError = null
                        probeFailedPairing = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = RadioBgDeep),
                ) {
                    Text("페어링 지우고 QR 다시 스캔", fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(Spacing.xl))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(
                    onClick = { confirmUnpair = true },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = RadioOnAirRed),
                ) {
                    Text("페어링 해제")
                }
                Spacer(Modifier.width(Spacing.md))
                Button(
                    onClick = onDone,
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = RadioBgDeep),
                ) {
                    Text("완료", fontWeight = FontWeight.Bold)
                }
            }
            return@Column
        }

        Text(
            text = "태블릿의 설정 → 원격 제어에서 QR을 띄운 뒤, 이 폰으로 스캔하세요. QR은 그 자리에서 한 번만 쓰입니다.",
            style = MaterialTheme.typography.bodyMedium,
            color = SpotifyTextMuted,
        )
        Spacer(Modifier.height(Spacing.xl))

        Button(
            onClick = { openScanner() },
            enabled = !verifying,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = RadioBgDeep),
        ) {
            Text("QR 스캔", fontWeight = FontWeight.Bold)
        }

        if (verifying) {
            Spacer(Modifier.height(Spacing.md))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.width(16.dp).height(16.dp), color = SpotifyGreen, strokeWidth = 2.dp)
                Spacer(Modifier.width(Spacing.sm))
                Text(
                    text = "브로커 연결 확인 중…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SpotifyTextMuted,
                )
            }
        }
        if (checkError != null) {
            Spacer(Modifier.height(Spacing.md))
            Text(
                text = checkError!!,
                style = MaterialTheme.typography.bodyMedium,
                color = RadioOnAirRed,
            )
            val failed = probeFailedPairing
            if (failed != null) {
                Spacer(Modifier.height(Spacing.xs))
                Text(
                    text = "브로커가 잠시 꺼져 있을 수도 있습니다. 그래도 저장하면 리모컨 화면에서 계속 다시 연결을 시도합니다.",
                    style = MaterialTheme.typography.bodySmall,
                    color = SpotifyTextMuted,
                )
                Spacer(Modifier.height(Spacing.sm))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(
                        onClick = { save(failed) },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyTextMuted),
                    ) {
                        Text("그래도 저장")
                    }
                    Spacer(Modifier.width(Spacing.md))
                    Button(
                        onClick = { probeAndSave(failed) },
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = RadioBgDeep),
                    ) {
                        Text("다시 시도", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (hintText != null) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                text = hintText!!,
                style = MaterialTheme.typography.bodySmall,
                color = SpotifyTextMuted,
            )
        }

        Spacer(Modifier.height(Spacing.xxl))
        Text(
            text = "코드 직접 입력",
            style = MaterialTheme.typography.titleSmall,
            color = SpotifyTextPrimary,
        )
        Spacer(Modifier.height(Spacing.xs))
        Text(
            text = "카메라를 쓸 수 없을 때 태블릿 화면의 코드를 그대로 옮겨 적습니다.",
            style = MaterialTheme.typography.bodySmall,
            color = SpotifyTextMuted,
        )
        Spacer(Modifier.height(Spacing.sm))
        OutlinedTextField(
            value = manualCode,
            onValueChange = {
                manualCode = it
                if (errorText != null) errorText = null
            },
            modifier = Modifier.fillMaxWidth(),
            singleLine = false,
            minLines = 2,
            maxLines = 4,
            placeholder = { Text("페어링 코드", color = SpotifyTextMuted) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done, autoCorrect = false),
            isError = errorText != null,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SpotifyGreen,
                cursorColor = SpotifyGreen,
                focusedTextColor = SpotifyTextPrimary,
                unfocusedTextColor = SpotifyTextPrimary,
            ),
        )
        if (errorText != null) {
            Spacer(Modifier.height(Spacing.xs))
            Text(
                text = errorText!!,
                style = MaterialTheme.typography.bodySmall,
                color = RadioOnAirRed,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            OutlinedButton(
                onClick = onDone,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyTextMuted),
            ) {
                Text("나중에")
            }
            Spacer(Modifier.width(Spacing.md))
            OutlinedButton(
                onClick = { accept(manualCode) },
                enabled = manualCode.isNotBlank() && !verifying,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyGreen),
            ) {
                Text("확인")
            }
        }
        Spacer(Modifier.height(48.dp))
    }
}

// 페어링 화면 안에 띄우는 세로 QR 스캐너. 라이브러리의 CaptureActivity는 자체 매니페스트가 sensorLandscape로
// 고정돼 있어(ScanOptions.setOrientationLocked로는 못 바꾸고 매니페스트 override가 필요) 별도 액티비티 대신
// DecoratedBarcodeView를 이 화면에 직접 넣는다. 그러면 앱 화면 방향(폰=세로)을 그대로 따른다.
@Composable
private fun InlineQrScanner(
    onResult: (String) -> Unit,
    onClose: () -> Unit,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    var handled by remember { mutableStateOf(false) }
    var scannerView by remember { mutableStateOf<DecoratedBarcodeView?>(null) }

    BackHandler(onBack = onClose)

    DisposableEffect(lifecycleOwner, scannerView) {
        val view = scannerView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> view?.resume()
                Lifecycle.Event.ON_PAUSE -> view?.pause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) view?.resume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            view?.pause()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(RadioBgDeep)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                DecoratedBarcodeView(ctx).apply {
                    barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                    setStatusText("태블릿 화면의 QR을 스캔하세요")
                    decodeSingle(object : BarcodeCallback {
                        override fun barcodeResult(result: BarcodeResult) {
                            if (handled) return
                            val text = result.text ?: return
                            handled = true
                            pause()
                            onResult(text)
                        }

                        override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) = Unit
                    })
                    scannerView = this
                }
            },
        )
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xl),
            horizontalArrangement = Arrangement.Center,
        ) {
            OutlinedButton(
                onClick = onClose,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = SpotifyTextPrimary),
            ) {
                Text("닫기")
            }
        }
    }
}

// 화면에 보여줘도 되는 페어링 요약. 키·계정은 절대 포함하지 않는다.
internal object PairingSummary {
    fun brokerHost(brokerUrl: String): String {
        val host = runCatching { URI(brokerUrl).host }.getOrNull()
        if (!host.isNullOrBlank()) return host
        // URI 파싱이 안 되는 문자열이면 스킴과 경로를 벗겨낸 부분만.
        return brokerUrl.substringAfter("://").substringBefore("/").substringBefore("?").ifBlank { "알 수 없음" }
    }

    fun maskedPairId(pairId: String): String {
        val head = pairId.take(4)
        return if (pairId.length <= 4) head else "$head••••"
    }
}
