package com.arms.androidauto.core.media

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class MediaPlayer(private val context: Context) {
    private var player: ExoPlayer? = null

    // 재생 실패(네트워크 오류 등) 시 호출되는 콜백. UI에서 사용자에게 실패를 알리는 데 사용.
    var onPlaybackError: ((String) -> Unit)? = null

    // 이전/다음 곡 이동은 Compose State를 거치지 않고 ExoPlayer 내부에서 바로 일어나므로,
    // UI가 현재 곡 제목/앨범아트를 갱신하려면 이 콜백으로 트랙 전환을 알려줘야 한다.
    var onTrackChanged: ((title: String?, artworkUri: String?) -> Unit)? = null

    // 재생/일시정지 상태를 UI가 직접 토글해서 들고 있으면, 버퍼링·오디오 포커스 상실·에러처럼
    // 플레이어 쪽에서 먼저 멈추는 경우와 어긋난다. 실제 상태를 그대로 전달받는다.
    var onIsPlayingChanged: ((Boolean) -> Unit)? = null

    init {
        // ExoPlayer 초기화. Android Context가 없으면 이 부분에서 문제 발생 가능.
        // 실제 Android 앱에서는 Context가 제공되므로 정상 작동.
        try {
            player = ExoPlayer.Builder(context).build().apply {
                // 오디오 포커스를 명시적으로 요청해야 블루투스 등 외부 출력 경로가 확실히 열린다.
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus= */ true
                )
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        onPlaybackError?.invoke(error.message ?: "스트림 재생에 실패했습니다.")
                    }

                    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                        onTrackChanged?.invoke(
                            mediaItem?.mediaMetadata?.title?.toString(),
                            mediaItem?.mediaMetadata?.artworkUri?.toString()
                        )
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        onIsPlayingChanged?.invoke(isPlaying)
                    }
                })
            }
        } catch (e: Exception) {
            // 초기화 실패 시 player는 null로 남고, 이후 모든 재생 요청이 onPlaybackError로 알린다.
            player = null
        }
    }

    fun play(streamUrl: String) {
        player?.let {
            val mediaItem = MediaItem.fromUri(streamUrl)
            it.setMediaItem(mediaItem)
            it.prepare()
            it.play()
        } ?: run {
            onPlaybackError?.invoke("미디어 플레이어를 사용할 수 없습니다.")
        }
    }

    // playQueue에 넘길 곡 하나. 제목만이 아니라 아티스트/앨범/커버까지 실어야
    // 미니플레이어·잠금화면·알림에 곡 정보와 앨범 아트가 함께 표시된다.
    data class QueueTrack(
        val title: String,
        val url: String,
        val artworkUri: String? = null,
        val artist: String? = null,
        val album: String? = null
    )

    // NAS 재생목록처럼 여러 곡을 순서대로 재생할 때 사용. 실시간 라디오와 달리 진짜 트랙
    // 탐색(이전/다음/진행바)이 의미가 있으므로, ExoPlayer의 기본 재생목록 기능을 그대로 쓴다.
    // startPositionMs는 앱 재시작 후 "이어듣기"에서 마지막 위치부터 재생을 재개할 때 쓴다.
    fun playQueue(items: List<QueueTrack>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        player?.let {
            val mediaItems = items.map { track ->
                val metadata = androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .apply { track.artworkUri?.let { uri -> setArtworkUri(android.net.Uri.parse(uri)) } }
                    .build()
                MediaItem.Builder()
                    .setUri(track.url)
                    .setMediaMetadata(metadata)
                    .build()
            }
            it.setMediaItems(mediaItems, startIndex, startPositionMs)
            it.prepare()
            it.play()
        } ?: run {
            onPlaybackError?.invoke("미디어 플레이어를 사용할 수 없습니다.")
        }
    }

    // 이어듣기 저장용. 현재 재생 중인 큐 내 트랙 위치(0-기반). 큐가 없으면 0.
    fun currentTrackIndex(): Int = player?.currentMediaItemIndex ?: 0

    fun nextTrack() { player?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    fun previousTrack() { player?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() } }

    // NAS 재생목록처럼 큐 위치가 있는 재생의 진짜 일시정지/재개. 실시간 라디오에는 쓰지 않는다.
    fun pause() { player?.pause() }
    fun resume() { player?.play() }

    // 진행바용. 라디오(실시간 스트림)는 duration이 없으므로 0을 돌려준다.
    fun currentPositionMs(): Long = player?.currentPosition ?: 0L

    fun durationMs(): Long {
        val duration = player?.duration ?: C.TIME_UNSET
        return if (duration == C.TIME_UNSET) 0L else duration
    }

    fun seekTo(positionMs: Long) { player?.seekTo(positionMs) }

    // 셔플/반복은 ExoPlayer가 큐 순서를 직접 관리하므로 그대로 위임하면 된다.
    var shuffleEnabled: Boolean
        get() = player?.shuffleModeEnabled ?: false
        set(value) { player?.shuffleModeEnabled = value }

    // Player.REPEAT_MODE_OFF / REPEAT_MODE_ONE / REPEAT_MODE_ALL
    var repeatMode: Int
        get() = player?.repeatMode ?: Player.REPEAT_MODE_OFF
        set(value) { player?.repeatMode = value }

    fun stop() {
        player?.stop()
    }

    // 액티비티 종료 시 자원 해제. stop()과 분리하여, stop() 이후에도 재생을 재개할 수 있도록 함.
    fun release() {
        player?.release()
        player = null
    }
}
