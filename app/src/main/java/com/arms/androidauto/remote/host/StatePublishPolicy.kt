package com.arms.androidauto.remote.host

import com.arms.androidauto.core.remote.HostState

// "보이는 값"만 뽑은 지문. 이 값이 그대로면 상태를 다시 publish 하지 않는다.
// updatedAtMs는 제외(매번 바뀌므로), 배터리는 5% 단위로 뭉개서 1%마다 publish 하지 않게 한다.
data class StateFingerprint(
    val mediaId: String?,
    val title: String?,
    val artist: String?,
    val isPlaying: Boolean,
    val playbackState: Int,
    val artworkRef: String?,
    val btConnected: Boolean,
    val btDeviceName: String?,
    val batteryBucket: Int?,
    // 채널 목록이 나중에(DB 로드 뒤) 도착해도 게스트가 받도록 목록 변화도 지문에 넣는다.
    val itemsKey: Int,
)

sealed class PublishDecision {
    // 바뀐 것이 없고 하트비트 시각도 아니다.
    object Skip : PublishDecision()
    // 지금 바로 publish (첫 상태·강제 새로고침·하트비트).
    object Now : PublishDecision()
    // 값이 바뀌었다. 연달아 오는 이벤트(전환 직후 BUFFERING→READY→메타데이터)를 묶기 위해 debounce 뒤 publish.
    object Debounced : PublishDecision()
}

// 언제 publish 할지 결정만 한다(타이머·전송은 서비스 몫). 순수 Kotlin.
class StatePublishPolicy(
    val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    val heartbeatMs: Long = DEFAULT_HEARTBEAT_MS,
) {

    fun decide(
        lastPublished: StateFingerprint?,
        next: StateFingerprint,
        lastPublishedAtMs: Long?,
        nowMs: Long,
        forced: Boolean = false,
    ): PublishDecision {
        if (forced) return PublishDecision.Now
        if (lastPublished == null || lastPublishedAtMs == null) return PublishDecision.Now
        if (next != lastPublished) return PublishDecision.Debounced
        if (isHeartbeatDue(lastPublishedAtMs, nowMs)) return PublishDecision.Now
        return PublishDecision.Skip
    }

    fun isHeartbeatDue(lastPublishedAtMs: Long?, nowMs: Long): Boolean =
        lastPublishedAtMs == null || nowMs - lastPublishedAtMs >= heartbeatMs

    // 다음 하트비트까지 기다릴 시간(0 이상).
    fun heartbeatDelayMs(lastPublishedAtMs: Long?, nowMs: Long): Long {
        if (lastPublishedAtMs == null) return 0L
        return (lastPublishedAtMs + heartbeatMs - nowMs).coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MS = 500L
        const val DEFAULT_HEARTBEAT_MS = 5 * 60_000L
        const val BATTERY_BUCKET_PERCENT = 5

        fun batteryBucket(percent: Int?): Int? =
            percent?.coerceIn(0, 100)?.let { it / BATTERY_BUCKET_PERCENT * BATTERY_BUCKET_PERCENT }

        fun fingerprintOf(state: HostState): StateFingerprint = StateFingerprint(
            mediaId = state.mediaId,
            title = state.title,
            artist = state.artist,
            isPlaying = state.isPlaying,
            playbackState = state.playbackState,
            artworkRef = state.artworkRef,
            btConnected = state.bluetooth?.connected == true,
            btDeviceName = state.bluetooth?.deviceName,
            batteryBucket = batteryBucket(state.batteryPercent),
            itemsKey = state.items.hashCode(),
        )
    }
}
