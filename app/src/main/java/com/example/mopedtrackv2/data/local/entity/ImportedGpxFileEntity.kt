package com.example.mopedtrackv2.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "imported_gpx_files")
data class ImportedGpxFileEntity(
    @PrimaryKey val fileHash: String,
    val fileName: String,
    val importedAt: Long,
    val trackPointCount: Int,
    val segmentsCompletedCount: Int,
    val encodedTrace: String = "",
    val isVisibleOnMap: Boolean = false
)
