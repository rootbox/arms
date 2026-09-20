package com.arms.androidauto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioDisplayPolicyTest {
    @Test fun `채널 - 프로그램 형식으로 합친다`() {
        assertEquals("SBS 파워FM - 황제성의 황제파워", RadioDisplayPolicy.sessionTitle("SBS 파워FM (107.7 MHz)", "황제성의 황제파워"))
        assertEquals("KBS Cool FM - 한해의 키스더라디오", RadioDisplayPolicy.sessionTitle("KBS Cool FM (89.1 MHz)", "한해의 키스더라디오"))
    }
    @Test fun `프로그램을 모르면 채널명만`() {
        assertEquals("SBS 파워FM (107.7 MHz)", RadioDisplayPolicy.sessionTitle("SBS 파워FM (107.7 MHz)", null))
        assertEquals("SBS 파워FM (107.7 MHz)", RadioDisplayPolicy.sessionTitle("SBS 파워FM (107.7 MHz)", "정보 없음"))
        assertEquals("KBS Cool FM (89.1 MHz)", RadioDisplayPolicy.sessionTitle("KBS Cool FM (89.1 MHz)", "편성 정보를 제공하지 않습니다"))
    }
    @Test fun `미니플레이어는 프로그램명, 없으면 기존 문구`() {
        assertEquals("황제성의 황제파워", RadioDisplayPolicy.miniPlayerTitle("황제성의 황제파워", "실시간 방송 중"))
        assertEquals("실시간 방송 중", RadioDisplayPolicy.miniPlayerTitle("정보 없음", "실시간 방송 중"))
        assertTrue(RadioDisplayPolicy.isMeaningfulProgram("박소현의 러브게임"))
        assertFalse(RadioDisplayPolicy.isMeaningfulProgram(""))
    }
}
