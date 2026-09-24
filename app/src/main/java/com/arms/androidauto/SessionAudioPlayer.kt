package com.arms.androidauto

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
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

    // 서비스가 현재 항목의 정보(곡 제목·커버 등)를 갱신했을 때. 인밴드(ICY) 채널의 곡 정보는
    // 폰이 따로 조회하지 않고 이 값을 그대로 보여준다.
    var onNowPlayingChanged: ((title: String?, subtitle: String?, artist: String?, artworkUri: String?) -> Unit)? = null

    // 외부(미디어키·헤드유닛·알림)에서 정지돼 플레이어가 IDLE이 됐을 때. 큐는 남아 있어 항목 전환
    // 이벤트가 없으므로, 폰 화면이 "재생 중" 표시를 내리려면 이 신호가 필요하다.
    var onStoppedExternally: (() -> Unit)? = null

    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var released = false
    private val connected = CompletableDeferred<Boolean>()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { onIsPlayingChanged?.invoke(isPlaying) }
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_IDLE && !released) onStoppedExternally?.invoke()
        }
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
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            onNowPlayingChanged?.invoke(
                mediaMetadata.title?.toString(), mediaMetadata.subtitle?.toString(),
                mediaMetadata.artist?.toString(), mediaMetadata.artworkUri?.toString()
            )
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
    // 컨트롤러는 인자를 자기 기준으로 검증해 예외를 던질 수 있다(예: IllegalSeekPositionException).
    // 재생 요청 하나가 앱 전체를 죽이면 안 되므로 여기서 잡아 재생 오류로 알린다.
    private fun withController(action: (MediaController) -> Unit) {
        val safe: (MediaController) -> Unit = { c ->
            try { action(c) } catch (e: Exception) {
                onPlaybackError?.invoke(e.message ?: e.javaClass.simpleName)
            }
        }
        controller?.let(safe) ?: pending.addLast(safe)
    }

    // "앨범의 n번째 곡부터 / 이어듣기 위치"는 setMediaItems(startIndex)로 보낼 수 없다.
    // 컨트롤러가 서비스로 보내기 전에 자기가 받은 목록(앨범 항목 1개) 기준으로 인덱스를 검증해
    // IllegalSeekPositionException을 던지기 때문이다(rc1 크래시). 대신 요청 메타데이터에 실어 보내고,
    // 서비스가 큐를 실제 곡들로 확장한 뒤 시작 위치를 정한다.
    private fun requestItem(mediaId: String, startIndex: Int, startPositionMs: Long): MediaItem =
        MediaItem.Builder().setMediaId(mediaId).setRequestMetadata(
            MediaItem.RequestMetadata.Builder().setExtras(Bundle().apply {
                putInt(EXTRA_START_INDEX, startIndex); putLong(EXTRA_START_POSITION_MS, startPositionMs)
            }).build()
        ).build()

    // ---- 이 앱 전용 재생 시작 (mediaId 기반) ----

    fun playStation(stationId: String) = withController { c ->
        c.setMediaItem(MediaItem.Builder().setMediaId(stationId).build())
        c.prepare(); c.play()
    }

    fun playNasAlbum(album: NasAlbum, startIndex: Int = 0, startPositionMs: Long = 0L) = withController { c ->
        c.setMediaItem(requestItem(MediaIdScheme.encodeAlbum(album), startIndex, startPositionMs))
        c.prepare(); c.play()
    }

    fun playNasPlaylist(playlistId: Long, startIndex: Int = 0, startPositionMs: Long = 0L) = withController { c ->
        c.setMediaItem(requestItem(MediaIdScheme.encodePlaylist(playlistId), startIndex, startPositionMs))
        c.prepare(); c.play()
    }

    // 앱을 열었을 때 서비스가 이미 재생 중인지(블루투스/알림에서 시작된 경우) 확인용.
    fun currentMediaId(): String? = controller?.currentMediaItem?.mediaId
    fun hasItems(): Boolean = (controller?.mediaItemCount ?: 0) > 0
    fun isPlaying(): Boolean = controller?.isPlaying == true
    fun isConnected(): Boolean = controller != null
    fun currentTitle(): String? = controller?.currentMediaItem?.mediaMetadata?.title?.toString()
    fun currentSubtitle(): String? = controller?.currentMediaItem?.mediaMetadata?.subtitle?.toString()
    fun currentArtist(): String? = controller?.currentMediaItem?.mediaMetadata?.artist?.toString()
    fun currentArtworkUri(): String? = controller?.currentMediaItem?.mediaMetadata?.artworkUri?.toString()
    fun isIdle(): Boolean = controller?.playbackState == Player.STATE_IDLE

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
    // 외부 STOP으로 IDLE이 된 뒤에는 prepare()가 있어야 다시 재생된다(큐는 남아 있음).
    override fun resume() { controller?.let { if (it.playbackState == Player.STATE_IDLE) it.prepare(); it.play() } }
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

    companion object {
        const val EXTRA_START_INDEX = "com.arms.androidauto.START_INDEX"
        const val EXTRA_START_POSITION_MS = "com.arms.androidauto.START_POSITION_MS"
    }

    // 액티비티 종료 시 컨트롤러만 놓는다. 재생은 서비스가 계속 이어간다(알림에서 제어).
    override fun release() {
        released = true
        controller?.removeListener(listener)
        controller?.release()
        controller = null
    }
}
