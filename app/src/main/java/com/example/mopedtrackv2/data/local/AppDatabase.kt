package com.example.mopedtrackv2.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.mopedtrackv2.data.local.dao.DebugTraceDao
import com.example.mopedtrackv2.data.local.dao.ImportedGpxFileDao
import com.example.mopedtrackv2.data.local.dao.RegionDao
import com.example.mopedtrackv2.data.local.dao.RoadSegmentDao
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.RegionEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity

@Database(
    entities = [
        RegionEntity::class, 
        RoadSegmentEntity::class, 
        ImportedGpxFileEntity::class, 
        DebugTraceEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun regionDao(): RegionDao
    abstract fun roadSegmentDao(): RoadSegmentDao
    abstract fun importedGpxFileDao(): ImportedGpxFileDao
    abstract fun debugTraceDao(): DebugTraceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `imported_gpx_files` (`fileHash` TEXT NOT NULL, `fileName` TEXT NOT NULL, `importedAt` INTEGER NOT NULL, `trackPointCount` INTEGER NOT NULL, `segmentsCompletedCount` INTEGER NOT NULL, PRIMARY KEY(`fileHash`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `debug_traces` (`traceId` TEXT NOT NULL, `pointsEncoded` TEXT NOT NULL, `pointCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`traceId`))"
                )
            }
        }
        
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `imported_gpx_files` ADD COLUMN `encodedTrace` TEXT NOT NULL DEFAULT ''")
            }
        }
        
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `imported_gpx_files` ADD COLUMN `isVisibleOnMap` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mopedtrack_db"
                )
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
