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
import com.arms.androidauto.BuildConfig
import com.arms.androidauto.core.remote.BrokerProbe
import com.arms.androidauto.core.remote.BrokerUrlPolicy
import com.arms.androidauto.core.remote.Pairing
import com.arms.androidauto.core.remote.RemoteFailure
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI

// 호스트(태블릿) 페어링 화면.
// - 브로커 설정(URL/계정/비밀번호)은 한 번만 입력해 저장한다(페어링을 해제해도 남는다).
// - 그 뒤로는 "페어링 QR 만들기" 한 번으로 키 생성·저장·서비스 시작·QR 표시까지 간다.
// - QR을 띄운 뒤 게스트가 Hello를 보내면(RemoteHostService.guests) QR을 즉시 걷고 "페어링 완료"를 보여준다.
//   QR은 키를 담고 있으므로 게스트가 붙은 뒤에는 절대 화면에 남기지 않는다.
// - 이전 페어링이 있는 상태에서 QR을 새로 만들면 이전 게스트에게 연결 종료를 알리고(revoked) 새 키로 바꾼다.
// - 아래에 "스마트싱스 연결" 섹션(SmartThingsSection): 로컬 제어 켜기/끄기, 연결 허용(10분), 연결된 클라이언트 해제.
// - 2026-10-06 사고 대응: 브로커 설정은 정책 검사(BrokerUrlPolicy) + 실제 접속 확인(BrokerProbe)을 통과해야
//   저장된다. 저장된 주소가 정책상 못 쓰는 값이면(예: 127.0.0.1) 빨간 카드와 함께 폼을 강제로 연다.
//   QR은 브로커에 실제로 붙은 것을 확인한 뒤에만 만든다(호스트 Ready, 또는 서비스가 없으면 BrokerProbe).
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
    // 저장된 브로커 주소의 정책 위반(예: 개발용 127.0.0.1). 있으면 폼을 강제로 열고 QR을 막는다.
    val brokerProblem = broker?.let { brokerPolicyProblem(it.url) }
    var showBrokerForm by remember { mutableStateOf(broker == null || brokerProblem != null) }
    var confirmUnpair by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    // QR 만들기 전 확인 단계의 진행 문구(예: "브로커 연결 확인 중…"). null이면 진행 중 아님.
    var busyLabel by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    // QR 만들기가 브로커 연결 문제로 막혔을 때 true → "지금 다시 연결" 버튼을 보인다.
    var connectBlocked by remember { mutableStateOf(false) }
    val hostStatus by RemoteHostService.status.collectAsState()

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

    // 브로커에 실제로 붙는지 확인한다. 저장된 페어링이 지금 브로커 설정과 같은 주소로 돌고 있으면 호스트 상태
    // (Ready)로, 아니면(첫 페어링·주소 변경·서비스 꺼짐) 일회성 접속(BrokerProbe)으로. 실패하면 사람이 읽을 이유.
    suspend fun confirmBrokerReachable(b: RemoteSettingsStore.BrokerSettings): String? {
        val hostUsesSameBroker = stored?.brokerUrl?.trim() == b.url.trim() && RemoteHostService.isRunning.value
        if (hostUsesSameBroker) {
            if (RemoteHostService.status.value is HostStatus.Ready) return null
            busyLabel = "브로커에 다시 연결하는 중…"
            RemoteHostService.retryNow(context)
            val ready = withTimeoutOrNull(READY_WAIT_MS) { RemoteHostService.status.first { it is HostStatus.Ready } }
            return if (ready != null) null else HostStatusPolicy.detail(RemoteHostService.status.value)
        }
        busyLabel = "브로커 연결 확인 중…"
        val result = withContext(Dispatchers.IO) {
            BrokerProbe.probe(b.url, b.username, b.password, clientId = store.clientId() + "-probe")
        }
        return when (result) {
            is BrokerProbe.Result.Ok -> null
            is BrokerProbe.Result.Failed -> RemoteFailure.message(result.kind)
        }
    }

    fun generate() {
        val b = broker ?: run { showBrokerForm = true; return }
        brokerPolicyProblem(b.url)?.let {
            error = BrokerUrlPolicy.message(it)
            showBrokerForm = true
            return
        }
        busy = true
        error = null
        connectBlocked = false
        scope.launch {
            val problem = confirmBrokerReachable(b)
            busyLabel = null
            if (problem != null) {
                error = "브로커에 연결되지 않아 QR을 만들지 않았습니다. $problem"
                connectBlocked = true
                busy = false
                return@launch
            }
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
            showBrokerForm -> {
                if (broker != null && brokerProblem != null) InvalidBrokerCard(broker!!.url, brokerProblem)
                BrokerSettingsForm(
                    initial = broker,
                    clientId = { store.clientId() + "-probe" },
                    onSave = { settings ->
                        runCatching { store.saveBrokerSettings(settings) }
                            .onSuccess {
                                broker = settings
                                showBrokerForm = false
                                error = null
                                connectBlocked = false
                            }
                            .onFailure { error = "저장 실패: ${it.javaClass.simpleName}" }
                    },
                    // 저장된 주소가 못 쓰는 값이면 돌아갈 곳이 없다(폼 강제).
                    onCancel = if (broker != null && brokerProblem == null) ({ showBrokerForm = false }) else null,
                    error = error,
                )
            }
            pairedGuest != null -> PairedView(guest = pairedGuest!!, onClose = onDone)
            fresh != null -> FreshPairingView(pairing = fresh!!, status = hostStatus, onDone = onDone)
            else -> ReadyView(
                broker = broker!!,
                stored = stored,
                busy = busy,
                busyLabel = busyLabel,
                error = error,
                hostStatus = hostStatus,
                connectBlocked = connectBlocked,
                canGenerate = brokerProblem == null,
                onGenerate = ::generate,
                onRetry = {
                    RemoteHostService.retryNow(context)
                    generate()
                },
                onUnpair = { confirmUnpair = true },
                onEditBroker = { showBrokerForm = true },
                onDone = onDone,
            )
        }
        // 스마트싱스 로컬 제어는 브로커·폰 리모컨과 무관하게 쓸 수 있다(QR·완료 화면에서만 숨긴다).
        if (fresh == null && pairedGuest == null) SmartThingsSection()
    }
}

