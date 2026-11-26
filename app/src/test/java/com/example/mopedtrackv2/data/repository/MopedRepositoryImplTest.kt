package com.example.mopedtrackv2.data.repository

import com.example.mopedtrackv2.data.gpx.ExternalTraceCopySummary
import com.example.mopedtrackv2.data.gpx.GpxExportResult
import com.example.mopedtrackv2.data.gpx.GpxExporter
import com.example.mopedtrackv2.data.gpx.InternalGpxTraceStore
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
import com.example.mopedtrackv2.data.remote.NominatimResult
import com.example.mopedtrackv2.data.remote.OsmApi
import com.example.mopedtrackv2.data.remote.OVERPASS_ENDPOINTS
import com.example.mopedtrackv2.data.remote.OverpassElement
import com.example.mopedtrackv2.data.remote.OverpassException
import com.example.mopedtrackv2.data.remote.OverpassResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import com.example.mopedtrackv2.data.service.RideTrackingController
import com.example.mopedtrackv2.domain.model.OsmPinCategory
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.TrackPoint
import com.example.mopedtrackv2.domain.model.UnitSystem
import com.example.mopedtrackv2.utils.HashUtils
import com.example.mopedtrackv2.utils.PolylineUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.time.Instant

class MopedRepositoryImplTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class FakeRegionDao : RegionDao {
        val regions = mutableMapOf<String, RegionEntity>()
        override suspend fun insertRegion(region: RegionEntity) {
            regions[region.regionId] = region
        }
        override suspend fun insertRegions(regions: List<RegionEntity>) {
            regions.forEach { this.regions[it.regionId] = it }
        }
        override fun getAllRegions(): Flow<List<RegionEntity>> = flowOf(regions.values.toList())
        override suspend fun getRegionById(regionId: String): RegionEntity? = regions[regionId]
        override suspend fun deleteRegionById(regionId: String) {
            regions.remove(regionId)
        }
        override suspend fun clearRegions() {
            regions.clear()
        }
    }

    private class FakeRoadSegmentDao : RoadSegmentDao {
        val segments = mutableListOf<RoadSegmentEntity>()
        override suspend fun insertRoadSegments(segments: List<RoadSegmentEntity>) {
            this.segments.addAll(segments)
        }
        override fun getRoadSegmentsByRegion(regionId: String): Flow<List<RoadSegmentEntity>> {
            return flowOf(segments.filter { it.regionId == regionId })
        }
        override fun getRoadSegmentsInBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Flow<List<RoadSegmentEntity>> {
            return flowOf(segments)
        }
        override suspend fun getCandidateSegments(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadSegmentEntity> {
            return segments
        }
        var lastUncompletedQueryBounds: List<Double>? = null
        override suspend fun getUncompletedCandidateSegments(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadSegmentEntity> {
            lastUncompletedQueryBounds = listOf(minLat, maxLat, minLon, maxLon)
            return segments.filter {
                !it.isCompleted &&
                    it.maxLat >= minLat && it.minLat <= maxLat &&
                    it.maxLon >= minLon && it.minLon <= maxLon
            }
        }
        override suspend fun markSegmentsCompletedByIds(segmentIds: List<String>, completedAt: Long) {
            for (i in segments.indices) {
                if (!segments[i].isCompleted && segments[i].segmentId in segmentIds) {
                    segments[i] = segments[i].copy(isCompleted = true, completedAt = completedAt)
                }
            }
        }
        override suspend fun markSegmentsCompletedByPairedIds(pairedIds: List<String>, completedAt: Long) {
            for (i in segments.indices) {
                if (!segments[i].isCompleted && segments[i].pairedSegmentId != null && segments[i].pairedSegmentId in pairedIds) {
                    segments[i] = segments[i].copy(isCompleted = true, completedAt = completedAt)
                }
            }
        }
        override suspend fun updateSegments(segments: List<RoadSegmentEntity>) {
            segments.forEach { updated ->
                val idx = this.segments.indexOfFirst { it.segmentId == updated.segmentId }
                if (idx != -1) this.segments[idx] = updated
            }
        }
        override suspend fun getRoadSegmentById(segmentId: String): RoadSegmentEntity? {
            return segments.find { it.segmentId == segmentId }
        }
        override suspend fun updateCompletionStatus(segmentId: String, isCompleted: Boolean, completedAt: Long?) {
            val idx = segments.indexOfFirst { it.segmentId == segmentId }
            if (idx != -1) {
                segments[idx] = segments[idx].copy(isCompleted = isCompleted, completedAt = completedAt)
            }
        }
        override suspend fun getPairedSegmentId(segmentId: String): String? {
            return segments.find { it.segmentId == segmentId }?.pairedSegmentId
        }
        override suspend fun updateCompletionStatusByPairedId(pairedId: String, isCompleted: Boolean, completedAt: Long?) {
            segments.indices.filter { segments[it].pairedSegmentId == pairedId }.forEach {
                segments[it] = segments[it].copy(isCompleted = isCompleted, completedAt = completedAt)
            }
        }
        override suspend fun deleteRoadSegmentsByRegion(regionId: String) {
            segments.removeAll { it.regionId == regionId }
        }
        override suspend fun getRoadSegmentsForRegion(regionId: String): List<RoadSegmentEntity> {
            return segments.filter { it.regionId == regionId }
        }
        override suspend fun replaceRegionSegments(regionId: String, segments: List<RoadSegmentEntity>) {
            deleteRoadSegmentsByRegion(regionId)
            insertRoadSegments(segments)
        }
        override suspend fun clearRoadSegments() {
            segments.clear()
        }
        override suspend fun resetAllCompletionStatus() {
            for (i in segments.indices) {
                segments[i] = segments[i].copy(isCompleted = false, completedAt = null)
            }
        }
        var regionNamesById: Map<String, String> = emptyMap()
        private fun canonicalKey(seg: RoadSegmentEntity): String {
            val paired = seg.pairedSegmentId
            return when {
                paired.isNullOrEmpty() -> seg.segmentId
                segments.any { it.segmentId == paired } -> if (seg.segmentId < paired) seg.segmentId else paired
                else -> paired
            }
        }
        private fun isUnnamed(seg: RoadSegmentEntity): Boolean {
            return seg.name.isNullOrEmpty() || seg.name == "Unnamed Road"
        }
        override fun getOverallRoadStatsFlow(): Flow<RoadStatsAggregate> {
            val grouped = segments.groupBy { it.regionId to canonicalKey(it) }.values
            var totalSegs = 0
            var completedSegs = 0
            var totalLen = 0.0
            var completedLen = 0.0
            var unnamedCompleted = 0
            for (group in grouped) {
                val isDone = group.any { it.isCompleted }
                val groupUnnamed = group.all { isUnnamed(it) }
                if (groupUnnamed) {
                    if (isDone) unnamedCompleted++
                    continue
                }
                totalSegs++
                val avgLen = group.map { it.lengthMeters }.average()
                totalLen += avgLen
                if (isDone) {
                    completedSegs++
                    completedLen += avgLen
                }
            }
            return flowOf(RoadStatsAggregate(totalSegs, completedSegs, totalLen, completedLen, unnamedCompleted))
        }
        override fun getRegionStatsFlow(): Flow<List<RegionStatsRow>> {
            val allRegionIds = (regionNamesById.keys + segments.map { it.regionId }).distinct()
            val rows = allRegionIds.map { regionId ->
                val regionSegs = segments.filter { it.regionId == regionId && !isUnnamed(it) }
                val grouped = regionSegs.groupBy { canonicalKey(it) }.values
                var totalSegs = 0
                var completedSegs = 0
                var totalLen = 0.0
                var completedLen = 0.0
                for (group in grouped) {
                    totalSegs++
                    val isDone = group.any { it.isCompleted }
                    val avgLen = group.map { it.lengthMeters }.average()
                    totalLen += avgLen
                    if (isDone) {
                        completedSegs++
                        completedLen += avgLen
                    }
                }
                RegionStatsRow(
                    regionId = regionId,
                    regionName = regionNamesById[regionId] ?: regionId,
                    totalSegments = totalSegs,
                    completedSegments = completedSegs,
                    totalLengthMeters = totalLen,
                    completedLengthMeters = completedLen
                )
            }.sortedBy { it.regionName }
            return flowOf(rows)
        }
    }

    private class FakeImportedGpxFileDao : ImportedGpxFileDao {
        val files = mutableListOf<ImportedGpxFileEntity>()
        override fun getAllFilesFlow(): Flow<List<ImportedGpxFileEntity>> = flowOf(files)
        override suspend fun getFileByHash(hash: String): ImportedGpxFileEntity? = files.find { it.fileHash == hash }
        override suspend fun insertFile(file: ImportedGpxFileEntity) {
            val idx = files.indexOfFirst { it.fileHash == file.fileHash }
            if (idx != -1) files[idx] = file else files.add(file)
        }
        override suspend fun getImportedFilesCount(): Int = files.size
        override suspend fun updateVisibility(hash: String, isVisible: Boolean) {
            val idx = files.indexOfFirst { it.fileHash == hash }
            if (idx != -1) files[idx] = files[idx].copy(isVisibleOnMap = isVisible)
        }
        override suspend fun updateAllVisibility(isVisible: Boolean) {
            for (i in files.indices) {
                files[i] = files[i].copy(isVisibleOnMap = isVisible)
            }
        }
        override fun getVisibleFilesFlow(): Flow<List<ImportedGpxFileEntity>> = flowOf(files.filter { it.isVisibleOnMap })
        override suspend fun deleteFileByHash(hash: String) {
            files.removeAll { it.fileHash == hash }
        }
        override suspend fun deleteLegacyTraces() {
            files.removeAll { it.encodedTrace.isEmpty() }
        }
        override suspend fun resetAllSegmentsCompletedCount() {
            for (i in files.indices) {
                files[i] = files[i].copy(segmentsCompletedCount = 0)
            }
        }
        override fun getGpxArchiveStatsFlow(): Flow<GpxArchiveStatsAggregate> {
            return flowOf(
                GpxArchiveStatsAggregate(
                    totalGpxFiles = files.size,
                    totalTrackPoints = files.sumOf { it.trackPointCount.toLong() }
                )
            )
        }
    }

    private class FakeDebugTraceDao : DebugTraceDao {
        var trace: DebugTraceEntity? = null
        override fun getLastTraceFlow(): Flow<DebugTraceEntity?> = flowOf(trace)
        override suspend fun insertTrace(trace: DebugTraceEntity) { this.trace = trace }
        override suspend fun clearAllTraces() { trace = null }
    }

    private class FakeOsmPinDao : OsmPinDao {
        val pins = MutableStateFlow<List<OsmPinEntity>>(emptyList())
        override fun getAllPinsFlow(): Flow<List<OsmPinEntity>> = pins
        override fun getUnresolvedPinsFlow(): Flow<List<OsmPinEntity>> =
            flowOf(pins.value.filter { !it.isResolved }.sortedByDescending { it.createdAt })
        override suspend fun getPinById(pinId: String): OsmPinEntity? = pins.value.find { it.pinId == pinId }
        override suspend fun insertPin(pin: OsmPinEntity) {
            val updated = pins.value.filterNot { it.pinId == pin.pinId } + pin
            pins.value = updated.sortedByDescending { it.createdAt }
        }
        override suspend fun updatePin(pin: OsmPinEntity) {
            pins.value = pins.value.map { if (it.pinId == pin.pinId) pin else it }
        }
        override suspend fun setPinResolved(pinId: String, isResolved: Boolean) {
            pins.value = pins.value.map { if (it.pinId == pinId) it.copy(isResolved = isResolved) else it }
        }
        override suspend fun deletePin(pin: OsmPinEntity) {
            pins.value = pins.value.filterNot { it.pinId == pin.pinId }
        }
        override suspend fun deletePinById(pinId: String) {
            pins.value = pins.value.filterNot { it.pinId == pinId }
        }
        override suspend fun clearAllPins() {
            pins.value = emptyList()
        }
    }

    private class FakeSettingsDataStore : SettingsDataStore {
        private val savedCameraState = MutableStateFlow<SavedCameraPosition?>(null)
        private val pollingIntervalState = MutableStateFlow(1000L)
        private val minDisplacementState = MutableStateFlow(2.0f)
        private val gpxExportTreeUriState = MutableStateFlow<String?>(null)
        private val weeklyAutoRefreshState = MutableStateFlow(false)
        private val hiddenRegionIdsState = MutableStateFlow<Set<String>>(emptySet())

        override val matchRadiusFlow: Flow<Int> = flowOf(15)
        override val coverageThresholdFlow: Flow<Int> = flowOf(80)
        override val debugOverlayEnabledFlow: Flow<Boolean> = flowOf(false)
        override val showCompletedSegmentsFlow: Flow<Boolean> = flowOf(true)
        override val showUncompletedSegmentsFlow: Flow<Boolean> = flowOf(true)
        override val showGpxTracesFlow: Flow<Boolean> = flowOf(true)
        override val hiddenRegionIdsFlow: Flow<Set<String>> = hiddenRegionIdsState
        override val savedCameraPositionFlow: Flow<SavedCameraPosition?> = savedCameraState
        override val pollingIntervalMsFlow: Flow<Long> = pollingIntervalState
        override val minDisplacementMetersFlow: Flow<Float> = minDisplacementState
        override val gpxExportTreeUriFlow: Flow<String?> = gpxExportTreeUriState
        override val unitSystemFlow: Flow<UnitSystem> = flowOf(UnitSystem.IMPERIAL)
        override val weeklyAutoRefreshFlow: Flow<Boolean> = weeklyAutoRefreshState

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
        override suspend fun setSavedCameraPosition(lat: Double, lon: Double, zoom: Double, bearing: Double, tilt: Double) {
            savedCameraState.value = SavedCameraPosition(lat, lon, zoom, bearing, tilt)
        }
        override suspend fun setPollingIntervalMs(intervalMs: Long) {
            pollingIntervalState.value = intervalMs.coerceIn(500L, 5000L)
        }
        override suspend fun setMinDisplacementMeters(meters: Float) {
            minDisplacementState.value = meters.coerceIn(0.0f, 15.0f)
        }
        override suspend fun setGpxExportTreeUri(uri: String?) {
            gpxExportTreeUriState.value = uri
        }
        override suspend fun setUnitSystem(unitSystem: UnitSystem) {}
        override suspend fun setWeeklyAutoRefresh(enabled: Boolean) {
            weeklyAutoRefreshState.value = enabled
        }
    }

    private class FakeOsmApi(
        private val healthyStatusUrls: Set<String> = emptySet(),
        private val handler: (url: String, query: String) -> OverpassResponse
    ) : OsmApi {
        val queriedUrls = mutableListOf<String>()
        val queries = mutableListOf<String>()
        val probedUrls = java.util.Collections.synchronizedList(mutableListOf<String>())

        override suspend fun searchNominatim(query: String, format: String, polygonGeojson: Int): List<NominatimResult> {
            return emptyList()
        }

        override suspend fun queryOverpass(url: String, query: String): OverpassResponse {
            queriedUrls.add(url)
            queries.add(query)
            return handler(url, query)
        }

        override suspend fun getOverpassStatus(url: String): retrofit2.Response<okhttp3.ResponseBody> {
            probedUrls.add(url)
            if (url in healthyStatusUrls) {
                return retrofit2.Response.success("8 slots available now.".toResponseBody(null))
            }
            throw java.net.SocketTimeoutException("status probe timeout")
        }
    }

    private val sampleRegion = Region(
        regionId = "1001",
        name = "Test Region",
        adminLevel = 0,
        osmRelationId = 3600123456L,
        boundingBox = "51.50,51.52,-0.12,-0.10",
        lastUpdatedTimestamp = System.currentTimeMillis()
    )

    private val sampleOverpassResponse = OverpassResponse(
        elements = listOf(
            OverpassElement(type = "node", id = 1, lat = 51.50, lon = -0.12),
            OverpassElement(type = "node", id = 2, lat = 51.51, lon = -0.11),
            OverpassElement(
                type = "way",
                id = 101,
                nodes = listOf(1, 2),
                tags = mapOf("name" to "Main St", "highway" to "residential")
            )
        )
    )

    @Test
    fun testEndpointFallbackRetriesOnFailure() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val debugDao = FakeDebugTraceDao()
        val settingsDataStore = FakeSettingsDataStore()
        val failedUrls = mutableListOf<String>()

        val fakeOsmApi = FakeOsmApi { url, query ->
            if (url == OVERPASS_ENDPOINTS[0]) {
                failedUrls.add(url)
                throw IOException("502 Bad Gateway")
            }
            sampleOverpassResponse
        }

        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, FakeOsmPinDao(), settingsDataStore, fakeOsmApi)
        repository.importRegion(sampleRegion)

        assertEquals(2, fakeOsmApi.queriedUrls.size)
        assertEquals(OVERPASS_ENDPOINTS[0], fakeOsmApi.queriedUrls[0])
        assertEquals(OVERPASS_ENDPOINTS[1], fakeOsmApi.queriedUrls[1])
        assertEquals(1, roadSegmentDao.segments.size)
        assertEquals("Main St", roadSegmentDao.segments[0].name)
    }

    @Test
    fun testAreaQueryFallbackToBboxQueryWhenAreaFailsOnAllEndpoints() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val debugDao = FakeDebugTraceDao()
        val settingsDataStore = FakeSettingsDataStore()

        val fakeOsmApi = FakeOsmApi { url, query ->
            if (query.contains("area(")) {
                throw IOException("Area query timed out")
            }
            sampleOverpassResponse
        }

        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, FakeOsmPinDao(), settingsDataStore, fakeOsmApi)
        repository.importRegion(sampleRegion)

        // N queries for area (one per endpoint), 1 query for bbox (first endpoint succeeds)
        assertEquals(OVERPASS_ENDPOINTS.size + 1, fakeOsmApi.queriedUrls.size)
        assertTrue(fakeOsmApi.queries.last().contains("way(51.5,-0.12,51.52,-0.1)"))
        assertEquals(1, roadSegmentDao.segments.size)
    }

    @Test
    fun testAllEndpointsAndFallbacksFailThrowsOverpassException() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val debugDao = FakeDebugTraceDao()
        val settingsDataStore = FakeSettingsDataStore()

        val fakeOsmApi = FakeOsmApi { url, query ->
            throw IOException("HTTP 502 Bad Gateway")
        }

        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, FakeOsmPinDao(), settingsDataStore, fakeOsmApi)
        try {
            repository.importRegion(sampleRegion)
            fail("Expected OverpassException")
        } catch (e: OverpassException) {
            assertTrue(e.message!!.startsWith("All OpenStreetMap (Overpass) servers are busy or unreachable"))
            assertTrue(e.message!!.contains("HTTP 502 Bad Gateway"))
        }
    }

    @Test
    fun testHealthProbeSkipsUnresponsiveEndpoints() = runBlocking {
        val roadSegmentDao = FakeRoadSegmentDao()
        val healthyEndpoint = OVERPASS_ENDPOINTS.last()
        val fakeOsmApi = FakeOsmApi(
            healthyStatusUrls = setOf(com.example.mopedtrackv2.data.remote.overpassStatusUrl(healthyEndpoint))
        ) { _, _ -> sampleOverpassResponse }

        val repository = MopedRepositoryImpl(FakeRegionDao(), roadSegmentDao, FakeImportedGpxFileDao(), FakeDebugTraceDao(), FakeOsmPinDao(), FakeSettingsDataStore(), fakeOsmApi)
        repository.importRegion(sampleRegion)

        assertEquals(OVERPASS_ENDPOINTS.size, fakeOsmApi.probedUrls.size)
        assertEquals(listOf(healthyEndpoint), fakeOsmApi.queriedUrls)
        assertEquals(1, roadSegmentDao.segments.size)
    }

    @Test
    fun testTimedOutEndpointsAreSkippedForBboxFallback() = runBlocking {
        val roadSegmentDao = FakeRoadSegmentDao()
        val fakeOsmApi = FakeOsmApi { url, query ->
            when {
                url == OVERPASS_ENDPOINTS[0] -> throw java.net.SocketTimeoutException("timeout")
                query.contains("area(") -> throw IOException("HTTP 504")
                else -> sampleOverpassResponse
            }
        }

        val repository = MopedRepositoryImpl(FakeRegionDao(), roadSegmentDao, FakeImportedGpxFileDao(), FakeDebugTraceDao(), FakeOsmPinDao(), FakeSettingsDataStore(), fakeOsmApi)
        repository.importRegion(sampleRegion)

        // Area pass hits every endpoint; bbox pass skips the timed-out endpoint 0 and succeeds on endpoint 1.
        assertEquals(OVERPASS_ENDPOINTS.size + 1, fakeOsmApi.queriedUrls.size)
        assertEquals(OVERPASS_ENDPOINTS[1], fakeOsmApi.queriedUrls.last())
        assertTrue(fakeOsmApi.queries.last().contains("way(51.5,-0.12,51.52,-0.1)"))
        assertEquals(1, roadSegmentDao.segments.size)
    }

    @Test
    fun testRuntimeErrorRemarkTriggersFallback() = runBlocking {
        val roadSegmentDao = FakeRoadSegmentDao()
        val fakeOsmApi = FakeOsmApi { url, _ ->
            if (url == OVERPASS_ENDPOINTS[0]) {
                OverpassResponse(
                    elements = sampleOverpassResponse.elements.take(1),
                    remark = "runtime error: Query timed out in \"query\" at line 4 after 91 seconds."
                )
            } else {
                sampleOverpassResponse
            }
        }

        val repository = MopedRepositoryImpl(FakeRegionDao(), roadSegmentDao, FakeImportedGpxFileDao(), FakeDebugTraceDao(), FakeOsmPinDao(), FakeSettingsDataStore(), fakeOsmApi)
        repository.importRegion(sampleRegion)

        assertEquals(listOf(OVERPASS_ENDPOINTS[0], OVERPASS_ENDPOINTS[1]), fakeOsmApi.queriedUrls)
        assertEquals(1, roadSegmentDao.segments.size)
    }

    @Test
    fun testToggleSegmentCompletionSingleSegment() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val debugDao = FakeDebugTraceDao()
        val settingsDataStore = FakeSettingsDataStore()
        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, FakeOsmPinDao(), settingsDataStore)

        val seg = RoadSegmentEntity(
            segmentId = "seg-1",
            regionId = "1001",
            osmWayId = 100,
            name = "Test St",
            highwayType = "residential",
            isOneway = false,
            pairedSegmentId = null,
            isDeadEnd = false,
            geometryEncoded = "[]",
            lengthMeters = 100.0,
            minLat = 50.0, maxLat = 51.0, minLon = 10.0, maxLon = 11.0,
            isCompleted = false,
            completedAt = null
        )
        roadSegmentDao.insertRoadSegments(listOf(seg))

        val timestamp = System.currentTimeMillis()
        repository.toggleSegmentCompletion("seg-1", true, timestamp)

        val updated = roadSegmentDao.getRoadSegmentById("seg-1")
        assertTrue(updated?.isCompleted == true)
        assertEquals(timestamp, updated?.completedAt)
    }

    @Test
    fun testToggleSegmentCompletionPairedSegment() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val debugDao = FakeDebugTraceDao()
        val settingsDataStore = FakeSettingsDataStore()
        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, FakeOsmPinDao(), settingsDataStore)

        val seg1 = RoadSegmentEntity(
            segmentId = "seg-1",
            regionId = "1001",
            osmWayId = 100,
            name = "Dual Way North",
            highwayType = "secondary",
            isOneway = true,
            pairedSegmentId = "pair-99",
            isDeadEnd = false,
            geometryEncoded = "[]",
            lengthMeters = 100.0,
            minLat = 50.0, maxLat = 51.0, minLon = 10.0, maxLon = 11.0,
            isCompleted = false,
            completedAt = null
        )
        val seg2 = RoadSegmentEntity(
            segmentId = "seg-2",
            regionId = "1001",
            osmWayId = 101,
            name = "Dual Way South",
            highwayType = "secondary",
            isOneway = true,
            pairedSegmentId = "pair-99",
            isDeadEnd = false,
            geometryEncoded = "[]",
            lengthMeters = 100.0,
            minLat = 50.0, maxLat = 51.0, minLon = 10.0, maxLon = 11.0,
            isCompleted = false,
            completedAt = null
        )
        roadSegmentDao.insertRoadSegments(listOf(seg1, seg2))

        val timestamp = System.currentTimeMillis()
        repository.toggleSegmentCompletion("seg-1", true, timestamp)

        val updated1 = roadSegmentDao.getRoadSegmentById("seg-1")
        val updated2 = roadSegmentDao.getRoadSegmentById("seg-2")

        assertTrue(updated1?.isCompleted == true)
        assertEquals(timestamp, updated1?.completedAt)
        assertTrue(updated2?.isCompleted == true)
        assertEquals(timestamp, updated2?.completedAt)
    }

    @Test
    fun testSaveAndRestoreCameraPosition() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val debugDao = FakeDebugTraceDao()
        val settingsDataStore = FakeSettingsDataStore()
        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, FakeOsmPinDao(), settingsDataStore)

        repository.setSavedCameraPosition(37.7749, -122.4194, 15.0, 45.0, 10.0)

        val restored = repository.savedCameraPositionFlow.first()
        assertTrue(restored != null)
        assertEquals(37.7749, restored!!.latitude, 0.0001)
        assertEquals(-122.4194, restored.longitude, 0.0001)
        assertEquals(15.0, restored.zoom, 0.0001)
        assertEquals(45.0, restored.bearing, 0.0001)
        assertEquals(10.0, restored.tilt, 0.0001)
    }

    @Test
    fun testRideRecordingAndExportPreferences() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val debugDao = FakeDebugTraceDao()
        val settingsDataStore = FakeSettingsDataStore()
        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, FakeOsmPinDao(), settingsDataStore)

        assertEquals(1000L, repository.pollingIntervalMsFlow.first())
        assertEquals(2.0f, repository.minDisplacementMetersFlow.first(), 0.0001f)
        assertEquals(null, repository.gpxExportTreeUriFlow.first())

        repository.setPollingIntervalMs(2500L)
        repository.setMinDisplacementMeters(8.0f)
        repository.setGpxExportTreeUri("content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FRides")

        assertEquals(2500L, repository.pollingIntervalMsFlow.first())
        assertEquals(8.0f, repository.minDisplacementMetersFlow.first(), 0.0001f)
        assertEquals(
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments%2FRides",
            repository.gpxExportTreeUriFlow.first()
        )

        repository.setGpxExportTreeUri(null)
        assertEquals(null, repository.gpxExportTreeUriFlow.first())
    }

    @Test
    fun testMatchLiveTraceWindowMarksMatchedAndPairedSegmentsCompleted() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val debugDao = FakeDebugTraceDao()
        val settingsDataStore = FakeSettingsDataStore()
        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, FakeOsmPinDao(), settingsDataStore)

        val seg1Pts = listOf(Pair(37.7749, -122.4194), Pair(37.7749, -122.4184), Pair(37.7749, -122.4174))
        val seg1 = RoadSegmentEntity(
            segmentId = "seg-1",
            regionId = "reg-1",
            osmWayId = 100L,
            name = "Market St EB",
            highwayType = "primary",
            isOneway = true,
            pairedSegmentId = "pair-market",
            isDeadEnd = false,
            geometryEncoded = PolylineUtils.encode(seg1Pts),
            lengthMeters = 176.0,
            minLat = 37.7749, maxLat = 37.7749, minLon = -122.4194, maxLon = -122.4174,
            isCompleted = false
        )
        val seg2 = RoadSegmentEntity(
            segmentId = "seg-2",
            regionId = "reg-1",
            osmWayId = 101L,
            name = "Market St WB",
            highwayType = "primary",
            isOneway = true,
            pairedSegmentId = "pair-market",
            isDeadEnd = false,
            geometryEncoded = PolylineUtils.encode(seg1Pts.reversed()),
            lengthMeters = 176.0,
            minLat = 37.77515, maxLat = 37.77515, minLon = -122.4194, maxLon = -122.4174,
            isCompleted = false
        )
        val farSeg = RoadSegmentEntity(
            segmentId = "seg-far",
            regionId = "reg-1",
            osmWayId = 102L,
            name = "Far Ave",
            highwayType = "residential",
            isOneway = false,
            pairedSegmentId = null,
            isDeadEnd = false,
            geometryEncoded = PolylineUtils.encode(listOf(Pair(37.7900, -122.4194), Pair(37.7900, -122.4174))),
            lengthMeters = 176.0,
            minLat = 37.7900, maxLat = 37.7900, minLon = -122.4194, maxLon = -122.4174,
            isCompleted = false
        )
        roadSegmentDao.insertRoadSegments(listOf(seg1, seg2, farSeg))

        val trace = listOf(
            TrackPoint(lat = 37.7749, lon = -122.4194, timestamp = 1000L),
            TrackPoint(lat = 37.7749, lon = -122.4189, timestamp = 2000L),
            TrackPoint(lat = 37.7749, lon = -122.4184, timestamp = 3000L),
            TrackPoint(lat = 37.7749, lon = -122.4179, timestamp = 4000L),
            TrackPoint(lat = 37.7749, lon = -122.4174, timestamp = 5000L)
        )

        val matchedCount = repository.matchLiveTraceWindow(trace)
        assertTrue(matchedCount >= 1)

        val bounds = roadSegmentDao.lastUncompletedQueryBounds
        assertNotNull(bounds)
        // Padded by matchRadiusMeters (15m) + 10m = 25m (~0.000225 deg)
        assertTrue(bounds!![0] < 37.7749)
        assertTrue(bounds[1] > 37.7749)

        assertTrue(roadSegmentDao.getRoadSegmentById("seg-1")?.isCompleted == true)
        assertTrue(roadSegmentDao.getRoadSegmentById("seg-2")?.isCompleted == true)
        assertFalse(roadSegmentDao.getRoadSegmentById("seg-far")?.isCompleted == true)
    }

    @Test
    fun testSaveRideRecordingExportsGpxDeduplicatesAndResetsState() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val debugDao = FakeDebugTraceDao()
        val settingsDataStore = FakeSettingsDataStore()
        val controller = RideTrackingController()

        var exportedFileName: String? = null
        var exportedContent: String? = null
        var exportedTreeUri: String? = null
        val fakeExporter = object : GpxExporter {
            override suspend fun exportGpx(fileName: String, content: String, treeUriString: String?): GpxExportResult {
                exportedFileName = fileName
                exportedContent = content
                exportedTreeUri = treeUriString
                return GpxExportResult(fileName, content.toByteArray(Charsets.UTF_8), usedSafTree = true)
            }
        }

        val repository = MopedRepositoryImpl(
            regionDao = regionDao,
            roadSegmentDao = roadSegmentDao,
            importedGpxFileDao = gpxDao,
            debugTraceDao = debugDao,
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = settingsDataStore,
            rideTrackingController = controller,
            gpxExporter = fakeExporter
        )

        settingsDataStore.setGpxExportTreeUri("content://com.android.externalstorage.documents/tree/primary%3ARides")
        repository.startRideRecording()

        val t1 = Instant.parse("2026-11-24T10:00:00Z").toEpochMilli()
        val t2 = Instant.parse("2026-11-24T10:00:10Z").toEpochMilli()
        controller.appendTrackPoint(TrackPoint(lat = 37.7749, lon = -122.4194, altitude = 10.0, timestamp = t1))
        controller.appendTrackPoint(TrackPoint(lat = 37.7755, lon = -122.4185, altitude = 12.0, timestamp = t2))

        val saved = repository.saveRideRecording()
        assertTrue(saved)

        assertNotNull(exportedFileName)
        assertTrue(exportedFileName!!.startsWith("MopedTrack_"))
        assertTrue(exportedFileName!!.endsWith(".gpx"))
        assertEquals("content://com.android.externalstorage.documents/tree/primary%3ARides", exportedTreeUri)
        assertTrue(exportedContent!!.contains("version=\"1.1\""))
        assertTrue(exportedContent!!.contains("<time>2026-11-24T10:00:00Z</time>"))

        val expectedHash = HashUtils.sha256Hex(exportedContent!!.toByteArray(Charsets.UTF_8))
        val inserted = gpxDao.getFileByHash(expectedHash)
        assertNotNull(inserted)
        assertEquals(exportedFileName, inserted!!.fileName)
        assertEquals(2, inserted.trackPointCount)
        assertFalse(inserted.isVisibleOnMap)
        assertEquals(
            PolylineUtils.encode(listOf(Pair(37.7749, -122.4194), Pair(37.7755, -122.4185))),
            inserted.encodedTrace
        )
        assertEquals(10_000L, inserted.durationMillis)
        assertEquals(t1, inserted.recordedAt)
        assertTrue(inserted.distanceMeters > 50.0)

        assertEquals(RideRecordingState.IDLE, repository.recordingState.value)
        assertTrue(repository.activeTrace.value.isEmpty())
    }

    @Test
    fun `backfillGpxMetadata populates distanceMeters for legacy imported files`() = runBlocking {
        val gpxDao = FakeImportedGpxFileDao()
        val encoded = PolylineUtils.encode(listOf(Pair(37.7749, -122.4194), Pair(37.7759, -122.4194)))
        gpxDao.insertFile(
            ImportedGpxFileEntity(
                fileHash = "legacy-1",
                fileName = "legacy.gpx",
                importedAt = 1000L,
                trackPointCount = 2,
                segmentsCompletedCount = 1,
                encodedTrace = encoded,
                isVisibleOnMap = false,
                distanceMeters = 0.0
            )
        )

        val repository = MopedRepositoryImpl(
            regionDao = FakeRegionDao(),
            roadSegmentDao = FakeRoadSegmentDao(),
            importedGpxFileDao = gpxDao,
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = FakeSettingsDataStore()
        )

        repository.backfillGpxMetadata()

        val updated = gpxDao.getFileByHash("legacy-1")
        assertNotNull(updated)
        assertTrue(updated!!.distanceMeters > 100.0)
    }

    @Test
    fun `setAllGpxVisibility enables and disables all imported GPX traces`() = runBlocking {
        val gpxDao = FakeImportedGpxFileDao()
        gpxDao.insertFile(
            ImportedGpxFileEntity(
                fileHash = "h1",
                fileName = "ride1.gpx",
                importedAt = 1000L,
                trackPointCount = 10,
                segmentsCompletedCount = 1,
                encodedTrace = "abc",
                isVisibleOnMap = true
            )
        )
        gpxDao.insertFile(
            ImportedGpxFileEntity(
                fileHash = "h2",
                fileName = "ride2.gpx",
                importedAt = 2000L,
                trackPointCount = 20,
                segmentsCompletedCount = 2,
                encodedTrace = "def",
                isVisibleOnMap = false
            )
        )

        val repository = MopedRepositoryImpl(
            regionDao = FakeRegionDao(),
            roadSegmentDao = FakeRoadSegmentDao(),
            importedGpxFileDao = gpxDao,
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = FakeSettingsDataStore()
        )

        repository.setAllGpxVisibility(true)
        assertTrue(gpxDao.files.all { it.isVisibleOnMap })

        repository.setAllGpxVisibility(false)
        assertTrue(gpxDao.files.none { it.isVisibleOnMap })
    }

    @Test
    fun `OSM pin insert query update and delete operations persist via OsmPinDao`() = runBlocking {
        val osmPinDao = FakeOsmPinDao()
        val repository = MopedRepositoryImpl(
            regionDao = FakeRegionDao(),
            roadSegmentDao = FakeRoadSegmentDao(),
            importedGpxFileDao = FakeImportedGpxFileDao(),
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = osmPinDao,
            settingsDataStore = FakeSettingsDataStore()
        )

        val pin1 = OsmPinEntity(
            pinId = "pin-1",
            lat = 37.7749,
            lon = -122.4194,
            category = OsmPinCategory.MISSING_ROAD.name,
            note = "New alley",
            createdAt = 1000L,
            isResolved = false
        )
        val pin2 = OsmPinEntity(
            pinId = "pin-2",
            lat = 37.7755,
            lon = -122.4185,
            category = OsmPinCategory.PRIVATE_GATED.name,
            note = "Gate locked",
            createdAt = 2000L,
            isResolved = false
        )

        repository.insertOsmPin(pin1)
        repository.insertOsmPin(pin2)

        val allPins = repository.getAllOsmPinsFlow().first()
        assertEquals(2, allPins.size)
        assertEquals("pin-2", allPins[0].pinId)
        assertEquals("pin-1", allPins[1].pinId)
        assertEquals(pin1, repository.getOsmPinById("pin-1"))

        val updatedPin1 = pin1.copy(isResolved = true, note = "Mapped in OSM")
        repository.updateOsmPin(updatedPin1)

        assertEquals(updatedPin1, repository.getOsmPinById("pin-1"))
        val unresolved = repository.getUnresolvedOsmPinsFlow().first()
        assertEquals(listOf(pin2), unresolved)

        repository.deleteOsmPin("pin-2")
        assertEquals(listOf(updatedPin1), repository.getAllOsmPinsFlow().first())
    }

    @Test
    fun `refreshRegion preserves completion when OSM way splits and IDs change while adding new roads`() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val settingsDataStore = FakeSettingsDataStore()

        val region = Region(
            regionId = "reg-refresh-1",
            name = "Refreshville",
            adminLevel = 8,
            osmRelationId = 3600555555L,
            boundingBox = "46.87,46.88,-96.91,-96.89",
            lastUpdatedTimestamp = 1000L
        )
        regionDao.insertRegion(
            RegionEntity(
                regionId = region.regionId,
                name = region.name,
                adminLevel = region.adminLevel,
                osmRelationId = region.osmRelationId,
                boundingBox = region.boundingBox,
                lastUpdatedTimestamp = 1000L
            )
        )

        // Old completed road spanning West -> East (from -96.905 to -96.901) with NO GPX stored
        val savedCompletedAt = 1700000000000L
        val oldCompletedCoords = listOf(
            Pair(46.8750, -96.9050),
            Pair(46.8750, -96.9030),
            Pair(46.8750, -96.9010)
        )
        roadSegmentDao.insertRoadSegments(
            listOf(
                RoadSegmentEntity(
                    segmentId = "old-completed-1",
                    regionId = region.regionId,
                    osmWayId = 100L,
                    name = "Main Ave",
                    highwayType = "residential",
                    isOneway = false,
                    pairedSegmentId = null,
                    isDeadEnd = false,
                    geometryEncoded = PolylineUtils.encodeToJson(oldCompletedCoords),
                    lengthMeters = 304.0,
                    minLat = 46.8750,
                    maxLat = 46.8750,
                    minLon = -96.9050,
                    maxLon = -96.9010,
                    isCompleted = true,
                    completedAt = savedCompletedAt
                )
            )
        )

        // New Overpass response:
        // - Main Ave is split at midpoint (-96.9030) by a brand-new cross street and assigned NEW way IDs (9001, 9002)!
        // - Cross St (way 9003) is newly mapped and never ridden.
        val updatedOverpass = OverpassResponse(
            elements = listOf(
                OverpassElement("node", 1L, lat = 46.8750, lon = -96.9050),
                OverpassElement("node", 2L, lat = 46.8750, lon = -96.9030),
                OverpassElement("node", 3L, lat = 46.8750, lon = -96.9010),
                OverpassElement("node", 4L, lat = 46.8740, lon = -96.9030),
                OverpassElement("node", 5L, lat = 46.8760, lon = -96.9030),
                OverpassElement(
                    type = "way",
                    id = 9001L,
                    nodes = listOf(1L, 2L),
                    tags = mapOf("name" to "Main Ave", "highway" to "residential")
                ),
                OverpassElement(
                    type = "way",
                    id = 9002L,
                    nodes = listOf(2L, 3L),
                    tags = mapOf("name" to "Main Ave", "highway" to "residential")
                ),
                OverpassElement(
                    type = "way",
                    id = 9003L,
                    nodes = listOf(4L, 2L, 5L),
                    tags = mapOf("name" to "Cross St", "highway" to "residential")
                )
            )
        )
        val fakeOsmApi = FakeOsmApi { _, _ -> updatedOverpass }
        var txCount = 0

        val repository = MopedRepositoryImpl(
            regionDao = regionDao,
            roadSegmentDao = roadSegmentDao,
            importedGpxFileDao = gpxDao,
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = settingsDataStore,
            osmApi = fakeOsmApi,
            runInTransaction = { block ->
                txCount++
                block()
            }
        )

        val result = repository.refreshRegion(region.regionId)

        assertEquals(1, txCount)
        assertEquals(4, result.totalSegments) // 2 halves of Main Ave + 2 halves of Cross St
        assertEquals(2, result.preservedCompletedCount)
        assertEquals(0, result.gpxMatchedNewSegmentsCount)
        assertEquals(3, result.newSegmentsCount) // 4 - 1 old segment

        val updatedSegments = roadSegmentDao.getRoadSegmentsForRegion(region.regionId)
        val mainAveSegments = updatedSegments.filter { it.name == "Main Ave" }
        val crossStSegments = updatedSegments.filter { it.name == "Cross St" }
        assertEquals(2, mainAveSegments.size)
        assertEquals(2, crossStSegments.size)

        assertTrue(mainAveSegments.all { it.isCompleted && it.completedAt == savedCompletedAt })
        assertTrue(crossStSegments.none { it.isCompleted })
        assertTrue(regionDao.getRegionById(region.regionId)!!.lastUpdatedTimestamp > 1000L)
    }

    @Test
    fun `refreshRegion automatically completes newly mapped roads covered by stored GPX traces`() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val settingsDataStore = FakeSettingsDataStore()

        val regionId = "reg-gpx-1"
        regionDao.insertRegion(
            RegionEntity(
                regionId = regionId,
                name = "GpxTown",
                adminLevel = 8,
                osmRelationId = 3600777777L,
                boundingBox = "46.87,46.88,-96.91,-96.89",
                lastUpdatedTimestamp = 1000L
            )
        )

        // Previously only Oak St was in the DB (not completed)
        roadSegmentDao.insertRoadSegments(
            listOf(
                RoadSegmentEntity(
                    segmentId = "old-oak",
                    regionId = regionId,
                    osmWayId = 10L,
                    name = "Oak St",
                    highwayType = "residential",
                    isOneway = false,
                    pairedSegmentId = null,
                    isDeadEnd = false,
                    geometryEncoded = PolylineUtils.encodeToJson(listOf(Pair(46.8750, -96.9050), Pair(46.8750, -96.9030))),
                    lengthMeters = 152.0,
                    minLat = 46.8750,
                    maxLat = 46.8750,
                    minLon = -96.9050,
                    maxLon = -96.9030,
                    isCompleted = false
                )
            )
        )

        // Rider previously imported a GPX trace riding down New Alley (46.8760, -96.9050 -> -96.9020)
        val gpxPts = listOf(
            Pair(46.8760, -96.9050),
            Pair(46.8760, -96.9040),
            Pair(46.8760, -96.9030),
            Pair(46.8760, -96.9020)
        )
        gpxDao.insertFile(
            ImportedGpxFileEntity(
                fileHash = "gpx-hash-1",
                fileName = "alley_ride.gpx",
                importedAt = 5000L,
                trackPointCount = gpxPts.size,
                segmentsCompletedCount = 0,
                encodedTrace = PolylineUtils.encode(gpxPts),
                isVisibleOnMap = false
            )
        )

        // Overpass now includes New Alley (way 20L)!
        val updatedOverpass = OverpassResponse(
            elements = listOf(
                OverpassElement("node", 1L, lat = 46.8750, lon = -96.9050),
                OverpassElement("node", 2L, lat = 46.8750, lon = -96.9030),
                OverpassElement("node", 3L, lat = 46.8760, lon = -96.9050),
                OverpassElement("node", 4L, lat = 46.8760, lon = -96.9020),
                OverpassElement("way", 10L, nodes = listOf(1L, 2L), tags = mapOf("name" to "Oak St", "highway" to "residential")),
                OverpassElement("way", 20L, nodes = listOf(3L, 4L), tags = mapOf("name" to "New Alley", "highway" to "residential"))
            )
        )
        val fakeOsmApi = FakeOsmApi { _, _ -> updatedOverpass }
        val repository = MopedRepositoryImpl(
            regionDao = regionDao,
            roadSegmentDao = roadSegmentDao,
            importedGpxFileDao = gpxDao,
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = settingsDataStore,
            osmApi = fakeOsmApi
        )

        val result = repository.refreshRegion(regionId)

        assertEquals(2, result.totalSegments)
        assertEquals(0, result.preservedCompletedCount)
        assertEquals(1, result.gpxMatchedNewSegmentsCount)
        assertEquals(1, result.newSegmentsCount)

        val segments = roadSegmentDao.getRoadSegmentsForRegion(regionId)
        assertFalse(segments.first { it.name == "Oak St" }.isCompleted)
        assertTrue(segments.first { it.name == "New Alley" }.isCompleted)
    }

    @Test
    fun `refreshRegion is non-destructive when Overpass fails`() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val regionId = "reg-fail-1"
        regionDao.insertRegion(
            RegionEntity(
                regionId = regionId,
                name = "FailTown",
                adminLevel = 8,
                osmRelationId = 3600999999L,
                boundingBox = "46.87,46.88,-96.91,-96.89",
                lastUpdatedTimestamp = 1000L
            )
        )
        val existingSeg = RoadSegmentEntity(
            segmentId = "keep-me",
            regionId = regionId,
            osmWayId = 55L,
            name = "Safe Blvd",
            highwayType = "residential",
            isOneway = false,
            pairedSegmentId = null,
            isDeadEnd = false,
            geometryEncoded = PolylineUtils.encodeToJson(listOf(Pair(46.875, -96.905), Pair(46.875, -96.903))),
            lengthMeters = 152.0,
            minLat = 46.875,
            maxLat = 46.875,
            minLon = -96.905,
            maxLon = -96.903,
            isCompleted = true,
            completedAt = 99999L
        )
        roadSegmentDao.insertRoadSegments(listOf(existingSeg))

        val fakeOsmApi = FakeOsmApi { _, _ -> throw IOException("503 Service Unavailable") }
        val repository = MopedRepositoryImpl(
            regionDao = regionDao,
            roadSegmentDao = roadSegmentDao,
            importedGpxFileDao = FakeImportedGpxFileDao(),
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = FakeSettingsDataStore(),
            osmApi = fakeOsmApi
        )

        try {
            repository.refreshRegion(regionId)
            fail("Expected OverpassException")
        } catch (_: OverpassException) {
            // Expected
        }

        val retained = roadSegmentDao.getRoadSegmentsForRegion(regionId)
        assertEquals(listOf(existingSeg), retained)
    }

    @Test
    fun `refreshRegion propagates preserved completion across divided road pairs`() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val regionId = "reg-pair-1"
        regionDao.insertRegion(
            RegionEntity(
                regionId = regionId,
                name = "DualWayTown",
                adminLevel = 8,
                osmRelationId = 3600888888L,
                boundingBox = "46.87,46.88,-96.91,-96.89",
                lastUpdatedTimestamp = 1000L
            )
        )

        // Old completed westbound carriageway only (at lat 46.8750)
        val savedCompletedAt = 1700000123000L
        roadSegmentDao.insertRoadSegments(
            listOf(
                RoadSegmentEntity(
                    segmentId = "old-wb",
                    regionId = regionId,
                    osmWayId = 301L,
                    name = "13th Ave",
                    highwayType = "secondary",
                    isOneway = true,
                    pairedSegmentId = null,
                    isDeadEnd = false,
                    geometryEncoded = PolylineUtils.encodeToJson(
                        listOf(Pair(46.8750, -96.9050), Pair(46.8750, -96.9030))
                    ),
                    lengthMeters = 152.0,
                    minLat = 46.8750,
                    maxLat = 46.8750,
                    minLon = -96.9050,
                    maxLon = -96.9030,
                    isCompleted = true,
                    completedAt = savedCompletedAt
                )
            )
        )

        // Overpass now contains BOTH carriageways ~33m apart (lat 46.8750 and lat 46.8753), paired as a divided road
        val updatedOverpass = OverpassResponse(
            elements = listOf(
                OverpassElement("node", 1L, lat = 46.8750, lon = -96.9050),
                OverpassElement("node", 2L, lat = 46.8750, lon = -96.9030),
                OverpassElement("node", 3L, lat = 46.8753, lon = -96.9030),
                OverpassElement("node", 4L, lat = 46.8753, lon = -96.9050),
                OverpassElement(
                    type = "way",
                    id = 401L,
                    nodes = listOf(1L, 2L),
                    tags = mapOf("name" to "13th Ave", "highway" to "secondary", "oneway" to "yes")
                ),
                OverpassElement(
                    type = "way",
                    id = 402L,
                    nodes = listOf(3L, 4L),
                    tags = mapOf("name" to "13th Ave", "highway" to "secondary", "oneway" to "yes")
                )
            )
        )

        val repository = MopedRepositoryImpl(
            regionDao = regionDao,
            roadSegmentDao = roadSegmentDao,
            importedGpxFileDao = FakeImportedGpxFileDao(),
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = FakeSettingsDataStore(),
            osmApi = FakeOsmApi { _, _ -> updatedOverpass }
        )

        val result = repository.refreshRegion(regionId)
        assertEquals(2, result.totalSegments)
        assertEquals(2, result.preservedCompletedCount)

        val segments = roadSegmentDao.getRoadSegmentsForRegion(regionId)
        assertEquals(2, segments.size)
        assertTrue(segments.all { it.isCompleted && it.completedAt == savedCompletedAt })
        assertNotNull(segments[0].pairedSegmentId)
        assertEquals(segments[0].pairedSegmentId, segments[1].pairedSegmentId)
    }

    @Test
    fun `deleteImportedFile removes trace by hash`() = runBlocking {
        val gpxDao = FakeImportedGpxFileDao()
        gpxDao.insertFile(
            ImportedGpxFileEntity(
                fileHash = "hash-1",
                fileName = "ride1.gpx",
                importedAt = 1000L,
                trackPointCount = 10,
                segmentsCompletedCount = 2
            )
        )
        gpxDao.insertFile(
            ImportedGpxFileEntity(
                fileHash = "hash-2",
                fileName = "ride2.gpx",
                importedAt = 2000L,
                trackPointCount = 20,
                segmentsCompletedCount = 4
            )
        )

        val repository = MopedRepositoryImpl(
            regionDao = FakeRegionDao(),
            roadSegmentDao = FakeRoadSegmentDao(),
            importedGpxFileDao = gpxDao,
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = FakeSettingsDataStore()
        )

        repository.deleteImportedFile("hash-1")

        assertNull(gpxDao.getFileByHash("hash-1"))
        assertNotNull(gpxDao.getFileByHash("hash-2"))
        assertEquals(1, repository.getImportedFilesCount())
    }

    @Test
    fun `InternalGpxTraceStore saves reads and deletes trace files by hash`() {
        val dir = tempFolder.newFolder("gpx_traces")
        val store = InternalGpxTraceStore(dir)
        val payload = "<gpx version=\"1.1\"/>".toByteArray(Charsets.UTF_8)
        val hash = HashUtils.sha256Hex(payload)

        assertFalse(store.exists(hash))
        assertNull(store.read(hash))

        store.save(hash, payload)
        assertTrue(store.exists(hash))
        assertEquals(payload.toList(), store.read(hash)?.toList())

        store.delete(hash)
        assertFalse(store.exists(hash))
        assertNull(store.read(hash))
    }

    @Test
    fun `openGpxStorageSession stores imported traces internally and externally when defined and deleteImportedFile cleans internal copy`() = runBlocking {
        val dir = tempFolder.newFolder("repo_internal_gpx")
        val internalStore = InternalGpxTraceStore(dir)
        val externalWrites = mutableListOf<Triple<String, String, String?>>()
        val settingsDataStore = FakeSettingsDataStore()
        val gpxDao = FakeImportedGpxFileDao()

        val fakeExporter = object : GpxExporter {
            override suspend fun exportGpx(fileName: String, content: String, treeUriString: String?): GpxExportResult {
                val bytes = content.toByteArray(Charsets.UTF_8)
                return GpxExportResult(fileName, bytes, usedSafTree = !treeUriString.isNullOrBlank())
            }

            override suspend fun storeTrace(fileName: String, fileHash: String, contentBytes: ByteArray, treeUriString: String?) {
                internalStore.save(fileHash, contentBytes)
                if (!treeUriString.isNullOrBlank()) {
                    externalWrites.add(Triple(fileName, fileHash, treeUriString))
                }
            }

            override suspend fun deleteInternalTrace(fileHash: String) {
                internalStore.delete(fileHash)
            }
        }

        val repository = MopedRepositoryImpl(
            regionDao = FakeRegionDao(),
            roadSegmentDao = FakeRoadSegmentDao(),
            importedGpxFileDao = gpxDao,
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = settingsDataStore,
            gpxExporter = fakeExporter
        )

        val bytes1 = "<gpx>ride1</gpx>".toByteArray(Charsets.UTF_8)
        val hash1 = HashUtils.sha256Hex(bytes1)
        val sessionNoExternal = repository.openGpxStorageSession()
        sessionNoExternal.storeTrace("ride1.gpx", hash1, bytes1)
        assertTrue(internalStore.exists(hash1))
        assertTrue(externalWrites.isEmpty())

        val externalUri = "content://com.android.externalstorage.documents/tree/primary%3ATraces"
        settingsDataStore.setGpxExportTreeUri(externalUri)
        val bytes2 = "<gpx>ride2</gpx>".toByteArray(Charsets.UTF_8)
        val hash2 = HashUtils.sha256Hex(bytes2)
        val sessionWithExternal = repository.openGpxStorageSession()
        sessionWithExternal.storeTrace("ride2.gpx", hash2, bytes2)
        assertTrue(internalStore.exists(hash2))
        assertEquals(listOf(Triple("ride2.gpx", hash2, externalUri)), externalWrites)

        gpxDao.insertFile(
            ImportedGpxFileEntity(
                fileHash = hash1,
                fileName = "ride1.gpx",
                importedAt = 1000L,
                trackPointCount = 2,
                segmentsCompletedCount = 0
            )
        )
        repository.deleteImportedFile(hash1)
        assertNull(gpxDao.getFileByHash(hash1))
        assertFalse(internalStore.exists(hash1))
    }

    @Test
    fun `copyAllTracesToExternalFolder forwards all stored traces to exporter`() = runBlocking {
        val gpxDao = FakeImportedGpxFileDao()
        val trace1 = ImportedGpxFileEntity("h1", "t1.gpx", 1000L, 10, 1)
        val trace2 = ImportedGpxFileEntity("h2", "t2.gpx", 2000L, 20, 2)
        gpxDao.insertFile(trace1)
        gpxDao.insertFile(trace2)

        var capturedUri: String? = null
        var capturedTraces: List<ImportedGpxFileEntity> = emptyList()
        val fakeExporter = object : GpxExporter {
            override suspend fun exportGpx(fileName: String, content: String, treeUriString: String?): GpxExportResult {
                return GpxExportResult(fileName, content.toByteArray(), usedSafTree = true)
            }

            override suspend fun copyTracesToExternalFolder(
                traces: List<ImportedGpxFileEntity>,
                treeUriString: String,
                onProgress: (Int, Int) -> Unit
            ): ExternalTraceCopySummary {
                capturedUri = treeUriString
                capturedTraces = traces
                onProgress(1, traces.size)
                onProgress(2, traces.size)
                return ExternalTraceCopySummary(
                    totalTraces = traces.size,
                    copiedCount = traces.size,
                    skippedExistingCount = 0,
                    failedCount = 0,
                    folderWritable = true
                )
            }
        }

        val repository = MopedRepositoryImpl(
            regionDao = FakeRegionDao(),
            roadSegmentDao = FakeRoadSegmentDao(),
            importedGpxFileDao = gpxDao,
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = FakeSettingsDataStore(),
            gpxExporter = fakeExporter
        )

        val progressUpdates = mutableListOf<Pair<Int, Int>>()
        val targetUri = "content://com.android.externalstorage.documents/tree/primary%3ANewTraces"
        val summary = repository.copyAllTracesToExternalFolder(targetUri) { current, total ->
            progressUpdates.add(current to total)
        }

        assertEquals(targetUri, capturedUri)
        assertEquals(2, capturedTraces.size)
        assertEquals(listOf(1 to 2, 2 to 2), progressUpdates)
        assertEquals(2, summary.copiedCount)
        assertTrue(summary.folderWritable)
    }

    @Test
    fun `getStatsSummaryFlow deduplicates paired segments and aggregates region and gpx stats`() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()

        regionDao.insertRegion(RegionEntity("r1", "North", 8, 1L, "0,1,0,1", 1000L))
        regionDao.insertRegion(RegionEntity("r2", "South", 8, 2L, "1,2,1,2", 1000L))
        roadDao.regionNamesById = mapOf("r1" to "North", "r2" to "South")

        // Paired two-way road (shared pairId) -> counts as 1 physical segment of 400m (completed)
        // Plus 1 unpaired uncompleted segment of 600m in r1
        // Plus 1 unpaired completed segment of 500m in r2
        roadDao.insertRoadSegments(
            listOf(
                RoadSegmentEntity(
                    segmentId = "s1",
                    regionId = "r1",
                    osmWayId = 10L,
                    name = "Main St NB",
                    highwayType = "primary",
                    isOneway = true,
                    pairedSegmentId = "pair-main",
                    isDeadEnd = false,
                    geometryEncoded = "",
                    lengthMeters = 400.0,
                    minLat = 0.0, maxLat = 0.1, minLon = 0.0, maxLon = 0.1,
                    isCompleted = true,
                    completedAt = 100L
                ),
                RoadSegmentEntity(
                    segmentId = "s2",
                    regionId = "r1",
                    osmWayId = 11L,
                    name = "Main St SB",
                    highwayType = "primary",
                    isOneway = true,
                    pairedSegmentId = "pair-main",
                    isDeadEnd = false,
                    geometryEncoded = "",
                    lengthMeters = 400.0,
                    minLat = 0.0, maxLat = 0.1, minLon = 0.0, maxLon = 0.1,
                    isCompleted = true,
                    completedAt = 100L
                ),
                RoadSegmentEntity(
                    segmentId = "s3",
                    regionId = "r1",
                    osmWayId = 12L,
                    name = "Oak Ave",
                    highwayType = "residential",
                    isOneway = false,
                    pairedSegmentId = null,
                    isDeadEnd = false,
                    geometryEncoded = "",
                    lengthMeters = 600.0,
                    minLat = 0.0, maxLat = 0.1, minLon = 0.0, maxLon = 0.1,
                    isCompleted = false,
                    completedAt = null
                ),
                RoadSegmentEntity(
                    segmentId = "s4",
                    regionId = "r2",
                    osmWayId = 13L,
                    name = "Pine Rd",
                    highwayType = "residential",
                    isOneway = false,
                    pairedSegmentId = null,
                    isDeadEnd = true,
                    geometryEncoded = "",
                    lengthMeters = 500.0,
                    minLat = 1.0, maxLat = 1.1, minLon = 1.0, maxLon = 1.1,
                    isCompleted = true,
                    completedAt = 200L
                ),
                // Unnamed completed & uncompleted segments (excluded from overall & region stats, completed counted separately)
                RoadSegmentEntity(
                    segmentId = "s5",
                    regionId = "r2",
                    osmWayId = 14L,
                    name = "Unnamed Road",
                    highwayType = "service",
                    isOneway = false,
                    pairedSegmentId = null,
                    isDeadEnd = true,
                    geometryEncoded = "",
                    lengthMeters = 900.0,
                    minLat = 1.0, maxLat = 1.1, minLon = 1.0, maxLon = 1.1,
                    isCompleted = true,
                    completedAt = 250L
                ),
                RoadSegmentEntity(
                    segmentId = "s6",
                    regionId = "r2",
                    osmWayId = 15L,
                    name = "Unnamed Road",
                    highwayType = "service",
                    isOneway = false,
                    pairedSegmentId = null,
                    isDeadEnd = true,
                    geometryEncoded = "",
                    lengthMeters = 1200.0,
                    minLat = 1.0, maxLat = 1.1, minLon = 1.0, maxLon = 1.1,
                    isCompleted = false,
                    completedAt = null
                )
            )
        )

        gpxDao.insertFile(ImportedGpxFileEntity("h1", "ride1.gpx", 1000L, 120, 2))
        gpxDao.insertFile(ImportedGpxFileEntity("h2", "ride2.gpx", 2000L, 380, 1))

        val repository = MopedRepositoryImpl(
            regionDao = regionDao,
            roadSegmentDao = roadDao,
            importedGpxFileDao = gpxDao,
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = FakeSettingsDataStore()
        )

        val stats = repository.getStatsSummaryFlow().first()

        assertEquals(3, stats.totalSegments)
        assertEquals(2, stats.completedSegments)
        assertEquals(1, stats.remainingSegments)
        assertEquals(1500.0, stats.totalDistanceMeters, 0.01)
        assertEquals(900.0, stats.completedDistanceMeters, 0.01)
        assertEquals(600.0, stats.remainingDistanceMeters, 0.01)
        assertEquals(60.0, stats.completionPercent, 0.01)
        assertEquals(1, stats.unnamedCompletedSegments)

        assertEquals(2, stats.regionBreakdown.size)
        val north = stats.regionBreakdown[0]
        assertEquals("North", north.regionName)
        assertEquals(2, north.totalSegments)
        assertEquals(1, north.completedSegments)
        assertEquals(1000.0, north.totalDistanceMeters, 0.01)
        assertEquals(400.0, north.completedDistanceMeters, 0.01)
        assertEquals(40.0, north.completionPercent, 0.01)

        val south = stats.regionBreakdown[1]
        assertEquals("South", south.regionName)
        assertEquals(1, south.totalSegments)
        assertEquals(1, south.completedSegments)
        assertEquals(500.0, south.totalDistanceMeters, 0.01)
        assertEquals(500.0, south.completedDistanceMeters, 0.01)
        assertEquals(100.0, south.completionPercent, 0.01)

        assertEquals(2, stats.totalGpxFiles)
        assertEquals(500L, stats.totalTrackPoints)
    }

    @Test
    fun `getStatsSummaryFlow handles empty database without division by zero`() = runBlocking {
        val repository = MopedRepositoryImpl(
            regionDao = FakeRegionDao(),
            roadSegmentDao = FakeRoadSegmentDao(),
            importedGpxFileDao = FakeImportedGpxFileDao(),
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = FakeSettingsDataStore()
        )

        val stats = repository.getStatsSummaryFlow().first()

        assertEquals(0, stats.totalSegments)
        assertEquals(0, stats.completedSegments)
        assertEquals(0, stats.remainingSegments)
        assertEquals(0.0, stats.totalDistanceMeters, 0.0)
        assertEquals(0.0, stats.completedDistanceMeters, 0.0)
        assertEquals(0.0, stats.remainingDistanceMeters, 0.0)
        assertEquals(0.0, stats.completionPercent, 0.0)
        assertEquals(0, stats.unnamedCompletedSegments)
        assertTrue(stats.regionBreakdown.isEmpty())
        assertEquals(0, stats.totalGpxFiles)
        assertEquals(0L, stats.totalTrackPoints)
    }

    @Test
    fun `region visibility updates hiddenRegionIdsFlow and cleans up on deleteRegion`() = runBlocking {
        val regionDao = FakeRegionDao()
        regionDao.insertRegion(
            RegionEntity("reg-1", "North", 8, 1L, "0,1,0,1", 1000L)
        )
        regionDao.insertRegion(
            RegionEntity("reg-2", "South", 8, 2L, "0,1,0,1", 1000L)
        )
        val repository = MopedRepositoryImpl(
            regionDao = regionDao,
            roadSegmentDao = FakeRoadSegmentDao(),
            importedGpxFileDao = FakeImportedGpxFileDao(),
            debugTraceDao = FakeDebugTraceDao(),
            osmPinDao = FakeOsmPinDao(),
            settingsDataStore = FakeSettingsDataStore()
        )

        assertEquals(emptySet<String>(), repository.hiddenRegionIdsFlow.first())

        repository.setRegionVisibility("reg-1", isVisible = false)
        assertEquals(setOf("reg-1"), repository.hiddenRegionIdsFlow.first())

        repository.setHiddenRegionIds(setOf("reg-1", "reg-2"))
        assertEquals(setOf("reg-1", "reg-2"), repository.hiddenRegionIdsFlow.first())

        // Deleting reg-1 removes its hidden flag while preserving reg-2
        repository.deleteRegion("reg-1")
        assertEquals(setOf("reg-2"), repository.hiddenRegionIdsFlow.first())

        // Re-enabling reg-2 makes all regions visible
        repository.setRegionVisibility("reg-2", isVisible = true)
        assertEquals(emptySet<String>(), repository.hiddenRegionIdsFlow.first())
    }
}
