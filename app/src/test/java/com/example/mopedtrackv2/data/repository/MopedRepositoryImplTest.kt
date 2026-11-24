package com.example.mopedtrackv2.data.repository

import com.example.mopedtrackv2.data.local.dao.DebugTraceDao
import com.example.mopedtrackv2.data.local.dao.ImportedGpxFileDao
import com.example.mopedtrackv2.data.local.dao.RegionDao
import com.example.mopedtrackv2.data.local.dao.RoadSegmentDao
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.RegionEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.data.preferences.SettingsDataStore
import com.example.mopedtrackv2.data.remote.NominatimResult
import com.example.mopedtrackv2.data.remote.OsmApi
import com.example.mopedtrackv2.data.remote.OVERPASS_ENDPOINTS
import com.example.mopedtrackv2.data.remote.OverpassElement
import com.example.mopedtrackv2.data.remote.OverpassException
import com.example.mopedtrackv2.data.remote.OverpassResponse
import com.example.mopedtrackv2.domain.model.Region
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class MopedRepositoryImplTest {

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
        override suspend fun clearRoadSegments() {
            segments.clear()
        }
        override suspend fun resetAllCompletionStatus() {
            for (i in segments.indices) {
                segments[i] = segments[i].copy(isCompleted = false, completedAt = null)
            }
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
        override fun getVisibleFilesFlow(): Flow<List<ImportedGpxFileEntity>> = flowOf(files.filter { it.isVisibleOnMap })
        override suspend fun deleteLegacyTraces() {
            files.removeAll { it.encodedTrace.isEmpty() }
        }
        override suspend fun resetAllSegmentsCompletedCount() {
            for (i in files.indices) {
                files[i] = files[i].copy(segmentsCompletedCount = 0)
            }
        }
    }

    private class FakeDebugTraceDao : DebugTraceDao {
        var trace: DebugTraceEntity? = null
        override fun getLastTraceFlow(): Flow<DebugTraceEntity?> = flowOf(trace)
        override suspend fun insertTrace(trace: DebugTraceEntity) { this.trace = trace }
        override suspend fun clearAllTraces() { trace = null }
    }

    private class FakeSettingsDataStore : SettingsDataStore {
        override val matchRadiusFlow: Flow<Int> = flowOf(15)
        override val coverageThresholdFlow: Flow<Int> = flowOf(80)
        override val debugOverlayEnabledFlow: Flow<Boolean> = flowOf(false)
        override val showCompletedSegmentsFlow: Flow<Boolean> = flowOf(true)
        override val showUncompletedSegmentsFlow: Flow<Boolean> = flowOf(true)
        override val showGpxTracesFlow: Flow<Boolean> = flowOf(true)

        override suspend fun setMatchRadius(radius: Int) {}
        override suspend fun setCoverageThreshold(threshold: Int) {}
        override suspend fun setDebugOverlayEnabled(enabled: Boolean) {}
        override suspend fun setShowCompletedSegments(show: Boolean) {}
        override suspend fun setShowUncompletedSegments(show: Boolean) {}
        override suspend fun setShowGpxTraces(show: Boolean) {}
    }

    private class FakeOsmApi(
        private val handler: (url: String, query: String) -> OverpassResponse
    ) : OsmApi {
        val queriedUrls = mutableListOf<String>()
        val queries = mutableListOf<String>()

        override suspend fun searchNominatim(query: String, format: String, polygonGeojson: Int): List<NominatimResult> {
            return emptyList()
        }

        override suspend fun queryOverpass(url: String, query: String): OverpassResponse {
            queriedUrls.add(url)
            queries.add(query)
            return handler(url, query)
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

        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, settingsDataStore, fakeOsmApi)
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

        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, settingsDataStore, fakeOsmApi)
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

        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, settingsDataStore, fakeOsmApi)
        try {
            repository.importRegion(sampleRegion)
            fail("Expected OverpassException")
        } catch (e: OverpassException) {
            assertEquals("Overpass servers are currently busy (HTTP 502). Please try again in a moment.", e.message)
        }
    }

    @Test
    fun testToggleSegmentCompletionSingleSegment() = runBlocking {
        val regionDao = FakeRegionDao()
        val roadSegmentDao = FakeRoadSegmentDao()
        val gpxDao = FakeImportedGpxFileDao()
        val debugDao = FakeDebugTraceDao()
        val settingsDataStore = FakeSettingsDataStore()
        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, settingsDataStore)

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
        val repository = MopedRepositoryImpl(regionDao, roadSegmentDao, gpxDao, debugDao, settingsDataStore)

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
}
