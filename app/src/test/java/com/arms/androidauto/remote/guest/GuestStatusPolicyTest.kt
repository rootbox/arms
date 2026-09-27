package com.arms.androidauto.remote.guest

import com.arms.androidauto.core.remote.BluetoothStatus
import com.arms.androidauto.core.remote.ConnectionState
import com.arms.androidauto.core.remote.HostState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestStatusPolicyTest {
    private val now = 1_000_000_000L

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

    @Test fun `페어링이 없으면 연결 상태와 무관하게 NOT_PAIRED`() {
        assertEquals(GuestStatus.NotPaired, GuestStatusPolicy.compute(false, ConnectionState.CONNECTED, state(now), now))
        assertEquals(GuestStatus.NotPaired, GuestStatusPolicy.compute(false, ConnectionState.DISCONNECTED, null, now))
    }

    @Test fun `연결 중이면 CONNECTING, 끊기면 OFFLINE(상태가 있어도)`() {
        assertEquals(GuestStatus.Connecting, GuestStatusPolicy.compute(true, ConnectionState.CONNECTING, null, now))
        assertEquals(GuestStatus.Connecting, GuestStatusPolicy.compute(true, ConnectionState.CONNECTING, state(now), now))
        assertEquals(GuestStatus.Offline, GuestStatusPolicy.compute(true, ConnectionState.DISCONNECTED, state(now), now))
    }

    @Test fun `연결됐지만 retained 상태가 아직 없으면 NO_STATE_YET`() {
        assertEquals(GuestStatus.NoStateYet, GuestStatusPolicy.compute(true, ConnectionState.CONNECTED, null, now))
    }

    @Test fun `갱신 2분 미만이면 LIVE, 2분 이상이면 STALE(분 단위)`() {
        assertEquals(GuestStatus.Live, GuestStatusPolicy.compute(true, ConnectionState.CONNECTED, state(now), now))
        assertEquals(GuestStatus.Live, GuestStatusPolicy.compute(true, ConnectionState.CONNECTED, state(now - 119_999L), now))
        assertEquals(GuestStatus.Stale(2), GuestStatusPolicy.compute(true, ConnectionState.CONNECTED, state(now - 120_000L), now))
        assertEquals(GuestStatus.Stale(3), GuestStatusPolicy.compute(true, ConnectionState.CONNECTED, state(now - 3 * 60_000L - 5_000L), now))
        assertEquals(GuestStatus.Stale(90), GuestStatusPolicy.compute(true, ConnectionState.CONNECTED, state(now - 90 * 60_000L), now))
    }

    @Test fun `상태 줄 문구`() {
        assertEquals("태블릿 · 연결됨", GuestStatusPolicy.label(GuestStatus.Live))
        assertEquals("태블릿 · 연결 중…", GuestStatusPolicy.label(GuestStatus.Connecting))
        assertEquals("태블릿 · 연결 끊김", GuestStatusPolicy.label(GuestStatus.Offline))
        assertEquals("태블릿 · 마지막 갱신 3분 전", GuestStatusPolicy.label(GuestStatus.Stale(3)))
        assertEquals("태블릿 · 마지막 갱신 2시간 전", GuestStatusPolicy.label(GuestStatus.Stale(125)))
        assertEquals("태블릿 · 페어링 안 됨", GuestStatusPolicy.label(GuestStatus.NotPaired))
    }

    @Test fun `artworkRef 해석 - station 접두사는 채널 id, URL은 그대로, 그 외는 null`() {
        assertEquals(GuestStatusPolicy.Artwork.Station("4"), GuestStatusPolicy.parseArtworkRef("station:4"))
        assertEquals(GuestStatusPolicy.Artwork.Station("kbs1"), GuestStatusPolicy.parseArtworkRef("station:kbs1"))
        assertEquals(GuestStatusPolicy.Artwork.Url("https://img.example/a.jpg"), GuestStatusPolicy.parseArtworkRef("https://img.example/a.jpg"))
        assertNull(GuestStatusPolicy.parseArtworkRef("station:"))
        assertNull(GuestStatusPolicy.parseArtworkRef(null))
        assertNull(GuestStatusPolicy.parseArtworkRef(""))
        // NAS 커버는 sid가 들어갈 수 있는 URL을 주지 않기로 했으므로 임의 문자열은 버린다.
        assertNull(GuestStatusPolicy.parseArtworkRef("nas:abc123"))
        assertNull(GuestStatusPolicy.parseArtworkRef("file:///sdcard/x.jpg"))
    }

    @Test fun `상대 시각 문구 - 방금 전, n분 전, n시간 전`() {
        assertEquals("방금 전", GuestStatusPolicy.formatMinutesAgo(0))
        assertEquals("1분 전", GuestStatusPolicy.formatMinutesAgo(1))
        assertEquals("59분 전", GuestStatusPolicy.formatMinutesAgo(59))
        assertEquals("1시간 전", GuestStatusPolicy.formatMinutesAgo(60))
        assertEquals("3시간 전", GuestStatusPolicy.formatMinutesAgo(200))
        assertEquals("방금 전", GuestStatusPolicy.formatAgo(now - 30_000L, now))
        assertEquals("5분 전", GuestStatusPolicy.formatAgo(now - 5 * 60_000L - 10L, now))
        // 미래 시각(시계 차이)은 음수가 되지 않게 "방금 전"
        assertEquals("방금 전", GuestStatusPolicy.formatAgo(now + 60_000L, now))
        assertNull(GuestStatusPolicy.formatAgo(null, now))
    }

    @Test fun `블루투스 카드 문구`() {
        assertEquals(
            "블루투스 스피커: 연결됨 · JBL Flip",
            GuestStatusPolicy.bluetoothLabel(connected = true, deviceName = "JBL Flip", changedAtMs = now, nowMs = now),
        )
        assertEquals(
            "블루투스 스피커: 연결됨 · 이름 없음",
            GuestStatusPolicy.bluetoothLabel(connected = true, deviceName = " ", changedAtMs = null, nowMs = now),
        )
        assertEquals(
            "블루투스 스피커: 끊김 (7분 전)",
            GuestStatusPolicy.bluetoothLabel(connected = false, deviceName = "JBL Flip", changedAtMs = now - 7 * 60_000L, nowMs = now),
        )
        assertEquals(
            "블루투스 스피커: 끊김",
            GuestStatusPolicy.bluetoothLabel(connected = false, deviceName = null, changedAtMs = null, nowMs = now),
        )
        val bt = BluetoothStatus(connected = false, deviceName = "X", changedAtMs = now - 61 * 60_000L)
        assertEquals("블루투스 스피커: 끊김 (1시간 전)", GuestStatusPolicy.bluetoothLabel(bt.connected, bt.deviceName, bt.changedAtMs, now))
    }

    @Test fun `명령 전송은 연결됐고 대기 중인 ack가 없을 때만`() {
        assertTrue(GuestStatusPolicy.canSend(ConnectionState.CONNECTED, pendingSeq = null))
        assertFalse(GuestStatusPolicy.canSend(ConnectionState.CONNECTED, pendingSeq = 7L))
        assertFalse(GuestStatusPolicy.canSend(ConnectionState.CONNECTING, pendingSeq = null))
        assertFalse(GuestStatusPolicy.canSend(ConnectionState.DISCONNECTED, pendingSeq = null))
    }

    @Test fun `타임아웃과 오래됨 기준은 플랜 값과 같다`() {
        assertEquals(5_000L, GuestStatusPolicy.ACK_TIMEOUT_MS)
        assertEquals(120_000L, GuestStatusPolicy.STALE_AFTER_MS)
        assertEquals("태블릿 응답 없음", GuestStatusPolicy.NO_ACK_MESSAGE)
    }

    @Test fun `Hello 주기는 60초, 호스트 정리 기준(3분)보다 짧다`() {
        assertEquals(60_000L, GuestStatusPolicy.HELLO_INTERVAL_MS)
        assertTrue(GuestStatusPolicy.HELLO_INTERVAL_MS < 3 * 60_000L)
    }

    @Test fun `호스트가 연결을 종료하면 다른 조건보다 REVOKED가 우선`() {
        // 페어링이 지워진 뒤(paired=false)에도, 아직 연결돼 있는 순간에도 안내가 우선이다.
        assertEquals(GuestStatus.Revoked, GuestStatusPolicy.compute(false, ConnectionState.DISCONNECTED, null, now, revoked = true))
        assertEquals(GuestStatus.Revoked, GuestStatusPolicy.compute(true, ConnectionState.CONNECTED, state(now), now, revoked = true))
        assertEquals(GuestStatus.Revoked, GuestStatusPolicy.compute(true, ConnectionState.CONNECTING, null, now, revoked = true))
        // 기본값(revoked=false)은 기존 동작 그대로.
        assertEquals(GuestStatus.Live, GuestStatusPolicy.compute(true, ConnectionState.CONNECTED, state(now), now))
        assertEquals("태블릿 · 호스트가 연결을 종료함", GuestStatusPolicy.label(GuestStatus.Revoked))
        assertTrue(GuestStatusPolicy.REVOKED_MESSAGE.contains("QR"))
    }

    @Test fun `게스트 이름은 모델명 그대로, 공백 정리와 40자 제한만`() {
        assertEquals("SM-S937N", GuestStatusPolicy.guestName("SM-S937N"))
        assertEquals("SM-S937N", GuestStatusPolicy.guestName("  SM-S937N \n"))
        assertEquals("Pixel 8 Pro", GuestStatusPolicy.guestName("Pixel   8\tPro"))
        // 모델명이 비면 제조사, 그것도 없으면 기본값.
        assertEquals("samsung", GuestStatusPolicy.guestName("", "samsung"))
        assertEquals("Android", GuestStatusPolicy.guestName(null, null))
        assertEquals("Android", GuestStatusPolicy.guestName(" ", " "))
        // 제어 문자만 있는 값은 기본값.
        assertEquals("Android", GuestStatusPolicy.guestName("\u0000\u0001"))
        val long = "X".repeat(100)
        assertEquals(40, GuestStatusPolicy.guestName(long).length)
        assertEquals(40, GuestStatusPolicy.MAX_GUEST_NAME_LENGTH)
    }

    @Test fun `볼륨은 0에서 100 사이로 자른다`() {
        assertEquals(0, GuestStatusPolicy.clampVolume(-5))
        assertEquals(0, GuestStatusPolicy.clampVolume(0))
        assertEquals(37, GuestStatusPolicy.clampVolume(37))
        assertEquals(100, GuestStatusPolicy.clampVolume(100))
        assertEquals(100, GuestStatusPolicy.clampVolume(140))
    }
}
