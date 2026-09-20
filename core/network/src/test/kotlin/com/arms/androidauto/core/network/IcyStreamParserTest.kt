package com.arms.androidauto.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IcyStreamParserTest {
    private fun body(metaint: Int, meta: String): ByteArray {
        val m = meta.toByteArray(Charsets.UTF_8)
        val padded = m + ByteArray((16 - m.size % 16) % 16)
        return ByteArray(metaint) + byteArrayOf((padded.size / 16).toByte()) + padded
    }

    @Test fun `metaint 뒤 첫 블록의 StreamTitle을 꺼낸다`() {
        val b = body(16, "StreamTitle='임재현 - Heaven (2023)';StreamUrl='';")
        assertEquals("임재현 - Heaven (2023)", extractIcyStreamTitle(b, 16))
    }
    @Test fun `길이 0 블록(곡 정보 없음)은 null`() { assertNull(extractIcyStreamTitle(ByteArray(16) + byteArrayOf(0), 16)) }
    @Test fun `본문이 metaint보다 짧으면 null`() { assertNull(extractIcyStreamTitle(ByteArray(5), 16)) }
    @Test fun `icy-name의 홍보 문구를 뗀다`() {
        assertEquals("Naya Ballad - Soul Kpop", cleanIcyName("Naya Ballad - Soul Kpop # https://sCast.kr"))
        assertNull(cleanIcyName("  "))
    }
    @Test fun `검색어 정리 - 괄호와 구분자 제거`() {
        assertEquals("임재현 Heaven", cleanTrackQuery("임재현 - Heaven (2023)"))
        assertEquals("K.Will Right In Front Of You", cleanTrackQuery("K.Will (케이윌) - Right In Front Of You (네 앞에)"))
    }
    @Test fun `Deezer 응답은 곡이 일치할 때만 큰 커버를 고른다`() {
        val json = """{"data":[{"title":"Right In Front Of you","artist":{"name":"K. will"},"album":{"cover_big":"https://b/big.jpg","cover_xl":"https://b/xl.jpg"}}]}"""
        assertEquals("https://b/xl.jpg", parseDeezerCover(json, "K.Will (케이윌) - Right In Front Of You (네 앞에)"))
        assertNull(parseDeezerCover("""{"data":[]}""", "a - b"))
        assertNull(parseDeezerCover("not json", "a - b"))
    }
    @Test fun `엉뚱한 곡(오탐)은 거부한다`() {
        val wrong = """{"data":[{"title":"Your presence is heaven (주 임재 천국과 같네)","artist":{"name":"Seulki Hong (홍슬기)"},"album":{"cover_xl":"https://b/x.jpg"}}]}"""
        assertNull(parseDeezerCover(wrong, "임재현 - Heaven (2023)"))
        assertEquals(false, deezerMatches("임재현 - Heaven", "Seulki Hong", "Your presence is heaven"))
        assertEquals(true, deezerMatches("Ailee - 첫눈처럼 너에게 가겠다", "Ailee (에일리)", "첫눈처럼 너에게 가겠다 (Live)"))
        // 아티스트가 비어 있으면 "포함" 검사가 항상 참이 되어 오탐이 통과하므로 거부해야 한다
        assertEquals(false, deezerMatches("UNI.T - 난말야", "", "난 말야"))
        assertEquals(false, deezerMatches("UNI.T - 난말야", "As One", "난 말야"))
    }
}