private const val PAIRED_AUTO_CLOSE_MS = 2_000L
// "페어링 QR 만들기"에서 호스트가 브로커에 다시 붙기를 기다리는 최대 시간.
private const val READY_WAIT_MS = 10_000L

// release 빌드에서는 127.0.0.1·localhost 등 개발용 주소를 막는다(debug는 adb reverse 개발용으로 허용).
internal fun brokerPolicyProblem(url: String): BrokerUrlPolicy.Problem? =
    (BrokerUrlPolicy.check(url, allowLoopback = BuildConfig.DEBUG) as? BrokerUrlPolicy.Result.Invalid)?.problem

// 저장된 브로커 주소가 정책상 못 쓰는 값일 때 폼 위에 띄우는 빨간 카드.
@Composable
private fun InvalidBrokerCard(url: String, problem: BrokerUrlPolicy.Problem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .background(RadioOnAirRed.copy(alpha = 0.18f))
            .padding(Spacing.lg),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
    ) {
        val host = brokerHost(url)
        Text(
            if (problem == BrokerUrlPolicy.Problem.LOOPBACK) "저장된 브로커 주소($host)는 다른 기기와 연결될 수 없습니다"
            else "저장된 브로커 주소($host)는 사용할 수 없습니다",
            color = RadioOnAirRed, style = MaterialTheme.typography.titleSmall,
        )
        Text(BrokerUrlPolicy.message(problem), color = RadioOnDark, style = MaterialTheme.typography.bodySmall)
    }
}

