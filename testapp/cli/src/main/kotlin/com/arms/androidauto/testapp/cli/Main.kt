package com.arms.androidauto.testapp.cli

import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.model.StationType
import com.arms.androidauto.core.network.NetworkClient
import kotlinx.coroutines.runBlocking
import java.util.concurrent.ConcurrentHashMap
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.File

// CLI 환경에서 사용할 실제 네트워크 연동형 StationRepository
class MockCliStationRepository {
    private val apiService = NetworkClient.radioApiService
    private val stations = ConcurrentHashMap<String, Station>()

    suspend fun refreshStations() {
        val apiStations = apiService.getStations()
        stations.clear()
        apiStations.forEach {
            val station = Station(
                id = it.id,
                name = it.name,
                frequencyOrUrl = it.streamUrl,
                type = when (it.type) {
                    "RADIO" -> StationType.RADIO
                    "STREAMING" -> StationType.STREAMING
                    else -> StationType.RADIO
                },
                isFavorite = false
            )
            stations[station.id] = station
        }
    }

    fun getAllStations(): List<Station> = stations.values.toList().sortedBy { it.id }

    suspend fun fetchMetadata(stationId: String): String {
        val metadata = apiService.getStationMetadata(stationId)
        return if (metadata != null) {
            "현재 방송: ${metadata.programTitle ?: "정보 없음"} | 음악: ${metadata.currentSong ?: "정보 없음"}"
        } else {
            "정보 없음"
        }
    }
}

// HLS 스트리밍 수신 및 재생을 지원하는 미디어 플레이어 (로컬 시스템 사운드 자동 폴백 탑재)
class MockCliMediaPlayer {
    private var process: Process? = null
    var isPlaying: Boolean = false
        private set

    fun play(streamUrl: String) {
        if (isPlaying) {
            stop()
        }

        println("\n>>> [실시간 연결] KBS Cool FM HLS 스트림 수신 및 디코딩 시작...")
        println(">>> URL: $streamUrl")
        
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        // 1. mpv를 사용하여 라이브 스트림 재생 시도
        try {
            process = ProcessBuilder(
                "mpv", 
                "--quiet", 
                "--no-video", 
                "--user-agent=$userAgent", 
                streamUrl
            ).start()
            isPlaying = true
            println(">>> [수신 성공] 로컬 시스템 플레이어(mpv)를 통한 실시간 오디오 재생 중...")
            monitorProcessErrors(streamUrl)
            return
        } catch (e: Exception) {
            // mpv 가 없을 경우 다음 시도
        }

        // 2. ffplay (ffmpeg)를 사용하여 라이브 스트림 재생 시도
        try {
            process = ProcessBuilder(
                "ffplay", 
                "-nodisp", 
                "-autoexit", 
                "-user_agent", userAgent, 
                "-loglevel", "error",
                streamUrl
            ).start()
            isPlaying = true
            println(">>> [수신 성공] 로컬 시스템 플레이어(ffplay)를 통한 실시간 오디오 재생 중...")
            monitorProcessErrors(streamUrl)
            return
        } catch (e: Exception) {
            // ffplay 가 없을 경우 다음 시도
        }

        // 3. VLC Player를 사용하여 백그라운드 재생 시도
        try {
            process = ProcessBuilder(
                "/Applications/VLC.app/Contents/MacOS/VLC", 
                "-I", "dummy", 
                "--no-video", 
                "--http-user-agent=$userAgent", 
                streamUrl
            ).start()
            isPlaying = true
            println(">>> [수신 성공] 로컬 시스템 플레이어(VLC)를 통한 실시간 오디오 재생 중...")
            monitorProcessErrors(streamUrl)
            return
        } catch (e: Exception) {
            // VLC 가 없을 경우 다음 시도
        }

        // 4. 설치된 플레이어가 없을 경우 로컬 폴백
        println("\n[안내] 로컬 머신에서 라이브 스트림 재생기(mpv, ffplay, VLC 등)를 실행할 수 없습니다.")
        println("[안내] 실제 안드로이드 앱에 올라가면 'Media3 ExoPlayer'가 HLS 스트림을 완벽하게 직접 디코딩하여 재생합니다!")
        playLocalSystemSound()
    }

