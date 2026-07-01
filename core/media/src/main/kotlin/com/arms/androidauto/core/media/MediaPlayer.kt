package com.arms.androidauto.core.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer

class MediaPlayer(private val context: Context) {
    private var player: ExoPlayer? = null

    // 재생 실패(네트워크 오류 등) 시 호출되는 콜백. UI에서 사용자에게 실패를 알리는 데 사용.
    var onPlaybackError: ((String) -> Unit)? = null

    init {
        // ExoPlayer 초기화. Android Context가 없으면 이 부분에서 문제 발생 가능.
        // 실제 Android 앱에서는 Context가 제공되므로 정상 작동.
        try {
            player = ExoPlayer.Builder(context).build().apply {
                addListener(object : Player.Listener {
                    override fun onPlayerError(error: PlaybackException) {
                        onPlaybackError?.invoke(error.message ?: "스트림 재생에 실패했습니다.")
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
