package com.example.mopedtrackv2.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.mopedtrackv2.data.local.entity.RegionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RegionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegion(region: RegionEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRegions(regions: List<RegionEntity>)

    @Query("SELECT * FROM regions")
    fun getAllRegions(): Flow<List<RegionEntity>>

    @Query("SELECT * FROM regions WHERE regionId = :regionId")
    suspend fun getRegionById(regionId: String): RegionEntity?

    @Query("DELETE FROM regions WHERE regionId = :regionId")
    suspend fun deleteRegionById(regionId: String)

    @Query("DELETE FROM regions")
    suspend fun clearRegions()
}
