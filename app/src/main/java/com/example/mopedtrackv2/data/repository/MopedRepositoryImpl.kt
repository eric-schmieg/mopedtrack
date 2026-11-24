package com.example.mopedtrackv2.data.repository

import android.util.Log
import com.example.mopedtrackv2.data.local.dao.DebugTraceDao
import com.example.mopedtrackv2.data.local.dao.ImportedGpxFileDao
import com.example.mopedtrackv2.data.local.dao.RegionDao
import com.example.mopedtrackv2.data.local.dao.RoadSegmentDao
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.RegionEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.data.preferences.SettingsDataStore
import com.example.mopedtrackv2.data.remote.OsmApi
import com.example.mopedtrackv2.data.remote.OVERPASS_ENDPOINTS
import com.example.mopedtrackv2.data.remote.OverpassElement
import com.example.mopedtrackv2.data.remote.OverpassException
import com.example.mopedtrackv2.data.remote.OverpassResponse
import com.example.mopedtrackv2.data.remote.RetrofitClient
import com.example.mopedtrackv2.domain.engine.GpsPoint
import com.example.mopedtrackv2.domain.engine.SegmentMatcherEngine
import com.example.mopedtrackv2.domain.engine.TraceSpatialIndex
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.model.RoadSegment
import com.example.mopedtrackv2.domain.repository.MopedRepository
import com.example.mopedtrackv2.utils.MathUtils
import com.example.mopedtrackv2.utils.PolylineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.util.UUID

private const val HIGHWAY_FILTER_REGEX = "^(residential|unclassified|tertiary|tertiary_link|secondary|secondary_link|primary|primary_link|trunk|trunk_link|living_street|service)$"

