package com.arms.androidauto.desktop

import com.arms.androidauto.core.network.NetworkClient

// 데스크톱 스택 헤드리스 검증: core/network(메타데이터) + DesktopAudioPlayer(VLCJ)를
// AudioPlayer 인터페이스로 묶어 3개 공개 소스를 실제 재생한다. Phase 3에서 Compose UI로 대체.
fun main() {
    val api = NetworkClient.radioApiService
    val player = DesktopAudioPlayer()
    var lastPlaying = false
    player.onIsPlayingChanged = { lastPlaying = it }
    player.onPlaybackError = { println("SMOKE_ERR $it") }

    for ((id, name) in listOf("1" to "KBS", "2" to "SBS", "3" to "KPOP")) {
        val meta = api.getStationMetadata(id)
        val url = api.getPlaybackUrl(id) ?: meta?.streamUrl
        if (url.isNullOrBlank()) { println("SMOKE [$name] no-url"); continue }
        lastPlaying = false
        player.play(url)
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline && !lastPlaying) Thread.sleep(200)
        Thread.sleep(1500)
        println("SMOKE [$name] playing=$lastPlaying posMs=${player.currentPositionMs()} title=${meta?.programTitle?.take(24)} cover=${meta?.imageUrl != null}")
        player.stop(); Thread.sleep(300)
    }
    player.release()
    println("SMOKE done")
}
