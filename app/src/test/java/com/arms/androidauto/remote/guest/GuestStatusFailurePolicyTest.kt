package com.arms.androidauto.remote.guest

import com.arms.androidauto.core.remote.BrokerUrlPolicy
import com.arms.androidauto.core.remote.ConnectionState
import com.arms.androidauto.core.remote.FailureKind
import com.arms.androidauto.core.remote.HostState
import com.arms.androidauto.core.remote.RemoteFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// 2026-10-06 페어링 사고 이후 추가된 상태: 설정 오류 / 브로커 연결 실패 / 태블릿 응답 없음.
class GuestStatusFailurePolicyTest {
    private val now = 1_000_000_000L
    private val sub = now - 60_000L // 1분 전에 구독 완료

    private fun state(updatedAtMs: Long) = HostState(
        mediaId = "4",
        title = "발라드",
        artist = null,
        isPlaying = true,
        playbackState = 3,
        artworkRef = "station:4",
        bluetooth = null,
        batteryPercent = 80,
        updatedAtMs = updatedAtMs,
    )

    private fun compute(
        paired: Boolean = true,
        connection: ConnectionState = ConnectionState.CONNECTED,
        host: HostState? = state(now),
        revoked: Boolean = false,
        config: BrokerUrlPolicy.Problem? = null,
        failure: FailureKind? = null,
        subscribedAt: Long? = null,
        ackAt: Long? = null,
        nowMs: Long = now,
    ) = GuestStatusPolicy.compute(
        paired = paired,
        connectionState = connection,
        hostState = host,
        nowMs = nowMs,
        revoked = revoked,
        configProblem = config,
        lastFailure = failure,
        subscribedAtMs = subscribedAt,
        lastAckAtMs = ackAt,
    )

    // ---- InvalidConfig ----

    @Test fun `저장된 주소가 규칙 위반이면 연결 상태와 무관하게 INVALID_CONFIG`() {
        for (c in ConnectionState.values()) {
            assertEquals(
                GuestStatus.InvalidConfig(BrokerUrlPolicy.Problem.LOOPBACK),
                compute(connection = c, config = BrokerUrlPolicy.Problem.LOOPBACK, failure = FailureKind.REFUSED),
            )
        }
    }

    @Test fun `우선순위 - REVOKED, NOT_PAIRED가 INVALID_CONFIG보다 앞선다`() {
        assertEquals(GuestStatus.Revoked, compute(revoked = true, config = BrokerUrlPolicy.Problem.LOOPBACK))
        assertEquals(GuestStatus.NotPaired, compute(paired = false, config = BrokerUrlPolicy.Problem.LOOPBACK))
    }

    // ---- BrokerUnreachable ----

    @Test fun `미연결이고 원인을 알면 BROKER_UNREACHABLE (끊김·연결 중 모두)`() {
        assertEquals(
            GuestStatus.BrokerUnreachable(FailureKind.DNS),
            compute(connection = ConnectionState.DISCONNECTED, failure = FailureKind.DNS),
        )
        assertEquals(
            GuestStatus.BrokerUnreachable(FailureKind.TLS),
            compute(connection = ConnectionState.CONNECTING, failure = FailureKind.TLS, host = null),
        )
    }

    @Test fun `원인을 모르면 기존 OFFLINE·CONNECTING 그대로`() {
        assertEquals(GuestStatus.Offline, compute(connection = ConnectionState.DISCONNECTED))
        assertEquals(GuestStatus.Connecting, compute(connection = ConnectionState.CONNECTING))
    }

    @Test fun `연결됐으면 남아 있는 실패 원인은 무시`() {
        assertEquals(GuestStatus.Live, compute(failure = FailureKind.REFUSED))
    }

    @Test fun `우선순위 - INVALID_CONFIG가 BROKER_UNREACHABLE보다 앞선다`() {
        assertEquals(
            GuestStatus.InvalidConfig(BrokerUrlPolicy.Problem.PLAINTEXT_PUBLIC),
            compute(
                connection = ConnectionState.DISCONNECTED,
                config = BrokerUrlPolicy.Problem.PLAINTEXT_PUBLIC,
                failure = FailureKind.DNS,
            ),
        )
        assertEquals(GuestStatus.Revoked, compute(connection = ConnectionState.DISCONNECTED, revoked = true, failure = FailureKind.DNS))
        assertEquals(GuestStatus.NotPaired, compute(connection = ConnectionState.DISCONNECTED, paired = false, failure = FailureKind.DNS))
    }

    // ---- HostSilent ----

    @Test fun `구독 시각을 모르면 HOST_SILENT 판단을 하지 않는다(기존 호출 호환)`() {
        assertEquals(GuestStatus.NoStateYet, compute(host = null))
        assertEquals(GuestStatus.Stale(10), compute(host = state(now - 10 * 60_000L)))
    }