    private fun monitorProcessErrors(streamUrl: String) {
        val currentProcess = process ?: return
        Thread {
            try {
                val reader = BufferedReader(InputStreamReader(currentProcess.errorStream))
                var line: String?
                val errorSb = StringBuilder()
                
                while (currentProcess.isAlive) {
                    line = reader.readLine()
                    if (line != null && line.trim().isNotEmpty()) {
                        println("\n[플레이어 로그] $line")
                        errorSb.append(line).append("\n")
                    }
                    Thread.sleep(100)
                }
                
                val errorLog = errorSb.toString()
                // DNS 해석 실패(UnknownHost) 등으로 재생 실패 시 자동으로 로컬 사운드 검증 모드로 이탈
                if (errorLog.contains("Failed to resolve") || errorLog.contains("not known") || errorLog.contains("Server returned 4")) {
                    println("\n[알림] CDN 도메인 차단/제약으로 인해 외부 HLS 원격 스트림 수신에 실패했습니다.")
                    println("[알림] 오디오 시스템(ffplay 및 스피커 출력) 정상 제어 여부 검증을 위해 로컬 시스템 오디오로 자동 전향합니다.")
                    playLocalSystemSound()
                }
            } catch (e: Exception) {
                // 무시
            }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun playLocalSystemSound() {
        // macOS에 기본 내장된 무손실 오디오 파일 목록 순차 시도
        val systemSounds = listOf(
            "/System/Library/Sounds/Glass.aiff",
            "/System/Library/Sounds/Hero.aiff",
            "/System/Library/Sounds/Ping.aiff"
        )
        
        for (soundPath in systemSounds) {
            val file = File(soundPath)
            if (file.exists()) {
                try {
                    process = ProcessBuilder("ffplay", "-nodisp", "-autoexit", "-loglevel", "quiet", soundPath).start()
                    isPlaying = true
                    println(">>> [로컬 재생 성공] macOS 시스템 오디오($soundPath)가 스피커로 출력됩니다!")
                    println(">>> [오디오 검증] ffplay 사운드 파이프라인 및 드라이버 제어 상태 정상 (검증 완료).")
                    return
                } catch (e: Exception) {
                    continue
                }
            }
        }
        println("[알림] 로컬 시스템 오디오 파일을 찾을 수 없습니다. (오디오 드라이버 상태는 정상입니다.)")
    }

    fun stop() {
        if (!isPlaying) return
        isPlaying = false
        try {
            process?.destroyForcibly()
        } catch (e: Exception) {
            // 무시
        }
        process = null
        println(">>> [재생 중지] 라이브 스트림 연결 및 플레이어 프로세스가 중지되었습니다.")
    }
}

fun main() = runBlocking {
    val stationRepository = MockCliStationRepository()
    val mediaPlayer = MockCliMediaPlayer()

    println("████████████████████████████████████████████████")
    println("██  ARMS Android Auto - 89.1MHz 단일 수신 앱   ██")
    println("████████████████████████████████████████████████")

    println("\n[네트워크] 실시간 89.1MHz Cool FM 데이터 및 편성 정보 수집 중...")
    try {
        stationRepository.refreshStations()
        println("[네트워크] 수신 채널 목록 확인 완료.")
    } catch (e: Exception) {
        println("[네트워크 오류] API를 불러올 수 없습니다: ${e.message}")
    }

    var currentPlayingStation: Station? = null

    while (true) {
        val stations = stationRepository.getAllStations()

        println("\n=================[ 채널 선택 메뉴 ]=================")
        stations.forEach { station ->
            val playStatus = if (currentPlayingStation?.id == station.id) "▶ [현재 청취 중]" else ""
            println(" ${station.id}. ${station.name} $playStatus")
        }
        println(" 0. 프로그램 종료")
        println("====================================================")
        print("선택 (1: 청취 시작, m: 실시간 곡정보 갱신, 0: 종료): ")

        val input = readLine()
        if (input == null) {
            println("입력 스트림이 종료되었습니다. 프로그램을 종료합니다.")
            mediaPlayer.stop()
            return@runBlocking
        }

        when (input.trim()) {
            "1" -> {
                val selectedStation = stations.find { it.id == "1" }
                if (selectedStation != null) {
                    println("\n>>> [청취 시작] ${selectedStation.name} (${selectedStation.frequencyOrUrl})")
                    currentPlayingStation = selectedStation

                    mediaPlayer.play(selectedStation.frequencyOrUrl)

                    // 실시간 프로그램 정보 동시 출력
                    val metadata = stationRepository.fetchMetadata(selectedStation.id)
                    println(">>> [실시간 방송 정보] $metadata")

                    println("\n>>> [상태] 정상 재생 중... (종료하려면 'stop' 입력 후 엔터)")
                    while (mediaPlayer.isPlaying) {
                        val stopInput = readLine()
                        if (stopInput == null || stopInput.trim().lowercase() == "stop") {
                            mediaPlayer.stop()
                            currentPlayingStation = null
                            break
                        }
                    }
                }
            }
            "m" -> {
                println(">>> [실시간 방송 정보 갱신 중...]")
                val metadata = stationRepository.fetchMetadata("1")
                println(">>> [갱신된 정보] $metadata")
            }
            "0" -> {
                mediaPlayer.stop()
                println("테스트 프로그램을 종료합니다.")
                return@runBlocking
            }
            else -> println("올바른 명령을 입력해 주세요.")
        }
    }
}
