package com.arms.androidauto

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.arms.androidauto.auto.MediaIdScheme
import com.arms.androidauto.core.model.NasAlbum
import com.arms.androidauto.core.playback.AudioPlayer
import com.arms.androidauto.core.playback.QueueTrack
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

// 폰 화면의 재생을 ARMSMediaLibraryService의 세션으로 보내는 AudioPlayer.
//
// 예전엔 폰 화면이 MediaSession 없는 자체 ExoPlayer(core/media MediaPlayer)로 재생했다.
// 블루투스(AVRCP)는 OS의 MediaSession을 통해서만 곡 정보를 받고 버튼을 보내므로, 그 재생은
// 차량 입장에서 "정체불명의 소리"였다(정보 미노출·이전/다음 무반응·재연결 미재개). 이 클래스는
// 폰을 서비스 세션의 컨트롤러 하나로 만들어, 차량(Android Auto)과 완전히 같은 재생 경로
// (편성 정보·커버·이전/다음=채널 전환·자동 재개·알림)를 타게 한다. 플레이어는 하나가 된다.
//
// 라디오/NAS 재생은 URL이 아니라 mediaId로 요청한다. 서비스가 mediaId를 해석해 새로 서명된
// 스트림 URL과 메타데이터를 채우는 검증된 경로(onAddMediaItems)를 그대로 쓰기 위해서다.
class SessionAudioPlayer(context: Context) : AudioPlayer {

    override var onPlaybackError: ((String) -> Unit)? = null
    override var onTrackChanged: ((title: String?, artworkUri: String?) -> Unit)? = null
    override var onIsPlayingChanged: ((Boolean) -> Unit)? = null

    // 서비스 쪽에서 재생 항목이 바뀌었을 때(차량/블루투스의 이전·다음 버튼, 알림, 정지로 큐가
    // 비는 경우) 폰 화면이 따라가도록 알린다. 플레이어가 하나가 되면서 "폰이 시작하지 않은
    // 변경"이 생겼기 때문에, 폰 화면은 자기 상태를 스스로 정하지 말고 세션을 따라야 한다.
    var onMediaIdChanged: ((mediaId: String?) -> Unit)? = null

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var released = false
    private val connected = CompletableDeferred<Boolean>()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { onIsPlayingChanged?.invoke(isPlaying) }
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            onMediaIdChanged?.invoke(mediaItem?.mediaId)
            onTrackChanged?.invoke(
                mediaItem?.mediaMetadata?.title?.toString(),
                mediaItem?.mediaMetadata?.artworkUri?.toString()
            )
        }
        override fun onPlayerError(error: PlaybackException) {
            onPlaybackError?.invoke(error.message ?: "스트림 재생에 실패했습니다.")
        }
    }

    init {
        val token = SessionToken(context, ComponentName(context, ARMSMediaLibraryService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            if (released) { runCatching { future.get().release() }; return@addListener }
            val c = runCatching { future.get() }.getOrElse {
                onPlaybackError?.invoke("미디어 서비스에 연결할 수 없습니다.")
                connected.complete(false)
                return@addListener
            }
            c.addListener(listener)
            controller = c
            connected.complete(true)
            // 연결 전에 들어온 요청을 순서대로 실행한다.
            while (pending.isNotEmpty()) pending.removeFirst()(c)
        }, MoreExecutors.directExecutor())
    }

    // 컨트롤러가 아직 연결 중이면 요청을 보관했다가 연결되는 즉시 실행한다.
    private fun withController(action: (MediaController) -> Unit) {
        controller?.let(action) ?: pending.addLast(action)
    }

    // ---- 이 앱 전용 재생 시작 (mediaId 기반) ----

    fun playStation(stationId: String) = withController { c ->
        c.setMediaItem(MediaItem.Builder().setMediaId(stationId).build())
        c.prepare(); c.play()
    }

    fun playNasAlbum(album: NasAlbum, startIndex: Int = 0, startPositionMs: Long = 0L) = withController { c ->
        c.setMediaItems(listOf(MediaItem.Builder().setMediaId(MediaIdScheme.encodeAlbum(album)).build()), startIndex, startPositionMs)
        c.prepare(); c.play()
    }

    fun playNasPlaylist(playlistId: Long, startIndex: Int = 0, startPositionMs: Long = 0L) = withController { c ->
        c.setMediaItems(listOf(MediaItem.Builder().setMediaId(MediaIdScheme.encodePlaylist(playlistId)).build()), startIndex, startPositionMs)
        c.prepare(); c.play()
    }

    // 앱을 열었을 때 서비스가 이미 재생 중인지(블루투스/알림에서 시작된 경우) 확인용.
    fun currentMediaId(): String? = controller?.currentMediaItem?.mediaId
    fun hasItems(): Boolean = (controller?.mediaItemCount ?: 0) > 0
    fun isPlaying(): Boolean = controller?.isPlaying == true
    fun isConnected(): Boolean = controller != null
    fun currentTitle(): String? = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
    fun currentArtworkUri(): String? = controller?.currentMediaItem?.mediaMetadata?.artworkUri?.toString()

    // 앱을 열자마자 "이미 재생 중인가"를 판단하려면 컨트롤러 연결(비동기)을 잠깐 기다려야 한다.
    suspend fun awaitConnected(timeoutMs: Long = 3_000L): Boolean =
        withTimeoutOrNull(timeoutMs) { connected.await() } == true

    // ---- AudioPlayer 계약 ----

    override fun play(streamUrl: String) = withController { c ->
        c.setMediaItem(MediaItem.fromUri(streamUrl)); c.prepare(); c.play()
    }

    override fun playQueue(items: List<QueueTrack>, startIndex: Int, startPositionMs: Long) = withController { c ->
        val mediaItems = items.map { t ->
            MediaItem.Builder().setUri(t.url).setMediaMetadata(
                MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist).setAlbumTitle(t.album)
                    .apply { t.artworkUri?.let { setArtworkUri(android.net.Uri.parse(it)) } }.build()
            ).build()
        }
        c.setMediaItems(mediaItems, startIndex, startPositionMs); c.prepare(); c.play()
    }

    override fun currentTrackIndex(): Int = controller?.currentMediaItemIndex ?: 0
    override fun nextTrack() { controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } }
    override fun previousTrack() { controller?.let { if (it.hasPreviousMediaItem()) it.seekToPreviousMediaItem() } }
    override fun pause() { controller?.pause() }
    override fun resume() { controller?.play() }
    override fun currentPositionMs(): Long = controller?.currentPosition ?: 0L
    override fun durationMs(): Long {
        val d = controller?.duration ?: C.TIME_UNSET
        return if (d == C.TIME_UNSET) 0L else d
    }
    override fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    override var shuffleEnabled: Boolean
        get() = controller?.shuffleModeEnabled ?: false
        set(value) { controller?.shuffleModeEnabled = value }

    override var repeatMode: Int
        get() = controller?.repeatMode ?: Player.REPEAT_MODE_OFF
        set(value) { controller?.repeatMode = value }

    override fun stop() { controller?.stop() }

    // 액티비티 종료 시 컨트롤러만 놓는다. 재생은 서비스가 계속 이어간다(알림에서 제어).
    override fun release() {
        released = true
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }
}
