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
    // UI가 현재 곡 제목을 갱신하려면 이 콜백으로 트랙 전환을 알려줘야 한다.
    var onTrackChanged: ((String?) -> Unit)? = null

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
                        onTrackChanged?.invoke(mediaItem?.mediaMetadata?.title?.toString())
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        onIsPlayingChanged?.invoke(isPlaying)
                    }
                })
            }
        } catch (e: Exception) {
            println("MediaPlayer 초기화 실패: ${e.message}")
            player = null
        }
    }

    fun play(streamUrl: String) {
        player?.let {
            val mediaItem = MediaItem.fromUri(streamUrl)
            it.setMediaItem(mediaItem)
            it.prepare()
            it.play()
            println("MediaPlayer: ${streamUrl} 재생 시작") // 로컬 테스트용 로그
        } ?: run {
            println("MediaPlayer가 초기화되지 않았습니다. 실제 Android 환경에서 실행해주세요.")
            onPlaybackError?.invoke("미디어 플레이어를 사용할 수 없습니다.")
        }
    }

    // NAS 재생목록처럼 여러 곡을 순서대로 재생할 때 사용. 실시간 라디오와 달리 진짜 트랙
    // 탐색(이전/다음/진행바)이 의미가 있으므로, ExoPlayer의 기본 재생목록 기능을 그대로 쓴다.
    fun playQueue(items: List<Pair<String, String>>, startIndex: Int = 0) {
        player?.let {
            val mediaItems = items.map { (title, url) ->
                MediaItem.Builder()
                    .setUri(url)
                    .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).build())
                    .build()
            }
            it.setMediaItems(mediaItems, startIndex, 0L)
            it.prepare()
            it.play()
        } ?: run {
            onPlaybackError?.invoke("미디어 플레이어를 사용할 수 없습니다.")
        }
    }

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
        println("MediaPlayer: 재생 중지") // 로컬 테스트용 로그
    }

    // 액티비티 종료 시 자원 해제. stop()과 분리하여, stop() 이후에도 재생을 재개할 수 있도록 함.
    fun release() {
        player?.release()
        player = null
    }

    fun isPlaying(): Boolean {
        return player?.isPlaying ?: false
    }
}
