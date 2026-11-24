package com.example.mopedtrackv2.presentation.home

import android.Manifest
import android.content.Context
import android.graphics.RectF
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mopedtrackv2.domain.engine.GpsPoint
import com.example.mopedtrackv2.domain.engine.SegmentMatcherEngine
import com.example.mopedtrackv2.domain.model.RoadSegment
import com.example.mopedtrackv2.utils.MathUtils
import com.example.mopedtrackv2.utils.PolylineUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.style.expressions.Expression.*
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

private fun activateLocationComponentSafely(
    map: MapLibreMap,
    context: Context
) {
    val style = map.style ?: return
    val locationComponent = map.locationComponent
    try {
        if (!locationComponent.isLocationComponentActivated) {
            val options = LocationComponentActivationOptions.builder(context, style).build()
            locationComponent.activateLocationComponent(options)
        }
        if (locationComponent.isLocationComponentActivated) {
            locationComponent.isLocationComponentEnabled = true
            locationComponent.cameraMode = CameraMode.NONE
            locationComponent.renderMode = RenderMode.COMPASS
        }
    } catch (_: SecurityException) {
        // Permission not granted or revoked
    } catch (_: Exception) {
        // Safety fallback
    }
}

private fun triggerViewportQuery(map: MapLibreMap, viewModel: HomeViewModel) {
    val bounds = map.projection.visibleRegion.latLngBounds
    val minLat = minOf(bounds.latitudeSouth, bounds.latitudeNorth)
    val maxLat = maxOf(bounds.latitudeSouth, bounds.latitudeNorth)
    val minLon = minOf(bounds.longitudeWest, bounds.longitudeEast)
    val maxLon = maxOf(bounds.longitudeWest, bounds.longitudeEast)
    Log.d("MopedTrack", "triggerViewportQuery: minLat=$minLat, maxLat=$maxLat, minLon=$minLon, maxLon=$maxLon")
    viewModel.onCameraIdle(
        minLat = minLat,
        maxLat = maxLat,
        minLon = minLon,
        maxLon = maxLon
    )
}

