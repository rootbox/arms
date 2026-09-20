package com.arms.androidauto.core.data

import android.content.Context
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.network.NetworkClient
import com.arms.androidauto.core.network.StationApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

data class NowPlayingInfo(
    val programTitle: String,
    val currentSong: String,
    val imageUrl: String?
)

class StationRepository(context: Context) {
    private val appContext = context.applicationContext
    private val stationDao = AppDatabase.getDatabase(context).stationDao()
    private val radioApiService = NetworkClient.radioApiService
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // 초기 방송국 목록을 네트워크에서 가져와 DB에 저장 (초기 캐싱)
    suspend fun refreshStations() {
        val apiStations = withContext(Dispatchers.IO) { radioApiService.getStations() }
        val stationEntities = apiStations.map { apiResponse ->
            StationEntity(
                id = apiResponse.id,
                name = apiResponse.name,
                frequencyOrUrl = apiResponse.streamUrl,
                type = when (apiResponse.type) {
                    "RADIO" -> com.arms.androidauto.core.model.StationType.RADIO
                    "STREAMING" -> com.arms.androidauto.core.model.StationType.STREAMING
                    else -> com.arms.androidauto.core.model.StationType.RADIO // 기본값
                },
                isFavorite = stationDao.getStationEntityById(apiResponse.id)?.isFavorite ?: false
            )
        }
        stationDao.insertAll(stationEntities)
    }

    fun getAllStations(): Flow<List<Station>> {
        return stationDao.getAllStationEntities().map { entities ->
            entities.map { it.toStation() }
        }
    }

    fun getFavoriteStations(): Flow<List<Station>> {
        return stationDao.getFavoriteStationEntities().map { entities ->
            entities.map { it.toStation() }
        }
    }

    suspend fun updateStationFavoriteStatus(stationId: String, isFavorite: Boolean) {
        val stationEntity = stationDao.getStationEntityById(stationId)
        if (stationEntity != null) {
            stationDao.updateStation(stationEntity.copy(isFavorite = isFavorite))
        }
    }

    // 재생 직전에 호출: 캐시된 URL은 서명 토큰이 만료되었을 수 있으므로 항상 새로 받아옴
    suspend fun getPlaybackUrl(stationId: String): String? {
        return withContext(Dispatchers.IO) { radioApiService.getPlaybackUrl(stationId) }
    }

    // 실제 네트워크 호출로 메타데이터(방송 프로그램명, 곡 정보, 이미지) 조회
    // 조회에 실패하면 null. 예전에는 실패를 "정보 없음"이라는 정상 값처럼 돌려줬는데, 그러면
    // 호출부가 "정보가 바뀌었다"고 판단해 멀쩡한 편성 정보와 커버를 지워버렸다.
    // 실패는 실패로 알려서 호출부가 기존 값을 유지하게 한다.
    suspend fun fetchMetadata(stationId: String): NowPlayingInfo? {
        val metadata = withContext(Dispatchers.IO) { radioApiService.getStationMetadata(stationId) }
            ?: return null
        return NowPlayingInfo(
            programTitle = metadata.programTitle ?: "정보 없음",
            currentSong = metadata.currentSong ?: "정보 없음",
            imageUrl = metadata.imageUrl ?: defaultArtworkUri(stationId)
        )
    }

    // 곡별 커버가 없는 스트리밍 채널(발라드/2세대)은 회색 대신 채널 아트를 보여준다.
    // 앱에 번들된 리소스라 네트워크 없이 항상 정확하다. 폰(Coil)·차량(서비스 loadArtwork) 모두
    // android.resource:// URI를 읽을 수 있다.
    private fun defaultArtworkUri(stationId: String): String? {
        val res = when (stationId) {
            "4" -> R.drawable.art_kpop_ballad
            "5" -> R.drawable.art_kpop_rewind
            else -> return null
        }
        return "android.resource://${appContext.packageName}/$res"
    }

    // 다음 실행 시 자동 재개할 수 있도록 마지막으로 재생한 채널을 저장
    fun saveLastPlayedStationId(stationId: String) {
        prefs.edit().putString(KEY_LAST_PLAYED_STATION_ID, stationId).apply()
    }

    fun getLastPlayedStationId(): String? {
        return prefs.getString(KEY_LAST_PLAYED_STATION_ID, null)
    }

    companion object {
        private const val PREFS_NAME = "arms_radio_prefs"
        private const val KEY_LAST_PLAYED_STATION_ID = "last_played_station_id"
    }
}
