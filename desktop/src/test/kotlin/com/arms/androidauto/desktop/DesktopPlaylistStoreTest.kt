package com.arms.androidauto.desktop
import com.arms.androidauto.desktop.storage.DesktopPlaylistStore
import kotlin.test.Test
import kotlin.test.assertEquals
import java.nio.file.Files
class DesktopPlaylistStoreTest {
    private fun store() = DesktopPlaylistStore(Files.createTempDirectory("pl").toFile())
    @Test fun `생성-담기-순서보존-중복방지-이름변경-삭제`() {
        val st = store()
        val id = st.createPlaylist("서태지")
        st.addSongs(id, listOf("a","b","c"))
        st.addSongs(id, listOf("c","d"))            // c는 중복 → 스킵, d만 추가
        assertEquals(listOf("a","b","c","d"), st.getPlaylists().first().songIds)
        st.rename(id, "명곡")
        assertEquals("명곡", st.getPlaylists().first().name)
        st.delete(id)
        assertEquals(0, st.getPlaylists().size)
    }
    @Test fun `파일에 영속화되어 새 인스턴스에서 읽힌다`() {
        val dir = Files.createTempDirectory("pl2").toFile()
        val id = DesktopPlaylistStore(dir).createPlaylist("x")
        DesktopPlaylistStore(dir).addSongs(id, listOf("1","2"))
        assertEquals(listOf("1","2"), DesktopPlaylistStore(dir).getPlaylists().first().songIds)
    }
}
