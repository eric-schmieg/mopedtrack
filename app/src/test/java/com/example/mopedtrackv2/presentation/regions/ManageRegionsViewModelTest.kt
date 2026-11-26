package com.example.mopedtrackv2.presentation.regions

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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalCoroutinesApi::class)
class ManageRegionsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeMopedRepository : MopedRepository {
        val savedRegionsState = MutableStateFlow<List<Region>>(emptyList())
        var searchResultsToReturn = listOf(
            Region(
                regionId = "1",
                name = "Paris",
                adminLevel = 8,
                osmRelationId = 7444L,
                boundingBox = "48.8,48.9,2.2,2.4",
                lastUpdatedTimestamp = 1000L
            )
        )
        var searchCallCount = 0
        var lastSearchQuery = ""
        var importedRegions = mutableListOf<Region>()
        var refreshedRegionIds = mutableListOf<String>()
        var refreshResultFactory: (String) -> RegionRefreshResult = { id ->
            RegionRefreshResult(
                regionId = id,
                regionName = savedRegionsState.value.find { it.regionId == id }?.name ?: id,
                totalSegments = 50,
                preservedCompletedCount = 12,
                gpxMatchedNewSegmentsCount = 3,
                newSegmentsCount = 5
            )
        }
        var refreshError: Throwable? = null
        var deletedRegionIds = mutableListOf<String>()

        override fun getAllRegions(): Flow<List<Region>> = savedRegionsState

        override suspend fun getRegionById(regionId: String): Region? =
            savedRegionsState.value.find { it.regionId == regionId }

        override suspend fun searchRegions(query: String): List<Region> {
            searchCallCount++
            lastSearchQuery = query
            return searchResultsToReturn.filter { it.name.contains(query, ignoreCase = true) }
        }

        override suspend fun importRegion(region: Region, onProgress: (Float, String) -> Unit) {
            importedRegions.add(region)
            savedRegionsState.value = savedRegionsState.value + region
            onProgress(1.0f, "Complete!")
        }

        override suspend fun refreshRegion(regionId: String, onProgress: (Float, String) -> Unit): RegionRefreshResult {
            refreshError?.let { throw it }
            refreshedRegionIds.add(regionId)
            onProgress(0.5f, "Preserving progress...")
            onProgress(1.0f, "Complete!")
            return refreshResultFactory(regionId)
        }

        override suspend fun deleteRegion(regionId: String) {
            deletedRegionIds.add(regionId)
            savedRegionsState.value = savedRegionsState.value.filterNot { it.regionId == regionId }
        }

        override fun getRoadSegmentsByRegion(regionId: String): Flow<List<RoadSegment>> =
            MutableStateFlow(emptyList())

        override fun getRoadSegmentsInBoundingBox(
            minLat: Double,
            maxLat: Double,
            minLon: Double,
            maxLon: Double
        ): Flow<List<RoadSegment>> = MutableStateFlow(emptyList())

        override suspend fun getRoadSegmentById(segmentId: String): RoadSegment? = null

        override suspend fun toggleSegmentCompletion(
            segmentId: String,
            isCompleted: Boolean,
            completedAt: Long?
        ) {}

