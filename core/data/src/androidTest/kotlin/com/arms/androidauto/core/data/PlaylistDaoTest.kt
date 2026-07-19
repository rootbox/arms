package com.arms.androidauto.core.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.arms.androidauto.core.model.NasSong
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlaylistDaoTest {

    private lateinit var database: PlaylistDatabase
    private lateinit var repository: NasPlaylistRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, PlaylistDatabase::class.java).build()
        repository = NasPlaylistRepository.forTesting(database)
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun song(id: String, title: String = "곡 $id") =
        NasSong(id = id, title = title, artist = "아티스트", album = "앨범", albumArtist = "앨범아티스트")

    @Test
    fun 담은_순서대로_돌아온다() = runTest {
        val id = repository.createPlaylist("드라이브용")
        repository.addSongs(id, listOf(song("c"), song("a"), song("b")))

        val tracks = repository.getTracks(id)

        assertEquals(listOf("c", "a", "b"), tracks.map { it.songId })
    }

    @Test
    fun 나중에_담은_곡이_뒤에_붙는다() = runTest {
        val id = repository.createPlaylist("목록")
        repository.addSongs(id, listOf(song("a"), song("b")))
        repository.addSongs(id, listOf(song("c")))

        assertEquals(listOf("a", "b", "c"), repository.getTracks(id).map { it.songId })
    }

    @Test
    fun 이미_담긴_곡은_무시된다() = runTest {
        val id = repository.createPlaylist("목록")
        repository.addSongs(id, listOf(song("a"), song("b")))

        val added = repository.addSongs(id, listOf(song("b"), song("c")))

        // b는 무시되고 c만 추가
        assertEquals(1, added)
        assertEquals(listOf("a", "b", "c"), repository.getTracks(id).map { it.songId })
    }

    @Test
    fun 같은_곡이라도_다른_플레이리스트에는_담긴다() = runTest {
        val first = repository.createPlaylist("A")
        val second = repository.createPlaylist("B")

        repository.addSongs(first, listOf(song("a")))
        val added = repository.addSongs(second, listOf(song("a")))

        assertEquals(1, added)
        assertEquals(listOf("a"), repository.getTracks(second).map { it.songId })
    }

    @Test
    fun 플레이리스트를_지우면_담긴_곡도_사라진다() = runTest {
        val id = repository.createPlaylist("지울 목록")
        repository.addSongs(id, listOf(song("a"), song("b")))

        repository.delete(id)

        assertNull(repository.getPlaylist(id))
        assertEquals(emptyList<String>(), repository.getTracks(id).map { it.songId })
    }

    @Test
    fun 곡_수가_함께_조회된다() = runTest {
        val withSongs = repository.createPlaylist("곡 있음")
        val empty = repository.createPlaylist("비어 있음")
        repository.addSongs(withSongs, listOf(song("a"), song("b")))

        val playlists = repository.getPlaylists().associateBy { it.id }

        assertEquals(2, playlists.getValue(withSongs).songCount)
        // 곡이 없는 플레이리스트도 목록에서 빠지지 않는다 (LEFT JOIN)
        assertEquals(0, playlists.getValue(empty).songCount)
    }

    @Test
    fun 곡을_빼면_목록에서_사라진다() = runTest {
        val id = repository.createPlaylist("목록")
        repository.addSongs(id, listOf(song("a"), song("b")))

        repository.removeTrack(id, "a")

        assertEquals(listOf("b"), repository.getTracks(id).map { it.songId })
    }

    @Test
    fun 이름을_바꾸면_반영된다() = runTest {
        val id = repository.createPlaylist("옛 이름")

        repository.rename(id, "새 이름")

        assertEquals("새 이름", repository.getPlaylist(id)?.name)
    }

    @Test
    fun 저장된_곡정보로_재생목록을_복원할_수_있다() = runTest {
        val id = repository.createPlaylist("목록")
        repository.addSongs(id, listOf(song("music_1", title = "첫 곡")))

        val restored = repository.getTracks(id).first().toNasSong()

        assertEquals("music_1", restored.id)
        assertEquals("첫 곡", restored.title)
        assertEquals("앨범아티스트", restored.albumArtist)
    }

    @Test
    fun 변경사항이_Flow로_전달된다() = runTest {
        val id = repository.createPlaylist("목록")
        repository.addSongs(id, listOf(song("a")))

        assertEquals(listOf("a"), repository.observeTracks(id).first().map { it.songId })
    }
}
