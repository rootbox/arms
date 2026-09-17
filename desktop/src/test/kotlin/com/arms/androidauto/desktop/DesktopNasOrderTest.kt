package com.arms.androidauto.desktop
import com.arms.androidauto.core.model.NasSong
import com.arms.androidauto.desktop.nas.orderSongsByIds
import kotlin.test.Test
import kotlin.test.assertEquals
class DesktopNasOrderTest {
    private fun s(id: String) = NasSong(id, "t$id", null, null, null)
    @Test fun `요청 ID 순서를 그대로 유지한다`() {
        val songs = listOf(s("3"), s("1"), s("2")) // 캐시 순서(NAS가 준 순서)
        val ordered = orderSongsByIds(songs, listOf("2","3","1")) // 담은 순서
        assertEquals(listOf("2","3","1"), ordered.map { it.id })
    }
    @Test fun `목록에 없는 ID는 건너뛴다`() {
        assertEquals(listOf("1"), orderSongsByIds(listOf(s("1")), listOf("9","1","8")).map { it.id })
    }
}
