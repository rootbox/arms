package com.arms.androidauto.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// SBS 온에어 API(play-api/1.0/onair/channel/S07) 해석. 실측(2026-09-17) 페이로드 형태를 고정한다.
// 예전 bora/today(보이는 라디오 편성표)는 하루 대부분 시간대에 파워FM 프로그램이 없어 커버가 비었다.
class SbsOnairParserTest {

    private val api = RadioApiServiceImpl(okhttp3.OkHttpClient())

    private val payload = """
        {"onair":{"info":{
          "title":"황제성의 황제파워","channelid":"S07","starttime":"16:00","endtime":"18:00",
          "thumbimg":"https://img2.sbs.co.kr/x-270-152.jpg",
          "thumbs":{"640":"https://img2.sbs.co.kr/x-640-360.jpg","1280":"https://img2.sbs.co.kr/x-1280-720.jpg"},
          "program_image":"https://img2.sbs.co.kr/x-270-152.jpg",
          "background":"https://image.cloud.sbs.co.kr/play/radio/power.jpg"
        }}}
    """.trimIndent()

    @Test
    fun `제목과 640 썸네일을 우선 쓴다`() {
        val p = api.parseSbsOnair(payload)!!
        assertEquals("황제성의 황제파워", p.title)
        assertEquals("https://img2.sbs.co.kr/x-640-360.jpg", p.imageUrl)
        assertEquals("https://image.cloud.sbs.co.kr/play/radio/power.jpg", p.channelImageUrl)
    }

    @Test
    fun `썸네일이 없으면 program_image로 내려간다`() {
        val raw = """{"onair":{"info":{"title":"T","program_image":"https://img2.sbs.co.kr/small.jpg"}}}"""
        assertEquals("https://img2.sbs.co.kr/small.jpg", api.parseSbsOnair(raw)!!.imageUrl)
    }

    @Test
    fun `이미지가 하나도 없어도 제목은 살리고 imageUrl만 null`() {
        val raw = """{"onair":{"info":{"title":"T","background":"https://image.cloud.sbs.co.kr/play/radio/power.jpg"}}}"""
        val p = api.parseSbsOnair(raw)!!
        assertEquals("T", p.title)
        assertNull(p.imageUrl)
        assertEquals("https://image.cloud.sbs.co.kr/play/radio/power.jpg", p.channelImageUrl)
    }

    @Test
    fun `제목이 없거나 구조가 다르면 null`() {
        assertNull(api.parseSbsOnair("""{"onair":{"info":{"title":""}}}"""))
        assertNull(api.parseSbsOnair("""{"onair":{}}"""))
        assertNull(api.parseSbsOnair("not json"))
    }
}
