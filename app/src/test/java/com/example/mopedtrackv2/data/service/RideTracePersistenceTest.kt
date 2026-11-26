package com.example.mopedtrackv2.data.service

import com.example.mopedtrackv2.data.gpx.GpxExportResult
import com.example.mopedtrackv2.data.gpx.GpxExporter
import com.example.mopedtrackv2.data.local.dao.DebugTraceDao
import com.example.mopedtrackv2.data.local.dao.GpxArchiveStatsAggregate
import com.example.mopedtrackv2.data.local.dao.ImportedGpxFileDao
import com.example.mopedtrackv2.data.local.dao.OsmPinDao
import com.example.mopedtrackv2.data.local.dao.RegionDao
import com.example.mopedtrackv2.data.local.dao.RegionStatsRow
import com.example.mopedtrackv2.data.local.dao.RoadSegmentDao
import com.example.mopedtrackv2.data.local.dao.RoadStatsAggregate
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.data.local.entity.RegionEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.data.preferences.SavedCameraPosition
import com.example.mopedtrackv2.data.preferences.SettingsDataStore
import com.example.mopedtrackv2.data.repository.MopedRepositoryImpl
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.TrackPoint
import com.example.mopedtrackv2.domain.model.UnitSystem
import com.example.mopedtrackv2.utils.HashUtils
import java.io.File
import java.util.Collections
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RideTracePersistenceTest {

    @Rule
    @JvmField
    val tempFolder = TemporaryFolder()

    private class DirectExecutorService : AbstractExecutorService() {
        @Volatile
        private var shutdown = false
        override fun shutdown() { shutdown = true }
        override fun shutdownNow(): MutableList<Runnable> {
            shutdown = true
            return Collections.emptyList()
        }
        override fun isShutdown(): Boolean = shutdown
        override fun isTerminated(): Boolean = shutdown
        override fun awaitTermination(timeout: Long, unit: TimeUnit): Boolean = true
        override fun execute(command: Runnable) { command.run() }
    }

    @Test
    fun `appended track points persist to disk immediately and recover as PAUSED after restart`() {
        val dir = tempFolder.newFolder("active_ride")
        val store1 = FileRideTraceStore(dir, DirectExecutorService())
        val controller1 = RideTrackingController(traceStore = store1)

        controller1.startRecording(notifyService = false)
        val p1 = TrackPoint(lat = 37.7749, lon = -122.4194, altitude = 15.5, timestamp = 1_000L, speed = 8.2f, accuracy = 4.0f)
        val p2 = TrackPoint(lat = 37.7752, lon = -122.4190, altitude = null, timestamp = 2_000L, speed = null, accuracy = 5.5f)
        val p3 = TrackPoint(lat = 37.7758, lon = -122.4182, altitude = 18.0, timestamp = 3_000L, speed = 10.0f, accuracy = null)

        controller1.appendTrackPoint(p1)
        controller1.appendTrackPoint(p2)
        controller1.appendTrackPoint(p3)
        controller1.addCompletedSegments(4)

        // Simulate phone dying and restarting with a fresh store & controller
        var resumeServiceStarted = false
        val store2 = FileRideTraceStore(dir, DirectExecutorService())
        val controller2 = RideTrackingController(
            onStartService = { resumeServiceStarted = true },
            traceStore = store2
        )

        assertEquals(RideRecordingState.PAUSED, controller2.recordingState.value)
        assertEquals(listOf(p1, p2, p3), controller2.activeTrace.value)
        assertEquals(4, controller2.getCompletedSegmentCount())
        assertEquals(3, controller2.consumeRecoveredPointCount())
        assertEquals(0, controller2.consumeRecoveredPointCount())

        // Resuming starts the foreground service and keeps appending to the same persisted trace
        controller2.resumeRecording()
        assertTrue(resumeServiceStarted)
        assertEquals(RideRecordingState.RECORDING, controller2.recordingState.value)

        val p4 = TrackPoint(lat = 37.7765, lon = -122.4175, timestamp = 4_000L)
        controller2.appendTrackPoint(p4)

        // Simulate a second restart while recording
        val store3 = FileRideTraceStore(dir, DirectExecutorService())
        val controller3 = RideTrackingController(traceStore = store3)
        assertEquals(RideRecordingState.PAUSED, controller3.recordingState.value)
        assertEquals(listOf(p1, p2, p3, p4), controller3.activeTrace.value)
    }

    @Test
    fun `truncated trailing line from sudden power loss is ignored while prior points recover`() {
        val dir = tempFolder.newFolder("active_ride")
        val store1 = FileRideTraceStore(dir, DirectExecutorService())
        val controller1 = RideTrackingController(traceStore = store1)

        controller1.startRecording(notifyService = false)
        val p1 = TrackPoint(lat = 37.7749, lon = -122.4194, timestamp = 1_000L)
        val p2 = TrackPoint(lat = 37.7755, lon = -122.4185, timestamp = 2_000L)
        controller1.appendTrackPoint(p1)
        controller1.appendTrackPoint(p2)

        // Append a torn/incomplete line lacking trailing newline (simulating power cut mid-write)
        File(dir, "active_ride_points.tsv").appendText("3000\t37.7760\t-122.41", Charsets.UTF_8)

        val store2 = FileRideTraceStore(dir, DirectExecutorService())
        val controller2 = RideTrackingController(traceStore = store2)

        assertEquals(RideRecordingState.PAUSED, controller2.recordingState.value)
        assertEquals(listOf(p1, p2), controller2.activeTrace.value)
    }

    @Test
    fun `discardRecording clears disk files so next launch starts IDLE`() {
        val dir = tempFolder.newFolder("active_ride")
        val store1 = FileRideTraceStore(dir, DirectExecutorService())
        val controller1 = RideTrackingController(traceStore = store1)

        controller1.startRecording(notifyService = false)
        controller1.appendTrackPoint(TrackPoint(lat = 37.7749, lon = -122.4194, timestamp = 1_000L))
        controller1.discardRecording()

        val store2 = FileRideTraceStore(dir, DirectExecutorService())
        val controller2 = RideTrackingController(traceStore = store2)

        assertEquals(RideRecordingState.IDLE, controller2.recordingState.value)
        assertTrue(controller2.activeTrace.value.isEmpty())
        assertEquals(0, controller2.consumeRecoveredPointCount())
        assertNull(store2.loadSession())
    }

    @Test
    fun `empty active session does not recover as paused after restart`() {
        val dir = tempFolder.newFolder("active_ride")
        val store1 = FileRideTraceStore(dir, DirectExecutorService())
        val controller1 = RideTrackingController(traceStore = store1)

        controller1.startRecording(notifyService = false)

        val store2 = FileRideTraceStore(dir, DirectExecutorService())
        val controller2 = RideTrackingController(traceStore = store2)

        assertEquals(RideRecordingState.IDLE, controller2.recordingState.value)
        assertTrue(controller2.activeTrace.value.isEmpty())
    }

    @Test
    fun `recovered ride preserves completed segment count when saved to GPX and clears disk`() = runBlocking {
        val dir = tempFolder.newFolder("active_ride")
        val store1 = FileRideTraceStore(dir, DirectExecutorService())
        val controller1 = RideTrackingController(traceStore = store1)

        controller1.startRecording(notifyService = false)
        val p1 = TrackPoint(lat = 37.7749, lon = -122.4194, timestamp = 1_000L)
        val p2 = TrackPoint(lat = 37.7755, lon = -122.4185, timestamp = 11_000L)
        controller1.appendTrackPoint(p1)
        controller1.appendTrackPoint(p2)
        controller1.addCompletedSegments(5)

        // Simulate app restart / recovery
        val store2 = FileRideTraceStore(dir, DirectExecutorService())
        val controller2 = RideTrackingController(traceStore = store2)

        val gpxDao = StubImportedGpxFileDao()
        var exportedContent: String? = null
        val exporter = object : GpxExporter {
            override suspend fun exportGpx(fileName: String, content: String, treeUriString: String?): GpxExportResult {
                exportedContent = content
                return GpxExportResult(fileName, content.toByteArray(Charsets.UTF_8), usedSafTree = false)
            }
        }

        val repository = MopedRepositoryImpl(
            regionDao = StubRegionDao(),
            roadSegmentDao = StubRoadSegmentDao(),
            importedGpxFileDao = gpxDao,
            debugTraceDao = StubDebugTraceDao(),
            osmPinDao = StubOsmPinDao(),
            settingsDataStore = StubSettingsDataStore(),
            rideTrackingController = controller2,
            gpxExporter = exporter
        )

        val saved = repository.saveRideRecording()
        assertTrue(saved)
        assertNotNull(exportedContent)

        val expectedHash = HashUtils.sha256Hex(exportedContent!!.toByteArray(Charsets.UTF_8))
        val inserted = gpxDao.getFileByHash(expectedHash)
        assertNotNull(inserted)
        assertEquals(2, inserted!!.trackPointCount)
        assertEquals(5, inserted.segmentsCompletedCount)

        // Disk store should now be clean
        val store3 = FileRideTraceStore(dir, DirectExecutorService())
        assertNull(store3.loadSession())
    }

    @Test
    fun `default background disk writer persists points and flushes on awaitIdle`() {
        val dir = tempFolder.newFolder("active_ride_async")
        val store1 = FileRideTraceStore(dir)
        val controller1 = RideTrackingController(traceStore = store1)

        controller1.startRecording(notifyService = false)
        val p1 = TrackPoint(lat = 41.8781, lon = -87.6298, timestamp = 10_000L)
        val p2 = TrackPoint(lat = 41.8790, lon = -87.6290, timestamp = 11_000L)
        controller1.appendTrackPoint(p1)
        controller1.appendTrackPoint(p2)
        store1.awaitIdle()

        val store2 = FileRideTraceStore(dir)
        val controller2 = RideTrackingController(traceStore = store2)
        assertEquals(RideRecordingState.PAUSED, controller2.recordingState.value)
        assertEquals(listOf(p1, p2), controller2.activeTrace.value)
    }

    private class StubRegionDao : RegionDao {
        override suspend fun insertRegion(region: RegionEntity) {}
        override suspend fun insertRegions(regions: List<RegionEntity>) {}
        override fun getAllRegions(): Flow<List<RegionEntity>> = flowOf(emptyList())
        override suspend fun getRegionById(regionId: String): RegionEntity? = null
        override suspend fun deleteRegionById(regionId: String) {}
        override suspend fun clearRegions() {}
    }

    private class StubRoadSegmentDao : RoadSegmentDao {
        override suspend fun insertRoadSegments(segments: List<RoadSegmentEntity>) {}
        override fun getRoadSegmentsByRegion(regionId: String): Flow<List<RoadSegmentEntity>> = flowOf(emptyList())
        override fun getRoadSegmentsInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<RoadSegmentEntity>> = flowOf(emptyList())
        override suspend fun getCandidateSegments(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadSegmentEntity> = emptyList()
        override suspend fun getUncompletedCandidateSegments(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadSegmentEntity> = emptyList()
        override suspend fun markSegmentsCompletedByIds(segmentIds: List<String>, completedAt: Long) {}
        override suspend fun markSegmentsCompletedByPairedIds(pairedIds: List<String>, completedAt: Long) {}
        override suspend fun updateSegments(segments: List<RoadSegmentEntity>) {}
        override suspend fun getRoadSegmentById(segmentId: String): RoadSegmentEntity? = null
        override suspend fun updateCompletionStatus(segmentId: String, isCompleted: Boolean, completedAt: Long?) {}
        override suspend fun getPairedSegmentId(segmentId: String): String? = null
        override suspend fun updateCompletionStatusByPairedId(pairedId: String, isCompleted: Boolean, completedAt: Long?) {}
        override suspend fun getRoadSegmentsForRegion(regionId: String): List<RoadSegmentEntity> = emptyList()
        override suspend fun deleteRoadSegmentsByRegion(regionId: String) {}
        override suspend fun clearRoadSegments() {}
        override suspend fun resetAllCompletionStatus() {}
        override fun getOverallRoadStatsFlow(): Flow<RoadStatsAggregate> = flowOf(RoadStatsAggregate(0, 0, 0.0, 0.0))
        override fun getRegionStatsFlow(): Flow<List<RegionStatsRow>> = flowOf(emptyList())
    }

    private class StubImportedGpxFileDao : ImportedGpxFileDao {
        private val files = linkedMapOf<String, ImportedGpxFileEntity>()
        override fun getAllFilesFlow(): Flow<List<ImportedGpxFileEntity>> = flowOf(files.values.toList())
        override suspend fun getFileByHash(hash: String): ImportedGpxFileEntity? = files[hash]
        override suspend fun insertFile(file: ImportedGpxFileEntity) { files[file.fileHash] = file }
        override suspend fun getImportedFilesCount(): Int = files.size
        override suspend fun updateVisibility(hash: String, isVisible: Boolean) {}
        override suspend fun updateAllVisibility(isVisible: Boolean) {}
        override fun getVisibleFilesFlow(): Flow<List<ImportedGpxFileEntity>> = flowOf(emptyList())
        override suspend fun deleteFileByHash(hash: String) { files.remove(hash) }
        override suspend fun deleteLegacyTraces() {}
        override suspend fun resetAllSegmentsCompletedCount() {}
        override fun getGpxArchiveStatsFlow(): Flow<GpxArchiveStatsAggregate> = flowOf(GpxArchiveStatsAggregate(0, 0L))
    }

    private class StubDebugTraceDao : DebugTraceDao {
        override fun getLastTraceFlow(): Flow<DebugTraceEntity?> = flowOf(null)
        override suspend fun insertTrace(trace: DebugTraceEntity) {}
        override suspend fun clearAllTraces() {}
    }

    private class StubOsmPinDao : OsmPinDao {
        override fun getAllPinsFlow(): Flow<List<OsmPinEntity>> = flowOf(emptyList())
        override fun getUnresolvedPinsFlow(): Flow<List<OsmPinEntity>> = flowOf(emptyList())
        override suspend fun getPinById(pinId: String): OsmPinEntity? = null
        override suspend fun insertPin(pin: OsmPinEntity) {}
        override suspend fun updatePin(pin: OsmPinEntity) {}
        override suspend fun setPinResolved(pinId: String, isResolved: Boolean) {}
        override suspend fun deletePin(pin: OsmPinEntity) {}
        override suspend fun deletePinById(pinId: String) {}
        override suspend fun clearAllPins() {}
    }

    private class StubSettingsDataStore : SettingsDataStore {
        override val matchRadiusFlow: Flow<Int> = MutableStateFlow(15)
        override val coverageThresholdFlow: Flow<Int> = MutableStateFlow(80)
        override val debugOverlayEnabledFlow: Flow<Boolean> = MutableStateFlow(false)
        override val showCompletedSegmentsFlow: Flow<Boolean> = MutableStateFlow(true)
        override val showUncompletedSegmentsFlow: Flow<Boolean> = MutableStateFlow(true)
        override val showGpxTracesFlow: Flow<Boolean> = MutableStateFlow(true)
        override val hiddenRegionIdsFlow: Flow<Set<String>> = MutableStateFlow(emptySet())
        override val savedCameraPositionFlow: Flow<SavedCameraPosition?> = MutableStateFlow(null)
        override val pollingIntervalMsFlow: Flow<Long> = MutableStateFlow(1000L)
        override val minDisplacementMetersFlow: Flow<Float> = MutableStateFlow(2.0f)
        override val gpxExportTreeUriFlow: Flow<String?> = MutableStateFlow(null)
        override val unitSystemFlow: Flow<UnitSystem> = MutableStateFlow(UnitSystem.IMPERIAL)
        override val weeklyAutoRefreshFlow: Flow<Boolean> = MutableStateFlow(false)

        override suspend fun setMatchRadius(radius: Int) {}
        override suspend fun setCoverageThreshold(threshold: Int) {}
        override suspend fun setDebugOverlayEnabled(enabled: Boolean) {}
        override suspend fun setShowCompletedSegments(show: Boolean) {}
        override suspend fun setShowUncompletedSegments(show: Boolean) {}
        override suspend fun setShowGpxTraces(show: Boolean) {}
        override suspend fun setRegionVisibility(regionId: String, isVisible: Boolean) {}
        override suspend fun setHiddenRegionIds(regionIds: Set<String>) {}
        override suspend fun setSavedCameraPosition(lat: Double, lon: Double, zoom: Double, bearing: Double, tilt: Double) {}
        override suspend fun setPollingIntervalMs(intervalMs: Long) {}
        override suspend fun setMinDisplacementMeters(meters: Float) {}
        override suspend fun setGpxExportTreeUri(uri: String?) {}
        override suspend fun setUnitSystem(unitSystem: UnitSystem) {}
        override suspend fun setWeeklyAutoRefresh(enabled: Boolean) {}
    }
}
