package com.arms.androidauto.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SseFormatTest {

    @Test
    fun singleLineEvent() {
        assertEquals("event: state\ndata: {\"power\":\"on\"}\n\n", SseFormat.event("state", "{\"power\":\"on\"}"))
    }

    @Test
    fun multiLineDataGetsOneDataFieldPerLine() {
        assertEquals("event: state\ndata: a\ndata: b\ndata: c\n\n", SseFormat.event("state", "a\nb\r\nc"))
    }

    @Test
    fun pingIsComment() {
        assertEquals(": ping\n\n", SseFormat.PING)
    }

    @Test
    fun headersAreEventStreamKeepAlive() {
        val h = SseFormat.HEADERS
        assertTrue(h.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(h.contains("Content-Type: text/event-stream\r\n"))
        assertTrue(h.contains("Cache-Control: no-cache\r\n"))
        assertTrue(h.contains("Connection: keep-alive\r\n"))
        assertTrue(h.endsWith("\r\n\r\n"))
        assertTrue(!h.contains("Content-Length"))
    }
}
