package com.arms.androidauto.core.model

// 사용자가 직접 곡을 골라 담는 재생목록.
// songCount는 저장된 컬럼이 아니라 조회 시 곡 테이블을 세어서 채운다
// (담기/빼기마다 두 곳을 맞추다 보면 언젠가 반드시 어긋나기 때문).
data class NasPlaylist(
    val id: Long,
    val name: String,
    val songCount: Int,
    val updatedAtMs: Long
)

// 플레이리스트에 담긴 곡. 곡 ID만 저장하면 목록을 그릴 때마다 NAS 로그인 + 전체 곡 조회가
// 선행되므로, 화면에 필요한 정보는 함께 저장해둔다. 그래야 차량에서 네트워크 없이도
// 목록이 즉시 뜨고 터널이나 NAS 다운 상황에서도 무엇이 담겨 있는지 볼 수 있다.
data class NasPlaylistTrack(
    val songId: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?
) {
    fun toNasSong(): NasSong = NasSong(
        id = songId,
        title = title,
        artist = artist,
        album = album,
        albumArtist = albumArtist
    )
}

fun NasSong.toPlaylistTrack(): NasPlaylistTrack = NasPlaylistTrack(
    songId = id,
    title = title,
    artist = artist,
    album = album,
    albumArtist = albumArtist
)
