package com.example.mopedtrackv2.data.repository

import android.util.Log
import com.example.mopedtrackv2.data.local.dao.DebugTraceDao
import com.example.mopedtrackv2.data.local.dao.ImportedGpxFileDao
import com.example.mopedtrackv2.data.local.dao.OsmPinDao
import com.example.mopedtrackv2.data.local.dao.RegionDao
import com.example.mopedtrackv2.data.local.dao.RoadSegmentDao
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.data.local.entity.RegionEntity
import com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity
import com.example.mopedtrackv2.data.preferences.SavedCameraPosition
import com.example.mopedtrackv2.data.preferences.SettingsDataStore
import com.example.mopedtrackv2.data.gpx.ExternalTraceCopySummary
import com.example.mopedtrackv2.data.gpx.GpxBatchStorageSession
import com.example.mopedtrackv2.data.gpx.GpxExporter
import com.example.mopedtrackv2.data.gpx.NoOpGpxBatchStorageSession
import com.example.mopedtrackv2.data.remote.OsmApi
import com.example.mopedtrackv2.data.remote.OVERPASS_ENDPOINTS
import com.example.mopedtrackv2.data.remote.OverpassElement
import com.example.mopedtrackv2.data.remote.OverpassException
import com.example.mopedtrackv2.data.remote.OverpassResponse
import com.example.mopedtrackv2.data.remote.RetrofitClient
import com.example.mopedtrackv2.data.remote.overpassStatusUrl
import com.example.mopedtrackv2.data.service.RideTrackingController
import com.example.mopedtrackv2.domain.engine.GpsPoint
import com.example.mopedtrackv2.domain.engine.LiveSegmentMatcher
import com.example.mopedtrackv2.domain.engine.SegmentMatcherEngine
import com.example.mopedtrackv2.domain.engine.TraceSegment
import com.example.mopedtrackv2.domain.engine.TraceSpatialIndex
import com.example.mopedtrackv2.domain.gpx.GpxWriter
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.model.RegionRefreshResult
import com.example.mopedtrackv2.domain.model.RegionStats
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.RoadSegment
import com.example.mopedtrackv2.domain.model.StatsSummary
import com.example.mopedtrackv2.domain.model.TrackPoint
import com.example.mopedtrackv2.domain.model.UnitSystem
import com.example.mopedtrackv2.domain.repository.MopedRepository
import com.example.mopedtrackv2.utils.HashUtils
import com.example.mopedtrackv2.utils.MathUtils
import com.example.mopedtrackv2.utils.PolylineUtils
import kotlin.math.cos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import retrofit2.HttpException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.URI
import java.net.UnknownHostException
import java.util.UUID

private const val HIGHWAY_FILTER_REGEX = "^(residential|unclassified|tertiary|tertiary_link|secondary|secondary_link|primary|primary_link|trunk|trunk_link|living_street|service)$"

/**
 * Server-side pre-filtering: drops driveways/parking aisles and private roads before download
 * (they are also rejected client-side in [MopedRepositoryImpl.importRegion]). This substantially
 * reduces payload size and query time for city-sized regions.
 */
private const val OVERPASS_WAY_FILTERS =
    "[\"highway\"~\"$HIGHWAY_FILTER_REGEX\"]" +
        "[\"service\"!~\"^(driveway|parking_aisle|drive-through)$\"]" +
        "[\"access\"!~\"^(private|no)$\"]"

/** Server-side Overpass query timeout. Keep below RetrofitClient's Overpass read timeout. */
private const val OVERPASS_SERVER_TIMEOUT_SECONDS = 90

/** Max time to wait for an Overpass mirror's `/api/status` health probe. */
private const val OVERPASS_STATUS_PROBE_TIMEOUT_MS = 12_000L

