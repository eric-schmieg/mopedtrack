package com.example.mopedtrackv2.presentation.stats

import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.data.preferences.SavedCameraPosition
import com.example.mopedtrackv2.domain.gpx.GpxMetadataFormatter
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.model.RegionRefreshResult
import com.example.mopedtrackv2.domain.model.RegionStats
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
class StatsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeStatsRepository : MopedRepository {
        val statsSummaryState = MutableStateFlow(StatsSummary())
        val unitSystemState = MutableStateFlow(UnitSystem.IMPERIAL)

        override fun getStatsSummaryFlow(): Flow<StatsSummary> = statsSummaryState
        override val unitSystemFlow: Flow<UnitSystem> = unitSystemState
        override suspend fun setUnitSystem(unitSystem: UnitSystem) {
            unitSystemState.value = unitSystem
        }

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
        override suspend fun setWeeklyAutoRefresh(enabled: Boolean) {}

        override fun getImportedFilesFlow(): Flow<List<ImportedGpxFileEntity>> = MutableStateFlow(emptyList())
        override suspend fun getImportedFileByHash(hash: String): ImportedGpxFileEntity? = null
        override suspend fun insertImportedFile(file: ImportedGpxFileEntity) {}
        override suspend fun deleteImportedFile(hash: String) {}
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
    }

    @Test
    fun `uiState reflects repository stats and reacts to unit system changes`() = runTest {
        val repo = FakeStatsRepository()
        val oneMileMeters = 1609.344
        repo.statsSummaryState.value = StatsSummary(
            totalSegments = 10,
            completedSegments = 5,
            totalDistanceMeters = oneMileMeters * 4.0,
            completedDistanceMeters = oneMileMeters * 2.5,
            unnamedCompletedSegments = 17,
            regionBreakdown = listOf(
                RegionStats(
                    regionId = "r1",
                    regionName = "Downtown",
                    totalSegments = 10,
                    completedSegments = 5,
                    totalDistanceMeters = oneMileMeters * 4.0,
                    completedDistanceMeters = oneMileMeters * 2.5
                )
            ),
            totalGpxFiles = 4,
            totalTrackPoints = 1234L
        )

        val viewModel = StatsViewModel(repo)
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        advanceUntilIdle()
        val imperialState = viewModel.uiState.value
        assertFalse(imperialState.isLoading)
        assertEquals(UnitSystem.IMPERIAL, imperialState.unitSystem)
        assertEquals(62.5, imperialState.summary.completionPercent, 0.01)
        assertEquals("62.5%", StatsViewModel.formatPercent(imperialState.summary.completionPercent))
        assertEquals(17, imperialState.summary.unnamedCompletedSegments)
        assertEquals(
            "2.50 mi",
            GpxMetadataFormatter.formatDistance(imperialState.summary.completedDistanceMeters, imperialState.unitSystem)
        )
        assertEquals(
            "1.50 mi",
            GpxMetadataFormatter.formatDistance(imperialState.summary.remainingDistanceMeters, imperialState.unitSystem)
        )
        assertEquals(4, imperialState.summary.totalGpxFiles)
        assertEquals(1234L, imperialState.summary.totalTrackPoints)

        repo.unitSystemState.value = UnitSystem.METRIC
        advanceUntilIdle()
        val metricState = viewModel.uiState.value
        assertEquals(UnitSystem.METRIC, metricState.unitSystem)
        assertEquals(
            "4.02 km",
            GpxMetadataFormatter.formatDistance(metricState.summary.completedDistanceMeters, metricState.unitSystem)
        )
        assertEquals(
            "2.41 km",
            GpxMetadataFormatter.formatDistance(metricState.summary.remainingDistanceMeters, metricState.unitSystem)
        )

        collectJob.cancel()
    }

    @Test
    fun `zero segments and zero distance do not divide by zero`() = runTest {
        val repo = FakeStatsRepository()
        repo.statsSummaryState.value = StatsSummary(
            totalSegments = 0,
            completedSegments = 0,
            totalDistanceMeters = 0.0,
            completedDistanceMeters = 0.0,
            regionBreakdown = listOf(
                RegionStats(
                    regionId = "empty-region",
                    regionName = "Empty Zone",
                    totalSegments = 0,
                    completedSegments = 0,
                    totalDistanceMeters = 0.0,
                    completedDistanceMeters = 0.0
                ),
                RegionStats(
                    regionId = "zero-len-region",
                    regionName = "Zero Length Zone",
                    totalSegments = 4,
                    completedSegments = 1,
                    totalDistanceMeters = 0.0,
                    completedDistanceMeters = 0.0
                )
            ),
            totalGpxFiles = 0,
            totalTrackPoints = 0L
        )

        val viewModel = StatsViewModel(repo)
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.uiState.collect {}
        }

        advanceUntilIdle()
        val state = viewModel.uiState.value
        assertFalse(state.summary.completionPercent.isNaN())
        assertEquals(0.0, state.summary.completionPercent, 0.0)
        assertEquals("0.0%", StatsViewModel.formatPercent(state.summary.completionPercent))
        assertEquals(0, state.summary.remainingSegments)
        assertEquals(0.0, state.summary.remainingDistanceMeters, 0.0)

        val emptyRegion = state.summary.regionBreakdown[0]
        assertTrue(emptyRegion.completionPercent.isFinite())
        assertEquals(0.0, emptyRegion.completionPercent, 0.0)

        val zeroLenRegion = state.summary.regionBreakdown[1]
        assertEquals(25.0, zeroLenRegion.completionPercent, 0.01)

        collectJob.cancel()
    }

    @Test
    fun `percentage and remaining values clamp safely on edge inputs`() {
        val overCompleted = StatsSummary(
            totalSegments = 5,
            completedSegments = 7,
            totalDistanceMeters = 1000.0,
            completedDistanceMeters = 1200.0
        )
        assertEquals(100.0, overCompleted.completionPercent, 0.0)
        assertEquals(0, overCompleted.remainingSegments)
        assertEquals(0.0, overCompleted.remainingDistanceMeters, 0.0)
        assertEquals("0.0%", StatsViewModel.formatPercent(Double.NaN))
        assertEquals("100.0%", StatsViewModel.formatPercent(120.0))
        assertEquals("1,250", StatsViewModel.formatCount(1250))
    }
}
