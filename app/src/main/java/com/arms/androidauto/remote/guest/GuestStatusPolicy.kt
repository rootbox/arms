package com.arms.androidauto.remote.guest

import com.arms.androidauto.core.remote.ConnectionState
import com.arms.androidauto.core.remote.HostState

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
}

object GuestStatusPolicy {
    // 이 시간이 지나도록 갱신이 없으면 "오래된 상태"로 본다(플랜 §7).
    const val STALE_AFTER_MS = 2 * 60_000L
    // 명령 후 이 시간 안에 ack가 없으면 "태블릿 응답 없음"(플랜 §5).
    const val ACK_TIMEOUT_MS = 5_000L
    const val NO_ACK_MESSAGE = "태블릿 응답 없음"

    fun compute(
        paired: Boolean,
        connectionState: ConnectionState,
        hostState: HostState?,
        nowMs: Long,
    ): GuestStatus {
        if (!paired) return GuestStatus.NotPaired
        return when (connectionState) {
            ConnectionState.CONNECTING -> GuestStatus.Connecting
            ConnectionState.DISCONNECTED -> GuestStatus.Offline
            ConnectionState.CONNECTED -> {
                if (hostState == null) return GuestStatus.NoStateYet
                val age = nowMs - hostState.updatedAtMs
                if (age >= STALE_AFTER_MS) GuestStatus.Stale(minutesAgo = age / 60_000L) else GuestStatus.Live
            }
        }
    }

    // 상단 상태 줄 문구. 태블릿 = 호스트.
    fun label(status: GuestStatus): String = when (status) {
        GuestStatus.NotPaired -> "태블릿 · 페어링 안 됨"
        GuestStatus.Connecting -> "태블릿 · 연결 중…"
        GuestStatus.Offline -> "태블릿 · 연결 끊김"
        GuestStatus.NoStateYet -> "태블릿 · 연결됨 · 상태 기다리는 중"
        GuestStatus.Live -> "태블릿 · 연결됨"
        is GuestStatus.Stale -> "태블릿 · 마지막 갱신 ${formatMinutesAgo(status.minutesAgo)}"
    }

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
}
