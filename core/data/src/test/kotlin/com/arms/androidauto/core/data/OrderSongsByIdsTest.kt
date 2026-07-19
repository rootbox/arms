package com.arms.androidauto.core.data

import com.arms.androidauto.core.model.NasSong
import kotlin.test.Test
import kotlin.test.assertEquals

class OrderSongsByIdsTest {

    private fun song(id: String) =
        NasSong(id = id, title = "곡 $id", artist = null, album = null, albumArtist = null)

    // 캐시는 NAS가 준 순서로 담겨 있다. 플레이리스트는 사용자가 담은 순서를 따라야 한다.
    @Test
    fun `요청한 순서를 그대로 유지한다`() {
        val cached = listOf(song("a"), song("b"), song("c"))

        val result = orderSongsByIds(cached, listOf("c", "a", "b"))

        assertEquals(listOf("c", "a", "b"), result.map { it.id })
    }

    @Test
    fun `목록에 없는 곡은 건너뛴다`() {
        val cached = listOf(song("a"), song("b"))

        val result = orderSongsByIds(cached, listOf("a", "사라진곡", "b"))

        assertEquals(listOf("a", "b"), result.map { it.id })
    }

    @Test
    fun `같은 곡을 여러 번 요청하면 그만큼 나온다`() {
        val cached = listOf(song("a"), song("b"))

        val result = orderSongsByIds(cached, listOf("a", "b", "a"))

        assertEquals(listOf("a", "b", "a"), result.map { it.id })
    }

    @Test
    fun `빈 입력은 빈 결과`() {
        assertEquals(emptyList(), orderSongsByIds(listOf(song("a")), emptyList()))
        assertEquals(emptyList(), orderSongsByIds(emptyList(), listOf("a")))
    }

    // 세션 실패로 곡 목록을 못 받아온 경우. 호출부가 "삭제된 곡"으로 오해하지 않도록
    // 빈 목록이 그대로 나와야 한다.
    @Test
    fun `곡 목록이 비어 있으면 아무것도 반환하지 않는다`() {
        assertEquals(emptyList(), orderSongsByIds(emptyList(), listOf("a", "b", "c")))
    }
}
