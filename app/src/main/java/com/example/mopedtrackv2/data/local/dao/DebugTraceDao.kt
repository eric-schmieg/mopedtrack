package com.example.mopedtrackv2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DebugTraceDao {
    @Query("SELECT * FROM debug_traces WHERE traceId = 'last_trace' LIMIT 1")
    fun getLastTraceFlow(): Flow<DebugTraceEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrace(trace: DebugTraceEntity)

    @Query("DELETE FROM debug_traces")
    suspend fun clearAllTraces()
}
