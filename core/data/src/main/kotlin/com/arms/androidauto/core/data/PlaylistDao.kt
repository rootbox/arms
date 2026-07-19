package com.arms.androidauto.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PlaylistDao {

    // 곡 수는 조인해서 세어 채운다. LEFT JOIN이라 곡이 하나도 없는 플레이리스트도 0으로 나온다.
    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.updatedAtMs AS updatedAtMs,
               COUNT(t.id) AS songCount
        FROM playlists p
        LEFT JOIN playlist_tracks t ON t.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.sortOrder ASC, p.updatedAtMs DESC
        """
    )
    fun observePlaylists(): Flow<List<PlaylistWithCount>>

    // 차량 브라우징처럼 1회성 조회가 필요한 곳에서 사용 (Flow를 구독할 수명주기가 없다)
    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.updatedAtMs AS updatedAtMs,
               COUNT(t.id) AS songCount
        FROM playlists p
        LEFT JOIN playlist_tracks t ON t.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.sortOrder ASC, p.updatedAtMs DESC
        """
    )
    suspend fun getPlaylists(): List<PlaylistWithCount>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylist(playlistId: Long): PlaylistEntity?

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    fun observeTracks(playlistId: Long): Flow<List<PlaylistTrackEntity>>

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :playlistId ORDER BY position ASC")
    suspend fun getTracks(playlistId: Long): List<PlaylistTrackEntity>

    @Insert
    suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    // 이미 담긴 곡(playlistId+songId 유니크)은 조용히 무시된다.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertTracks(tracks: List<PlaylistTrackEntity>): List<Long>

    @Query("SELECT MAX(position) FROM playlist_tracks WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int?

    @Query("UPDATE playlists SET name = :name, updatedAtMs = :updatedAtMs WHERE id = :playlistId")
    suspend fun renamePlaylist(playlistId: Long, name: String, updatedAtMs: Long)

    @Query("UPDATE playlists SET updatedAtMs = :updatedAtMs WHERE id = :playlistId")
    suspend fun touch(playlistId: Long, updatedAtMs: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun deleteTrack(playlistId: Long, songId: String)
}
