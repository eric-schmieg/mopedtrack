package com.example.mopedtrackv2.data.local

import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.domain.model.OsmPinCategory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OsmPinDatabaseMigrationTest {

    private val testDbName = "migration_test_mopedtrack.db"

    @After
    fun tearDown() {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(testDbName)
    }

    @Test
    fun migrate5To6_preservesExistingDataAndSupportsOsmPinDaoCrud() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(testDbName)

        // 1. Create a v5 database with existing data across all 4 tables
        val v5Config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(testDbName)
            .callback(object : SupportSQLiteOpenHelper.Callback(5) {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `regions` (`regionId` TEXT NOT NULL, `name` TEXT NOT NULL, `adminLevel` INTEGER NOT NULL, `osmRelationId` INTEGER NOT NULL, `boundingBox` TEXT NOT NULL, `lastUpdatedTimestamp` INTEGER NOT NULL, PRIMARY KEY(`regionId`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `road_segments` (`segmentId` TEXT NOT NULL, `regionId` TEXT NOT NULL, `osmWayId` INTEGER NOT NULL, `name` TEXT, `highwayType` TEXT NOT NULL, `isOneway` INTEGER NOT NULL, `pairedSegmentId` TEXT, `isDeadEnd` INTEGER NOT NULL, `geometryEncoded` TEXT NOT NULL, `lengthMeters` REAL NOT NULL, `minLat` REAL NOT NULL, `maxLat` REAL NOT NULL, `minLon` REAL NOT NULL, `maxLon` REAL NOT NULL, `isCompleted` INTEGER NOT NULL, `completedAt` INTEGER, PRIMARY KEY(`segmentId`), FOREIGN KEY(`regionId`) REFERENCES `regions`(`regionId`) ON UPDATE NO ACTION ON DELETE CASCADE)"
                    )
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_road_segments_regionId` ON `road_segments` (`regionId`)")
                    db.execSQL("CREATE INDEX IF NOT EXISTS `index_road_segments_minLat_maxLat_minLon_maxLon` ON `road_segments` (`minLat`, `maxLat`, `minLon`, `maxLon`)")
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `imported_gpx_files` (`fileHash` TEXT NOT NULL, `fileName` TEXT NOT NULL, `importedAt` INTEGER NOT NULL, `trackPointCount` INTEGER NOT NULL, `segmentsCompletedCount` INTEGER NOT NULL, `encodedTrace` TEXT NOT NULL, `isVisibleOnMap` INTEGER NOT NULL, `distanceMeters` REAL NOT NULL, `durationMillis` INTEGER, `recordedAt` INTEGER, PRIMARY KEY(`fileHash`))"
                    )
                    db.execSQL(
                        "CREATE TABLE IF NOT EXISTS `debug_traces` (`traceId` TEXT NOT NULL, `pointsEncoded` TEXT NOT NULL, `pointCount` INTEGER NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`traceId`))"
                    )
                }

                override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()

        val v5Helper = FrameworkSQLiteOpenHelperFactory().create(v5Config)
        v5Helper.writableDatabase.use { v5Db ->
            v5Db.execSQL(
                "INSERT INTO `regions` VALUES ('reg-1', 'Test City', 8, 12345, '37.0,38.0,-122.0,-121.0', 1000)"
            )
            v5Db.execSQL(
                "INSERT INTO `road_segments` VALUES ('seg-1', 'reg-1', 999, 'Oak St', 'residential', 0, NULL, 0, 'abc', 120.5, 37.1, 37.2, -122.2, -122.1, 1, 2000)"
            )
            v5Db.execSQL(
                "INSERT INTO `imported_gpx_files` VALUES ('hash-1', 'ride.gpx', 3000, 42, 1, 'xyz', 1, 500.0, 60000, 2500)"
            )
            v5Db.execSQL(
                "INSERT INTO `debug_traces` VALUES ('last_trace', 'def', 5, 4000)"
            )
        }
        v5Helper.close()

        // 2. Open with Room v6 and MIGRATION_5_6
        val db = Room.databaseBuilder(context, AppDatabase::class.java, testDbName)
            .addMigrations(AppDatabase.MIGRATION_5_6)
            .build()

        try {
            // Verify all existing tables kept their rows
            val regions = db.regionDao().getAllRegions().first()
            assertEquals(1, regions.size)
            assertEquals("Test City", regions.first().name)

            val segment = db.roadSegmentDao().getRoadSegmentById("seg-1")
            assertNotNull(segment)
            assertEquals("Oak St", segment?.name)
            assertTrue(segment?.isCompleted == true)

            val gpx = db.importedGpxFileDao().getFileByHash("hash-1")
            assertNotNull(gpx)
            assertEquals("ride.gpx", gpx?.fileName)

            val dbg = db.debugTraceDao().getLastTraceFlow().first()
            assertNotNull(dbg)
            assertEquals("last_trace", dbg?.traceId)

            // 3. Exercise OsmPinDao CRUD on migrated v6 table
            val pinDao = db.osmPinDao()
            val pin1 = OsmPinEntity(
                pinId = "pin-1",
                lat = 37.7749,
                lon = -122.4194,
                category = OsmPinCategory.MISSING_ROAD.name,
                note = "New service lane",
                createdAt = 5000L,
                isResolved = false
            )
            val pin2 = OsmPinEntity(
                pinId = "pin-2",
                lat = 37.7755,
                lon = -122.4185,
                category = OsmPinCategory.WRONG_NAME.name,
                note = "Signed as Elm Ave",
                createdAt = 6000L,
                isResolved = false
            )

            pinDao.insertPin(pin1)
            pinDao.insertPin(pin2)

            val allPins = pinDao.getAllPinsFlow().first()
            assertEquals(2, allPins.size)
            assertEquals("pin-2", allPins[0].pinId)
            assertEquals("pin-1", allPins[1].pinId)

            pinDao.setPinResolved("pin-1", true)
            val unresolved = pinDao.getUnresolvedPinsFlow().first()
            assertEquals(listOf(pin2), unresolved)

            val updatedPin2 = pin2.copy(note = "Updated note")
            pinDao.updatePin(updatedPin2)
            assertEquals("Updated note", pinDao.getPinById("pin-2")?.note)

            pinDao.deletePinById("pin-1")
            assertNull(pinDao.getPinById("pin-1"))
            assertEquals(1, pinDao.getAllPinsFlow().first().size)
        } finally {
            db.close()
        }
    }
}
