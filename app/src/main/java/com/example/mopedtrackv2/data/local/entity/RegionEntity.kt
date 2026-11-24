package com.example.mopedtrackv2.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "regions")
data class RegionEntity(
    @PrimaryKey
    val regionId: String,
    val name: String,
    val adminLevel: Int,
    val osmRelationId: Long,
    val boundingBox: String, // Can store as JSON string or minLat,minLon,maxLat,maxLon
    val lastUpdatedTimestamp: Long
)
