package com.example.mopedtrackv2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ImportedGpxFileDao {
    @Query("SELECT * FROM imported_gpx_files ORDER BY importedAt DESC")
    fun getAllFilesFlow(): Flow<List<ImportedGpxFileEntity>>

    @Query("SELECT * FROM imported_gpx_files WHERE fileHash = :hash LIMIT 1")
    suspend fun getFileByHash(hash: String): ImportedGpxFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(file: ImportedGpxFileEntity)

    @Query("SELECT COUNT(*) FROM imported_gpx_files")
    suspend fun getImportedFilesCount(): Int

    @Query("UPDATE imported_gpx_files SET isVisibleOnMap = :isVisible WHERE fileHash = :hash")
    suspend fun updateVisibility(hash: String, isVisible: Boolean)
    
    @Query("SELECT * FROM imported_gpx_files WHERE isVisibleOnMap = 1")
    fun getVisibleFilesFlow(): Flow<List<ImportedGpxFileEntity>>

    @Query("DELETE FROM imported_gpx_files WHERE encodedTrace = ''")
    suspend fun deleteLegacyTraces()
    
    @Query("UPDATE imported_gpx_files SET segmentsCompletedCount = 0")
    suspend fun resetAllSegmentsCompletedCount()
}
