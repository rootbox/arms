package com.arms.androidauto.remote.host

import com.arms.androidauto.core.remote.BrokerUrlPolicy
import com.arms.androidauto.core.remote.ConnectionState
import com.arms.androidauto.core.remote.FailureKind
import com.arms.androidauto.core.remote.RemoteFailure
import com.arms.androidauto.core.remote.RemoteGuest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStatusPolicyTest {

    private val guest = RemoteGuest("Galaxy S22", 1_000L)

    private fun compute(
        running: Boolean = true,
        configProblem: BrokerUrlPolicy.Problem? = null,
        brokerState: ConnectionState = ConnectionState.DISCONNECTED,
        lastFailure: FailureKind? = null,
        failingSinceMs: Long? = null,
        guests: List<RemoteGuest> = emptyList(),
        attempt: Int = 0,
    ) = HostStatusPolicy.compute(running, configProblem, brokerState, lastFailure, failingSinceMs, guests, attempt)

    @Test
    fun notRunningWinsOverEverything() {
        assertEquals(
            HostStatus.NotRunning,
            compute(
                running = false, configProblem = BrokerUrlPolicy.Problem.LOOPBACK,
                brokerState = ConnectionState.CONNECTED, guests = listOf(guest),
            ),
        )
    }

    @Test
    fun configProblemIsInvalidConfigEvenIfConnected() {
        assertEquals(
            HostStatus.InvalidConfig(BrokerUrlPolicy.Problem.LOOPBACK),
            compute(configProblem = BrokerUrlPolicy.Problem.LOOPBACK, brokerState = ConnectionState.CONNECTED),
        )
    }

    @Test
    fun connectedIsReadyWithGuests() {
        assertEquals(HostStatus.Ready(emptyList()), compute(brokerState = ConnectionState.CONNECTED))
        assertEquals(
            HostStatus.Ready(listOf(guest)),
            compute(brokerState = ConnectionState.CONNECTED, guests = listOf(guest), lastFailure = FailureKind.DNS, failingSinceMs = 5L, attempt = 9),
        )
    }

    @Test
    fun beforeAnyFailureIsConnecting() {
        assertEquals(HostStatus.Connecting(1, null), compute(brokerState = ConnectionState.CONNECTING, attempt = 1))
        // 시도 전(attempt 0)도 1로 보인다.
        assertEquals(HostStatus.Connecting(1, null), compute(attempt = 0))
        // 실패 기록이 없으면 시도가 많아도 오프라인이 아니다.
        assertEquals(HostStatus.Connecting(7, null), compute(attempt = 7))
    }

    @Test
    fun firstFailuresStayConnectingWithReason() {
        assertEquals(
            HostStatus.Connecting(2, FailureKind.REFUSED),
            compute(brokerState = ConnectionState.CONNECTING, lastFailure = FailureKind.REFUSED, failingSinceMs = 100L, attempt = 2),
        )
        // 붙어 있다가 끊긴 직후(attempt 1, 이유 CLOSED)도 "다시 연결 중".
        assertEquals(
            HostStatus.Connecting(1, FailureKind.CLOSED),
            compute(lastFailure = FailureKind.CLOSED, failingSinceMs = 100L, attempt = 1),
        )
    }

    @Test
    fun repeatedFailuresAreOffline() {
        assertEquals(
            HostStatus.Offline(FailureKind.TIMEOUT, 100L),
            compute(brokerState = ConnectionState.CONNECTING, lastFailure = FailureKind.TIMEOUT, failingSinceMs = 100L, attempt = 3),
        )
        assertEquals(
            HostStatus.Offline(null, 100L),
            compute(failingSinceMs = 100L, attempt = HostStatusPolicy.CONNECTING_ATTEMPTS + 1),
        )
    }

    @Test
    fun detailTexts() {
        assertEquals("리모컨 호스트가 꺼져 있습니다.", HostStatusPolicy.detail(HostStatus.NotRunning))
        assertEquals(
            BrokerUrlPolicy.message(BrokerUrlPolicy.Problem.LOOPBACK),
            HostStatusPolicy.detail(HostStatus.InvalidConfig(BrokerUrlPolicy.Problem.LOOPBACK)),
        )
        assertEquals("브로커에 연결하는 중…", HostStatusPolicy.detail(HostStatus.Connecting(1, null)))
        assertTrue(HostStatusPolicy.detail(HostStatus.Connecting(2, FailureKind.DNS)).contains(RemoteFailure.message(FailureKind.DNS)))
        assertEquals(RemoteFailure.message(FailureKind.TLS), HostStatusPolicy.detail(HostStatus.Offline(FailureKind.TLS, 0L), nowMs = 59_999L))
        assertEquals(
            RemoteFailure.message(FailureKind.TLS) + " (12분째)",
            HostStatusPolicy.detail(HostStatus.Offline(FailureKind.TLS, 0L), nowMs = 12 * 60_000L + 5L),
        )
        assertEquals(RemoteFailure.message(FailureKind.UNKNOWN), HostStatusPolicy.detail(HostStatus.Offline(null, 0L), nowMs = 0L))
        assertEquals("브로커 연결됨", HostStatusPolicy.detail(HostStatus.Ready(listOf(guest))))
    }
}