// 브로커 설정 입력(최초 1회, 또는 "브로커 설정 변경"). 저장된 비밀번호는 다시 보여주지 않는다 —
// 비워 두고 저장하면 기존 비밀번호를 유지한다.
@Composable
private fun BrokerSettingsForm(
    initial: RemoteSettingsStore.BrokerSettings?,
    clientId: () -> String,
    onSave: (RemoteSettingsStore.BrokerSettings) -> Unit,
    onCancel: (() -> Unit)?,
    error: String?,
) {
    val scope = rememberCoroutineScope()
    var brokerUrl by remember { mutableStateOf(initial?.url ?: "wss://") }
    var username by remember { mutableStateOf(initial?.username ?: "") }
    var password by remember { mutableStateOf("") }
    val hasSavedPassword = !initial?.password.isNullOrEmpty()
    // 정책 위반(형식·127.0.0.1·평문 공용 주소). 저장 버튼을 눌렀을 때 URL 칸 아래에 보인다.
    var urlError by remember { mutableStateOf<String?>(null) }
    var probing by remember { mutableStateOf(false) }
    // 접속 확인 실패 이유. 있으면 "그래도 저장"을 보인다(브로커가 잠시 꺼져 있을 때용).
    var probeError by remember { mutableStateOf<String?>(null) }

    Text(
        if (initial == null) "NAS의 MQTT 브로커 주소와 계정을 한 번만 넣어 두면, 그 뒤로는 버튼 하나로 QR을 만듭니다."
        else "브로커 설정을 바꿉니다. 비밀번호를 비워 두면 저장된 값을 그대로 씁니다.",
        style = MaterialTheme.typography.bodyMedium, color = RadioOnDarkMuted,
    )
    OutlinedTextField(
        value = brokerUrl,
        onValueChange = { brokerUrl = it; urlError = null; probeError = null },
        label = { Text("브로커 URL") },
        placeholder = { Text("wss://호스트/mqtt") },
        singleLine = true,
        isError = urlError != null,
        supportingText = urlError?.let { msg -> { Text(msg, color = RadioOnAirRed) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = username,
        onValueChange = { username = it; probeError = null },
        label = { Text("계정") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = password,
        onValueChange = { password = it; probeError = null },
        label = { Text(if (hasSavedPassword) "비밀번호 (변경 시에만 입력)" else "비밀번호") },
        placeholder = { if (hasSavedPassword) Text("••••••••") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    error?.let { Text(it, color = RadioOnAirRed, style = MaterialTheme.typography.bodySmall) }

    val effectivePassword = if (password.isNotEmpty()) password else initial?.password ?: ""
    val canSave = !probing && brokerUrl.isNotBlank() && username.isNotBlank() && effectivePassword.isNotEmpty()

    // 정책 검사를 통과한 설정만 돌려준다(통과 못 하면 URL 칸 아래 오류, 저장 안 함).
    fun validated(): RemoteSettingsStore.BrokerSettings? {
        val problem = brokerPolicyProblem(brokerUrl)
        if (problem != null) {
            urlError = BrokerUrlPolicy.message(problem)
            probeError = null
            return null
        }
        urlError = null
        return RemoteSettingsStore.BrokerSettings(brokerUrl.trim(), username.trim(), effectivePassword)
    }

    fun probeAndSave() {
        val settings = validated() ?: return
        probing = true
        probeError = null
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                BrokerProbe.probe(settings.url, settings.username, settings.password, clientId = clientId())
            }
            probing = false
            when (result) {
                is BrokerProbe.Result.Ok -> {
                    Log.i("ARMS", "리모컨 호스트: 브로커 확인 OK (${result.elapsedMs}ms) → 저장")
                    onSave(settings)
                }
                is BrokerProbe.Result.Failed -> {
                    Log.w("ARMS", "리모컨 호스트: 브로커 확인 실패 (${result.kind})")
                    probeError = RemoteFailure.message(result.kind)
                }
            }
        }
    }

    if (probing) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = RadioOnDarkMuted, strokeWidth = 2.dp)
            Text("브로커 연결 확인 중…", color = RadioOnDarkMuted, style = MaterialTheme.typography.bodySmall)
        }
    }
    probeError?.let { Text(it, color = RadioOnAirRed, style = MaterialTheme.typography.bodySmall) }

    Spacer(Modifier.height(Spacing.sm))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
        if (onCancel != null) {
            OutlinedButton(enabled = !probing, onClick = onCancel, modifier = Modifier.weight(1f)) { Text("취소") }
        }
        Button(
            enabled = canSave,
            onClick = ::probeAndSave,
            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen),
            modifier = Modifier.weight(1f),
        ) {
            if (probing) CircularProgressIndicator(modifier = Modifier.size(18.dp), color = RadioBgDeep, strokeWidth = 2.dp)
            else Text("저장")
        }
    }
    if (probeError != null && !probing) {
        // 브로커가 잠시 꺼져 있을 때만. 정책 검사는 다시 한다(127.0.0.1 등은 이 버튼으로도 저장 불가).
        TextButton(onClick = { validated()?.let(onSave) }) { Text("그래도 저장", color = RadioOnDarkMuted) }
    }
}

