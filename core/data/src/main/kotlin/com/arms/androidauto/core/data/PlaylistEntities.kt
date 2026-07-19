package com.arms.androidauto.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.arms.androidauto.core.model.NasPlaylist
import com.arms.androidauto.core.model.NasPlaylistTrack

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMs: Long,
    val updatedAtMs: Long,
    val sortOrder: Int = 0
)

@Entity(
    tableName = "playlist_tracks",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            // 플레이리스트를 지우면 담긴 곡도 같이 사라진다 (고아 행 방지)
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["playlistId"]),
        // 같은 곡을 같은 플레이리스트에 두 번 담지 못하게 한다.
        // 삽입 시 OnConflictStrategy.IGNORE와 짝을 이룬다.
        Index(value = ["playlistId", "songId"], unique = true)
    ]
)
data class PlaylistTrackEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?,
    val position: Int
)

// 곡 수는 저장하지 않고 조회 시 COUNT로 계산해서 담는다.
data class PlaylistWithCount(
    @ColumnInfo(name = "id") val id: Long,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "updatedAtMs") val updatedAtMs: Long,
    @ColumnInfo(name = "songCount") val songCount: Int
)

fun PlaylistWithCount.toNasPlaylist(): NasPlaylist = NasPlaylist(
    id = id,
    name = name,
    songCount = songCount,
    updatedAtMs = updatedAtMs
)

fun PlaylistTrackEntity.toNasPlaylistTrack(): NasPlaylistTrack = NasPlaylistTrack(
    songId = songId,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist
)
