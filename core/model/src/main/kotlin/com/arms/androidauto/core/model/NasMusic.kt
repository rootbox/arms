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

// 재생 직전에 만들어지는 "바로 재생 가능한 곡". 스트림 URL과 커버 URL 모두 같은 세션(sid)으로
// 서명돼 있어, 세션이 유효한 동안 재생·앨범아트 표시가 함께 유효하다. 커버를 곡과 같은 시점에
// 묶어두면 재생 경로(폰/차량)가 이미지 로드를 위해 세션을 또 여는 일이 없다.
data class NasTrack(
    val song: NasSong,
    val streamUrl: String,
    val artworkUrl: String
)
