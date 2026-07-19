package com.arms.androidauto.auto

import com.arms.androidauto.core.model.NasAlbum
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaIdSchemeTest {

    private fun album(artist: String, name: String) = NasAlbum(name = name, albumArtist = artist, songCount = 1)

    @Test
    fun `앨범 mediaId는 원래 값으로 되돌아온다`() {
        val original = album("Metro Boomin", "HEROES & VILLAINS")
        val ref = MediaIdScheme.decode(MediaIdScheme.encodeAlbum(original))
        assertEquals(MediaIdScheme.MediaRef.Album("Metro Boomin", "HEROES & VILLAINS"), ref)
    }

    // 실제 라이브러리에 "iScreaM Vol.22 : 28 Reasons / Los Angeles Remixes" 같은 앨범이 있다.
    // 슬래시나 구분자로 쓰일 만한 문자가 들어와도 분해가 깨지면 안 된다.
    @Test
    fun `앨범명에 슬래시나 구분자가 있어도 안전하다`() {
        val tricky = listOf(
            album("", "iScreaM Vol.22 : 28 Reasons / Los Angeles Remixes"),
            album("A|||B", "C|||D"),
            album("아티스트", "앨범 #1 (100%) ?&="),
            album("", "")
        )
        tricky.forEach { original ->
            val mediaId = MediaIdScheme.encodeAlbum(original)
            val ref = MediaIdScheme.decode(mediaId)
            assertEquals(
                MediaIdScheme.MediaRef.Album(original.albumArtist, original.name),
                ref
            )
        }
    }

    @Test
    fun `아티스트와 곡 mediaId도 왕복한다`() {
        assertEquals(
            MediaIdScheme.MediaRef.Artist("10CM, BIG Naughty (서동현)"),
            MediaIdScheme.decode(MediaIdScheme.encodeArtist("10CM, BIG Naughty (서동현)"))
        )
        assertEquals(
            MediaIdScheme.MediaRef.Song("music_266"),
            MediaIdScheme.decode(MediaIdScheme.encodeSong("music_266"))
        )
    }

    // 기존에 저장돼 있던 마지막 재생 채널 ID("1","2",...)는 접두사가 없다.
    // 그대로 라디오로 해석돼야 예전 사용자의 자동 재개가 깨지지 않는다.
    @Test
    fun `접두사가 없으면 라디오 채널로 본다`() {
        assertEquals(MediaIdScheme.MediaRef.Radio("2"), MediaIdScheme.decode("2"))
    }

    @Test
    fun `NAS 여부를 접두사로 구분한다`() {
        assertTrue(MediaIdScheme.isNas(MediaIdScheme.encodeAlbum(album("a", "b"))))
        assertTrue(MediaIdScheme.isNas(MediaIdScheme.encodeSong("music_1")))
        assertFalse(MediaIdScheme.isNas("2"))
        assertFalse(MediaIdScheme.isNas(null))
    }

    @Test
    fun `플레이리스트 mediaId는 원래 id로 되돌아온다`() {
        listOf(1L, 42L, Long.MAX_VALUE).forEach { id ->
            assertEquals(
                MediaIdScheme.MediaRef.Playlist(id),
                MediaIdScheme.decode(MediaIdScheme.encodePlaylist(id))
            )
        }
    }

    // "[NAS_PLAYLIST]"는 "[NAS_PLAYLISTS]"의 접두사다. 분기 순서가 뒤바뀌면
    // 플레이리스트 목록 폴더가 플레이리스트 하나로 잘못 해석된다.
    @Test
    fun `플레이리스트 목록 폴더가 플레이리스트로 오해되지 않는다`() {
        assertEquals(
            MediaIdScheme.MediaRef.Folder(MediaIdScheme.PLAYLISTS_FOLDER),
            MediaIdScheme.decode(MediaIdScheme.PLAYLISTS_FOLDER)
        )
    }

    @Test
    fun `숫자가 아닌 플레이리스트 payload는 폴더로 폴백한다`() {
        val broken = "[NAS_PLAYLIST]" + java.util.Base64.getUrlEncoder().withoutPadding()
            .encodeToString("숫자아님".toByteArray())
        assertEquals(MediaIdScheme.MediaRef.Folder(broken), MediaIdScheme.decode(broken))
    }

    @Test
    fun `플레이리스트도 NAS로 판정된다`() {
        assertTrue(MediaIdScheme.isNas(MediaIdScheme.encodePlaylist(1)))
    }

    @Test
    fun `폴더 ID는 폴더로 해석된다`() {
        assertEquals(
            MediaIdScheme.MediaRef.Folder(MediaIdScheme.RECENT_FOLDER),
            MediaIdScheme.decode(MediaIdScheme.RECENT_FOLDER)
        )
        assertEquals(
            MediaIdScheme.MediaRef.Folder(MediaIdScheme.ARTISTS_FOLDER),
            MediaIdScheme.decode(MediaIdScheme.ARTISTS_FOLDER)
        )
        assertEquals(MediaIdScheme.MediaRef.Folder("[ALL_RADIO]"), MediaIdScheme.decode("[ALL_RADIO]"))
    }
}
