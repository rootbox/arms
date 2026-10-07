package com.arms.androidauto.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream

class HttpRequestParserTest {

    private fun parse(raw: String, max: Int = HttpRequestParser.DEFAULT_MAX_BYTES) =
        HttpRequestParser.parse(ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)), max)

    private fun expectStatus(raw: String, status: Int, max: Int = HttpRequestParser.DEFAULT_MAX_BYTES) {
        try {
            parse(raw, max)
            fail("expected HttpParseException($status)")
        } catch (e: HttpParseException) {
            assertEquals(status, e.status)
        }
    }

    @Test
    fun parsesGetWithHeadersAndQuery() {
        val req = parse("GET /api/v1/state?x=1 HTTP/1.1\r\nHost: 192.168.0.5:8765\r\nAuthorization: Bearer abc\r\n\r\n")!!
        assertEquals("GET", req.method)
        assertEquals("/api/v1/state", req.path)
        assertEquals("x=1", req.query)
        assertEquals("192.168.0.5:8765", req.header("Host"))
        assertEquals("Bearer abc", req.header("authorization"))
        assertEquals(0, req.body.size)
    }

    @Test
    fun parsesPostBodyByContentLength() {
        val body = """{"command":"setVolume","value":40}"""
        val req = parse("POST /api/v1/command HTTP/1.1\r\nContent-Type: application/json\r\nContent-Length: ${body.length}\r\n\r\n${body}EXTRA")!!
        assertEquals("POST", req.method)
        assertEquals(body, req.bodyText())
    }

    @Test
    fun utf8BodyLengthIsInBytes() {
        val body = """{"label":"거실"}"""
        val bytes = body.toByteArray(Charsets.UTF_8)
        val req = parse("POST /api/v1/pair HTTP/1.1\r\nContent-Length: ${bytes.size}\r\n\r\n$body")!!
        assertArrayEquals(bytes, req.body)
    }

    @Test
    fun acceptsBareLfLineEndings() {
        val req = parse("GET /api/v1/info HTTP/1.0\nHost: x\n\n")!!
        assertEquals("/api/v1/info", req.path)
        assertEquals("x", req.header("host"))
    }

    @Test
    fun decodesChunkedBody() {
        val req = parse("POST /api/v1/command HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n5\r\n{\"com\r\n0D\r\nmand\":\"play\"}\r\n0\r\n\r\n")!!
        assertEquals("""{"command":"play"}""", req.bodyText())
    }

    @Test
    fun eofBeforeAnyByteIsNull() {
        assertNull(parse(""))
    }

    @Test
    fun malformedRequestsAre400() {
        expectStatus("GARBAGE\r\n\r\n", 400)
        expectStatus("GET api/v1/info HTTP/1.1\r\n\r\n", 400)
        expectStatus("GET /x SPDY/3\r\n\r\n", 400)
        expectStatus("GET /x HTTP/1.1\r\nNoColonHere\r\n\r\n", 400)
        expectStatus("POST /x HTTP/1.1\r\nContent-Length: abc\r\n\r\n", 400)
        expectStatus("POST /x HTTP/1.1\r\nContent-Length: 10\r\n\r\nshort", 400)
        expectStatus("GET /x HTTP/1.1\r\nHost: x\r\n", 400) // 헤더 끝 없이 EOF
    }

    @Test
    fun oversizeRequestsAre413() {
        expectStatus("POST /x HTTP/1.1\r\nContent-Length: 20000\r\n\r\n", 413)
        val bigHeader = "GET /x HTTP/1.1\r\nX-Big: " + "a".repeat(2000) + "\r\n\r\n"
        expectStatus(bigHeader, 413, max = 1024)
    }

    @Test
    fun unsupportedTransferEncodingIs501() {
        expectStatus("POST /x HTTP/1.1\r\nTransfer-Encoding: gzip\r\n\r\n", 501)
    }
}
