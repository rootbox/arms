package com.arms.androidauto.core.model

enum class StationType {
    RADIO, STREAMING
}

data class Station(
    val id: String,
    val name: String,
    val frequencyOrUrl: String, // 라디오 주파수(예: 89.1) 또는 스트리밍 URL
    val type: StationType,
    val isFavorite: Boolean = false
)
