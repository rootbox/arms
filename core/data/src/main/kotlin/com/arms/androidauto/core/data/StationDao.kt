package com.arms.androidauto.core.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StationDao {
    @Query("SELECT * FROM stations")
    fun getAllStationEntities(): Flow<List<StationEntity>>

    @Query("SELECT * FROM stations WHERE isFavorite = 1")
    fun getFavoriteStationEntities(): Flow<List<StationEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(stations: List<StationEntity>)

    @Update
    suspend fun updateStation(station: StationEntity)

    @Query("SELECT * FROM stations WHERE id = :stationId")
    suspend fun getStationEntityById(stationId: String): StationEntity?
}