    @Test fun `구독 후 10초 유예 동안은 신호가 없어도 기다린다`() {
        val justSubscribed = now - 9_999L
        assertEquals(GuestStatus.NoStateYet, compute(host = null, subscribedAt = justSubscribed))
        // retained 상태가 오래돼도 유예 중에는 기존 STALE 그대로.
        assertEquals(GuestStatus.Stale(10), compute(host = state(now - 10 * 60_000L), subscribedAt = justSubscribed))
    }

    @Test fun `구독 후 10초가 지나도 아무 신호가 없으면 HOST_SILENT(null)`() {
        assertEquals(GuestStatus.HostSilent(null), compute(host = null, subscribedAt = now - 10_000L))
    }

    @Test fun `retained 상태만 있고 구독 이후 응답이 없으면 HOST_SILENT(마지막 신호 분)`() {
        // 태블릿이 꺼지기 직전(구독 전) 남긴 상태. 1분 전이라도 구독 이후 답이 없으면 응답 없음.
        val oldState = state(sub - 30_000L) // 1분 30초 전
        assertEquals(GuestStatus.HostSilent(1), compute(host = oldState, subscribedAt = sub))
        // 구독 전 ack(이전 세션)도 "구독 이후 신호"로 치지 않는다.
        assertEquals(GuestStatus.HostSilent(1), compute(host = oldState, subscribedAt = sub, ackAt = sub - 1L))
    }

    @Test fun `구독 이후 ack가 오면 상태가 오래돼도 HOST_SILENT가 아니다(하트비트 5분 사이 정상)`() {
        // 태블릿은 상태가 안 바뀌면 5분마다만 publish 한다. Hello ack가 살아 있음을 증명한다.
        val st = state(now - 2 * 60_000L - 30_000L)
        assertEquals(GuestStatus.Stale(2), compute(host = st, subscribedAt = sub, ackAt = now - 20_000L))
    }

    @Test fun `구독 이후 갱신된 상태가 오면 ack 없이도 살아 있음`() {
        assertEquals(GuestStatus.Live, compute(host = state(sub + 500L), subscribedAt = sub))
        assertEquals(GuestStatus.NoStateYet, compute(host = null, subscribedAt = sub, ackAt = sub + 300L))
    }

    @Test fun `마지막 신호(ack·상태 중 최신)가 3분 이상 지나면 HOST_SILENT`() {
        val longAgoSub = now - 30 * 60_000L
        // ack 2분 59초 전, 상태 10분 전 → 아직 응답 중(오래된 상태일 뿐)
        assertEquals(
            GuestStatus.Stale(10),
            compute(host = state(now - 10 * 60_000L), subscribedAt = longAgoSub, ackAt = now - 179_999L),
        )
        // ack 3분 전 → 응답 없음, 분은 최신 신호 기준
        assertEquals(
            GuestStatus.HostSilent(3),
            compute(host = state(now - 10 * 60_000L), subscribedAt = longAgoSub, ackAt = now - 180_000L),
        )
        // 상태가 ack보다 최신이면 상태 기준
        assertEquals(
            GuestStatus.HostSilent(4),
            compute(host = state(now - 4 * 60_000L), subscribedAt = longAgoSub, ackAt = now - 20 * 60_000L),
        )
    }

    @Test fun `HOST_SILENT는 연결됐을 때만 - 끊기면 OFFLINE·BROKER_UNREACHABLE가 앞선다`() {
        assertEquals(GuestStatus.Offline, compute(connection = ConnectionState.DISCONNECTED, host = null, subscribedAt = sub))
        assertEquals(
            GuestStatus.BrokerUnreachable(FailureKind.CLOSED),
            compute(connection = ConnectionState.CONNECTING, host = null, subscribedAt = sub, failure = FailureKind.CLOSED),
        )
        assertEquals(GuestStatus.Revoked, compute(host = null, subscribedAt = sub, revoked = true))
        assertEquals(
            GuestStatus.InvalidConfig(BrokerUrlPolicy.Problem.LOOPBACK),
            compute(host = null, subscribedAt = sub, config = BrokerUrlPolicy.Problem.LOOPBACK),
        )
    }

    @Test fun `시계가 어긋나 미래 신호여도 음수 분이 나오지 않는다`() {
        val longAgoSub = now - 30 * 60_000L
        assertEquals(GuestStatus.Live, compute(host = state(now + 5_000L), subscribedAt = longAgoSub))
    }

    // ---- 문구 ----