class MopedRepositoryImpl(
    private val regionDao: RegionDao,
    private val roadSegmentDao: RoadSegmentDao,
    private val importedGpxFileDao: ImportedGpxFileDao,
    private val debugTraceDao: DebugTraceDao,
    private val osmPinDao: OsmPinDao,
    private val settingsDataStore: SettingsDataStore,
    private val osmApi: OsmApi = RetrofitClient.osmApi,
    private val rideTrackingController: RideTrackingController = RideTrackingController(),
    private val gpxExporter: GpxExporter? = null,
    private val runInTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() }
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
    override val hiddenRegionIdsFlow: Flow<Set<String>> = settingsDataStore.hiddenRegionIdsFlow
    override val savedCameraPositionFlow: Flow<SavedCameraPosition?> = settingsDataStore.savedCameraPositionFlow
    override val pollingIntervalMsFlow: Flow<Long> = settingsDataStore.pollingIntervalMsFlow
    override val minDisplacementMetersFlow: Flow<Float> = settingsDataStore.minDisplacementMetersFlow
    override val gpxExportTreeUriFlow: Flow<String?> = settingsDataStore.gpxExportTreeUriFlow
    override val unitSystemFlow: Flow<UnitSystem> = settingsDataStore.unitSystemFlow
    override val weeklyAutoRefreshFlow: Flow<Boolean> = settingsDataStore.weeklyAutoRefreshFlow

    override suspend fun setMatchRadius(radius: Int) = settingsDataStore.setMatchRadius(radius)
    override suspend fun setCoverageThreshold(threshold: Int) = settingsDataStore.setCoverageThreshold(threshold)
    override suspend fun setDebugOverlayEnabled(enabled: Boolean) = settingsDataStore.setDebugOverlayEnabled(enabled)
    override suspend fun setShowCompletedSegments(show: Boolean) = settingsDataStore.setShowCompletedSegments(show)
    override suspend fun setShowUncompletedSegments(show: Boolean) = settingsDataStore.setShowUncompletedSegments(show)
    override suspend fun setShowGpxTraces(show: Boolean) = settingsDataStore.setShowGpxTraces(show)
    override suspend fun setRegionVisibility(regionId: String, isVisible: Boolean) =
        settingsDataStore.setRegionVisibility(regionId, isVisible)
    override suspend fun setHiddenRegionIds(regionIds: Set<String>) =
        settingsDataStore.setHiddenRegionIds(regionIds)
    override suspend fun setSavedCameraPosition(lat: Double, lon: Double, zoom: Double, bearing: Double, tilt: Double) =
        settingsDataStore.setSavedCameraPosition(lat, lon, zoom, bearing, tilt)
    override suspend fun setPollingIntervalMs(intervalMs: Long) = settingsDataStore.setPollingIntervalMs(intervalMs)
    override suspend fun setMinDisplacementMeters(meters: Float) = settingsDataStore.setMinDisplacementMeters(meters)
    override suspend fun setGpxExportTreeUri(uri: String?) = settingsDataStore.setGpxExportTreeUri(uri)
    override suspend fun setUnitSystem(unitSystem: UnitSystem) = settingsDataStore.setUnitSystem(unitSystem)
    override suspend fun setWeeklyAutoRefresh(enabled: Boolean) = settingsDataStore.setWeeklyAutoRefresh(enabled)

    // --- GPX & Debug Trace ---
    override fun getImportedFilesFlow(): Flow<List<ImportedGpxFileEntity>> = importedGpxFileDao.getAllFilesFlow()
    override suspend fun getImportedFileByHash(hash: String): ImportedGpxFileEntity? = importedGpxFileDao.getFileByHash(hash)
    override suspend fun insertImportedFile(file: ImportedGpxFileEntity) = importedGpxFileDao.insertFile(file)
    override suspend fun deleteImportedFile(hash: String) = withContext(Dispatchers.IO) {
        importedGpxFileDao.deleteFileByHash(hash)
        gpxExporter?.deleteInternalTrace(hash)
        Unit
    }

    override suspend fun openGpxStorageSession(): GpxBatchStorageSession = withContext(Dispatchers.IO) {
        val treeUri = settingsDataStore.gpxExportTreeUriFlow.first()
        gpxExporter?.openBatchStorageSession(treeUri) ?: NoOpGpxBatchStorageSession
    }

    override suspend fun copyAllTracesToExternalFolder(
        treeUriString: String,
        onProgress: (Int, Int) -> Unit
    ): ExternalTraceCopySummary = withContext(Dispatchers.IO + NonCancellable) {
        val traces = importedGpxFileDao.getAllFilesFlow().first()
        val exporter = gpxExporter ?: return@withContext ExternalTraceCopySummary(
            totalTraces = traces.size,
            copiedCount = 0,
            skippedExistingCount = 0,
            failedCount = 0,
            folderWritable = true
        )
        exporter.copyTracesToExternalFolder(
            traces = traces,
            treeUriString = treeUriString,
            onProgress = onProgress
        )
    }
    override suspend fun getImportedFilesCount(): Int = importedGpxFileDao.getImportedFilesCount()
    override suspend fun deleteLegacyTraces() = importedGpxFileDao.deleteLegacyTraces()
    override suspend fun backfillGpxMetadata() = withContext(Dispatchers.IO) {
        val allFiles = importedGpxFileDao.getAllFilesFlow().first()
        for (file in allFiles) {
            if (file.distanceMeters <= 0.0 && file.trackPointCount >= 2 && file.encodedTrace.isNotBlank()) {
                val computedDistance = com.example.mopedtrackv2.domain.gpx.GpxMetadataFormatter
                    .computeDistanceFromEncodedTrace(file.encodedTrace)
                if (computedDistance > 0.0) {
                    importedGpxFileDao.insertFile(file.copy(distanceMeters = computedDistance))
                }
            }
        }
    }
    override suspend fun toggleGpxVisibility(hash: String, isVisible: Boolean) = importedGpxFileDao.updateVisibility(hash, isVisible)
    override suspend fun setAllGpxVisibility(isVisible: Boolean) = importedGpxFileDao.updateAllVisibility(isVisible)
    override fun getVisibleGpxFilesFlow(): Flow<List<ImportedGpxFileEntity>> = importedGpxFileDao.getVisibleFilesFlow()

    override fun getLastDebugTraceFlow(): Flow<DebugTraceEntity?> = debugTraceDao.getLastTraceFlow()
    override suspend fun insertDebugTrace(trace: DebugTraceEntity) = debugTraceDao.insertTrace(trace)
    override suspend fun clearDebugTraces() = debugTraceDao.clearAllTraces()

    // --- OSM Pins ---
    override fun getAllOsmPinsFlow(): Flow<List<OsmPinEntity>> = osmPinDao.getAllPinsFlow()
    override fun getUnresolvedOsmPinsFlow(): Flow<List<OsmPinEntity>> = osmPinDao.getUnresolvedPinsFlow()
    override suspend fun getOsmPinById(pinId: String): OsmPinEntity? = withContext(Dispatchers.IO) {
        osmPinDao.getPinById(pinId)
    }
    override suspend fun insertOsmPin(pin: OsmPinEntity) = withContext(Dispatchers.IO) {
        osmPinDao.insertPin(pin)
    }
    override suspend fun updateOsmPin(pin: OsmPinEntity) = withContext(Dispatchers.IO) {
        osmPinDao.updatePin(pin)
    }
    override suspend fun deleteOsmPin(pinId: String) = withContext(Dispatchers.IO) {
        osmPinDao.deletePinById(pinId)
    }

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

    // --- Stats ---
    override fun getStatsSummaryFlow(): Flow<StatsSummary> {
        return combine(
            roadSegmentDao.getOverallRoadStatsFlow(),
            roadSegmentDao.getRegionStatsFlow(),
            importedGpxFileDao.getGpxArchiveStatsFlow()
        ) { overall, regionRows, gpxStats ->
            StatsSummary(
                totalSegments = overall.totalSegments,
                completedSegments = overall.completedSegments,
                totalDistanceMeters = overall.totalLengthMeters,
                completedDistanceMeters = overall.completedLengthMeters,
                unnamedCompletedSegments = overall.unnamedCompletedSegments,
                regionBreakdown = regionRows.map { row ->
                    RegionStats(
                        regionId = row.regionId,
                        regionName = row.regionName,
                        totalSegments = row.totalSegments,
                        completedSegments = row.completedSegments,
                        totalDistanceMeters = row.totalLengthMeters,
                        completedDistanceMeters = row.completedLengthMeters
                    )
                },
                totalGpxFiles = gpxStats.totalGpxFiles,
                totalTrackPoints = gpxStats.totalTrackPoints
            )
        }
    }

    // --- Ride Recording ---
    private val liveMatchMutex = Mutex()

    override val recordingState: StateFlow<RideRecordingState> = rideTrackingController.recordingState
    override val activeTrace: StateFlow<List<TrackPoint>> = rideTrackingController.activeTrace

    override fun startRideRecording() {
        rideTrackingController.startRecording()
    }

    override fun pauseRideRecording() = rideTrackingController.pauseRecording()
    override fun resumeRideRecording() = rideTrackingController.resumeRecording()
    override fun stopRideRecording() = rideTrackingController.stopRecording()

    override fun discardRideRecording() {
        rideTrackingController.discardRecording()
    }

    override fun consumeRecoveredRidePointCount(): Int =
        rideTrackingController.consumeRecoveredPointCount()

    override suspend fun matchLiveTraceWindow(tracePoints: List<TrackPoint>): Int = withContext(Dispatchers.IO) {
        if (tracePoints.size < 2) return@withContext 0

        liveMatchMutex.withLock {
            val window = LiveSegmentMatcher.extractRecentTraceWindow(tracePoints)
            val matchRadius = settingsDataStore.matchRadiusFlow.first().toDouble()
            val coverageThreshold = settingsDataStore.coverageThresholdFlow.first().toDouble()
            val bbox = LiveSegmentMatcher.computePaddedBoundingBox(window, matchRadius)

            val candidates = roadSegmentDao.getUncompletedCandidateSegments(
                minLat = bbox.minLat,
                maxLat = bbox.maxLat,
                minLon = bbox.minLon,
                maxLon = bbox.maxLon
            )
            if (candidates.isEmpty()) return@withLock 0

            val matched = LiveSegmentMatcher.findMatchedSegments(
                traceWindow = window,
                candidates = candidates,
                matchRadiusMeters = matchRadius,
                coverageThresholdPercent = coverageThreshold
            )
            if (matched.isEmpty()) return@withLock 0

            val matchedIds = matched.map { it.segmentId }.distinct()
            val pairedIds = matched.mapNotNull { it.pairedSegmentId }.distinct()
            val completedAt = System.currentTimeMillis()

            roadSegmentDao.markMatchedSegmentsCompleted(
                segmentIds = matchedIds,
                pairedIds = pairedIds,
                completedAt = completedAt
            )
            rideTrackingController.addCompletedSegments(matchedIds.size)
            Log.d("MopedTrack", "Live match completed ${matchedIds.size} segment(s) (pairedIds=${pairedIds.size})")
            matchedIds.size
        }
    }

    override suspend fun saveRideRecording(): Boolean = withContext(Dispatchers.IO) {
        val traceSnapshot = rideTrackingController.activeTrace.value.toList()

        if (traceSnapshot.isEmpty()) {
            rideTrackingController.discardRecording()
            return@withContext false
        }

        // Pause GPS updates while exporting so the persisted draft remains recoverable
        // if power is lost before the GPX export finishes.
        rideTrackingController.pauseRecording()

        if (traceSnapshot.size >= 2) {
            matchLiveTraceWindow(traceSnapshot)
        }

        val nowMs = System.currentTimeMillis()
        val fileName = GpxWriter.buildFileName(nowMs)
        val gpxContent = GpxWriter.serialize(
            trackPoints = traceSnapshot,
            trackName = fileName.removeSuffix(".gpx"),
            metadataTimeMs = traceSnapshot.first().timestamp
        )

        val exporter = gpxExporter
            ?: throw IllegalStateException("GpxExporter is not configured")
        val treeUri = settingsDataStore.gpxExportTreeUriFlow.first()
        val exportResult = exporter.exportGpx(
            fileName = fileName,
            content = gpxContent,
            treeUriString = treeUri
        )

        val hash = HashUtils.sha256Hex(exportResult.contentBytes)
        val encodedTrace = PolylineUtils.encode(traceSnapshot.map { Pair(it.lat, it.lon) })
        val distanceMeters = com.example.mopedtrackv2.domain.gpx.GpxParser.computeDistanceMeters(
            traceSnapshot.map { GpsPoint(it.lat, it.lon) }
        )
        val recordedAt = traceSnapshot.first().timestamp
        val durationMillis = if (traceSnapshot.size >= 2) {
            (traceSnapshot.last().timestamp - recordedAt).coerceAtLeast(0L)
        } else {
            null
        }

        importedGpxFileDao.insertFile(
            ImportedGpxFileEntity(
                fileHash = hash,
                fileName = exportResult.fileName,
                importedAt = nowMs,
                trackPointCount = traceSnapshot.size,
                segmentsCompletedCount = rideTrackingController.getCompletedSegmentCount(),
                encodedTrace = encodedTrace,
                isVisibleOnMap = false,
                distanceMeters = distanceMeters,
                durationMillis = durationMillis,
                recordedAt = recordedAt
            )
        )

        rideTrackingController.discardRecording()
        true
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
            [out:json][timeout:$OVERPASS_SERVER_TIMEOUT_SECONDS];
            area($osmRelationId)->.a;
            (
              way(area.a)$OVERPASS_WAY_FILTERS;
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
            [out:json][timeout:$OVERPASS_SERVER_TIMEOUT_SECONDS];
            (
              way($south,$west,$north,$east)$OVERPASS_WAY_FILTERS;
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    @Volatile
    private var preferredEndpointIndex = 0

    /**
     * Probes every mirror's `/api/status` in parallel (cheap GET) and returns the endpoints to use,
     * starting with the last known-good one. Mirrors that don't answer within
     * [OVERPASS_STATUS_PROBE_TIMEOUT_MS] are skipped so we don't burn a full read timeout on each
     * dead server. If no mirror answers, all endpoints are returned so the real query can still try.
     */
    private suspend fun resolveOverpassEndpointOrder(): List<String> {
        val endpoints = OVERPASS_ENDPOINTS
        val startIndex = preferredEndpointIndex.coerceIn(0, endpoints.size - 1)
        val rotated = List(endpoints.size) { endpoints[(startIndex + it) % endpoints.size] }

        val healthy = coroutineScope {
            rotated.map { endpoint ->
                async { endpoint to isOverpassEndpointHealthy(endpoint) }
            }.awaitAll()
        }.filter { it.second }.map { it.first }

        Log.d("MopedTrack", "Overpass health probe: healthy=$healthy of ${rotated.size}")
        return healthy.ifEmpty { rotated }
    }

    private suspend fun isOverpassEndpointHealthy(endpoint: String): Boolean =
        withTimeoutOrNull(OVERPASS_STATUS_PROBE_TIMEOUT_MS) {
            try {
                val response = osmApi.getOverpassStatus(overpassStatusUrl(endpoint))
                response.body()?.close()
                response.errorBody()?.close()
                response.isSuccessful
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MopedTrack", "Overpass status probe failed for $endpoint: ${e.message}")
                false
            }
        } ?: false

    /** Network-level failures (server down / not answering). Such mirrors are skipped for the rest of the import. */
    private fun isUnreachableError(e: Throwable): Boolean =
        e is SocketTimeoutException || e is ConnectException || e is UnknownHostException

    private fun describeOverpassError(e: Throwable?): String = when (e) {
        null -> "no response"
        is SocketTimeoutException -> "timed out"
        is ConnectException, is UnknownHostException -> "unreachable"
        is HttpException -> "HTTP ${e.code()}"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun hostOf(endpoint: String): String = runCatching { URI(endpoint).host }.getOrNull() ?: endpoint

    private suspend fun executeOverpassWithEndpointFallbacks(
        query: String,
        endpoints: List<String>,
        unreachableEndpoints: MutableSet<String>,
        onAttempt: (endpoint: String) -> Unit = {}
    ): OverpassResponse {
        var lastException: Exception? = null
        var lastEmptyResponse: OverpassResponse? = null

        for (endpoint in endpoints) {
            if (endpoint in unreachableEndpoints) continue
            try {
                onAttempt(endpoint)
                Log.d("MopedTrack", "Querying Overpass endpoint: $endpoint")
                val response = osmApi.queryOverpass(url = endpoint, query = query)

                // Overpass reports server-side failures (query timeout, out of memory) with HTTP 200
                // and a "remark"; the element list may be truncated, so never treat it as success.
                val remark = response.remark
                if (remark != null && remark.contains("error", ignoreCase = true)) {
                    throw OverpassException("Overpass server error: $remark")
                }

                if (response.elements.isNotEmpty()) {
                    Log.d("MopedTrack", "Successfully fetched ${response.elements.size} elements from $endpoint")
                    preferredEndpointIndex = OVERPASS_ENDPOINTS.indexOf(endpoint).coerceAtLeast(0)
                    return response
                } else {
                    Log.w("MopedTrack", "Endpoint $endpoint returned 0 elements, checking next endpoint...")
                    lastEmptyResponse = response
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MopedTrack", "Overpass endpoint $endpoint failed: ${describeOverpassError(e)}")
                lastException = e
                if (isUnreachableError(e)) unreachableEndpoints.add(endpoint)
                delay(300L)
            }
        }

        // If all endpoints returned 0 elements, return the empty response instead of failing
        if (lastEmptyResponse != null) {
            return lastEmptyResponse
        }

        throw lastException ?: OverpassException("All Overpass endpoints are unreachable")
    }

    private suspend fun fetchOverpassResponse(
        region: Region,
        onProgress: (Float, String) -> Unit
    ): OverpassResponse {
        val areaQuery = if (region.osmRelationId > 0) buildAreaQuery(region.osmRelationId) else null
        val bboxQuery = buildBboxQuery(region.boundingBox)

        onProgress(0.05f, "Checking map server availability...")
        val endpoints = resolveOverpassEndpointOrder()
        val unreachable = mutableSetOf<String>()
        var lastError: Exception? = null

        // 1. Try relation/area query first (exact region boundary)
        if (areaQuery != null) {
            try {
                val response = executeOverpassWithEndpointFallbacks(areaQuery, endpoints, unreachable) {
                    onProgress(0.1f, "Downloading roads for ${region.name} from ${hostOf(it)}...")
                }
                if (response.elements.isNotEmpty()) {
                    return response
                }
                Log.w("MopedTrack", "Area query returned 0 elements. Attempting bbox query fallback...")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.w("MopedTrack", "Area query failed across all endpoints (${describeOverpassError(e)}). Attempting bbox query fallback...")
            }
        }

        // 2. Fall back to the bounding box query, skipping mirrors that were unreachable above
        if (bboxQuery != null) {
            try {
                return executeOverpassWithEndpointFallbacks(bboxQuery, endpoints, unreachable) {
                    onProgress(0.2f, "Retrying with bounding box via ${hostOf(it)}...")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                Log.e("MopedTrack", "Bbox query failed across all endpoints (${describeOverpassError(e)})")
            }
        }

        throw OverpassException(
            "All OpenStreetMap (Overpass) servers are busy or unreachable right now " +
                "(last error: ${describeOverpassError(lastError)}). Please try again in a few minutes.",
            lastError
        )
    }

    private fun buildRoadSegmentsFromOverpass(
        regionId: String,
        response: OverpassResponse,
        onProgress: (Float, String) -> Unit
    ): List<RoadSegmentEntity> {
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
            val highway = tags["highway"] ?: return@filter false
            val serviceType = tags["service"]

            if (serviceType == "driveway" || serviceType == "parking_aisle" || serviceType == "drive-through") {
                return@filter false
            }

            if (highway !in includeHighways) return@filter false
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
                            val p2 = segmentCoords[j + 1]
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
                                regionId = regionId,
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
        // Find pairs with same name (case-insensitive), both oneway, parallel, and close (<100m)
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

                    val dist = MathUtils.haversineDistance(
                        (seg1.minLat + seg1.maxLat) / 2, (seg1.minLon + seg1.maxLon) / 2,
                        (seg2.minLat + seg2.maxLat) / 2, (seg2.minLon + seg2.maxLon) / 2
                    )

                    if (dist < 100.0) {
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

        return allSegments
    }

    override suspend fun importRegion(region: Region, onProgress: (Float, String) -> Unit) = withContext(Dispatchers.IO) {
        onProgress(0.02f, "Connecting to Overpass API for ${region.name}...")
        // Fetch data from Overpass API before modifying database
        val response = fetchOverpassResponse(region, onProgress)

        onProgress(0.35f, "Processing ${response.elements.size} map elements...")
        val allSegments = buildRoadSegmentsFromOverpass(region.regionId, response, onProgress)

        onProgress(0.92f, "Saving ${allSegments.size} road segments to database...")
        runInTransaction {
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
            roadSegmentDao.replaceRegionSegments(region.regionId, allSegments)
        }
        settingsDataStore.setRegionVisibility(region.regionId, true)
        Log.d("MopedTrack", "Imported region '${region.name}': inserted ${allSegments.size} road segments")

        onProgress(0.98f, "Re-scanning GPX traces against new road network...")
        rescanGpxTraces()

        onProgress(1.0f, "Complete!")
    }

    private data class CompletedSegmentSnapshot(
        val osmWayId: Long,
        val startPoint: GpsPoint,
        val endPoint: GpsPoint,
        val traceSegments: List<TraceSegment>,
        val completedAt: Long,
        val minLat: Double,
        val maxLat: Double,
        val minLon: Double,
        val maxLon: Double
    )

    override suspend fun refreshRegion(
        regionId: String,
        onProgress: (Float, String) -> Unit
    ): RegionRefreshResult = withContext(Dispatchers.IO) {
        val existingRegion = regionDao.getRegionById(regionId)
            ?: throw IllegalArgumentException("Region '$regionId' not found")
        val region = existingRegion.toDomain()

        onProgress(0.02f, "Connecting to Overpass API for ${region.name}...")
        val response = fetchOverpassResponse(region, onProgress)

        onProgress(0.35f, "Snapshotting completed progress...")
        val oldSegments = roadSegmentDao.getRoadSegmentsForRegion(regionId)
        val oldCompletedSegments = oldSegments.filter { it.isCompleted }

        val matchRadiusMeters = matchRadiusFlow.first().toDouble()
        val coverageThreshold = coverageThresholdFlow.first().toDouble()
        val fallbackNowMs = System.currentTimeMillis()

        val oldSnapshots = mutableListOf<CompletedSegmentSnapshot>()
        val oldSnapshotsByWayId = mutableMapOf<Long, MutableList<CompletedSegmentSnapshot>>()
        val snapshotCellSize = 0.005
        val snapshotGrid = mutableMapOf<Pair<Int, Int>, MutableList<CompletedSegmentSnapshot>>()

        for (oldSeg in oldCompletedSegments) {
            val decoded = PolylineUtils.decode(oldSeg.geometryEncoded)
            if (decoded.size < 2) continue
            val gpsPts = decoded.map { GpsPoint(it.first, it.second) }
            val traceSegs = ArrayList<TraceSegment>(gpsPts.size - 1)
            for (i in 0 until gpsPts.size - 1) {
                val p1 = gpsPts[i]
                val p2 = gpsPts[i + 1]
                val dist = MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
                val bearing = if (dist >= 1.0) MathUtils.bearing(p1.lat, p1.lon, p2.lat, p2.lon) else null
                traceSegs.add(TraceSegment(p1, p2, bearing))
            }
            val snap = CompletedSegmentSnapshot(
                osmWayId = oldSeg.osmWayId,
                startPoint = gpsPts.first(),
                endPoint = gpsPts.last(),
                traceSegments = traceSegs,
                completedAt = oldSeg.completedAt ?: fallbackNowMs,
                minLat = oldSeg.minLat,
                maxLat = oldSeg.maxLat,
                minLon = oldSeg.minLon,
                maxLon = oldSeg.maxLon
            )
            oldSnapshots.add(snap)
            oldSnapshotsByWayId.getOrPut(oldSeg.osmWayId) { mutableListOf() }.add(snap)

            val minLatCell = (snap.minLat / snapshotCellSize).toInt()
            val maxLatCell = (snap.maxLat / snapshotCellSize).toInt()
            val minLonCell = (snap.minLon / snapshotCellSize).toInt()
            val maxLonCell = (snap.maxLon / snapshotCellSize).toInt()
            for (cx in minLatCell..maxLatCell) {
                for (cy in minLonCell..maxLonCell) {
                    snapshotGrid.getOrPut(Pair(cx, cy)) { mutableListOf() }.add(snap)
                }
            }
        }

        onProgress(0.42f, "Processing ${response.elements.size} map elements...")
        val incomingSegments = buildRoadSegmentsFromOverpass(regionId, response) { progress, status ->
            val mappedProgress = 0.42f + ((progress - 0.45f) / 0.45f).coerceIn(0f, 1f) * 0.30f
            onProgress(mappedProgress, status)
        }

        onProgress(0.74f, "Preserving completed segments (${incomingSegments.size} segments)...")
        val workingSegments = incomingSegments.toMutableList()
        val padLat = matchRadiusMeters / 111000.0

        if (oldSnapshots.isNotEmpty()) {
            for (idx in workingSegments.indices) {
                val seg = workingSegments[idx]
                val decoded = PolylineUtils.decode(seg.geometryEncoded)
                if (decoded.size < 2) continue
                val newStart = GpsPoint(decoded.first().first, decoded.first().second)
                val newEnd = GpsPoint(decoded.last().first, decoded.last().second)

                // 1. Fast path: exact osmWayId + matching endpoints (forward or reverse)
                val sameWayMatch = oldSnapshotsByWayId[seg.osmWayId]?.firstOrNull { oldSnap ->
                    val forwardMatch =
                        MathUtils.haversineDistance(newStart.lat, newStart.lon, oldSnap.startPoint.lat, oldSnap.startPoint.lon) <= matchRadiusMeters &&
                            MathUtils.haversineDistance(newEnd.lat, newEnd.lon, oldSnap.endPoint.lat, oldSnap.endPoint.lon) <= matchRadiusMeters
                    val reverseMatch =
                        MathUtils.haversineDistance(newStart.lat, newStart.lon, oldSnap.endPoint.lat, oldSnap.endPoint.lon) <= matchRadiusMeters &&
                            MathUtils.haversineDistance(newEnd.lat, newEnd.lon, oldSnap.startPoint.lat, oldSnap.startPoint.lon) <= matchRadiusMeters
                    forwardMatch || reverseMatch
                }
                if (sameWayMatch != null) {
                    workingSegments[idx] = seg.copy(
                        isCompleted = true,
                        completedAt = sameWayMatch.completedAt
                    )
                    continue
                }

                // 2. Spatial overlap matching: >= 80% of 5m interpolated sub-points within matchRadiusMeters
                val latRad = Math.toRadians((seg.minLat + seg.maxLat) / 2.0)
                val cosLat = if (cos(latRad) > 0.1) cos(latRad) else 1.0
                val padLon = matchRadiusMeters / (111000.0 * cosLat)

                val qMinLat = seg.minLat - padLat
                val qMaxLat = seg.maxLat + padLat
                val qMinLon = seg.minLon - padLon
                val qMaxLon = seg.maxLon + padLon

                val minLatCell = (qMinLat.coerceIn(-90.0, 90.0) / snapshotCellSize).toInt()
                val maxLatCell = (qMaxLat.coerceIn(-90.0, 90.0) / snapshotCellSize).toInt()
                val minLonCell = (qMinLon.coerceIn(-180.0, 180.0) / snapshotCellSize).toInt()
                val maxLonCell = (qMaxLon.coerceIn(-180.0, 180.0) / snapshotCellSize).toInt()

                val candidates = LinkedHashSet<CompletedSegmentSnapshot>()
                for (cx in minLatCell..maxLatCell) {
                    for (cy in minLonCell..maxLonCell) {
                        snapshotGrid[Pair(cx, cy)]?.forEach { cand ->
                            if (cand.maxLat >= qMinLat && cand.minLat <= qMaxLat &&
                                cand.maxLon >= qMinLon && cand.minLon <= qMaxLon
                            ) {
                                candidates.add(cand)
                            }
                        }
                    }
                }
                if (candidates.isEmpty()) continue

                val newGpsPts = decoded.map { GpsPoint(it.first, it.second) }
                val subPoints = SegmentMatcherEngine.interpolateSegmentWithBearing(newGpsPts, 5.0)
                if (subPoints.isEmpty()) continue

                var coveredCount = 0
                var bestCompletedAt: Long? = null

                for (subPt in subPoints) {
                    var matchedSnap: CompletedSegmentSnapshot? = null
                    for (cand in candidates) {
                        if (SegmentMatcherEngine.isSubPointCovered(
                                subPt = subPt.point,
                                subPtBearing = subPt.bearing,
                                traceSegments = cand.traceSegments,
                                matchRadiusMeters = matchRadiusMeters
                            )
                        ) {
                            matchedSnap = cand
                            break
                        }
                    }
                    if (matchedSnap != null) {
                        coveredCount++
                        val ts = matchedSnap.completedAt
                        if (bestCompletedAt == null || ts > bestCompletedAt) {
                            bestCompletedAt = ts
                        }
                    }
                }

                val overlapRatio = coveredCount.toDouble() / subPoints.size.toDouble()
                if (overlapRatio >= 0.80) {
                    workingSegments[idx] = seg.copy(
                        isCompleted = true,
                        completedAt = bestCompletedAt ?: fallbackNowMs
                    )
                }
            }

            // Propagate preserved completion across divided-road pairs
            val completedPairTimestamps = mutableMapOf<String, Long>()
            for (seg in workingSegments) {
                val pairId = seg.pairedSegmentId
                if (seg.isCompleted && pairId != null) {
                    val ts = seg.completedAt ?: fallbackNowMs
                    val prev = completedPairTimestamps[pairId]
                    if (prev == null || ts > prev) {
                        completedPairTimestamps[pairId] = ts
                    }
                }
            }
            if (completedPairTimestamps.isNotEmpty()) {
                for (idx in workingSegments.indices) {
                    val seg = workingSegments[idx]
                    val pairId = seg.pairedSegmentId
                    if (!seg.isCompleted && pairId != null && completedPairTimestamps.containsKey(pairId)) {
                        workingSegments[idx] = seg.copy(
                            isCompleted = true,
                            completedAt = completedPairTimestamps[pairId]
                        )
                    }
                }
            }
        }

        val preservedCompletedCount = workingSegments.count { it.isCompleted }

        // 3. Re-evaluate stored GPX traces against newly mapped / remaining uncompleted segments
        onProgress(0.84f, "Matching stored GPX traces against new roads...")
        var gpxMatchedNewSegmentsCount = 0
        val allTraces = importedGpxFileDao.getAllFilesFlow().first()
        val nowMs = System.currentTimeMillis()

        val pairIdToIndices = mutableMapOf<String, MutableList<Int>>()
        for (i in workingSegments.indices) {
            workingSegments[i].pairedSegmentId?.let { pairId ->
                pairIdToIndices.getOrPut(pairId) { mutableListOf() }.add(i)
            }
        }

        for (file in allTraces) {
            if (file.encodedTrace.isBlank()) continue
            val tracePoints = PolylineUtils.decode(file.encodedTrace).map { GpsPoint(it.first, it.second) }
            if (tracePoints.size < 2) continue

            val tMinLat = tracePoints.minOf { it.lat } - padLat
            val tMaxLat = tracePoints.maxOf { it.lat } + padLat
            val tCenterLatRad = Math.toRadians((tMinLat + tMaxLat) / 2.0)
            val tCosLat = if (cos(tCenterLatRad) > 0.1) cos(tCenterLatRad) else 1.0
            val tPadLon = matchRadiusMeters / (111000.0 * tCosLat)
            val tMinLon = tracePoints.minOf { it.lon } - tPadLon
            val tMaxLon = tracePoints.maxOf { it.lon } + tPadLon

            val spatialIndex = TraceSpatialIndex(tracePoints)

            for (i in workingSegments.indices) {
                val seg = workingSegments[i]
                if (seg.isCompleted) continue
                if (seg.maxLat < tMinLat || seg.minLat > tMaxLat ||
                    seg.maxLon < tMinLon || seg.minLon > tMaxLon
                ) {
                    continue
                }

                val isMatched = SegmentMatcherEngine.evaluateSegment(
                    segment = seg,
                    spatialIndex = spatialIndex,
                    matchRadiusMeters = matchRadiusMeters,
                    coverageThresholdPercent = coverageThreshold
                )
                if (isMatched) {
                    workingSegments[i] = seg.copy(isCompleted = true, completedAt = nowMs)
                    gpxMatchedNewSegmentsCount++

                    seg.pairedSegmentId?.let { pairId ->
                        pairIdToIndices[pairId]?.forEach { partnerIdx ->
                            val partner = workingSegments[partnerIdx]
                            if (!partner.isCompleted) {
                                workingSegments[partnerIdx] = partner.copy(isCompleted = true, completedAt = nowMs)
                                gpxMatchedNewSegmentsCount++
                            }
                        }
                    }
                }
            }
        }

        onProgress(0.94f, "Saving ${workingSegments.size} road segments...")
        runInTransaction {
            regionDao.insertRegion(
                existingRegion.copy(lastUpdatedTimestamp = nowMs)
            )
            roadSegmentDao.replaceRegionSegments(regionId, workingSegments)
        }

        val newSegmentsCount = (workingSegments.size - oldSegments.size).coerceAtLeast(0)
        Log.d(
            "MopedTrack",
            "Refreshed region '${region.name}': total=${workingSegments.size}, preserved=$preservedCompletedCount, " +
                "gpxMatched=$gpxMatchedNewSegmentsCount, netNew=$newSegmentsCount"
        )
        onProgress(1.0f, "Complete!")

        RegionRefreshResult(
            regionId = regionId,
            regionName = region.name,
            totalSegments = workingSegments.size,
            preservedCompletedCount = preservedCompletedCount,
            gpxMatchedNewSegmentsCount = gpxMatchedNewSegmentsCount,
            newSegmentsCount = newSegmentsCount
        )
    }

    override suspend fun deleteRegion(regionId: String) = withContext(Dispatchers.IO) {
        roadSegmentDao.deleteRoadSegmentsByRegion(regionId)
        regionDao.deleteRegionById(regionId)
        settingsDataStore.setRegionVisibility(regionId, true)
    }

    override suspend fun rescanGpxTraces() = withContext(Dispatchers.IO) {
        val matchRadius = matchRadiusFlow.first().toDouble()
        val coverageThreshold = coverageThresholdFlow.first().toDouble()

        // Reset all segment completion progress so false-positive matches are un-completed during rescan
        resetAllProgress()

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

            // Update file record to show total segments completed (and backfill distance if missing)
            val resolvedDistance = if (file.distanceMeters <= 0.0 && tracePoints.size >= 2) {
                com.example.mopedtrackv2.domain.gpx.GpxParser.computeDistanceMeters(tracePoints)
            } else {
                file.distanceMeters
            }
            importedGpxFileDao.insertFile(
                file.copy(
                    segmentsCompletedCount = fileNewSegments,
                    distanceMeters = resolvedDistance
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
        settingsDataStore.setHiddenRegionIds(emptySet())
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
