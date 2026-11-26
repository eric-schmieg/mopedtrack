package com.example.mopedtrackv2.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "osm_pins")
data class OsmPinEntity(
    @PrimaryKey
    val pinId: String = UUID.randomUUID().toString(),
    val lat: Double,
    val lon: Double,
    val category: String,
    val note: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val isResolved: Boolean = false
)
