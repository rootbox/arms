package com.arms.androidauto.core.data

import android.content.Context
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.network.NetworkClient
import com.arms.androidauto.core.network.StationApiResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

class StationRepository(context: Context) {
    private val stationDao = AppDatabase.getDatabase(context).stationDao()
    private val radioApiService = NetworkClient.radioApiService

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

    // 실제 네트워크 호출로 메타데이터 조회
    suspend fun fetchMetadata(stationId: String): String {
        val metadata = withContext(Dispatchers.IO) { radioApiService.getStationMetadata(stationId) }
        return if (metadata != null) {
            "현재 방송: ${metadata.programTitle ?: "정보 없음"} | 음악: ${metadata.currentSong ?: "정보 없음"}"
        } else {
            "정보 없음"
        }
    }
}
