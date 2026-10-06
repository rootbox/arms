package com.arms.androidauto.remote.guest

import com.arms.androidauto.core.remote.BrokerUrlPolicy
import com.arms.androidauto.core.remote.ConnectionState
import com.arms.androidauto.core.remote.FailureKind
import com.arms.androidauto.core.remote.HostState
import com.arms.androidauto.core.remote.RemoteFailure

// 게스트(리모컨) 화면의 상단 상태 줄이 무엇을 보여줄지 정하는 순수 규칙.
// UI·네트워크와 분리해 단위 테스트로 고정한다.
sealed class GuestStatus {
    object NotPaired : GuestStatus()
    object Connecting : GuestStatus()
    object Offline : GuestStatus()
    // 브로커에는 붙었지만 retained 상태를 아직 받지 못함.
    object NoStateYet : GuestStatus()
    object Live : GuestStatus()
    // 태블릿이 절전 등으로 갱신을 멈춘 상태. 마지막 갱신으로부터 몇 분 지났는지 함께 표시.
    data class Stale(val minutesAgo: Long) : GuestStatus()
    // 호스트가 "연결 종료"를 눌렀다. 페어링은 이미 지워졌고, 다시 쓰려면 새 QR로 페어링해야 한다.
    object Revoked : GuestStatus()
    // 저장된 페어링의 브로커 주소 자체가 쓸 수 없다(예: 2026-10-06 사고의 ws://127.0.0.1). 재시도해도 소용없다.
    data class InvalidConfig(val problem: BrokerUrlPolicy.Problem) : GuestStatus()
    // 브로커에 붙지 못했고 원인을 안다(DNS·거부·TLS·계정 등). 원인 없이 끊긴 Offline과 구분한다.
    data class BrokerUnreachable(val kind: FailureKind) : GuestStatus()
    // 브로커에는 붙었지만 태블릿이 답이 없다(앱 꺼짐·태블릿 오프라인). minutesAgo == null: 받은 신호가 전혀 없음.
    data class HostSilent(val minutesAgo: Long?) : GuestStatus()
}

object GuestStatusPolicy {
    // 이 시간이 지나도록 갱신이 없으면 "오래된 상태"로 본다(플랜 §7).
    const val STALE_AFTER_MS = 2 * 60_000L
    // 명령 후 이 시간 안에 ack가 없으면 "태블릿 응답 없음"(플랜 §5).
    const val ACK_TIMEOUT_MS = 5_000L
    const val NO_ACK_MESSAGE = "태블릿 응답 없음"
    // 리모컨 화면이 열려 있는 동안 호스트에게 "붙어 있음"을 알리는 주기. 호스트는 3분 동안 소식이 없으면 정리한다.
    const val HELLO_INTERVAL_MS = 60_000L
    // 구독을 마친 뒤 이 시간 안에 태블릿 신호(ack 또는 구독 이후 갱신된 상태)가 없으면 "태블릿 응답 없음".
    const val HOST_REPLY_GRACE_MS = 10_000L
    // 마지막 태블릿 신호가 이보다 오래되면 "태블릿 응답 없음". Hello가 60초마다 ack를 받으므로 살아 있는
    // 태블릿이라면 상태 하트비트(5분)가 없어도 신호는 1분 안쪽으로 갱신된다.
    const val HOST_SILENT_AFTER_MS = 3 * 60_000L
    // 브로커 연결 재시도 간격: 3초부터 두 배씩, 30초 상한(3·6·12·24·30·30…).
    const val RETRY_FIRST_DELAY_MS = 3_000L
    const val RETRY_MAX_DELAY_MS = 30_000L
    // 페어링 화면·리모컨 화면 공통: QR에 127.0.0.1 같은 루프백 주소가 들어 있을 때의 설명.
    const val LOOPBACK_PAIRING_MESSAGE = "이 QR에는 태블릿 자신을 가리키는 주소(127.0.0.1)가 들어 있어 연결할 수 없습니다. " +
        "태블릿에서 브로커 주소를 NAS 주소(wss://…)로 바꾼 뒤 QR을 새로 만드세요."
    // 호스트가 연결을 종료했을 때 게스트 화면에 띄우는 안내.
    const val REVOKED_MESSAGE = "호스트가 연결을 종료했습니다. 다시 사용하려면 태블릿에서 QR을 새로 만들어 페어링하세요."
    // Hello에 담는 기기 이름의 최대 길이(모델명 정도만, 사용자 이름은 절대 넣지 않는다).
    const val MAX_GUEST_NAME_LENGTH = 40
    private const val DEFAULT_GUEST_NAME = "Android"

