package com.example.mopedtrackv2.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.mopedtrackv2.data.local.dao.DebugTraceDao
import com.example.mopedtrackv2.data.local.dao.ImportedGpxFileDao
import com.example.mopedtrackv2.data.local.dao.OsmPinDao
import com.example.mopedtrackv2.data.local.dao.RegionDao
import com.example.mopedtrackv2.data.local.dao.RoadSegmentDao
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.data.local.entity.RegionEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity

@Database(
    entities = [
        RegionEntity::class,
        RoadSegmentEntity::class,
        ImportedGpxFileEntity::class,
        DebugTraceEntity::class,
        OsmPinEntity::class
    ],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun regionDao(): RegionDao
    abstract fun roadSegmentDao(): RoadSegmentDao
    abstract fun importedGpxFileDao(): ImportedGpxFileDao
    abstract fun debugTraceDao(): DebugTraceDao
    abstract fun osmPinDao(): OsmPinDao

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

        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `imported_gpx_files` ADD COLUMN `distanceMeters` REAL NOT NULL DEFAULT 0.0")
                db.execSQL("ALTER TABLE `imported_gpx_files` ADD COLUMN `durationMillis` INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE `imported_gpx_files` ADD COLUMN `recordedAt` INTEGER DEFAULT NULL")
            }
        }

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `osm_pins` (`pinId` TEXT NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, `category` TEXT NOT NULL, `note` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, `isResolved` INTEGER NOT NULL, PRIMARY KEY(`pinId`))"
                )
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "mopedtrack_db"
                )
                .addMigrations(
                    MIGRATION_1_2,
                    MIGRATION_2_3,
                    MIGRATION_3_4,
                    MIGRATION_4_5,
                    MIGRATION_5_6
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
