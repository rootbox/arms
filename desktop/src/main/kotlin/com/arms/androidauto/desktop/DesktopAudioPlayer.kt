package com.arms.androidauto.desktop

import com.arms.androidauto.core.playback.AudioPlayer
import com.arms.androidauto.core.playback.QueueTrack
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.component.AudioPlayerComponent
import java.util.concurrent.Executors

// AudioPlayer의 데스크톱 구현. 안드로이드 ExoPlayer와 같은 계약을, macOS/Windows에서는
// VLCJ(libVLC)로 채운다. 라디오(단일 스트림)와 NAS 음악(유한 큐)을 모두 다룬다.
//
// 큐/셔플/반복/이어듣기는 VLCJ의 리스트 플레이어 대신 단일 플레이어 + 자체 큐 상태로 구현한다.
// Phase 0에서 검증된 단일 플레이어 API만 써서 동작을 예측 가능하게 유지하고, 셔플 순서·현재
// 인덱스·이어듣기 위치를 우리가 직접 제어하기 위해서다.
class DesktopAudioPlayer : AudioPlayer {

    override var onPlaybackError: ((String) -> Unit)? = null
    override var onTrackChanged: ((title: String?, artworkUri: String?) -> Unit)? = null
    override var onIsPlayingChanged: ((Boolean) -> Unit)? = null

    private val component = AudioPlayerComponent()
    private val player = component.mediaPlayer()
    // VLCJ 이벤트 스레드에서 플레이어를 다시 호출하면 안 되므로(문서 경고), 트랙 전환 같은
    // "다음 동작"은 별도 스레드로 던진다.
    private val worker = Executors.newSingleThreadExecutor { Runnable { }.let { Thread(it).apply { isDaemon = true } } }

    // 큐 재생 상태
    private var queue: List<QueueTrack> = emptyList()
    private var order: List<Int> = emptyList()   // queue 인덱스의 재생 순서(셔플 반영)
    private var orderPos: Int = -1               // order 내 현재 위치
    private var isQueueMode = false

    override var shuffleEnabled: Boolean = false
        set(value) {
            field = value
            if (isQueueMode) rebuildOrderKeepingCurrent()
        }

    override var repeatMode: Int = 0 // 0 OFF / 1 ONE / 2 ALL (Media3 상수와 동일 의미)

    init {
        NativeDiscovery().discover()
        player.audio().setVolume(70)
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mp: MediaPlayer) { onIsPlayingChanged?.invoke(true) }
            override fun paused(mp: MediaPlayer) { onIsPlayingChanged?.invoke(false) }
            override fun stopped(mp: MediaPlayer) { onIsPlayingChanged?.invoke(false) }
            override fun error(mp: MediaPlayer) {
                onPlaybackError?.invoke("재생에 실패했습니다.")
            }
            override fun finished(mp: MediaPlayer) {
                if (isQueueMode) worker.execute { advanceOnFinish() }
            }
        })
    }

    override fun play(streamUrl: String) {
        isQueueMode = false
        queue = emptyList(); order = emptyList(); orderPos = -1
        player.media().play(streamUrl)
        onTrackChanged?.invoke(null, null)
    }

    override fun playQueue(items: List<QueueTrack>, startIndex: Int, startPositionMs: Long) {
        if (items.isEmpty()) { onPlaybackError?.invoke("재생할 곡이 없습니다."); return }
        queue = items
        isQueueMode = true
        val safeStart = startIndex.coerceIn(0, items.lastIndex)
        order = buildOrder(items.indices.toList(), if (shuffleEnabled) safeStart else -1)
        orderPos = if (shuffleEnabled) 0 else safeStart
        playCurrent(startPositionMs)
    }

    private fun buildOrder(indices: List<Int>, firstIndex: Int): List<Int> {
        if (firstIndex < 0) return indices // 순차
        // 셔플: 지정한 시작 곡을 맨 앞에 두고 나머지를 섞는다
        val rest = indices.filter { it != firstIndex }.shuffled()
        return listOf(firstIndex) + rest
    }

    private fun rebuildOrderKeepingCurrent() {
        val current = currentTrackIndex()
        order = buildOrder(queue.indices.toList(), if (shuffleEnabled) current else -1)
        orderPos = if (shuffleEnabled) 0 else current
    }

    private fun playCurrent(startPositionMs: Long = 0L) {
        val trackIndex = order.getOrNull(orderPos) ?: return
        val track = queue[trackIndex]
        // libVLC은 :start-time 옵션(초)으로 시작 지점을 지정할 수 있다 → 이어듣기 복원.
        if (startPositionMs > 0) {
            player.media().play(track.url, ":start-time=${startPositionMs / 1000}")
        } else {
            player.media().play(track.url)
        }
        onTrackChanged?.invoke(track.title, track.artworkUri)
    }

    private fun advanceOnFinish() {
        when (repeatMode) {
            1 -> playCurrent()                       // 한 곡 반복
            else -> {
                if (orderPos < order.lastIndex) { orderPos++; playCurrent() }
                else if (repeatMode == 2) { orderPos = 0; playCurrent() } // 전체 반복
                // OFF면 큐 끝에서 정지
            }
        }
    }

    override fun nextTrack() {
        if (!isQueueMode) return
        worker.execute {
            if (orderPos < order.lastIndex) orderPos++ else if (repeatMode == 2) orderPos = 0 else return@execute
            playCurrent()
        }
    }

    override fun previousTrack() {
        if (!isQueueMode) return
        worker.execute {
            if (orderPos > 0) orderPos-- else if (repeatMode == 2) orderPos = order.lastIndex else return@execute
            playCurrent()
        }
    }

    // 이어듣기 저장용: 원본 items 기준 인덱스(셔플과 무관). 큐가 없으면 0.
    override fun currentTrackIndex(): Int = order.getOrNull(orderPos) ?: 0

    override fun pause() { player.controls().setPause(true) }
    override fun resume() { player.controls().setPause(false) }
    override fun currentPositionMs(): Long = player.status().time().coerceAtLeast(0)
    override fun durationMs(): Long = player.status().length().let { if (it > 0) it else 0L }
    override fun seekTo(positionMs: Long) { player.controls().setTime(positionMs) }

    override fun stop() { player.controls().stop() }
    override fun release() {
        worker.shutdownNow()
        component.release()
    }
}