    // 우선순위: Revoked > NotPaired > InvalidConfig > (미연결 + 원인 앎 → BrokerUnreachable) > Connecting/Offline
    //          > (연결됨) HostSilent > NoStateYet/Live/Stale.
    // subscribedAtMs: 구독을 마친 시각(게스트 시계). lastAckAtMs: 마지막 ack를 받은 시각(게스트 시계).
    // hostState.updatedAtMs는 태블릿이 publish한 시각(태블릿 시계)이라 retained 상태는 오래된 값일 수 있다.
    fun compute(
        paired: Boolean,
        connectionState: ConnectionState,
        hostState: HostState?,
        nowMs: Long,
        revoked: Boolean = false,
        configProblem: BrokerUrlPolicy.Problem? = null,
        lastFailure: FailureKind? = null,
        subscribedAtMs: Long? = null,
        lastAckAtMs: Long? = null,
    ): GuestStatus {
        // 호스트가 끊은 뒤에는 연결 상태·페어링 여부보다 안내가 우선이다.
        if (revoked) return GuestStatus.Revoked
        if (!paired) return GuestStatus.NotPaired
        if (configProblem != null) return GuestStatus.InvalidConfig(configProblem)
        if (connectionState != ConnectionState.CONNECTED && lastFailure != null) {
            return GuestStatus.BrokerUnreachable(lastFailure)
        }
        return when (connectionState) {
            ConnectionState.CONNECTING -> GuestStatus.Connecting
            ConnectionState.DISCONNECTED -> GuestStatus.Offline
            ConnectionState.CONNECTED -> {
                hostSilence(hostState, nowMs, subscribedAtMs, lastAckAtMs)?.let { return it }
                if (hostState == null) return GuestStatus.NoStateYet
                val age = nowMs - hostState.updatedAtMs
                if (age >= STALE_AFTER_MS) GuestStatus.Stale(minutesAgo = age / 60_000L) else GuestStatus.Live
            }
        }
    }

    // 브로커는 붙었는데 태블릿이 조용한지. 구독 직후 유예(10초) 동안은 판단하지 않는다.
    private fun hostSilence(hostState: HostState?, nowMs: Long, subscribedAtMs: Long?, lastAckAtMs: Long?): GuestStatus.HostSilent? {
        if (subscribedAtMs == null || nowMs - subscribedAtMs < HOST_REPLY_GRACE_MS) return null
        val stateAt = hostState?.updatedAtMs
        val heardSinceSubscribe = (lastAckAtMs != null && lastAckAtMs >= subscribedAtMs) ||
            (stateAt != null && stateAt >= subscribedAtMs)
        val lastSignal = listOfNotNull(stateAt, lastAckAtMs).maxOrNull()
        val minutes = lastSignal?.let { ((nowMs - it).coerceAtLeast(0L)) / 60_000L }
        if (!heardSinceSubscribe) return GuestStatus.HostSilent(minutes)
        if (lastSignal != null && nowMs - lastSignal >= HOST_SILENT_AFTER_MS) return GuestStatus.HostSilent(minutes)
        return null
    }

    // 상단 상태 줄 문구. 태블릿 = 호스트.
    fun label(status: GuestStatus): String = when (status) {
        GuestStatus.NotPaired -> "태블릿 · 페어링 안 됨"
        GuestStatus.Connecting -> "태블릿 · 연결 중…"
        GuestStatus.Offline -> "태블릿 · 연결 끊김"
        GuestStatus.NoStateYet -> "태블릿 · 연결됨 · 상태 기다리는 중"
        GuestStatus.Live -> "태블릿 · 연결됨"
        is GuestStatus.Stale -> "태블릿 · 마지막 갱신 ${formatMinutesAgo(status.minutesAgo)}"
        GuestStatus.Revoked -> "태블릿 · 호스트가 연결을 종료함"
        is GuestStatus.InvalidConfig -> "설정 오류 · 페어링을 다시 해주세요"
        is GuestStatus.BrokerUnreachable -> "브로커 연결 실패 · ${shortReason(status.kind)}"
        is GuestStatus.HostSilent ->
            if (status.minutesAgo == null) "태블릿 응답 없음 (받은 신호 없음)"
            else "태블릿 응답 없음 (${formatMinutesAgo(status.minutesAgo)} 마지막 신호)"
    }

    // 상태 줄에 들어갈 짧은 원인. 자세한 문구는 RemoteFailure.message.
    fun shortReason(kind: FailureKind): String = when (kind) {
        FailureKind.DNS -> "주소를 찾을 수 없음"
        FailureKind.REFUSED -> "브로커 응답 없음"
        FailureKind.TIMEOUT -> "시간 초과"
        FailureKind.TLS -> "인증서 오류"
        FailureKind.AUTH -> "계정 오류"
        FailureKind.WEBSOCKET -> "웹소켓 거부"
        FailureKind.CLOSED -> "연결 끊김"
        FailureKind.UNKNOWN -> "원인 불명"
    }

