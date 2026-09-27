package com.arms.androidauto.remote.host

import com.arms.androidauto.core.remote.RemoteGuest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestRegistryTest {

    private val registry = GuestRegistry(expiryMs = 180_000L)

    @Test
    fun helloAddsGuestAndRefreshKeepsSingleEntry() {
        assertTrue(registry.hello("Galaxy S22", 1_000L))
        assertEquals(listOf(RemoteGuest("Galaxy S22", 1_000L)), registry.guests)
        // 같은 이름의 Hello는 lastSeen만 갱신하고 "목록이 바뀌었다"고 하지 않는다.
        assertFalse(registry.hello("Galaxy S22", 61_000L))
        assertEquals(listOf(RemoteGuest("Galaxy S22", 61_000L)), registry.guests)
    }

    @Test
    fun byeRemovesOnlyKnownGuest() {
        registry.hello("Galaxy S22", 1_000L)
        registry.hello("Galaxy S24", 2_000L)
        assertFalse(registry.bye("iPhone"))
        assertTrue(registry.bye("Galaxy S22"))
        assertEquals(listOf("Galaxy S24"), registry.guests.map { it.name })
        assertFalse(registry.bye("Galaxy S22"))
    }

    @Test
    fun sweepExpiresGuestsSilentAfterThreeMinutes() {
        registry.hello("Galaxy S22", 0L)
        registry.hello("Galaxy S24", 100_000L)
        assertFalse(registry.sweep(179_999L))
        assertEquals(2, registry.guests.size)
        assertTrue(registry.sweep(180_000L))
        assertEquals(listOf("Galaxy S24"), registry.guests.map { it.name })
        assertFalse(registry.sweep(200_000L))
        assertTrue(registry.sweep(280_000L))
        assertTrue(registry.guests.isEmpty())
    }

    @Test
    fun helloAfterExpiryReappears() {
        registry.hello("Galaxy S22", 0L)
        registry.sweep(500_000L)
        assertTrue(registry.hello("Galaxy S22", 500_001L))
    }

    @Test
    fun orderIsFirstHelloFirst() {
        registry.hello("B", 1L)
        registry.hello("A", 2L)
        registry.hello("B", 3L)
        assertEquals(listOf("B", "A"), registry.guests.map { it.name })
    }

    @Test
    fun namesAreNormalized() {
        assertEquals("리모컨", GuestRegistry.normalizeName("   "))
        assertEquals("Galaxy S22", GuestRegistry.normalizeName("  Galaxy S22 \n"))
        assertEquals(40, GuestRegistry.normalizeName("x".repeat(100)).length)
        registry.hello("  Galaxy S22 ", 1L)
        assertTrue(registry.bye("Galaxy S22"))
    }

    @Test
    fun defaultExpiryIsThreeMinutes() {
        assertEquals(3 * 60_000L, GuestRegistry.DEFAULT_EXPIRY_MS)
    }

    // ---- 칩 문구 ----

    @Test
    fun presenceLabelSummarizesGuests() {
        assertNull(guestPresenceLabel(emptyList()))
        assertEquals("Galaxy S22 연결됨", guestPresenceLabel(listOf(RemoteGuest("Galaxy S22", 0L))))
        assertEquals(
            "Galaxy S22 외 2대 연결됨",
            guestPresenceLabel(listOf(RemoteGuest("Galaxy S22", 0L), RemoteGuest("Galaxy S24", 0L), RemoteGuest("Tab", 0L))),
        )
    }
}