    @Test fun `새 상태 줄 문구`() {
        assertEquals("설정 오류 · 페어링을 다시 해주세요", GuestStatusPolicy.label(GuestStatus.InvalidConfig(BrokerUrlPolicy.Problem.LOOPBACK)))
        assertEquals("브로커 연결 실패 · 주소를 찾을 수 없음", GuestStatusPolicy.label(GuestStatus.BrokerUnreachable(FailureKind.DNS)))
        assertEquals("브로커 연결 실패 · 인증서 오류", GuestStatusPolicy.label(GuestStatus.BrokerUnreachable(FailureKind.TLS)))
        assertEquals("태블릿 응답 없음 (5분 전 마지막 신호)", GuestStatusPolicy.label(GuestStatus.HostSilent(5)))
        assertEquals("태블릿 응답 없음 (방금 전 마지막 신호)", GuestStatusPolicy.label(GuestStatus.HostSilent(0)))
        assertEquals("태블릿 응답 없음 (받은 신호 없음)", GuestStatusPolicy.label(GuestStatus.HostSilent(null)))
    }

    @Test fun `모든 실패 원인에 짧은 문구가 있다`() {
        for (k in FailureKind.values()) {
            val reason = GuestStatusPolicy.shortReason(k)
            assertTrue(k.name, reason.isNotBlank() && reason.length <= 12)
        }
    }

    @Test fun `루프백은 사고 상황 안내, 나머지는 BrokerUrlPolicy 문구`() {
        assertEquals(
            "이 QR에는 태블릿 자신을 가리키는 주소(127.0.0.1)가 들어 있어 연결할 수 없습니다. " +
                "태블릿에서 브로커 주소를 NAS 주소(wss://…)로 바꾼 뒤 QR을 새로 만드세요.",
            GuestStatusPolicy.configMessage(BrokerUrlPolicy.Problem.LOOPBACK),
        )
        for (p in BrokerUrlPolicy.Problem.values().filter { it != BrokerUrlPolicy.Problem.LOOPBACK }) {
            assertEquals(BrokerUrlPolicy.message(p), GuestStatusPolicy.configMessage(p))
        }
    }

    @Test fun `오류 카드는 설정 오류·브로커 실패에서만, 상세 문구와 함께`() {
        assertEquals(
            GuestStatusPolicy.LOOPBACK_PAIRING_MESSAGE,
            GuestStatusPolicy.problemDetail(GuestStatus.InvalidConfig(BrokerUrlPolicy.Problem.LOOPBACK)),
        )
        assertEquals(
            RemoteFailure.message(FailureKind.AUTH),
            GuestStatusPolicy.problemDetail(GuestStatus.BrokerUnreachable(FailureKind.AUTH)),
        )
        val noCard = listOf(
            GuestStatus.NotPaired, GuestStatus.Connecting, GuestStatus.Offline, GuestStatus.NoStateYet,
            GuestStatus.Live, GuestStatus.Stale(3), GuestStatus.Revoked, GuestStatus.HostSilent(4), GuestStatus.HostSilent(null),
        )
        for (s in noCard) assertNull(s.toString(), GuestStatusPolicy.problemDetail(s))
    }

    // ---- 전송 가능 여부 ----

    @Test fun `설정 오류·브로커 실패에서는 컨트롤을 잠근다`() {
        val c = ConnectionState.CONNECTED
        assertFalse(GuestStatusPolicy.canSend(GuestStatus.InvalidConfig(BrokerUrlPolicy.Problem.LOOPBACK), c, null))
        assertFalse(GuestStatusPolicy.canSend(GuestStatus.BrokerUnreachable(FailureKind.REFUSED), c, null))
        assertTrue(GuestStatusPolicy.canSend(GuestStatus.Live, c, null))
        // 태블릿 응답 없음은 잠그지 않는다(누르면 "태블릿 응답 없음" ack 타임아웃으로 알려 준다).
        assertTrue(GuestStatusPolicy.canSend(GuestStatus.HostSilent(4), c, null))
        assertFalse(GuestStatusPolicy.canSend(GuestStatus.Live, c, 7L))
        assertFalse(GuestStatusPolicy.canSend(GuestStatus.Live, ConnectionState.CONNECTING, null))
    }

    // ---- 재시도 간격 ----

    @Test fun `재시도 간격은 3·6·12·24초 뒤 30초 상한`() {
        assertEquals(
            listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L, 30_000L),
            (0..6).map { GuestStatusPolicy.retryDelayMs(it) },
        )
        assertEquals(30_000L, GuestStatusPolicy.retryDelayMs(1_000))
        assertEquals(3_000L, GuestStatusPolicy.retryDelayMs(-1))
    }

    @Test fun `태블릿 응답 기준값`() {
        assertEquals(10_000L, GuestStatusPolicy.HOST_REPLY_GRACE_MS)
        assertEquals(3 * 60_000L, GuestStatusPolicy.HOST_SILENT_AFTER_MS)
        // 살아 있는 태블릿은 Hello ack로 1분마다 신호를 준다 — 응답 없음 기준보다 충분히 짧아야 한다.
        assertTrue(GuestStatusPolicy.HELLO_INTERVAL_MS * 2 < GuestStatusPolicy.HOST_SILENT_AFTER_MS)
    }
}
