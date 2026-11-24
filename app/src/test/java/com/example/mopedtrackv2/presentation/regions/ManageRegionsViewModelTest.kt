package com.example.mopedtrackv2.presentation.regions

import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.model.RoadSegment
import com.example.mopedtrackv2.domain.repository.MopedRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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

        override suspend fun setMatchRadius(radius: Int) {}
        override suspend fun setCoverageThreshold(threshold: Int) {}
        override suspend fun setDebugOverlayEnabled(enabled: Boolean) {}
        override suspend fun setShowCompletedSegments(show: Boolean) {}
        override suspend fun setShowUncompletedSegments(show: Boolean) {}
        override suspend fun setShowGpxTraces(show: Boolean) {}

        override fun getImportedFilesFlow(): Flow<List<ImportedGpxFileEntity>> = MutableStateFlow(emptyList())
        override suspend fun getImportedFileByHash(hash: String): ImportedGpxFileEntity? = null
        override suspend fun insertImportedFile(file: ImportedGpxFileEntity) {}
        override suspend fun getImportedFilesCount(): Int = 0
        override suspend fun deleteLegacyTraces() {}
        override suspend fun toggleGpxVisibility(hash: String, isVisible: Boolean) {}
        override fun getVisibleGpxFilesFlow(): Flow<List<ImportedGpxFileEntity>> = MutableStateFlow(emptyList())

        override fun getLastDebugTraceFlow(): Flow<DebugTraceEntity?> = MutableStateFlow(null)
        override suspend fun insertDebugTrace(trace: DebugTraceEntity) {}
        override suspend fun clearDebugTraces() {}

        override suspend fun resetAllProgress() {}
        override suspend fun rescanGpxTraces() {}
        override suspend fun getCandidateSegmentsForBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadSegmentEntity> = emptyList()
        override suspend fun updateRoadSegments(segments: List<RoadSegmentEntity>) {}
    }

    @Test
    fun testDebounceAutoSearchTriggersOnQueryLengthAtLeast2() = runTest {
        val fakeRepo = FakeMopedRepository()
        val viewModel = ManageRegionsViewModel(fakeRepo)

        // Single character query - should not trigger search
        viewModel.onSearchQueryChanged("P")
        advanceTimeBy(500.milliseconds)
        assertEquals(0, fakeRepo.searchCallCount)
        assertTrue(viewModel.searchResults.value.isEmpty())

        // 2 character query - should trigger search after 400ms debounce
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

        // Clearing or changing query to < 2 chars
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
}
