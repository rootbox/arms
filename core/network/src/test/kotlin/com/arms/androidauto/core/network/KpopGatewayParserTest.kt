package com.arms.androidauto.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// LISTEN.moe 게이트웨이 페이로드 해석. 실측(2026-09-17)한 실제 메시지 형태를 그대로 고정한다.
class KpopGatewayParserTest {

    private val trackUpdate = """
        {"op":1,"t":"TRACK_UPDATE","d":{"song":{
          "title":"전화받아",
          "artists":[{"name":"박지민"},{"name":"키노"},{"name":"WOODZ"},{"name":"네이슨"}],
          "albums":[{"name":"jiminxjamie","image":"jiminxjamie_cover_kpop.jpg"}]
        }}}
    """.trimIndent()

    @Test
    fun `TRACK_UPDATE에서 곡·아티스트·커버를 한 번에 얻는다`() {
        val track = parseKpopGatewayMessage(trackUpdate)!!
        assertEquals("전화받아", track.title)
        assertEquals("박지민, 키노, WOODZ, 네이슨", track.artist)
        assertEquals("https://cdn.listen.moe/covers/jiminxjamie_cover_kpop.jpg", track.coverUrl)
    }

    @Test
    fun `커버가 없는 곡은 coverUrl만 null이고 곡 정보는 살린다`() {
        val raw = """{"op":1,"t":"TRACK_UPDATE","d":{"song":{"title":"X","artists":[{"name":"A"}],"albums":[]}}}"""
        val track = parseKpopGatewayMessage(raw)!!
        assertEquals("X", track.title)
        assertNull(track.coverUrl)
    }

    @Test
    fun `아티스트가 없으면 K-POP으로 표기한다`() {
        val raw = """{"op":1,"t":"TRACK_UPDATE","d":{"song":{"title":"X","artists":[],"albums":[]}}}"""
        assertEquals("K-POP", parseKpopGatewayMessage(raw)!!.artist)
    }

    @Test
    fun `핸드셰이크(op 0)나 하트비트는 무시한다`() {
        assertNull(parseKpopGatewayMessage("""{"op":0,"d":{"message":"Welcome","heartbeat":45000}}"""))
        assertNull(parseKpopGatewayMessage("""{"op":10}"""))
    }

    @Test
    fun `깨진 페이로드는 예외 없이 null`() {
        assertNull(parseKpopGatewayMessage("not json"))
        assertNull(parseKpopGatewayMessage("""{"op":1,"t":"TRACK_UPDATE","d":{}}"""))
    }
}
