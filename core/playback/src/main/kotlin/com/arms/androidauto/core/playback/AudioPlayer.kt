package com.arms.androidauto.core.playback

// 큐 재생에 넘길 곡 하나. 제목만이 아니라 아티스트/앨범/커버까지 실어야
// 미니플레이어·잠금화면·알림에 곡 정보와 앨범 아트가 함께 표시된다.
// 플랫폼 독립 타입이라 안드로이드(ExoPlayer)·데스크톱(VLCJ) 구현이 공유한다.
data class QueueTrack(
    val title: String,
    val url: String,
    val artworkUri: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

// 오디오 재생 계약. 라디오(단일 실시간 스트림)와 NAS 음악(유한 큐) 양쪽을 다룬다.
//
// 안드로이드는 ExoPlayer(Media3), 데스크톱은 VLCJ(libVLC)로 구현한다. UI와 재생 로직은
// 이 인터페이스에만 의존하므로, 어느 플랫폼에서 도는지 알 필요가 없다.
// (현재 안드로이드 MediaPlayer의 공개 API를 그대로 승격한 것이다.)
interface AudioPlayer {

    // 재생 실패(네트워크 오류 등) 시 호출. UI가 사용자에게 실패를 알리는 데 쓴다.
    var onPlaybackError: ((String) -> Unit)?

    // 이전/다음 곡 이동은 UI 상태를 거치지 않고 플레이어 내부에서 일어나므로,
    // 현재 곡 제목/앨범아트를 UI가 갱신하려면 이 콜백으로 트랙 전환을 알려줘야 한다.
    var onTrackChanged: ((title: String?, artworkUri: String?) -> Unit)?

    // 버퍼링·오디오 포커스 상실·에러처럼 플레이어가 먼저 멈추는 경우까지 실제 상태를 전달한다.
    var onIsPlayingChanged: ((Boolean) -> Unit)?

    // 라디오처럼 단일 실시간 스트림을 재생. 큐/탐색 개념이 없다.
    fun play(streamUrl: String)

    // NAS 음악처럼 여러 곡을 순서대로 재생. startPositionMs는 "이어듣기" 복원용.
    fun playQueue(items: List<QueueTrack>, startIndex: Int = 0, startPositionMs: Long = 0L)

    // 이어듣기 저장용. 현재 큐 내 트랙 위치(0-기반). 큐가 없으면 0.
    fun currentTrackIndex(): Int

    fun nextTrack()
    fun previousTrack()
    fun pause()
    fun resume()

    // 진행바용. 실시간 스트림은 duration이 없으므로 0을 돌려준다.
    fun currentPositionMs(): Long
    fun durationMs(): Long
    fun seekTo(positionMs: Long)

    var shuffleEnabled: Boolean

    // REPEAT_MODE_OFF(0) / REPEAT_MODE_ONE(1) / REPEAT_MODE_ALL(2) — Media3 Player 상수와 동일 의미.
    var repeatMode: Int

    fun stop()
    fun release()
}
