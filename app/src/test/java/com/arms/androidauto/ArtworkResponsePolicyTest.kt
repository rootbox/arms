package com.arms.androidauto

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// CDN 오류 페이지(HTML)를 이미지로 저장해 회색으로 보이던 문제의 재발 방지.
class ArtworkResponsePolicyTest {

    @Test
    fun `2xx에 image 타입이면 받아들인다`() {
        assertTrue(ArtworkResponsePolicy.isAcceptable(200, "image/jpeg"))
        assertTrue(ArtworkResponsePolicy.isAcceptable(200, "image/png"))
        // KBS는 charset을 붙여 보낸다
        assertTrue(ArtworkResponsePolicy.isAcceptable(200, "image/jpeg; charset=UTF-8"))
        assertTrue(ArtworkResponsePolicy.isAcceptable(200, "IMAGE/JPEG"))
    }

    @Test
    fun `Content-Type이 없으면 디코딩 검사에 맡기고 통과시킨다`() {
        assertTrue(ArtworkResponsePolicy.isAcceptable(200, null))
    }

    @Test
    fun `이미지가 아닌 본문은 거부한다 - 오류 페이지를 저장하지 않는다`() {
        assertFalse(ArtworkResponsePolicy.isAcceptable(200, "text/html; charset=utf-8"))
        assertFalse(ArtworkResponsePolicy.isAcceptable(200, "application/json"))
    }

    @Test
    fun `오류 상태코드는 타입과 무관하게 거부한다`() {
        assertFalse(ArtworkResponsePolicy.isAcceptable(404, "image/jpeg"))
        assertFalse(ArtworkResponsePolicy.isAcceptable(500, "image/jpeg"))
        assertFalse(ArtworkResponsePolicy.isAcceptable(302, "image/jpeg"))
    }
}
