package com.arms.androidauto.core.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.arms.androidauto.core.model.Station
import com.arms.androidauto.core.model.StationType

@Entity(tableName = "stations")
data class StationEntity(
    @PrimaryKey val id: String,
    val name: String,
    val frequencyOrUrl: String,
    val type: StationType,
    val isFavorite: Boolean
)

fun StationEntity.toStation(): Station {
    return Station(
        id = id,
        name = name,
        frequencyOrUrl = frequencyOrUrl,
        type = type,
        isFavorite = isFavorite
    )
}

fun Station.toStationEntity(): StationEntity {
    return StationEntity(
        id = id,
        name = name,
        frequencyOrUrl = frequencyOrUrl,
        type = type,
        isFavorite = isFavorite
    )
}
