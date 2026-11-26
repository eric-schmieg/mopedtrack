package com.example.mopedtrackv2.domain.model

enum class RideRecordingState {
    IDLE,
    RECORDING,
    PAUSED
}

data class TrackPoint(
    val lat: Double,
    val lon: Double,
    val altitude: Double? = null,
    val timestamp: Long,
    val speed: Float? = null,
    val accuracy: Float? = null
)
