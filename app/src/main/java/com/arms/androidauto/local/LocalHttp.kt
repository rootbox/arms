package com.arms.androidauto.local

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

// 아주 작은 HTTP/1.1 요청 파서·응답 작성기. 로컬 제어 API 전용(같은 와이파이, 요청 16KB 이하).
data class HttpRequest(
    val method: String,
    val path: String,
    val query: String?,
    // 이름은 소문자. 같은 이름이 여러 번 오면 마지막 값.
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    fun header(name: String): String? = headers[name.lowercase()]
    fun bodyText(): String = body.toString(Charsets.UTF_8)

    override fun equals(other: Any?): Boolean =
        other is HttpRequest && method == other.method && path == other.path && query == other.query &&
            headers == other.headers && body.contentEquals(other.body)

    override fun hashCode(): Int = ((method.hashCode() * 31 + path.hashCode()) * 31 + headers.hashCode()) * 31 + body.contentHashCode()
}

class HttpParseException(val status: Int, message: String) : IOException(message)

object HttpRequestParser {
    const val DEFAULT_MAX_BYTES = 16 * 1024
    private const val MAX_HEADERS = 64

    // 요청 하나를 읽는다. 바이트 하나 없이 연결이 닫히면 null. 형식 오류는 HttpParseException(400/413/501).
    // maxBytes는 헤더+본문 전체 한도.
    fun parse(input: InputStream, maxBytes: Int = DEFAULT_MAX_BYTES): HttpRequest? {
        val counter = Counter(maxBytes)
        val requestLine = readLine(input, counter, allowEofAtStart = true) ?: return null
        val parts = requestLine.split(' ')
        if (parts.size != 3) throw HttpParseException(400, "bad request line")
        val (method, target, version) = parts
        if (!version.startsWith("HTTP/1.")) throw HttpParseException(400, "unsupported version")
        if (method.isEmpty() || !method.all { it in 'A'..'Z' }) throw HttpParseException(400, "bad method")
        if (!target.startsWith("/")) throw HttpParseException(400, "bad target")

        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input, counter, allowEofAtStart = false)
                ?: throw HttpParseException(400, "unexpected eof")
            if (line.isEmpty()) break
            if (headers.size >= MAX_HEADERS) throw HttpParseException(413, "too many headers")
            val colon = line.indexOf(':')
            if (colon <= 0) throw HttpParseException(400, "bad header")
            headers[line.substring(0, colon).trim().lowercase()] = line.substring(colon + 1).trim()
        }

        val body: ByteArray = when {
            headers["transfer-encoding"] != null -> {
                if (!headers["transfer-encoding"]!!.equals("chunked", ignoreCase = true)) throw HttpParseException(501, "unsupported transfer-encoding")
                readChunked(input, counter)
            }
            headers["content-length"] != null -> {
                val len = headers["content-length"]!!.toLongOrNull() ?: throw HttpParseException(400, "bad content-length")
                if (len < 0) throw HttpParseException(400, "bad content-length")
                if (len > counter.remaining) throw HttpParseException(413, "body too large")
                readExactly(input, len.toInt(), counter)
            }
            else -> ByteArray(0)
        }

        val q = target.indexOf('?')
        val path = if (q >= 0) target.substring(0, q) else target
        val query = if (q >= 0) target.substring(q + 1) else null
        return HttpRequest(method, path, query, headers, body)
    }

    private class Counter(var remaining: Int) {
        fun take(n: Int) {
            remaining -= n
            if (remaining < 0) throw HttpParseException(413, "request too large")
        }
    }

    // CRLF(또는 LF)까지 한 줄. ISO-8859-1로 읽는다(헤더는 ASCII).
    private fun readLine(input: InputStream, counter: Counter, allowEofAtStart: Boolean): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) {
                if (buf.size() == 0 && allowEofAtStart) return null
                throw HttpParseException(400, "unexpected eof")
            }
            counter.take(1)
            if (b == '\n'.code) break
            buf.write(b)
        }
        val bytes = buf.toByteArray()
        val len = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
        return String(bytes, 0, len, Charsets.ISO_8859_1)
    }

    private fun readExactly(input: InputStream, n: Int, counter: Counter): ByteArray {
        counter.take(n)
        val out = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = input.read(out, off, n - off)
            if (r == -1) throw HttpParseException(400, "unexpected eof in body")
            off += r
        }
        return out
    }

    // LuaSocket 등은 Content-Length 없이 본문을 보내면 chunked로 보낸다.
    private fun readChunked(input: InputStream, counter: Counter): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input, counter, allowEofAtStart = false) ?: throw HttpParseException(400, "eof")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16) ?: throw HttpParseException(400, "bad chunk size")
            if (size < 0) throw HttpParseException(400, "bad chunk size")
            if (size == 0) {
                // 트레일러는 읽고 버린다.
                while (true) {
                    val t = readLine(input, counter, allowEofAtStart = false) ?: throw HttpParseException(400, "eof")
                    if (t.isEmpty()) break
                }
                return out.toByteArray()
            }
            if (size > counter.remaining) throw HttpParseException(413, "body too large")
            out.write(readExactly(input, size, counter))
            val crlf = readLine(input, counter, allowEofAtStart = false)
            if (crlf == null || crlf.isNotEmpty()) throw HttpParseException(400, "bad chunk terminator")
        }
    }
}

object HttpResponses {
    fun statusText(code: Int): String = when (code) {
        200 -> "OK"
        204 -> "No Content"
        400 -> "Bad Request"
        401 -> "Unauthorized"
        403 -> "Forbidden"
        404 -> "Not Found"
        405 -> "Method Not Allowed"
        408 -> "Request Timeout"
        413 -> "Payload Too Large"
        500 -> "Internal Server Error"
        501 -> "Not Implemented"
        503 -> "Service Unavailable"
        504 -> "Gateway Timeout"
        else -> "Status"
    }

    // 응답 하나를 쓰고 끝낸다(항상 Connection: close).
    fun write(
        out: OutputStream,
        status: Int,
        contentType: String?,
        body: ByteArray,
        extraHeaders: List<Pair<String, String>> = emptyList(),
        omitBody: Boolean = false,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(status).append(' ').append(statusText(status)).append("\r\n")
        if (contentType != null) sb.append("Content-Type: ").append(contentType).append("\r\n")
        sb.append("Content-Length: ").append(body.size).append("\r\n")
        sb.append("Connection: close\r\n")
        extraHeaders.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        sb.append("\r\n")
        out.write(sb.toString().toByteArray(Charsets.UTF_8))
        if (!omitBody) out.write(body)
        out.flush()
    }

    fun json(out: OutputStream, status: Int, json: String, extraHeaders: List<Pair<String, String>> = emptyList()) =
        write(out, status, "application/json; charset=utf-8", json.toByteArray(Charsets.UTF_8), listOf("Cache-Control" to "no-store") + extraHeaders)
}

// Server-Sent Events 프레임.
object SseFormat {
    const val PING = ": ping\n\n"

    val HEADERS: String =
        "HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/event-stream\r\n" +
            "Cache-Control: no-cache\r\n" +
            "Connection: keep-alive\r\n" +
            "X-Accel-Buffering: no\r\n" +
            "\r\n"

    // data에 줄바꿈이 있으면 줄마다 "data: "를 붙인다(SSE 규격).
    fun event(event: String, data: String): String {
        val sb = StringBuilder()
        sb.append("event: ").append(event).append('\n')
        data.split("\r\n", "\n", "\r").forEach { sb.append("data: ").append(it).append('\n') }
        sb.append('\n')
        return sb.toString()
    }
}