        override suspend fun clearAllData() {
            savedRegionsState.value = emptyList()
        }

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
        override fun getStatsSummaryFlow(): Flow<StatsSummary> = MutableStateFlow(StatsSummary())
    }

    @Test
    fun testDebounceAutoSearchTriggersOnQueryLengthAtLeast2() = runTest {
        val fakeRepo = FakeMopedRepository()
        val viewModel = ManageRegionsViewModel(fakeRepo)

        viewModel.onSearchQueryChanged("P")
        advanceTimeBy(500.milliseconds)
        assertEquals(0, fakeRepo.searchCallCount)
        assertTrue(viewModel.searchResults.value.isEmpty())

        viewModel.onSearchQueryChanged("Pa")
        advanceTimeBy(450.milliseconds)
        assertEquals(1, fakeRepo.searchCallCount)
        assertEquals("Pa", fakeRepo.lastSearchQuery)
        assertEquals(1, viewModel.searchResults.value.size)
        assertEquals("Paris", viewModel.searchResults.value.first().name)
    }

    @Test
    fun testQueryLessThan2CharactersClearsSearchResults() = runTest {
        val fakeRepo = FakeMopedRepository()
        val viewModel = ManageRegionsViewModel(fakeRepo)

        viewModel.onSearchQueryChanged("Paris")
        advanceTimeBy(450.milliseconds)
        assertEquals(1, viewModel.searchResults.value.size)

        viewModel.onSearchQueryChanged("")
        advanceTimeBy(450.milliseconds)
        assertTrue(viewModel.searchResults.value.isEmpty())
    }

    @Test
    fun testManualSearchActionTriggersImmediatelyForValidQuery() = runTest {
        val fakeRepo = FakeMopedRepository()
        val viewModel = ManageRegionsViewModel(fakeRepo)

        viewModel.searchRegions("Paris")
        advanceTimeBy(100.milliseconds)
        assertEquals(1, fakeRepo.searchCallCount)
        assertEquals("Paris", fakeRepo.lastSearchQuery)
        assertEquals(1, viewModel.searchResults.value.size)
    }

    @Test
    fun testImportRegionUpdatesLoadingStateAndClearsResults() = runTest {
        val fakeRepo = FakeMopedRepository()
        val viewModel = ManageRegionsViewModel(fakeRepo)

        val sampleRegion = Region(
            regionId = "1",
            name = "Paris",
            adminLevel = 8,
            osmRelationId = 7444L,
            boundingBox = "48.8,48.9,2.2,2.4",
            lastUpdatedTimestamp = 1000L
        )

        viewModel.searchRegions("Paris")
        advanceTimeBy(100.milliseconds)
        assertEquals(1, viewModel.searchResults.value.size)

        viewModel.importRegion(sampleRegion)
        advanceTimeBy(100.milliseconds)

        assertEquals(1, fakeRepo.importedRegions.size)
        assertEquals(sampleRegion, fakeRepo.importedRegions.first())
        assertTrue(viewModel.searchResults.value.isEmpty())
        assertFalse(viewModel.isImporting.value)
        assertNull(viewModel.importingRegionId.value)
        assertNull(viewModel.importingRegionName.value)
    }

    @Test
    fun testRefreshRegionCallsNonDestructiveRefreshAndEmitsConfirmationSnackbar() = runTest {
        val fakeRepo = FakeMopedRepository()
        val sampleRegion = Region(
            regionId = "1",
            name = "West Fargo",
            adminLevel = 8,
            osmRelationId = 36001234L,
            boundingBox = "46.8,46.9,-96.9,-96.8",
            lastUpdatedTimestamp = 1000L
        )
        fakeRepo.savedRegionsState.value = listOf(sampleRegion)
        val viewModel = ManageRegionsViewModel(fakeRepo)

        val snackbars = mutableListOf<String>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarMessages.collect { snackbars.add(it) }
        }

        viewModel.refreshRegion(sampleRegion)
        advanceUntilIdle()

        assertEquals(listOf("1"), fakeRepo.refreshedRegionIds)
        assertTrue(fakeRepo.importedRegions.isEmpty())
        assertFalse(viewModel.isImporting.value)
        assertNull(viewModel.errorMessage.value)
        assertEquals(1, snackbars.size)
        assertTrue(snackbars[0].contains("West Fargo"))
        assertTrue(snackbars[0].contains("5 new segments added"))
        assertTrue(snackbars[0].contains("12 completed preserved"))
        assertTrue(snackbars[0].contains("3 GPX-matched"))
        collectJob.cancel()
    }

    @Test
    fun testRefreshAllRegionsRefreshesEachSavedRegionAndEmitsSummarySnackbar() = runTest {
        val fakeRepo = FakeMopedRepository()
        val r1 = Region("1", "West Fargo", 8, 36001L, "46.8,46.9,-96.9,-96.8", 1000L)
        val r2 = Region("2", "Fargo", 8, 36002L, "46.8,46.9,-96.8,-96.7", 1000L)
        fakeRepo.savedRegionsState.value = listOf(r1, r2)
        val viewModel = ManageRegionsViewModel(fakeRepo)
        advanceUntilIdle()

        val snackbars = mutableListOf<String>()
        val collectJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarMessages.collect { snackbars.add(it) }
        }

        viewModel.refreshAllRegions()
        advanceUntilIdle()

        assertEquals(listOf("1", "2"), fakeRepo.refreshedRegionIds)
        assertEquals(1, snackbars.size)
        assertTrue(snackbars[0].contains("Refreshed 2 regions"))
        assertTrue(snackbars[0].contains("10 new segments added"))
        assertTrue(snackbars[0].contains("24 completed preserved"))
        collectJob.cancel()
    }
}
