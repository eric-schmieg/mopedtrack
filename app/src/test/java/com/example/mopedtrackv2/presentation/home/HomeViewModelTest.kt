package com.example.mopedtrackv2.presentation.home

import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.data.preferences.SavedCameraPosition
import com.example.mopedtrackv2.data.service.RideTrackingController
import com.example.mopedtrackv2.domain.model.OsmPinCategory
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
import kotlinx.coroutines.flow.first
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.maplibre.android.location.engine.LocationEngineRequest

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private class FakeHomeRepository(
        val controller: RideTrackingController = RideTrackingController()
    ) : MopedRepository {
        override val recordingState: StateFlow<RideRecordingState> = controller.recordingState
        override val activeTrace: StateFlow<List<TrackPoint>> = controller.activeTrace

        override fun startRideRecording() = controller.startRecording()
        override fun pauseRideRecording() = controller.pauseRecording()
        override fun resumeRideRecording() = controller.resumeRecording()
        override fun stopRideRecording() = controller.stopRecording()
        override fun discardRideRecording() = controller.discardRecording()
        override fun consumeRecoveredRidePointCount(): Int = controller.consumeRecoveredPointCount()
        override suspend fun matchLiveTraceWindow(tracePoints: List<TrackPoint>): Int = 0
        override suspend fun saveRideRecording(): Boolean {
            val hadPoints = controller.activeTrace.value.isNotEmpty()
            controller.discardRecording()
            return hadPoints
        }

        val regionsState = MutableStateFlow<List<Region>>(emptyList())
        val hiddenRegionIdsState = MutableStateFlow<Set<String>>(emptySet())

        override fun getAllRegions(): Flow<List<Region>> = regionsState
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
        override val hiddenRegionIdsFlow: Flow<Set<String>> = hiddenRegionIdsState
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
        override suspend fun setRegionVisibility(regionId: String, isVisible: Boolean) {
            hiddenRegionIdsState.value = if (isVisible) {
                hiddenRegionIdsState.value - regionId
            } else {
                hiddenRegionIdsState.value + regionId
            }
        }
        override suspend fun setHiddenRegionIds(regionIds: Set<String>) {
            hiddenRegionIdsState.value = regionIds.toSet()
        }
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
        val insertedPins = mutableListOf<OsmPinEntity>()

        override fun getAllOsmPinsFlow(): Flow<List<OsmPinEntity>> = MutableStateFlow(emptyList())
        override fun getUnresolvedOsmPinsFlow(): Flow<List<OsmPinEntity>> = MutableStateFlow(emptyList())
        override suspend fun getOsmPinById(pinId: String): OsmPinEntity? = null
        override suspend fun insertOsmPin(pin: OsmPinEntity) {
            insertedPins.add(pin)
        }
        override suspend fun updateOsmPin(pin: OsmPinEntity) {}
        override suspend fun deleteOsmPin(pinId: String) {}
        override suspend fun resetAllProgress() {}
        override suspend fun rescanGpxTraces() {}
        override suspend fun getCandidateSegmentsForBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadSegmentEntity> = emptyList()
        override suspend fun updateRoadSegments(segments: List<RoadSegmentEntity>) {}
        override fun getStatsSummaryFlow(): Flow<StatsSummary> = MutableStateFlow(StatsSummary())
    }

    @Test
    fun `ride controls transition state and manage stop confirmation dialog`() = runTest {
        val repo = FakeHomeRepository()
        val viewModel = HomeViewModel(repo)
        advanceUntilIdle()

        assertEquals(RideRecordingState.IDLE, viewModel.recordingState.value)
        assertFalse(viewModel.showStopConfirmationDialog.value)

        // Requesting stop while IDLE does not open dialog
        viewModel.requestStopRide()
        assertFalse(viewModel.showStopConfirmationDialog.value)

        // Start Ride -> RECORDING
        viewModel.startRide()
        assertEquals(RideRecordingState.RECORDING, viewModel.recordingState.value)

        val p1 = TrackPoint(lat = 37.7749, lon = -122.4194, timestamp = 1000L)
        val p2 = TrackPoint(lat = 37.7755, lon = -122.4200, timestamp = 2000L)
        repo.controller.appendTrackPoint(p1)
        repo.controller.appendTrackPoint(p2)
        assertEquals(listOf(p1, p2), viewModel.activeTrace.value)

        // Pause -> PAUSED
        viewModel.pauseRide()
        assertEquals(RideRecordingState.PAUSED, viewModel.recordingState.value)

        // Open Stop dialog & Cancel -> stays PAUSED
        viewModel.requestStopRide()
        advanceUntilIdle()
        assertTrue(viewModel.showStopConfirmationDialog.value)
        viewModel.dismissStopRideDialog()
        advanceUntilIdle()
        assertFalse(viewModel.showStopConfirmationDialog.value)
        assertEquals(RideRecordingState.PAUSED, viewModel.recordingState.value)

        // Resume -> RECORDING
        viewModel.resumeRide()
        assertEquals(RideRecordingState.RECORDING, viewModel.recordingState.value)

        // Stop -> Save Ride resets recordingState to IDLE, clears activeTrace, and emits Snackbar
        val messages = mutableListOf<String>()
        val snackbarJob = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarMessages.collect { messages.add(it) }
        }
        viewModel.requestStopRide()
        advanceUntilIdle()
        assertTrue(viewModel.showStopConfirmationDialog.value)
        viewModel.confirmSaveRide()
        advanceUntilIdle()
        assertFalse(viewModel.showStopConfirmationDialog.value)
        assertEquals(RideRecordingState.IDLE, viewModel.recordingState.value)
        assertTrue(viewModel.activeTrace.value.isEmpty())
        assertEquals(listOf("Ride saved to GPX"), messages)
        snackbarJob.cancel()
    }

    @Test
    fun `external notification stop automatically closes stop confirmation dialog`() = runTest {
        val repo = FakeHomeRepository()
        val viewModel = HomeViewModel(repo)
        advanceUntilIdle()

        viewModel.startRide()
        viewModel.requestStopRide()
        advanceUntilIdle()
        assertTrue(viewModel.showStopConfirmationDialog.value)

        // Simulate notification Stop action stopping the controller directly
        repo.controller.stopRecording()
        advanceUntilIdle()

        assertEquals(RideRecordingState.IDLE, viewModel.recordingState.value)
        assertFalse(viewModel.showStopConfirmationDialog.value)
    }

    @Test
    fun `passive location power rule uses balanced power unless actively RECORDING`() {
        val idleRequest = buildMapLocationEngineRequest(RideRecordingState.IDLE)
        val pausedRequest = buildMapLocationEngineRequest(RideRecordingState.PAUSED)
        val recordingRequest = buildMapLocationEngineRequest(RideRecordingState.RECORDING)

        assertEquals(LocationEngineRequest.PRIORITY_BALANCED_POWER_ACCURACY, idleRequest.priority)
        assertEquals(PASSIVE_LOCATION_INTERVAL_MS, idleRequest.interval)

        assertEquals(LocationEngineRequest.PRIORITY_BALANCED_POWER_ACCURACY, pausedRequest.priority)
        assertEquals(PASSIVE_LOCATION_INTERVAL_MS, pausedRequest.interval)

        assertEquals(LocationEngineRequest.PRIORITY_HIGH_ACCURACY, recordingRequest.priority)
        assertEquals(RECORDING_MAP_LOCATION_INTERVAL_MS, recordingRequest.interval)
    }

    @Test
    fun `pin placement starts at map camera target, updates as moved, and preserves draft on adjust`() = runTest {
        val repo = FakeHomeRepository()
        val viewModel = HomeViewModel(repo)
        advanceUntilIdle()

        // Even with GPS / active trace available, placement prefers the visible map center
        viewModel.startRide()
        repo.controller.appendTrackPoint(TrackPoint(lat = 37.7700, lon = -122.4100, timestamp = 1000L))

        viewModel.startPinPlacement(
            cameraLat = 37.7800,
            cameraLon = -122.4300,
            gpsLat = 37.7710,
            gpsLon = -122.4110
        )

        val initial = viewModel.pinPlacement.value
        assertNotNull(initial)
        assertEquals(37.7800, initial!!.lat, 1e-6)
        assertEquals(-122.4300, initial.lon, 1e-6)
        assertNull(viewModel.pinDraft.value)

        // Moving the map updates placement coordinates
        viewModel.updatePinPlacementCoordinates(37.7855, -122.4355)
        assertEquals(37.7855, viewModel.pinPlacement.value!!.lat, 1e-6)
        assertEquals(-122.4355, viewModel.pinPlacement.value!!.lon, 1e-6)

        // Confirming location opens the draft sheet at the exact placed spot
        viewModel.confirmPinPlacement(lat = 37.7860, lon = -122.4360)
        assertNull(viewModel.pinPlacement.value)
        val draft = viewModel.pinDraft.value
        assertNotNull(draft)
        assertEquals(37.7860, draft!!.lat, 1e-6)
        assertEquals(-122.4360, draft.lon, 1e-6)

        // Select category & note, then adjust pin location -> preserves draft fields
        viewModel.selectPinCategory(OsmPinCategory.MISSING_ROAD)
        viewModel.updatePinNote("New cul-de-sac")
        viewModel.adjustPinLocation()
        assertNull(viewModel.pinDraft.value)
        val adjusted = viewModel.pinPlacement.value
        assertNotNull(adjusted)
        assertEquals(37.7860, adjusted!!.lat, 1e-6)
        assertEquals(-122.4360, adjusted.lon, 1e-6)
        assertEquals(OsmPinCategory.MISSING_ROAD, adjusted.draftCategory)
        assertEquals("New cul-de-sac", adjusted.draftNote)

        // Re-confirm at refined coordinates and save
        viewModel.confirmPinPlacement(lat = 37.7862, lon = -122.4364)
        assertEquals(OsmPinCategory.MISSING_ROAD, viewModel.pinDraft.value?.selectedCategory)
        assertEquals("New cul-de-sac", viewModel.pinDraft.value?.note)

        viewModel.saveOsmPin()
        advanceUntilIdle()
        assertNull(viewModel.pinDraft.value)
        assertEquals(1, repo.insertedPins.size)
        assertEquals(37.7862, repo.insertedPins.first().lat, 1e-6)
        assertEquals(-122.4364, repo.insertedPins.first().lon, 1e-6)
        assertEquals(OsmPinCategory.MISSING_ROAD.name, repo.insertedPins.first().category)
        assertEquals("New cul-de-sac", repo.insertedPins.first().note)
    }

    @Test
    fun `cancelPinPlacement clears placement state without opening draft`() = runTest {
        val repo = FakeHomeRepository()
        val viewModel = HomeViewModel(repo)
        advanceUntilIdle()

        viewModel.startPinPlacement(
            cameraLat = 40.7128,
            cameraLon = -74.0060,
            gpsLat = null,
            gpsLon = null
        )
        assertNotNull(viewModel.pinPlacement.value)

        viewModel.cancelPinPlacement()
        assertNull(viewModel.pinPlacement.value)
        assertNull(viewModel.pinDraft.value)
    }

    @Test
    fun `checkRecoveredRide emits snackbar once when unsaved points were recovered`() = runTest {
        val fakeStore = object : com.example.mopedtrackv2.data.service.RideTraceStore {
            override fun loadSession() = com.example.mopedtrackv2.data.service.PersistedRideSession(
                state = RideRecordingState.RECORDING,
                points = listOf(
                    TrackPoint(lat = 37.77, lon = -122.41, timestamp = 1000L),
                    TrackPoint(lat = 37.78, lon = -122.42, timestamp = 2000L)
                ),
                completedSegmentCount = 1
            )
            override fun startSession(state: RideRecordingState, completedSegmentCount: Int) {}
            override fun appendPoint(point: TrackPoint) {}
            override fun updateState(state: RideRecordingState, completedSegmentCount: Int) {}
            override fun clear() {}
        }
        val controller = RideTrackingController(traceStore = fakeStore)
        val repo = FakeHomeRepository(controller)
        val viewModel = HomeViewModel(repo)
        advanceUntilIdle()

        val messages = mutableListOf<String>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            viewModel.snackbarMessages.collect { messages.add(it) }
        }

        viewModel.checkRecoveredRide()
        viewModel.checkRecoveredRide() // second call should be a no-op
        assertEquals(listOf("Recovered unsaved ride (2 points)"), messages)
        assertEquals(RideRecordingState.PAUSED, viewModel.recordingState.value)
        assertEquals(2, viewModel.activeTrace.value.size)
        job.cancel()
    }

    @Test
    fun `region layer visibility toggles update hiddenRegionIds and bulk visibility`() = runTest {
        val repo = FakeHomeRepository()
        repo.regionsState.value = listOf(
            Region("r1", "Alpha", 8, 1L, "0,1,0,1", 1000L),
            Region("r2", "Beta", 8, 2L, "0,1,0,1", 1000L)
        )
        val viewModel = HomeViewModel(repo)
        advanceUntilIdle()

        assertEquals(emptySet<String>(), viewModel.hiddenRegionIds.first())

        viewModel.toggleRegionVisibility("r1", isVisible = false)
        advanceUntilIdle()
        assertEquals(setOf("r1"), viewModel.hiddenRegionIds.first())

        viewModel.setAllRegionsVisibility(isVisible = false)
        advanceUntilIdle()
        assertEquals(setOf("r1", "r2"), viewModel.hiddenRegionIds.first())

        viewModel.toggleRegionVisibility("r1", isVisible = true)
        advanceUntilIdle()
        assertEquals(setOf("r2"), viewModel.hiddenRegionIds.first())

        viewModel.setAllRegionsVisibility(isVisible = true)
        advanceUntilIdle()
        assertEquals(emptySet<String>(), viewModel.hiddenRegionIds.first())
    }

    @Test
    fun `shouldRenderRoadSegment hides segments belonging to hidden regions`() {
        val segRegion1Completed = RoadSegment(
            segmentId = "s1",
            regionId = "r1",
            osmWayId = 10L,
            name = "Main St",
            highwayType = "residential",
            isOneway = false,
            pairedSegmentId = null,
            isDeadEnd = false,
            geometryEncoded = "",
            lengthMeters = 100.0,
            minLat = 0.0,
            maxLat = 0.0,
            minLon = 0.0,
            maxLon = 0.0,
            isCompleted = true,
            completedAt = 1000L
        )
        val segRegion2Uncompleted = segRegion1Completed.copy(
            segmentId = "s2",
            regionId = "r2",
            isCompleted = false,
            completedAt = null
        )

        assertTrue(
            shouldRenderRoadSegment(
                segment = segRegion1Completed,
                showCompletedSegments = true,
                showUncompletedSegments = true,
                hiddenRegionIds = emptySet()
            )
        )
        assertFalse(
            shouldRenderRoadSegment(
                segment = segRegion1Completed,
                showCompletedSegments = true,
                showUncompletedSegments = true,
                hiddenRegionIds = setOf("r1")
            )
        )
        assertTrue(
            shouldRenderRoadSegment(
                segment = segRegion2Uncompleted,
                showCompletedSegments = true,
                showUncompletedSegments = true,
                hiddenRegionIds = setOf("r1")
            )
        )
        assertFalse(
            shouldRenderRoadSegment(
                segment = segRegion2Uncompleted,
                showCompletedSegments = true,
                showUncompletedSegments = true,
                hiddenRegionIds = setOf("r2")
            )
        )
    }
}
