package com.example.mopedtrackv2.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "debug_traces")
data class DebugTraceEntity(
    @PrimaryKey val traceId: String,
    val pointsEncoded: String,
    val pointCount: Int,
    val createdAt: Long
)
