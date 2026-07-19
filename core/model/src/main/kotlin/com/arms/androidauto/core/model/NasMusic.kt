package com.arms.androidauto.core.model

// Synology Audio Station에 별도로 만들어둔 재생목록이 없는 경우가 많아, 실제 라이브러리에 이미
// 정리되어 있는 "앨범" 단위를 보관함으로 사용한다. 앨범은 자체 ID가 없어 (앨범명, 앨범 아티스트)
// 조합을 키로 쓴다.
data class NasAlbum(
    val name: String,
    val albumArtist: String,
    val songCount: Int
) {
    val key: String get() = "$albumArtist|||$name"
}

data class NasSong(
    val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val albumArtist: String?
)
