package com.arms.androidauto.auto

import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import com.arms.androidauto.core.data.NasMusicRepository
import com.arms.androidauto.core.data.NasPlaylistRepository
import com.arms.androidauto.core.data.PlaybackStateStore
import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.core.model.NasSong

// 차량 브라우징 트리에서 NAS 음악 부분(최근 재생 / 아티스트 → 앨범)을 구성하고,
// 앨범을 실제 재생 큐로 바꾸는 책임만 담당한다.
@OptIn(UnstableApi::class)
class NasBrowseTree(
    private val nasRepository: NasMusicRepository,
    private val nasPlaylistRepository: NasPlaylistRepository,
    private val playbackStateStore: PlaybackStateStore
) {

    // 플레이리스트 목록은 DB만 읽는다 (네트워크 0). 담을 때 곡 정보까지 저장해둔 덕분에
    // NAS에 연결하지 못하는 상황에서도 목록 자체는 즉시 뜬다.
    suspend fun playlistItems(): List<MediaItem> =
        nasPlaylistRepository.getPlaylists().map { playlist ->
            val metadata = MediaMetadata.Builder()
                .setTitle(playlist.name)
                .setSubtitle("${playlist.songCount}곡")
                .setIsBrowsable(false)
                // 운전 중 탭 수를 줄이기 위해 곡 목록으로 들어가지 않고 바로 재생한다
                .setIsPlayable(true)
                .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                .build()
            MediaItem.Builder()
                .setMediaId(MediaIdScheme.encodePlaylist(playlist.id))
                .setMediaMetadata(metadata)
                .build()
        }

    suspend fun hasPlaylists(): Boolean = nasPlaylistRepository.getPlaylists().isNotEmpty()

    suspend fun findPlaylist(playlistId: Long) = nasPlaylistRepository.getPlaylist(playlistId)

    // 담아둔 순서대로 재생 큐를 만든다.
    // 표시 정보는 저장본이 아니라 NAS가 실제로 돌려준 곡 정보를 쓴다 - 그래야 재생되는 곡과
    // 화면에 뜨는 제목이 어긋나지 않는다.
    suspend fun playlistQueue(playlistId: Long): List<MediaItem> {
        val tracks = nasPlaylistRepository.getTracks(playlistId)
        if (tracks.isEmpty()) return emptyList()
        return nasRepository.getStreamUrlsForSongIds(tracks.map { it.songId })
            .mapIndexed { index, (song, streamUrl) -> songItem(song, streamUrl, index) }
    }

    // 최근 재생 목록은 저장된 정보만으로 그린다. NAS에 연결하지 않고도(터널, 로그인 실패 등)
    // 목록이 즉시 뜨고, 실제로 눌렀을 때 처음 네트워크를 쓴다.
    fun recentAlbumItems(): List<MediaItem> =
        playbackStateStore.getRecentAlbums().map { albumItem(it) }

    suspend fun artistFolderItems(): List<MediaItem> {
        return nasRepository.getAlbums()
            .groupBy { it.albumArtist }
            .toSortedMap()
            .map { (artist, albums) -> artistFolderItem(artist, albums.size) }
    }

    suspend fun albumItemsOfArtist(albumArtist: String): List<MediaItem> {
        return nasRepository.getAlbums()
            .filter { it.albumArtist == albumArtist }
            .sortedBy { it.name }
            .map { albumItem(it) }
    }

    suspend fun findAlbum(albumArtist: String, name: String): NasAlbum? =
        nasRepository.getAlbums().find { it.albumArtist == albumArtist && it.name == name }

    // 앨범 하나를 곡 순서대로 된 재생 큐로 변환한다.
    // 각 곡의 mediaId에도 NAS 접두사를 유지해야, 트랙이 넘어간 뒤에도 플레이어가
    // "지금 NAS를 재생 중"이라고 계속 판단할 수 있다 (이전/다음 동작 분기의 근거).
    suspend fun albumQueue(album: NasAlbum): List<MediaItem> {
        return nasRepository.getAlbumStreamUrls(album).mapIndexed { index, (song, streamUrl) ->
            songItem(song, streamUrl, index)
        }
    }

    private fun songItem(song: NasSong, streamUrl: String, index: Int): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist ?: song.albumArtist)
            .setAlbumTitle(song.album)
            .setAlbumArtist(song.albumArtist)
            .setTrackNumber(index + 1)
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .build()
        return MediaItem.Builder()
            // 곡에도 NAS 접두사를 유지해야 트랙이 넘어간 뒤에도 플레이어가
            // "NAS 재생 중"으로 계속 판단한다 (이전/다음 동작 분기의 근거)
            .setMediaId(MediaIdScheme.encodeSong(song.id))
            .setUri(streamUrl)
            .setMediaMetadata(metadata)
            .build()
    }

    private fun albumItem(album: NasAlbum): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(album.name)
            .setSubtitle(album.albumArtist.ifBlank { "알 수 없는 아티스트" })
            .setArtist(album.albumArtist)
            .setIsBrowsable(false)
            // 앨범 자체를 눌러 재생을 시작한다 (누르면 앨범 전체가 큐가 된다)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ALBUM)
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaIdScheme.encodeAlbum(album))
            .setMediaMetadata(metadata)
            .build()
    }

    private fun artistFolderItem(albumArtist: String, albumCount: Int): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(albumArtist.ifBlank { "알 수 없는 아티스트" })
            .setSubtitle("${albumCount}개 앨범")
            .setIsBrowsable(true)
            .setIsPlayable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_ARTIST)
            .build()
        return MediaItem.Builder()
            .setMediaId(MediaIdScheme.encodeArtist(albumArtist))
            .setMediaMetadata(metadata)
            .build()
    }
}
