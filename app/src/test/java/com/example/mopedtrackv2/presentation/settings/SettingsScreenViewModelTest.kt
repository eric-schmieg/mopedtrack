package com.example.mopedtrackv2.presentation.settings

import com.example.mopedtrackv2.data.gpx.ExternalTraceCopySummary
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.data.preferences.SavedCameraPosition
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.model.RegionRefreshResult
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.RoadSegment
import com.example.mopedtrackv2.domain.model.StatsSummary
import com.example.mopedtrackv2.domain.model.TrackPoint
import com.example.mopedtrackv2.domain.model.UnitSystem
import com.example.mopedtrackv2.domain.repository.MopedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsScreenViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeSettingsRepository : MopedRepository {
        val matchRadiusState = MutableStateFlow(15)
        val coverageThresholdState = MutableStateFlow(80)
        val debugOverlayState = MutableStateFlow(false)
        val unitSystemState = MutableStateFlow(UnitSystem.IMPERIAL)
        val pollingIntervalState = MutableStateFlow(1000L)
        val minDisplacementState = MutableStateFlow(2.0f)
        val gpxExportTreeUriState = MutableStateFlow<String?>(null)
        val weeklyAutoRefreshState = MutableStateFlow(false)
        val copyRequests = mutableListOf<String>()
        var nextCopySummary = ExternalTraceCopySummary(
            totalTraces = 3,
            copiedCount = 3,
            skippedExistingCount = 0,
            failedCount = 0,
            folderWritable = true
        )

        override val matchRadiusFlow: Flow<Int> = matchRadiusState
        override val coverageThresholdFlow: Flow<Int> = coverageThresholdState
        override val debugOverlayEnabledFlow: Flow<Boolean> = debugOverlayState
        override val showCompletedSegmentsFlow: Flow<Boolean> = MutableStateFlow(true)
        override val showUncompletedSegmentsFlow: Flow<Boolean> = MutableStateFlow(true)
        override val showGpxTracesFlow: Flow<Boolean> = MutableStateFlow(true)
        override val hiddenRegionIdsFlow: Flow<Set<String>> = MutableStateFlow(emptySet())
        override val savedCameraPositionFlow: Flow<SavedCameraPosition?> = MutableStateFlow(null)
        override val pollingIntervalMsFlow: Flow<Long> = pollingIntervalState
        override val minDisplacementMetersFlow: Flow<Float> = minDisplacementState
        override val gpxExportTreeUriFlow: Flow<String?> = gpxExportTreeUriState
        override val unitSystemFlow: Flow<UnitSystem> = unitSystemState
        override val weeklyAutoRefreshFlow: Flow<Boolean> = weeklyAutoRefreshState

        override suspend fun setMatchRadius(radius: Int) { matchRadiusState.value = radius.coerceIn(5, 30) }
        override suspend fun setCoverageThreshold(threshold: Int) { coverageThresholdState.value = threshold.coerceIn(50, 100) }
        override suspend fun setDebugOverlayEnabled(enabled: Boolean) { debugOverlayState.value = enabled }
        override suspend fun setShowCompletedSegments(show: Boolean) {}
        override suspend fun setShowUncompletedSegments(show: Boolean) {}
        override suspend fun setShowGpxTraces(show: Boolean) {}
        override suspend fun setRegionVisibility(regionId: String, isVisible: Boolean) {}
        override suspend fun setHiddenRegionIds(regionIds: Set<String>) {}
        override suspend fun setSavedCameraPosition(lat: Double, lon: Double, zoom: Double, bearing: Double, tilt: Double) {}
        override suspend fun setPollingIntervalMs(intervalMs: Long) { pollingIntervalState.value = intervalMs.coerceIn(500L, 5000L) }
        override suspend fun setMinDisplacementMeters(meters: Float) { minDisplacementState.value = meters.coerceIn(0.0f, 15.0f) }
        override suspend fun setGpxExportTreeUri(uri: String?) { gpxExportTreeUriState.value = uri }
        override suspend fun setUnitSystem(unitSystem: UnitSystem) { unitSystemState.value = unitSystem }
        override suspend fun setWeeklyAutoRefresh(enabled: Boolean) { weeklyAutoRefreshState.value = enabled }

        override fun getAllRegions(): Flow<List<Region>> = MutableStateFlow(emptyList())
        override suspend fun getRegionById(regionId: String): Region? = null
        override fun getRoadSegmentsByRegion(regionId: String): Flow<List<RoadSegment>> = MutableStateFlow(emptyList())
        override fun getRoadSegmentsInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<RoadSegment>> = MutableStateFlow(emptyList())
        override suspend fun getRoadSegmentById(segmentId: String): RoadSegment? = null
        override suspend fun toggleSegmentCompletion(segmentId: String, isCompleted: Boolean, completedAt: Long?) {}
        override suspend fun searchRegions(query: String): List<Region> = emptyList()
        override suspend fun importRegion(region: Region, onProgress: (Float, String) -> Unit) {}
        override suspend fun refreshRegion(regionId: String, onProgress: (Float, String) -> Unit): RegionRefreshResult =
            RegionRefreshResult(regionId, "", 0, 0, 0, 0)
        override suspend fun deleteRegion(regionId: String) {}
        override suspend fun clearAllData() {}
        override fun getImportedFilesFlow(): Flow<List<ImportedGpxFileEntity>> = MutableStateFlow(emptyList())
        override suspend fun getImportedFileByHash(hash: String): ImportedGpxFileEntity? = null
        override suspend fun insertImportedFile(file: ImportedGpxFileEntity) {}
        override suspend fun deleteImportedFile(hash: String) {}
        override suspend fun copyAllTracesToExternalFolder(
            treeUriString: String,
            onProgress: (Int, Int) -> Unit
        ): ExternalTraceCopySummary {
            copyRequests.add(treeUriString)
            onProgress(1, nextCopySummary.totalTraces)
            return nextCopySummary
        }
        override suspend fun getImportedFilesCount(): Int = 0
        override suspend fun deleteLegacyTraces() {}
        override suspend fun backfillGpxMetadata() {}
        override suspend fun toggleGpxVisibility(hash: String, isVisible: Boolean) {}
        override fun getVisibleGpxFilesFlow(): Flow<List<ImportedGpxFileEntity>> = MutableStateFlow(emptyList())
        override suspend fun setAllGpxVisibility(isVisible: Boolean) {}
        override fun getLastDebugTraceFlow(): Flow<DebugTraceEntity?> = MutableStateFlow(null)
        override suspend fun insertDebugTrace(trace: DebugTraceEntity) {}
        override suspend fun clearDebugTraces() {}
        override fun getAllOsmPinsFlow(): Flow<List<OsmPinEntity>> = MutableStateFlow(emptyList())
        override fun getUnresolvedOsmPinsFlow(): Flow<List<OsmPinEntity>> = MutableStateFlow(emptyList())
        override suspend fun getOsmPinById(pinId: String): OsmPinEntity? = null
        override suspend fun insertOsmPin(pin: OsmPinEntity) {}
        override suspend fun updateOsmPin(pin: OsmPinEntity) {}
        override suspend fun deleteOsmPin(pinId: String) {}
        override val recordingState: StateFlow<RideRecordingState> = MutableStateFlow(RideRecordingState.IDLE)
        override val activeTrace: StateFlow<List<TrackPoint>> = MutableStateFlow(emptyList())
        override fun startRideRecording() {}
        override fun pauseRideRecording() {}
        override fun resumeRideRecording() {}
        override fun stopRideRecording() {}
        override fun discardRideRecording() {}
        override suspend fun matchLiveTraceWindow(tracePoints: List<TrackPoint>): Int = 0
        override suspend fun saveRideRecording(): Boolean = false
        override suspend fun resetAllProgress() {}
        override suspend fun rescanGpxTraces() {}
        override suspend fun getCandidateSegmentsForBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadSegmentEntity> = emptyList()
        override suspend fun updateRoadSegments(segments: List<RoadSegmentEntity>) {}
        override fun getStatsSummaryFlow(): Flow<StatsSummary> = MutableStateFlow(StatsSummary())
    }

    @Test
    fun `toggling weeklyAutoRefresh updates repository and invokes scheduler callback`() = runTest {
        val repo = FakeSettingsRepository()
        val callbackEvents = mutableListOf<Boolean>()
        val viewModel = SettingsViewModel(
            repository = repo,
            onWeeklyAutoRefreshChanged = { callbackEvents.add(it) }
        )

        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.weeklyAutoRefresh.collect {}
        }

        assertFalse(viewModel.weeklyAutoRefresh.value)

        viewModel.setWeeklyAutoRefresh(true)
        advanceUntilIdle()
        assertTrue(viewModel.weeklyAutoRefresh.value)
        assertEquals(listOf(true), callbackEvents)

        viewModel.setWeeklyAutoRefresh(false)
        advanceUntilIdle()
        assertFalse(viewModel.weeklyAutoRefresh.value)
        assertEquals(listOf(true, false), callbackEvents)

        collectJob.cancel()
    }

    @Test
    fun `settings bindings clamp values and update unit system`() = runTest {
        val repo = FakeSettingsRepository()
        val viewModel = SettingsViewModel(repo)

        val collectJobs = listOf(
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.matchRadius.collect {} },
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.coverageThreshold.collect {} },
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.pollingIntervalMs.collect {} },
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.minDisplacementMeters.collect {} },
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.unitSystem.collect {} }
        )

        viewModel.setMatchRadius(100)
        viewModel.setCoverageThreshold(10)
        viewModel.setPollingIntervalMs(10000L)
        viewModel.setMinDisplacementMeters(-2f)
        viewModel.setUnitSystem(UnitSystem.METRIC)
        advanceUntilIdle()

        assertEquals(30, viewModel.matchRadius.value)
        assertEquals(50, viewModel.coverageThreshold.value)
        assertEquals(5000L, viewModel.pollingIntervalMs.value)
        assertEquals(0.0f, viewModel.minDisplacementMeters.value, 0.0001f)
        assertEquals(UnitSystem.METRIC, viewModel.unitSystem.value)

        collectJobs.forEach { it.cancel() }
    }

    @Test
    fun `changing external folder copies all traces and emits status toast`() = runTest {
        val repo = FakeSettingsRepository()
        val viewModel = SettingsViewModel(repo)
        val toasts = mutableListOf<String>()

        val toastJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.toastMessages.collect { toasts.add(it) }
        }
        val uriJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.gpxExportTreeUri.collect {}
        }

        val newUri = "content://com.android.externalstorage.documents/tree/primary%3ANewGpx"
        viewModel.setGpxExportTreeUri(newUri)
        advanceUntilIdle()

        assertEquals(newUri, viewModel.gpxExportTreeUri.value)
        assertEquals(listOf(newUri), repo.copyRequests)
        assertFalse(viewModel.isCopyingTracesToExternal.value)
        assertEquals("Copied 3 traces to external folder", toasts.last())

        viewModel.setGpxExportTreeUri(null)
        advanceUntilIdle()

        assertEquals(null, viewModel.gpxExportTreeUri.value)
        assertEquals(listOf(newUri), repo.copyRequests)
        assertEquals("External trace folder cleared", toasts.last())

        toastJob.cancel()
        uriJob.cancel()
    }
}
