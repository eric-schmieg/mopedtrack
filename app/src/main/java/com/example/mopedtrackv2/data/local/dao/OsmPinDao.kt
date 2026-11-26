package com.example.mopedtrackv2.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface OsmPinDao {
    @Query("SELECT * FROM osm_pins ORDER BY createdAt DESC")
    fun getAllPinsFlow(): Flow<List<OsmPinEntity>>

    @Query("SELECT * FROM osm_pins WHERE isResolved = 0 ORDER BY createdAt DESC")
    fun getUnresolvedPinsFlow(): Flow<List<OsmPinEntity>>

    @Query("SELECT * FROM osm_pins WHERE pinId = :pinId LIMIT 1")
    suspend fun getPinById(pinId: String): OsmPinEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPin(pin: OsmPinEntity)

    @Update
    suspend fun updatePin(pin: OsmPinEntity)

    @Query("UPDATE osm_pins SET isResolved = :isResolved WHERE pinId = :pinId")
    suspend fun setPinResolved(pinId: String, isResolved: Boolean)

    @Delete
    suspend fun deletePin(pin: OsmPinEntity)

    @Query("DELETE FROM osm_pins WHERE pinId = :pinId")
    suspend fun deletePinById(pinId: String)

    @Query("DELETE FROM osm_pins")
    suspend fun clearAllPins()
}