class MopedRepositoryImpl(
    private val regionDao: RegionDao,
    private val roadSegmentDao: RoadSegmentDao,
    private val importedGpxFileDao: ImportedGpxFileDao,
    private val debugTraceDao: DebugTraceDao,
    private val settingsDataStore: SettingsDataStore,
    private val osmApi: OsmApi = RetrofitClient.osmApi
) : MopedRepository {

    override fun getAllRegions(): Flow<List<Region>> {
        return regionDao.getAllRegions().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getRegionById(regionId: String): Region? {
        return regionDao.getRegionById(regionId)?.toDomain()
    }

    override fun getRoadSegmentsByRegion(regionId: String): Flow<List<RoadSegment>> {
        return roadSegmentDao.getRoadSegmentsByRegion(regionId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getRoadSegmentsInBoundingBox(
        minLat: Double,
        maxLat: Double,
        minLon: Double,
        maxLon: Double
    ): Flow<List<RoadSegment>> {
        return roadSegmentDao.getRoadSegmentsInBoundingBox(minLat, maxLat, minLon, maxLon).map { entities ->
            Log.d("MopedTrack", "getRoadSegmentsInBoundingBox(minLat=$minLat, maxLat=$maxLat, minLon=$minLon, maxLon=$maxLon) returned ${entities.size} segments")
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getRoadSegmentById(segmentId: String): RoadSegment? {
        return roadSegmentDao.getRoadSegmentById(segmentId)?.toDomain()
    }

    // --- Settings ---
    override val matchRadiusFlow: Flow<Int> = settingsDataStore.matchRadiusFlow
    override val coverageThresholdFlow: Flow<Int> = settingsDataStore.coverageThresholdFlow
    override val debugOverlayEnabledFlow: Flow<Boolean> = settingsDataStore.debugOverlayEnabledFlow
    override val showCompletedSegmentsFlow: Flow<Boolean> = settingsDataStore.showCompletedSegmentsFlow
    override val showUncompletedSegmentsFlow: Flow<Boolean> = settingsDataStore.showUncompletedSegmentsFlow
    override val showGpxTracesFlow: Flow<Boolean> = settingsDataStore.showGpxTracesFlow

    override suspend fun setMatchRadius(radius: Int) = settingsDataStore.setMatchRadius(radius)
    override suspend fun setCoverageThreshold(threshold: Int) = settingsDataStore.setCoverageThreshold(threshold)
    override suspend fun setDebugOverlayEnabled(enabled: Boolean) = settingsDataStore.setDebugOverlayEnabled(enabled)
    override suspend fun setShowCompletedSegments(show: Boolean) = settingsDataStore.setShowCompletedSegments(show)
    override suspend fun setShowUncompletedSegments(show: Boolean) = settingsDataStore.setShowUncompletedSegments(show)
    override suspend fun setShowGpxTraces(show: Boolean) = settingsDataStore.setShowGpxTraces(show)

    // --- GPX & Debug Trace ---
    override fun getImportedFilesFlow(): Flow<List<ImportedGpxFileEntity>> = importedGpxFileDao.getAllFilesFlow()
    override suspend fun getImportedFileByHash(hash: String): ImportedGpxFileEntity? = importedGpxFileDao.getFileByHash(hash)
    override suspend fun insertImportedFile(file: ImportedGpxFileEntity) = importedGpxFileDao.insertFile(file)
    override suspend fun getImportedFilesCount(): Int = importedGpxFileDao.getImportedFilesCount()
    override suspend fun deleteLegacyTraces() = importedGpxFileDao.deleteLegacyTraces()
    override suspend fun toggleGpxVisibility(hash: String, isVisible: Boolean) = importedGpxFileDao.updateVisibility(hash, isVisible)
    override fun getVisibleGpxFilesFlow(): Flow<List<ImportedGpxFileEntity>> = importedGpxFileDao.getVisibleFilesFlow()

    override fun getLastDebugTraceFlow(): Flow<DebugTraceEntity?> = debugTraceDao.getLastTraceFlow()
    override suspend fun insertDebugTrace(trace: DebugTraceEntity) = debugTraceDao.insertTrace(trace)
    override suspend fun clearDebugTraces() = debugTraceDao.clearAllTraces()

    // --- Bulk Updates ---
    override suspend fun resetAllProgress() = withContext(Dispatchers.IO) {
        roadSegmentDao.resetAllCompletionStatus()
        importedGpxFileDao.resetAllSegmentsCompletedCount()
    }

    override suspend fun getCandidateSegmentsForBoundingBox(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): List<RoadSegmentEntity> = withContext(Dispatchers.IO) {
        roadSegmentDao.getCandidateSegments(minLat, maxLat, minLon, maxLon)
    }
    
    override suspend fun updateRoadSegments(segments: List<RoadSegmentEntity>) = withContext(Dispatchers.IO) {
        roadSegmentDao.updateSegments(segments)
    }


    override suspend fun toggleSegmentCompletion(
        segmentId: String,
        isCompleted: Boolean,
        completedAt: Long?
    ) = withContext(Dispatchers.IO) {
        roadSegmentDao.toggleIsCompleted(segmentId, isCompleted, completedAt)
    }

    override suspend fun searchRegions(query: String): List<Region> = withContext(Dispatchers.IO) {
        val results = osmApi.searchNominatim(query = query)
        results.mapNotNull { res ->
            val areaId = when (res.osm_type) {
                "relation" -> 3600000000L + res.osm_id
                "way" -> 2400000000L + res.osm_id
                else -> return@mapNotNull null
            }
            Region(
                regionId = res.place_id.toString(),
                name = res.display_name,
                adminLevel = 0,
                osmRelationId = areaId,
                boundingBox = res.boundingbox.joinToString(","),
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
        }
    }

    private fun buildAreaQuery(osmRelationId: Long): String {
        return """
            [out:json][timeout:180][maxsize:1073741824];
            area($osmRelationId)->.a;
            (
              way(area.a)["highway"~"$HIGHWAY_FILTER_REGEX"];
              way(area.a)["bridge"];
              way(area.a)["man_made"="bridge"];
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    private fun buildBboxQuery(boundingBox: String?): String? {
        if (boundingBox.isNullOrBlank()) return null
        val parts = boundingBox.split(",")
        if (parts.size != 4) return null
        val south = parts[0].trim().toDoubleOrNull() ?: return null
        val north = parts[1].trim().toDoubleOrNull() ?: return null
        val west = parts[2].trim().toDoubleOrNull() ?: return null
        val east = parts[3].trim().toDoubleOrNull() ?: return null
        // Overpass QL bounding box syntax: (south, west, north, east)
        return """
            [out:json][timeout:180][maxsize:1073741824];
            (
              way($south,$west,$north,$east)["highway"~"$HIGHWAY_FILTER_REGEX"];
              way($south,$west,$north,$east)["bridge"];
              way($south,$west,$north,$east)["man_made"="bridge"];
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    @Volatile
    private var preferredEndpointIndex = 0

    private suspend fun executeOverpassWithEndpointFallbacks(query: String): OverpassResponse {
        var lastException: Exception? = null
        var lastEmptyResponse: OverpassResponse? = null
        val endpoints = OVERPASS_ENDPOINTS
        val totalEndpoints = endpoints.size
        val startIndex = preferredEndpointIndex.coerceIn(0, totalEndpoints - 1)

        for (i in 0 until totalEndpoints) {
            val currentIndex = (startIndex + i) % totalEndpoints
            val endpoint = endpoints[currentIndex]
            try {
                Log.d("MopedTrack", "Querying Overpass endpoint ($currentIndex): $endpoint")
                val response = osmApi.queryOverpass(url = endpoint, query = query)
                
                if (response.elements.isNotEmpty()) {
                    Log.d("MopedTrack", "Successfully fetched ${response.elements.size} elements from $endpoint")
                    preferredEndpointIndex = currentIndex
                    return response
                } else {
                    Log.w("MopedTrack", "Endpoint $endpoint returned 0 elements, checking next endpoint...")
                    lastEmptyResponse = response
                }
            } catch (e: Exception) {
                Log.w("MopedTrack", "Overpass endpoint $endpoint failed: ${e.message}")
                lastException = e
                kotlinx.coroutines.delay(500L)
            }
        }

        // If all endpoints returned 0 elements, return the empty response instead of failing
        if (lastEmptyResponse != null) {
            return lastEmptyResponse
        }

        throw lastException ?: OverpassException("All Overpass endpoints failed")
    }

    private suspend fun fetchOverpassResponse(region: Region): OverpassResponse {
        val areaQuery = if (region.osmRelationId > 0) buildAreaQuery(region.osmRelationId) else null
        val bboxQuery = buildBboxQuery(region.boundingBox)

        var areaQueryError: Exception? = null

        // 1. Try relation/area query first across all endpoints
        if (areaQuery != null) {
            try {
                return executeOverpassWithEndpointFallbacks(areaQuery)
            } catch (e: Exception) {
                areaQueryError = e
                Log.w("MopedTrack", "Area query failed across all endpoints (${e.message}). Attempting bbox query fallback...")
            }
        }

        // 2. Try bounding box query across all endpoints if area query failed or was unavailable
        if (bboxQuery != null) {
            try {
                return executeOverpassWithEndpointFallbacks(bboxQuery)
            } catch (e: Exception) {
                Log.e("MopedTrack", "Bbox query failed across all endpoints (${e.message})")
            }
        }

        throw OverpassException(
            "Overpass servers are currently busy (HTTP 502). Please try again in a moment.",
            areaQueryError
        )
    }

    override suspend fun importRegion(region: Region, onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        onProgress(0.05f, "Connecting to Overpass API for ${region.name}...")
        // Fetch data from Overpass API before modifying database
        val response = fetchOverpassResponse(region)

        onProgress(0.35f, "Processing ${response.elements.size} map elements...")
        // Clear any existing road segments for this region first to ensure clean overwrite
        roadSegmentDao.deleteRoadSegmentsByRegion(region.regionId)

        // Save the region to DB
        regionDao.insertRegion(
            RegionEntity(
                regionId = region.regionId,
                name = region.name,
                adminLevel = region.adminLevel,
                osmRelationId = region.osmRelationId,
                boundingBox = region.boundingBox,
                lastUpdatedTimestamp = System.currentTimeMillis()
            )
        )

        // Parse the response
        val nodes = mutableMapOf<Long, Pair<Double, Double>>()
        val ways = mutableListOf<OverpassElement>()
        
        response.elements.forEach { el ->
            if (el.type == "node" && el.lat != null && el.lon != null) {
                nodes[el.id] = Pair(el.lat, el.lon)
            } else if (el.type == "way") {
                ways.add(el)
            }
        }

        onProgress(0.50f, "Filtering ${ways.size} road ways...")
        // Filtering Rules
        val includeHighways = setOf(
            "residential", "unclassified", "tertiary", "tertiary_link", 
            "secondary", "secondary_link", "primary", "primary_link", 
            "trunk", "trunk_link", "living_street", "service", "track", "road"
        )
        val excludeHighways = setOf("motorway", "motorway_link", "footway", "pedestrian", "steps", "cycleway", "path")
        
        val filteredWays = ways.filter { way ->
            val tags = way.tags ?: return@filter false
            val highway = tags["highway"]
            val serviceType = tags["service"]
            val isBridge = tags["bridge"] == "yes" || tags.containsKey("bridge") || tags["man_made"] == "bridge"

            if (serviceType == "driveway" || serviceType == "parking_aisle" || serviceType == "drive-through") {
                return@filter false
            }

            if (!isBridge && highway !in includeHighways) return@filter false
            if (highway in excludeHighways) return@filter false
            if (tags["motorroad"] == "yes") return@filter false
            if (tags["access"] == "private" || tags["access"] == "no") return@filter false
            
            true
        }

        onProgress(0.65f, "Building road segment graph (${filteredWays.size} ways)...")
        // Intersection-Based Graph Slicing
        val nodeReferenceCounts = mutableMapOf<Long, Int>()
        filteredWays.forEach { way ->
            way.nodes?.forEach { nodeId ->
                nodeReferenceCounts[nodeId] = nodeReferenceCounts.getOrDefault(nodeId, 0) + 1
            }
        }

        val allSegments = mutableListOf<RoadSegmentEntity>()
        var processedWays = 0
        val totalFilteredWays = filteredWays.size
        
        filteredWays.forEach { way ->
            processedWays++
            if (totalFilteredWays > 0 && (processedWays % 100 == 0 || processedWays == totalFilteredWays)) {
                val wayProgress = 0.65f + (processedWays.toFloat() / totalFilteredWays.toFloat()) * 0.25f
                onProgress(wayProgress, "Slicing segments ($processedWays of $totalFilteredWays ways)...")
            }

            val wayNodes = way.nodes ?: return@forEach
            val tags = way.tags ?: return@forEach
            val wayName = tags["name"] 
                ?: if (tags["bridge"] == "yes" || tags.containsKey("bridge")) "Bridge" 
                else "Unnamed Road"
            val highwayType = tags["highway"] ?: "unknown"
            val isOneway = tags["oneway"] == "yes" || tags["oneway"] == "1" || tags["oneway"] == "-1"
            
            var currentSegmentNodes = mutableListOf<Long>()
            
            for (i in wayNodes.indices) {
                val nodeId = wayNodes[i]
                currentSegmentNodes.add(nodeId)
                
                // Split if it's an intersection (referenced > 1 time) OR it's the last node in the way
                val isIntersection = (nodeReferenceCounts[nodeId] ?: 0) > 1
                val isLastNode = i == wayNodes.size - 1
                
                if ((isIntersection || isLastNode) && currentSegmentNodes.size > 1) {
                    // We have a segment
                    val firstNode = currentSegmentNodes.first()
                    val lastNode = currentSegmentNodes.last()
                    
                    val isDeadEnd = (nodeReferenceCounts[firstNode] ?: 0) == 1 || (nodeReferenceCounts[lastNode] ?: 0) == 1
                    
                    val segmentCoords = currentSegmentNodes.mapNotNull { nodes[it] }
                    
                    if (segmentCoords.size > 1) {
                        var length = 0.0
                        var minLat = 90.0
                        var maxLat = -90.0
                        var minLon = 180.0
                        var maxLon = -180.0
                        
                        for (j in 0 until segmentCoords.size - 1) {
                            val p1 = segmentCoords[j]
                            val p2 = segmentCoords[j+1]
                            length += MathUtils.haversineDistance(p1.first, p1.second, p2.first, p2.second)
                        }
                        
                        segmentCoords.forEach {
                            if (it.first < minLat) minLat = it.first
                            if (it.first > maxLat) maxLat = it.first
                            if (it.second < minLon) minLon = it.second
                            if (it.second > maxLon) maxLon = it.second
                        }

                        val encodedGeometry = PolylineUtils.encodeToJson(segmentCoords)
                        
                        allSegments.add(
                            RoadSegmentEntity(
                                segmentId = UUID.randomUUID().toString(),
                                regionId = region.regionId,
                                osmWayId = way.id,
                                name = wayName,
                                highwayType = highwayType,
                                isOneway = isOneway,
                                pairedSegmentId = null,
                                isDeadEnd = isDeadEnd,
                                geometryEncoded = encodedGeometry,
                                lengthMeters = length,
                                minLat = minLat,
                                maxLat = maxLat,
                                minLon = minLon,
                                maxLon = maxLon
                            )
                        )
                    }
                    
                    // Start new segment from the intersection node
                    val lastNodeId = currentSegmentNodes.last()
                    currentSegmentNodes = mutableListOf(lastNodeId)
                }
            }
        }

        // Divided Road Pairing
        // Find pairs with same name (case-insensitive), both oneway, parallel, and close (<30m)
        val segmentsByName = allSegments.groupBy { it.name?.trim()?.lowercase() }
        
        for ((name, segments) in segmentsByName) {
            if (name == null || segments.size < 2) continue
            
            val onewaySegments = segments.filter { it.isOneway && it.pairedSegmentId == null }.toMutableList()
            
            for (i in onewaySegments.indices) {
                val seg1 = onewaySegments[i]
                if (seg1.pairedSegmentId != null) continue
                
                for (j in i + 1 until onewaySegments.size) {
                    val seg2 = onewaySegments[j]
                    if (seg2.pairedSegmentId != null) continue
                    
                    // Simple distance check (bounding boxes overlap or close)
                    val dist = MathUtils.haversineDistance(
                        (seg1.minLat + seg1.maxLat) / 2, (seg1.minLon + seg1.maxLon) / 2,
                        (seg2.minLat + seg2.maxLat) / 2, (seg2.minLon + seg2.maxLon) / 2
                    )
                    
                    if (dist < 100.0) { // fairly close
                        // Assign pair
                        val pairId = UUID.randomUUID().toString()
                        val idx1 = allSegments.indexOfFirst { it.segmentId == seg1.segmentId }
                        val idx2 = allSegments.indexOfFirst { it.segmentId == seg2.segmentId }
                        if (idx1 != -1 && idx2 != -1) {
                            allSegments[idx1] = allSegments[idx1].copy(pairedSegmentId = pairId)
                            allSegments[idx2] = allSegments[idx2].copy(pairedSegmentId = pairId)
                            onewaySegments[i] = onewaySegments[i].copy(pairedSegmentId = pairId)
                            onewaySegments[j] = onewaySegments[j].copy(pairedSegmentId = pairId)
                        }
                        break
                    }
                }
            }
        }
        
        onProgress(0.92f, "Saving ${allSegments.size} road segments to database...")
        val chunks = allSegments.chunked(500)
        val totalChunks = chunks.size
        chunks.forEachIndexed { index, chunk ->
            roadSegmentDao.insertRoadSegments(chunk)
            if (totalChunks > 0) {
                val dbProgress = 0.92f + ((index + 1).toFloat() / totalChunks.toFloat()) * 0.06f
                onProgress(dbProgress.coerceAtMost(0.98f), "Saving ${allSegments.size} segments to database...")
            }
        }
        Log.d("MopedTrack", "Imported region '${region.name}': inserted ${allSegments.size} road segments")

        onProgress(0.98f, "Re-scanning GPX traces against new road network...")
        rescanGpxTraces()

        onProgress(1.0f, "Complete!")
    }

    override suspend fun deleteRegion(regionId: String) = withContext(Dispatchers.IO) {
        roadSegmentDao.deleteRoadSegmentsByRegion(regionId)
        regionDao.deleteRegionById(regionId)
    }

    override suspend fun rescanGpxTraces() = withContext(Dispatchers.IO) {
        val matchRadius = matchRadiusFlow.first().toDouble()
        val coverageThreshold = coverageThresholdFlow.first().toDouble()

        val allTraces = importedGpxFileDao.getAllFilesFlow().first()
        Log.d("MopedTrack", "rescanGpxTraces: Starting rescan of ${allTraces.size} GPX files...")
        
        var totalMatchedSegments = 0

        for (file in allTraces) {
            if (file.encodedTrace.isBlank()) continue

            val tracePoints = PolylineUtils.decode(file.encodedTrace).map { GpsPoint(it.first, it.second) }
            if (tracePoints.isEmpty()) continue

            val spatialIndex = TraceSpatialIndex(tracePoints)

            // Find bounds
            val minLat = tracePoints.minOf { it.lat }
            val maxLat = tracePoints.maxOf { it.lat }
            val minLon = tracePoints.minOf { it.lon }
            val maxLon = tracePoints.maxOf { it.lon }

            val candidates = roadSegmentDao.getCandidateSegments(minLat, maxLat, minLon, maxLon)
            var fileNewSegments = 0
            val segmentsToUpdate = mutableListOf<RoadSegmentEntity>()

            for (segment in candidates) {
                if (!segment.isCompleted) {
                    val isMatched = SegmentMatcherEngine.evaluateSegment(segment, spatialIndex, matchRadius, coverageThreshold)
                    if (isMatched) {
                        segmentsToUpdate.add(segment.copy(isCompleted = true, completedAt = System.currentTimeMillis()))
                        fileNewSegments++

                        // Check for paired segment
                        segment.pairedSegmentId?.let { pairedId ->
                            val paired = candidates.find { it.segmentId == pairedId }
                            if (paired != null && !paired.isCompleted && !segmentsToUpdate.any { it.segmentId == pairedId }) {
                                segmentsToUpdate.add(paired.copy(isCompleted = true, completedAt = System.currentTimeMillis()))
                                fileNewSegments++
                            }
                        }
                    }
                }
            }

            if (segmentsToUpdate.isNotEmpty()) {
                roadSegmentDao.updateSegments(segmentsToUpdate)
                totalMatchedSegments += segmentsToUpdate.size
            }

            // Update file record to show total segments completed
            importedGpxFileDao.insertFile(
                file.copy(
                    segmentsCompletedCount = fileNewSegments
                )
            )
            Log.d("MopedTrack", "rescanGpxTraces: Processed '${file.fileName}', matched $fileNewSegments segments (candidate pool size: ${candidates.size})")
        }
        Log.d("MopedTrack", "rescanGpxTraces: Completed rescan. Total newly completed segments = $totalMatchedSegments")
        Unit
    }

    override suspend fun clearAllData() = withContext(Dispatchers.IO) {
        roadSegmentDao.clearRoadSegments()
        regionDao.clearRegions()
    }
}

private fun RegionEntity.toDomain() = Region(
    regionId = regionId,
    name = name,
    adminLevel = adminLevel,
    osmRelationId = osmRelationId,
    boundingBox = boundingBox,
    lastUpdatedTimestamp = lastUpdatedTimestamp
)

private fun RoadSegmentEntity.toDomain() = RoadSegment(
    segmentId = segmentId,
    regionId = regionId,
    osmWayId = osmWayId,
    name = name,
    highwayType = highwayType,
    isOneway = isOneway,
    pairedSegmentId = pairedSegmentId,
    isDeadEnd = isDeadEnd,
    geometryEncoded = geometryEncoded,
    lengthMeters = lengthMeters,
    minLat = minLat,
    maxLat = maxLat,
    minLon = minLon,
    maxLon = maxLon,
    isCompleted = isCompleted,
    completedAt = completedAt
)