// 브로커 설정이 있는 기본 화면: QR 만들기 버튼 하나. 이전 페어링이 있으면 요약과 해제 버튼도.
@Composable
private fun ReadyView(
    broker: RemoteSettingsStore.BrokerSettings,
    stored: Pairing?,
    busy: Boolean,
    busyLabel: String?,
    error: String?,
    hostStatus: HostStatus,
    connectBlocked: Boolean,
    canGenerate: Boolean,
    onGenerate: () -> Unit,
    onRetry: () -> Unit,
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
            // 호스트 서비스는 페어링 안의 주소로 붙는다. 브로커 설정만 바꿨다면 QR을 새로 만들어야 반영된다.
            if (stored.brokerUrl.trim() != broker.url.trim()) {
                Text(
                    "브로커 설정이 바뀌었습니다. QR을 새로 만들어야 폰과 이 기기가 새 주소로 연결됩니다.",
                    color = RadioOnAirRed, style = MaterialTheme.typography.bodySmall,
                )
            } else if (hostStatus !is HostStatus.NotRunning && hostStatus !is HostStatus.NoPairing) {
                Text(
                    "상태: ${HostStatusPolicy.detail(hostStatus)}",
                    color = if (hostStatus is HostStatus.Ready) RadioOnDarkMuted else RadioOnAirRed,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
    if (needsBtPermission && !btGranted) {
        Text(
            "블루투스 연결 권한이 있어야 호스트 서비스가 켜지고 스피커 상태를 볼 수 있습니다. QR을 만들 때 요청합니다.",
            style = MaterialTheme.typography.bodySmall, color = RadioOnDarkMuted,
        )
    }
    error?.let { Text(it, color = RadioOnAirRed, style = MaterialTheme.typography.bodySmall) }
    if (busy && busyLabel != null) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(16.dp), color = RadioOnDarkMuted, strokeWidth = 2.dp)
            Text(busyLabel, color = RadioOnDarkMuted, style = MaterialTheme.typography.bodySmall)
        }
    }

    Button(
        enabled = !busy && canGenerate,
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
    if (connectBlocked && !busy) {
        OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("지금 다시 연결") }
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
private fun FreshPairingView(pairing: Pairing, status: HostStatus, onDone: () -> Unit) {
    val context = LocalContext.current
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
    // QR 아래 작은 상태 줄: 브로커에 실제로 붙어 있는지(폰이 스캔해도 호스트가 없으면 소용없다).
    val ready = status is HostStatus.Ready
    val statusLine = when (status) {
        is HostStatus.Ready -> "브로커 연결됨 · 폰에서 스캔을 기다리는 중"
        HostStatus.NotRunning ->
            if (!RemoteHostService.hasForegroundPermission(context)) "블루투스 권한이 없어 리모컨 호스트를 켤 수 없습니다"
            else "리모컨 호스트 시작 중…"
        else -> HostStatusPolicy.detail(status)
    }
    val bad = status is HostStatus.Offline || status is HostStatus.InvalidConfig
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        if (!bad) CircularProgressIndicator(modifier = Modifier.size(16.dp), color = RadioOnDarkMuted, strokeWidth = 2.dp)
        Text(
            "$statusLine · 페어링 ID ${maskPairId(pairing.pairId)}",
            color = if (bad) RadioOnAirRed else RadioOnDarkMuted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    if (!ready && status !is HostStatus.NotRunning && status !is HostStatus.InvalidConfig) {
        TextButton(onClick = { RemoteHostService.retryNow(context) }) { Text("지금 다시 연결", color = RadioOnDarkMuted) }
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