    // attempt번째(0부터) 실패 뒤 기다릴 시간.
    fun retryDelayMs(attempt: Int): Long {
        if (attempt <= 0) return RETRY_FIRST_DELAY_MS
        val shift = attempt.coerceAtMost(4)
        return (RETRY_FIRST_DELAY_MS shl shift).coerceAtMost(RETRY_MAX_DELAY_MS)
    }

    // 브로커 주소 문제의 설명. 루프백은 이번 사고 상황(태블릿 QR에 개발 주소)에 맞춘 안내로 바꾼다.
    fun configMessage(problem: BrokerUrlPolicy.Problem): String =
        if (problem == BrokerUrlPolicy.Problem.LOOPBACK) LOOPBACK_PAIRING_MESSAGE else BrokerUrlPolicy.message(problem)

    // 리모컨 화면의 오류 카드(상세 문구 + "다시 연결"/"페어링 다시 하기")를 띄울 상태면 그 문구, 아니면 null.
    fun problemDetail(status: GuestStatus): String? = when (status) {
        is GuestStatus.InvalidConfig -> configMessage(status.problem)
        is GuestStatus.BrokerUnreachable -> RemoteFailure.message(status.kind)
        else -> null
    }

    // Hello에 담을 게스트 이름. Build.MODEL(예: "SM-S937N")을 그대로 쓰되 공백 정리·길이 제한만 한다.
    // 사용자 이름이 섞일 수 있는 값(기기 이름 설정, 계정)은 받지 않는다 — 호출자가 모델명만 넘긴다.
    fun guestName(model: String?, manufacturer: String? = null): String {
        val raw = model?.takeIf { it.isNotBlank() } ?: manufacturer?.takeIf { it.isNotBlank() } ?: DEFAULT_GUEST_NAME
        val cleaned = raw
            .map { if (it.isWhitespace()) ' ' else it } // 탭·줄바꿈은 공백으로(제어 문자로 지워지기 전에)
            .filter { !it.isISOControl() }
            .joinToString("")
            .trim()
            .replace(Regex(" +"), " ")
        if (cleaned.isEmpty()) return DEFAULT_GUEST_NAME
        return cleaned.take(MAX_GUEST_NAME_LENGTH)
    }

    // 볼륨 명령 값. 슬라이더가 0..100 밖의 값을 만들지는 않지만 프로토콜 경계를 여기서 한 번 더 지킨다.
    fun clampVolume(percent: Int): Int = percent.coerceIn(0, 100)

    // "artworkRef" 해석. "station:<id>" → 번들 채널 아트 id, http(s) URL → 공개 커버, 그 외 → null.
    sealed class Artwork {
        data class Station(val stationId: String) : Artwork()
        data class Url(val url: String) : Artwork()
    }

    fun parseArtworkRef(ref: String?): Artwork? {
        if (ref.isNullOrBlank()) return null
        if (ref.startsWith("station:")) {
            val id = ref.removePrefix("station:").trim()
            return if (id.isEmpty()) null else Artwork.Station(id)
        }
        if (ref.startsWith("https://") || ref.startsWith("http://")) return Artwork.Url(ref)
        return null
    }

    // 상대 시각. 1분 미만은 "방금 전", 60분 이상은 시간 단위.
    fun formatMinutesAgo(minutes: Long): String = when {
        minutes < 1L -> "방금 전"
        minutes < 60L -> "${minutes}분 전"
        else -> "${minutes / 60L}시간 전"
    }

    fun formatAgo(thenMs: Long?, nowMs: Long): String? {
        if (thenMs == null) return null
        val diff = nowMs - thenMs
        if (diff < 0L) return "방금 전"
        return formatMinutesAgo(diff / 60_000L)
    }

    // 블루투스 카드 한 줄 문구.
    fun bluetoothLabel(connected: Boolean, deviceName: String?, changedAtMs: Long?, nowMs: Long): String {
        return if (connected) {
            val name = deviceName?.takeIf { it.isNotBlank() } ?: "이름 없음"
            "블루투스 스피커: 연결됨 · $name"
        } else {
            val ago = formatAgo(changedAtMs, nowMs)
            if (ago == null) "블루투스 스피커: 끊김" else "블루투스 스피커: 끊김 ($ago)"
        }
    }

    // 명령 전송 가능 여부: 연결돼 있고, 앞선 명령의 ack를 기다리는 중이 아닐 때만.
    fun canSend(connectionState: ConnectionState, pendingSeq: Long?): Boolean =
        connectionState == ConnectionState.CONNECTED && pendingSeq == null

    // 화면 기준 전송 가능 여부: 설정 오류·브로커 연결 실패 상태에서는 연결 상태와 무관하게 컨트롤을 잠근다.
    fun canSend(status: GuestStatus, connectionState: ConnectionState, pendingSeq: Long?): Boolean =
        status !is GuestStatus.InvalidConfig && status !is GuestStatus.BrokerUnreachable &&
            canSend(connectionState, pendingSeq)
}
