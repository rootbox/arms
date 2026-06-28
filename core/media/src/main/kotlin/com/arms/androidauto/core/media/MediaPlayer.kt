package com.arms.androidauto.core.media

import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer

class MediaPlayer(private val context: Context) {
    private var player: ExoPlayer? = null

    init {
        // ExoPlayer 초기화. Android Context가 없으면 이 부분에서 문제 발생 가능.
        // 실제 Android 앱에서는 Context가 제공되므로 정상 작동.
        try {
            player = ExoPlayer.Builder(context).build()
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
        }
    }

    fun stop() {
        player?.stop()
        player?.release()
        player = null // 자원 해제 후 null 처리
        println("MediaPlayer: 재생 중지") // 로컬 테스트용 로그
    }

    fun isPlaying(): Boolean {
        return player?.isPlaying ?: false
    }
}