private fun createCorridorPolygon(points: List<GpsPoint>, radiusMeters: Double): Polygon? {
    if (points.size < 2) return null

    val latAvg = points.map { it.lat }.average()
    val latRad = Math.toRadians(latAvg)
    val cosLat = if (kotlin.math.cos(latRad) > 0.1) kotlin.math.cos(latRad) else 1.0

    val rLat = radiusMeters / 111000.0
    val rLon = radiusMeters / (111000.0 * cosLat)

    val leftOffsets = mutableListOf<Point>()
    val rightOffsets = mutableListOf<Point>()

    for (i in 0 until points.size) {
        val curr = points[i]
        val prev = if (i > 0) points[i - 1] else null
        val next = if (i < points.size - 1) points[i + 1] else null

        var dLat = 0.0
        var dLon = 0.0

        if (next != null && prev != null) {
            dLat = next.lat - prev.lat
            dLon = next.lon - prev.lon
        } else if (next != null) {
            dLat = next.lat - curr.lat
            dLon = next.lon - curr.lon
        } else if (prev != null) {
            dLat = curr.lat - prev.lat
            dLon = curr.lon - prev.lon
        }

        val len = kotlin.math.sqrt(dLat * dLat + dLon * dLon)
        if (len == 0.0) continue

        val normLat = -dLon / len
        val normLon = dLat / len

        val leftLat = curr.lat + normLat * rLat
        val leftLon = curr.lon + normLon * rLon

        val rightLat = curr.lat - normLat * rLat
        val rightLon = curr.lon - normLon * rLon

        leftOffsets.add(Point.fromLngLat(leftLon, leftLat))
        rightOffsets.add(Point.fromLngLat(rightLon, rightLat))
    }

    if (leftOffsets.isEmpty() || rightOffsets.isEmpty()) return null

    val ring = mutableListOf<Point>()
    ring.addAll(leftOffsets)
    ring.addAll(rightOffsets.reversed())
    ring.add(leftOffsets.first())

    return Polygon.fromLngLats(listOf(ring))
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onManageRegionsClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onImportGpxClick: () -> Unit
) {
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    val locationPermissionState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)

    val roadSegments by viewModel.roadSegments.collectAsState()
    val selectedSegment by viewModel.selectedSegment.collectAsState()
    val regions by viewModel.regions.collectAsState()
    
    val debugOverlayEnabled by viewModel.debugOverlayEnabled.collectAsState(initial = false)
    val debugBboxSegmentIds by viewModel.debugBboxSegmentIds.collectAsState()
    val matchRadius by viewModel.matchRadius.collectAsState(initial = 15)
    val showCompletedSegments by viewModel.showCompletedSegments.collectAsState(initial = true)
    val showUncompletedSegments by viewModel.showUncompletedSegments.collectAsState(initial = true)
    val showGpxTraces by viewModel.showGpxTraces.collectAsState(initial = true)
    val visibleGpxTraces by viewModel.visibleGpxTraces.collectAsState(initial = emptyList())
    val allImportedGpxFiles by viewModel.allImportedGpxFiles.collectAsState(initial = emptyList())
    val lastDebugTrace by viewModel.lastDebugTrace.collectAsState(initial = null)

    val context = LocalContext.current
    
    val sheetState = rememberModalBottomSheetState()
    var showLayersSheet by remember { mutableStateOf(false) }
    
    if (showLayersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLayersSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = "Map Layers",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show Completed Segments (Green)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = showCompletedSegments,
                        onCheckedChange = { viewModel.toggleShowCompletedSegments(it) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show Uncompleted Segments (Red)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = showUncompletedSegments,
                        onCheckedChange = { viewModel.toggleShowUncompletedSegments(it) }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Show GPX Traces (Blue)", modifier = Modifier.weight(1f))
                    Switch(
                        checked = showGpxTraces,
                        onCheckedChange = { viewModel.toggleShowGpxTraces(it) }
                    )
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    if (selectedSegment != null) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.clearSelectedSegment() },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Text(
                    text = selectedSegment?.name ?: "Unnamed Road",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Length: ${selectedSegment?.lengthMeters?.toInt() ?: 0} m",
                    style = MaterialTheme.typography.bodyMedium
                )
                val isBboxEnabled = selectedSegment?.let { debugBboxSegmentIds.contains(it.segmentId) } ?: false
                if (debugOverlayEnabled) {
                    val coverageInfo = remember(selectedSegment, visibleGpxTraces, allImportedGpxFiles, lastDebugTrace, matchRadius) {
                        val segment = selectedSegment ?: return@remember null
                        val decodedPairs = try { PolylineUtils.decode(segment.geometryEncoded) } catch (_: Exception) { emptyList() }
                        if (decodedPairs.size < 2) return@remember null
                        val segGpsPoints = decodedPairs.map { GpsPoint(it.first, it.second) }
                        val interpolatedSubPoints = SegmentMatcherEngine.interpolateSegment(segGpsPoints, 5.0)
                        if (interpolatedSubPoints.isEmpty()) return@remember null

                        val allGpsTraceSegments = mutableListOf<Pair<GpsPoint, GpsPoint>>()
                        val allTracesToEvaluate = (visibleGpxTraces + allImportedGpxFiles).distinctBy { it.fileHash }
                        allTracesToEvaluate.forEach { trace ->
                            try {
                                val pts = PolylineUtils.decode(trace.encodedTrace).map { GpsPoint(it.first, it.second) }
                                for (i in 0 until pts.size - 1) {
                                    val p1 = pts[i]
                                    val p2 = pts[i + 1]
                                    val dist = MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
                                    if (dist <= 150.0) {
                                        allGpsTraceSegments.add(Pair(p1, p2))
                                    }
                                }
                            } catch (_: Exception) {}
                        }
                        lastDebugTrace?.let { trace ->
                            try {
                                val pts = PolylineUtils.decode(trace.pointsEncoded).map { GpsPoint(it.first, it.second) }
                                for (i in 0 until pts.size - 1) {
                                    val p1 = pts[i]
                                    val p2 = pts[i + 1]
                                    val dist = MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
                                    if (dist <= 150.0) {
                                        allGpsTraceSegments.add(Pair(p1, p2))
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        val radiusMeters = matchRadius.toDouble()
                        var coveredCount = 0
                        interpolatedSubPoints.forEach { subPt ->
                            val isCovered = allGpsTraceSegments.any { (t1, t2) ->
                                MathUtils.distanceToLineSegmentMeters(
                                    subPt.lat, subPt.lon,
                                    t1.lat, t1.lon,
                                    t2.lat, t2.lon
                                ) <= radiusMeters
                            }
                            if (isCovered) coveredCount++
                        }
                        val total = interpolatedSubPoints.size
                        val pct = (coveredCount.toDouble() / total) * 100.0
                        Triple(pct.toInt(), coveredCount, total)
                    }

                    coverageInfo?.let { (pct, covered, total) ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Coverage: $pct% (80% required)",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (pct >= 80) Color(0xFF2E7D32) else Color(0xFFD84315)
                        )
                        Text(
                            text = "$covered of $total points covered within ${matchRadius}m match radius",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Show Debug Overlay",
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(
                            checked = isBboxEnabled,
                            onCheckedChange = { enabled ->
                                selectedSegment?.let { segment ->
                                    viewModel.toggleSegmentDebugBbox(segment.segmentId, enabled, segment.pairedSegmentId)
                                }
                            }
                        )
                    }
                }
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        selectedSegment?.let { 
                            viewModel.toggleSegmentCompletion(it) 
                        }
                    }
                ) {
                    Text(text = if (selectedSegment?.isCompleted == true) "Mark Uncompleted" else "Mark Completed")
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }

    // Safely activate location component when permission is granted or map becomes available
    LaunchedEffect(locationPermissionState.status.isGranted, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (locationPermissionState.status.isGranted) {
            activateLocationComponentSafely(map, context)
            
            // Only perform initial zoom to location once on app launch if camera hasn't been set up yet
            if (!viewModel.hasPerformedInitialZoom && viewModel.lastCameraPosition == null) {
                val locationComponent = map.locationComponent
                val location = try {
                    if (locationComponent.isLocationComponentActivated) locationComponent.lastKnownLocation else null
                } catch (_: Exception) {
                    null
                }
                
                if (location != null) {
                    viewModel.hasPerformedInitialZoom = true
                    val targetPos = CameraPosition.Builder()
                        .target(LatLng(location.latitude, location.longitude))
                        .zoom(16.0)
                        .build()
                    viewModel.lastCameraPosition = targetPos
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(targetPos), 1000)
                }
            }
        }
    }

    // Effect to update map source when segments change
    LaunchedEffect(roadSegments, debugBboxSegmentIds, debugOverlayEnabled, matchRadius, visibleGpxTraces, allImportedGpxFiles, lastDebugTrace, showCompletedSegments, showUncompletedSegments, showGpxTraces, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.style?.let { style ->
            withContext(Dispatchers.Default) {
                val features = roadSegments.mapNotNull { segment ->
                    if (segment.isCompleted && !showCompletedSegments) return@mapNotNull null
                    if (!segment.isCompleted && !showUncompletedSegments) return@mapNotNull null

                    // Do NOT render uncompleted driveways, service roads, or unnamed roads in red
                    if (!segment.isCompleted && (segment.highwayType == "service" || segment.name == "Unnamed Road")) {
                        return@mapNotNull null
                    }

                    try {
                        val points = PolylineUtils.decode(segment.geometryEncoded).map { p ->
                            Point.fromLngLat(p.second, p.first)
                        }
                        if (points.size < 2) return@mapNotNull null
                        val lineString = LineString.fromLngLats(points)
                        val feature = Feature.fromGeometry(lineString)
                        feature.addStringProperty("id", segment.segmentId)
                        feature.addStringProperty("segmentId", segment.segmentId)
                        feature.addBooleanProperty("isCompleted", segment.isCompleted)
                        feature
                    } catch (e: Exception) {
                        Log.e("HomeScreen", "Error decoding geometry for segment ${segment.segmentId}", e)
                        null
                    }
                }
                
                val debugFeatures = mutableListOf<Feature>()
                if (debugOverlayEnabled && debugBboxSegmentIds.isNotEmpty()) {
                    val targetSegments = roadSegments.filter {
                        debugBboxSegmentIds.contains(it.segmentId) || (it.pairedSegmentId != null && debugBboxSegmentIds.contains(it.pairedSegmentId))
                    }

                    // Pre-decode ALL trace line segments for distance checks
                    val allGpsTraceSegments = mutableListOf<Pair<GpsPoint, GpsPoint>>()
                    val allTracesToEvaluate = (visibleGpxTraces + allImportedGpxFiles).distinctBy { it.fileHash }
                    allTracesToEvaluate.forEach { trace ->
                        try {
                            val pts = PolylineUtils.decode(trace.encodedTrace).map { GpsPoint(it.first, it.second) }
                            for (i in 0 until pts.size - 1) {
                                val p1 = pts[i]
                                val p2 = pts[i + 1]
                                val dist = MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
                                if (dist <= 150.0) {
                                    allGpsTraceSegments.add(Pair(p1, p2))
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    lastDebugTrace?.let { trace ->
                        try {
                            val pts = PolylineUtils.decode(trace.pointsEncoded).map { GpsPoint(it.first, it.second) }
                            for (i in 0 until pts.size - 1) {
                                val p1 = pts[i]
                                val p2 = pts[i + 1]
                                val dist = MathUtils.haversineDistance(p1.lat, p1.lon, p2.lat, p2.lon)
                                if (dist <= 150.0) {
                                    allGpsTraceSegments.add(Pair(p1, p2))
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    targetSegments.forEach { segment ->
                        try {
                            val decodedPairs = PolylineUtils.decode(segment.geometryEncoded)
                            if (decodedPairs.size >= 2) {
                                val segGpsPoints = decodedPairs.map { GpsPoint(it.first, it.second) }
                                val radiusMeters = matchRadius.toDouble()

                                // 1. Dynamic Geographic Corridor Polygon buffered by matchRadius setting
                                val corridorPolygon = createCorridorPolygon(segGpsPoints, radiusMeters)
                                if (corridorPolygon != null) {
                                    val corridorFeature = Feature.fromGeometry(corridorPolygon)
                                    corridorFeature.addBooleanProperty("isDebugCorridor", true)
                                    debugFeatures.add(corridorFeature)
                                }

                                // 2. Interpolated Sub-Points & Sub-Segments
                                val interpolatedSubPoints = SegmentMatcherEngine.interpolateSegment(segGpsPoints, 5.0)

                                val coveredFlags = interpolatedSubPoints.map { subPt ->
                                    segment.isCompleted || allGpsTraceSegments.any { (t1, t2) ->
                                        MathUtils.distanceToLineSegmentMeters(
                                            subPt.lat, subPt.lon,
                                            t1.lat, t1.lon,
                                            t2.lat, t2.lon
                                        ) <= radiusMeters
                                    }
                                }

                                // Sub-point circles along the road
                                interpolatedSubPoints.forEachIndexed { idx, subPt ->
                                    val isCovered = coveredFlags[idx]
                                    val subPtFeature = Feature.fromGeometry(Point.fromLngLat(subPt.lon, subPt.lat))
                                    subPtFeature.addBooleanProperty("isDebugSubPoint", true)
                                    subPtFeature.addBooleanProperty("isSubPointCovered", isCovered)
                                    debugFeatures.add(subPtFeature)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("HomeScreen", "Error creating debug overlay for segment ${segment.segmentId}", e)
                        }
                    }
                }

                if (showGpxTraces) {
                    visibleGpxTraces.forEach { trace ->
                        try {
                            val tracePoints = PolylineUtils.decode(trace.encodedTrace)
                            
                            if (tracePoints.size >= 2) {
                                // Split trace points on gaps > 150m so paused/teleported traces don't draw diagonal lines across town
                                val subPaths = mutableListOf<MutableList<Pair<Double, Double>>>()
                                var currentSubPath = mutableListOf<Pair<Double, Double>>()
                                currentSubPath.add(tracePoints.first())

                                for (i in 0 until tracePoints.size - 1) {
                                    val p1 = tracePoints[i]
                                    val p2 = tracePoints[i + 1]
                                    val dist = MathUtils.haversineDistance(p1.first, p1.second, p2.first, p2.second)

                                    if (dist > 150.0) {
                                        if (currentSubPath.size >= 2) {
                                            subPaths.add(currentSubPath)
                                        }
                                        currentSubPath = mutableListOf()
                                    }
                                    currentSubPath.add(p2)
                                }
                                if (currentSubPath.size >= 2) {
                                    subPaths.add(currentSubPath)
                                }

                                for (path in subPaths) {
                                    val points = path.map { Point.fromLngLat(it.second, it.first) }
                                    val traceLine = LineString.fromLngLats(points)
                                    val traceFeature = Feature.fromGeometry(traceLine)
                                    traceFeature.addBooleanProperty("isDebugTrace", true)
                                    debugFeatures.add(traceFeature)
                                }
                            }
                            
                            if (debugOverlayEnabled) {
                                tracePoints.forEach { p ->
                                    val ptFeature = Feature.fromGeometry(Point.fromLngLat(p.second, p.first))
                                    ptFeature.addBooleanProperty("isDebugPoint", true)
                                    debugFeatures.add(ptFeature)
                                }
                            }
                        } catch (e: Exception) {
                            Log.e("HomeScreen", "Error decoding trace geometry", e)
                        }
                    }
                }
                
                val allFeatures = features + debugFeatures
                val featureCollection = FeatureCollection.fromFeatures(allFeatures)
                withContext(Dispatchers.Main) {
                    val source = style.getSourceAs<GeoJsonSource>("segments-source")
                    source?.setGeoJson(featureCollection)
                }
            }
        }
    }

    // Effect to trigger viewport query or initial region zoom on first launch
    LaunchedEffect(regions, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        
        // If initial camera setup has already been completed, NEVER move the camera automatically!
        if (viewModel.hasPerformedInitialZoom || viewModel.lastCameraPosition != null) {
            triggerViewportQuery(map, viewModel)
            return@LaunchedEffect
        }

        val latestRegion = regions.maxByOrNull { it.lastUpdatedTimestamp }
        if (latestRegion != null) {
            viewModel.hasPerformedInitialZoom = true
            val bboxParts = latestRegion.boundingBox.split(",")
            if (bboxParts.size == 4) {
                val minLat = bboxParts[0].toDoubleOrNull()
                val maxLat = bboxParts[1].toDoubleOrNull()
                val minLon = bboxParts[2].toDoubleOrNull()
                val maxLon = bboxParts[3].toDoubleOrNull()
                if (minLat != null && maxLat != null && minLon != null && maxLon != null) {
                    try {
                        val bounds = LatLngBounds.from(maxLat, maxLon, minLat, minLon)
                        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 50), 1000)
                    } catch (_: Exception) {
                        triggerViewportQuery(map, viewModel)
                    }
                } else {
                    triggerViewportQuery(map, viewModel)
                }
            } else {
                triggerViewportQuery(map, viewModel)
            }
        } else {
            triggerViewportQuery(map, viewModel)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("MopedTrack") },
                windowInsets = WindowInsets.statusBars,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                actions = {
                    IconButton(onClick = { menuExpanded = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                    }
                    DropdownMenu(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Manage Regions") },
                            onClick = {
                                menuExpanded = false
                                onManageRegionsClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Manage GPX") },
                            onClick = { 
                                menuExpanded = false 
                                onImportGpxClick()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("Route Planner") },
                            onClick = { menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("OSM Pins") },
                            onClick = { menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Stats") },
                            onClick = { menuExpanded = false }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { 
                                menuExpanded = false 
                                onSettingsClick()
                            }
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End
            ) {
                FloatingActionButton(
                    onClick = { showLayersSheet = true },
                    modifier = Modifier.padding(bottom = 16.dp),
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Icon(Icons.Default.Layers, contentDescription = "Map Layers")
                }

                FloatingActionButton(
                    onClick = {
                        if (locationPermissionState.status.isGranted) {
                            mapLibreMap?.let { map ->
                                activateLocationComponentSafely(map, context)
                                val locationComponent = map.locationComponent
                                if (locationComponent.isLocationComponentActivated) {
                                    val location = try {
                                        locationComponent.lastKnownLocation
                                    } catch (_: Exception) {
                                        null
                                    }
                                    if (location != null) {
                                        map.animateCamera(
                                            CameraUpdateFactory.newCameraPosition(
                                                CameraPosition.Builder()
                                                    .target(LatLng(location.latitude, location.longitude))
                                                    .zoom(16.0)
                                                    .build()
                                            ),
                                            1000
                                        )
                                    } else {
                                        locationComponent.cameraMode = CameraMode.TRACKING_GPS
                                        map.animateCamera(CameraUpdateFactory.zoomTo(16.0), 1000)
                                    }
                                }
                            }
                        } else {
                            locationPermissionState.launchPermissionRequest()
                        }
                    }
                ) {
                    Icon(Icons.Default.MyLocation, contentDescription = "Center on My Location")
                }
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            contentAlignment = Alignment.Center
        ) {
            MapLibreCompose(
                modifier = Modifier.fillMaxSize(),
                styleUrl = "https://tiles.openfreemap.org/styles/liberty",
                onMapReady = { map ->
                    map.getStyle { style ->
                        // Apply crisp, high-contrast text halo to all SymbolLayers for street name legibility
                        for (layer in style.layers) {
                            if (layer is SymbolLayer) {
                                layer.setProperties(
                                    textHaloColor(android.graphics.Color.WHITE),
                                    textHaloWidth(2.5f),
                                    textHaloBlur(0.5f)
                                )
                            }
                        }

                        if (style.getSource("segments-source") == null) {
                            val geoJsonSource = GeoJsonSource("segments-source")
                            style.addSource(geoJsonSource)
                        }

                        val firstSymbolLayer = style.layers.firstOrNull { layer ->
                            layer is SymbolLayer || layer.id.contains("label", ignoreCase = true) || layer.id.contains("symbol", ignoreCase = true)
                        }

                        if (style.getLayer("segments-casing-layer") == null) {
                            val casingLayer = LineLayer("segments-casing-layer", "segments-source")
                            casingLayer.setProperties(
                                lineCap("round"),
                                lineJoin("round"),
                                lineWidth(
                                    switchCase(
                                        eq(get("isDebugTrace"), literal(true)),
                                        literal(6f), // slightly thicker for trace outline
                                        eq(get("isCompleted"), literal(true)),
                                        literal(7f),
                                        literal(6f)
                                    )
                                ),
                                lineColor(
                                    color(android.graphics.Color.parseColor("#FFFFFF"))
                                ),
                                lineOpacity(0.9f)
                            )
                            if (firstSymbolLayer != null) {
                                style.addLayerBelow(casingLayer, firstSymbolLayer.id)
                            } else {
                                style.addLayer(casingLayer)
                            }
                        }

                        if (style.getLayer("segments-layer") == null) {
                            val lineLayer = LineLayer("segments-layer", "segments-source")
                            lineLayer.setProperties(
                                lineCap("round"),
                                lineJoin("round"),
                                lineWidth(
                                    switchCase(
                                        eq(get("isDebugTrace"), literal(true)),
                                        literal(3f), // thinner, crisp trace line
                                        eq(get("isCompleted"), literal(true)),
                                        literal(5f),
                                        literal(4f)
                                    )
                                ),
                                lineColor(
                                    switchCase(
                                        eq(get("isDebugTrace"), literal(true)),
                                        color(android.graphics.Color.parseColor("#2196F3")), // Blue trace
                                        eq(get("isCompleted"), literal(true)),
                                        color(android.graphics.Color.parseColor("#2E7D32")), // Green completed
                                        color(android.graphics.Color.parseColor("#D84315"))  // Red uncompleted
                                    )
                                ),
                                lineOpacity(
                                    switchCase(
                                        eq(get("isDebugTrace"), literal(true)),
                                        literal(1.0f),
                                        literal(0.9f)
                                    )
                                )
                            )
                            if (style.getLayer("segments-casing-layer") != null) {
                                style.addLayerAbove(lineLayer, "segments-casing-layer")
                            } else if (firstSymbolLayer != null) {
                                style.addLayerBelow(lineLayer, firstSymbolLayer.id)
                            } else {
                                style.addLayer(lineLayer)
                            }
                        }
                        
                        // Debug corridor fill layer (Dynamic geographic polygon corridor matching matchRadius setting)
                        if (style.getLayer("debug-corridor-fill-layer") == null) {
                            val fillLayer = FillLayer("debug-corridor-fill-layer", "segments-source")
                            fillLayer.setFilter(eq(get("isDebugCorridor"), literal(true)))
                            fillLayer.setProperties(
                                fillColor(color(android.graphics.Color.parseColor("#2196F3"))), // Blue corridor fill
                                fillOpacity(0.25f)
                            )
                            style.addLayer(fillLayer)
                        }

                        // Debug corridor outline layer
                        if (style.getLayer("debug-corridor-outline-layer") == null) {
                            val outlineLayer = LineLayer("debug-corridor-outline-layer", "segments-source")
                            outlineLayer.setFilter(eq(get("isDebugCorridor"), literal(true)))
                            outlineLayer.setProperties(
                                lineColor(color(android.graphics.Color.parseColor("#1976D2"))),
                                lineWidth(1.8f),
                                lineDasharray(arrayOf(2f, 2f))
                            )
                            style.addLayer(outlineLayer)
                        }

                        // Debug sub-points layer (Green = Covered within match radius, Red = Out of range)
                        if (style.getLayer("debug-subpoints-layer") == null) {
                            val subPointsLayer = org.maplibre.android.style.layers.CircleLayer("debug-subpoints-layer", "segments-source")
                            subPointsLayer.setFilter(eq(get("isDebugSubPoint"), literal(true)))
                            subPointsLayer.setProperties(
                                org.maplibre.android.style.layers.PropertyFactory.circleRadius(5.5f),
                                org.maplibre.android.style.layers.PropertyFactory.circleColor(
                                    switchCase(
                                        eq(get("isSubPointCovered"), literal(true)),
                                        color(android.graphics.Color.parseColor("#4CAF50")), // Green if covered
                                        color(android.graphics.Color.parseColor("#F44336"))  // Red if missed
                                    )
                                ),
                                org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor(android.graphics.Color.WHITE),
                                org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(1.2f)
                            )
                            style.addLayer(subPointsLayer)
                        }

                        // Optional debug points layer when overlay is enabled (GPX trace dots)
                        if (style.getLayer("debug-points-layer") == null) {
                            val pointsLayer = org.maplibre.android.style.layers.CircleLayer("debug-points-layer", "segments-source")
                            pointsLayer.setFilter(eq(get("isDebugPoint"), literal(true)))
                            pointsLayer.setProperties(
                                org.maplibre.android.style.layers.PropertyFactory.circleRadius(4f),
                                org.maplibre.android.style.layers.PropertyFactory.circleColor(android.graphics.Color.parseColor("#FFC107")), // Amber dots
                                org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor(android.graphics.Color.BLACK),
                                org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth(1f)
                            )
                            style.addLayer(pointsLayer)
                        }

                        // Restore camera if we had one
                        viewModel.lastCameraPosition?.let {
                            map.cameraPosition = it
                        }

                        // Set mapLibreMap only after style is ready
                        mapLibreMap = map
                    }
                    
                    map.addOnCameraIdleListener {
                        if (map.cameraPosition.zoom > 2.0) {
                            viewModel.lastCameraPosition = map.cameraPosition
                        }
                        triggerViewportQuery(map, viewModel)
                    }

                    // Trigger initial viewport query on map load
                    triggerViewportQuery(map, viewModel)

                    // Long tap on a road segment selects the segment
                    map.addOnMapLongClickListener { point ->
                        Log.d("HomeScreen", "Map long clicked at LatLng: (${point.latitude}, ${point.longitude})")
                        val pointF = map.projection.toScreenLocation(point)
                        Log.d("HomeScreen", "Projected to screen location: (${pointF.x}, ${pointF.y})")
                        
                        val hitTolerance = 50f
                        val touchBox = RectF(
                            pointF.x - hitTolerance,
                            pointF.y - hitTolerance,
                            pointF.x + hitTolerance,
                            pointF.y + hitTolerance
                        )
                        val features = map.queryRenderedFeatures(touchBox, "segments-layer")
                        Log.d("HomeScreen", "queryRenderedFeatures('segments-layer') found ${features.size} features on long click")

                        if (features.isNotEmpty()) {
                            val clickedFeature = features.firstOrNull { feat ->
                                val featId = feat.getStringProperty("segmentId")
                                    ?: feat.getStringProperty("id")
                                    ?: feat.id()
                                !featId.isNullOrEmpty()
                            } ?: features.first()

                            val id = clickedFeature.getStringProperty("segmentId")
                                ?: clickedFeature.getStringProperty("id")
                                ?: clickedFeature.id()

                            Log.d("HomeScreen", "Long-tapped feature segment ID: $id")
                            if (!id.isNullOrEmpty()) {
                                viewModel.selectSegment(id)
                                return@addOnMapLongClickListener true
                            } else {
                                Log.w("HomeScreen", "Long-clicked feature found but segment ID property was null/empty")
                            }
                        }
                        false
                    }

                    if (locationPermissionState.status.isGranted) {
                        activateLocationComponentSafely(map, context)
                    }
                }
            )
        }
    }
}
