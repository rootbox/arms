package com.arms.androidauto.core.data

import android.content.Context
import com.arms.androidauto.core.model.NasPlaylist
import com.arms.androidauto.core.model.NasPlaylistTrack
import com.arms.androidauto.core.model.NasSong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class NasPlaylistRepository private constructor(private val dao: PlaylistDao) {

    fun observePlaylists(): Flow<List<NasPlaylist>> =
        dao.observePlaylists().map { list -> list.map { it.toNasPlaylist() } }

    suspend fun getPlaylists(): List<NasPlaylist> = withContext(Dispatchers.IO) {
        dao.getPlaylists().map { it.toNasPlaylist() }
    }

    suspend fun getPlaylist(playlistId: Long): NasPlaylist? = withContext(Dispatchers.IO) {
        dao.getPlaylists().find { it.id == playlistId }?.toNasPlaylist()
    }

    fun observeTracks(playlistId: Long): Flow<List<NasPlaylistTrack>> =
        dao.observeTracks(playlistId).map { list -> list.map { it.toNasPlaylistTrack() } }

    suspend fun getTracks(playlistId: Long): List<NasPlaylistTrack> = withContext(Dispatchers.IO) {
        dao.getTracks(playlistId).map { it.toNasPlaylistTrack() }
    }

    suspend fun createPlaylist(name: String): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        dao.insertPlaylist(
            PlaylistEntity(name = name.trim(), createdAtMs = now, updatedAtMs = now)
        )
    }

    // 곡 하나를 담을 때도 이 함수를 쓴다 ("앨범 전체 담기"가 자연스럽게 따라온다).
    // 반환값은 실제로 추가된 곡 수 - 이미 담겨 있던 곡은 무시되므로 요청 수와 다를 수 있다.
    suspend fun addSongs(playlistId: Long, songs: List<NasSong>): Int = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext 0
        val startPosition = (dao.maxPosition(playlistId) ?: -1) + 1
        val entities = songs.mapIndexed { index, song ->
            PlaylistTrackEntity(
                playlistId = playlistId,
                songId = song.id,
                title = song.title,
                artist = song.artist,
                album = song.album,
                albumArtist = song.albumArtist,
                position = startPosition + index
            )
        }
        // IGNORE로 무시된 행은 rowId가 -1로 돌아온다.
        val inserted = dao.insertTracks(entities).count { it != -1L }
        if (inserted > 0) dao.touch(playlistId, System.currentTimeMillis())
        inserted
    }

    suspend fun removeTrack(playlistId: Long, songId: String) = withContext(Dispatchers.IO) {
        dao.deleteTrack(playlistId, songId)
        dao.touch(playlistId, System.currentTimeMillis())
    }

    suspend fun rename(playlistId: Long, name: String) = withContext(Dispatchers.IO) {
        dao.renamePlaylist(playlistId, name.trim(), System.currentTimeMillis())
    }

    // 담긴 곡은 외래키 CASCADE로 함께 삭제된다.
    suspend fun delete(playlistId: Long) = withContext(Dispatchers.IO) {
        dao.deletePlaylist(playlistId)
    }

    companion object {
        // 폰 화면과 차량 서비스가 같은 프로세스에서 돌기 때문에 인스턴스를 공유한다.
        @Volatile
        private var instance: NasPlaylistRepository? = null

        fun get(context: Context): NasPlaylistRepository {
            return instance ?: synchronized(this) {
                instance ?: NasPlaylistRepository(
                    PlaylistDatabase.getDatabase(context).playlistDao()
                ).also { instance = it }
            }
        }

        // 테스트에서 메모리 DB를 주입하기 위한 진입점 (싱글턴을 건드리지 않는다)
        internal fun forTesting(database: PlaylistDatabase): NasPlaylistRepository =
            NasPlaylistRepository(database.playlistDao())
    }
}
