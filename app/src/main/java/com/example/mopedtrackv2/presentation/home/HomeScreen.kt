package com.example.mopedtrackv2.presentation.home

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditLocationAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.mopedtrackv2.domain.engine.GpsPoint
import com.example.mopedtrackv2.domain.engine.SegmentMatcherEngine
import com.example.mopedtrackv2.domain.gpx.GpxMetadataFormatter
import com.example.mopedtrackv2.domain.model.OsmPinCategory
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.RoadSegment
import com.example.mopedtrackv2.domain.model.UnitSystem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.example.mopedtrackv2.presentation.importgpx.ImportGpxViewModel
import com.example.mopedtrackv2.presentation.importgpx.ManageGpxPanelContent
import com.example.mopedtrackv2.presentation.pins.OsmPinsPanelContent
import com.example.mopedtrackv2.presentation.pins.OsmPinsViewModel
import com.example.mopedtrackv2.presentation.regions.ManageRegionsPanelContent
import com.example.mopedtrackv2.presentation.regions.ManageRegionsViewModel
import com.example.mopedtrackv2.presentation.settings.SettingsPanelContent
import com.example.mopedtrackv2.presentation.settings.SettingsViewModel
import com.example.mopedtrackv2.presentation.stats.StatsPanelContent
import com.example.mopedtrackv2.presentation.stats.StatsViewModel
import com.example.mopedtrackv2.utils.MathUtils
import com.example.mopedtrackv2.utils.PolylineUtils
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.modes.CameraMode
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

internal fun shouldRenderRoadSegment(
    segment: RoadSegment,
    showCompletedSegments: Boolean,
    showUncompletedSegments: Boolean,
    hiddenRegionIds: Set<String>
): Boolean {
    if (segment.regionId in hiddenRegionIds) return false
    if (segment.isCompleted && !showCompletedSegments) return false
    if (!segment.isCompleted && !showUncompletedSegments) return false

    // Do NOT render uncompleted driveways, service roads, or unnamed roads in red
    if (!segment.isCompleted && (segment.highwayType == "service" || segment.name == "Unnamed Road")) {
        return false
    }
    return true
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

private enum class HamburgerPanelSection(val label: String) {
    MANAGE_GPX("Traces"),
    MANAGE_REGIONS("Manage Regions"),
    ROUTE_PLANNER("Route Planner"),
    OSM_PINS("OSM Pins"),
    STATS("Stats"),
    SETTINGS("Settings")
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalPermissionsApi::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    importGpxViewModel: ImportGpxViewModel,
    manageRegionsViewModel: ManageRegionsViewModel,
    settingsViewModel: SettingsViewModel,
    osmPinsViewModel: OsmPinsViewModel,
    statsViewModel: StatsViewModel
) {
    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var selectorExpanded by remember { mutableStateOf(false) }
    var selectedPanelSection by remember { mutableStateOf(HamburgerPanelSection.MANAGE_GPX) }
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val context = LocalContext.current
    val locationPermissionState = rememberPermissionState(Manifest.permission.ACCESS_FINE_LOCATION)
    var fineLocationGrantedByLauncher by remember { mutableStateOf(false) }
    val isFineLocationGranted = locationPermissionState.status.isGranted ||
        fineLocationGrantedByLauncher ||
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    val ridePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        fineLocationGrantedByLauncher = fineGranted
        if (fineGranted) {
            viewModel.startRide()
        }
    }

    val onStartRideClick: () -> Unit = {
        val hasFineLocation = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasPostNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (hasFineLocation && hasPostNotifications) {
            fineLocationGrantedByLauncher = true
            viewModel.startRide()
        } else {
            val permissionsToRequest = buildList {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
            ridePermissionsLauncher.launch(permissionsToRequest)
        }
    }

    val roadSegments by viewModel.roadSegments.collectAsState()
    val selectedSegment by viewModel.selectedSegment.collectAsState()
    val regions by viewModel.regions.collectAsState()
    val isCameraRestored by viewModel.isCameraRestored.collectAsState()
    val recordingState by viewModel.recordingState.collectAsState()
    val activeTrace by viewModel.activeTrace.collectAsState()
    val showStopConfirmationDialog by viewModel.showStopConfirmationDialog.collectAsState()
    val pinPlacement by viewModel.pinPlacement.collectAsState()
    val pinDraft by viewModel.pinDraft.collectAsState()
    val unresolvedPins by viewModel.unresolvedPins.collectAsState()
    val selectedPin by viewModel.selectedPin.collectAsState()
    val currentPinPlacement by rememberUpdatedState(pinPlacement)
    var isPinPanning by remember { mutableStateOf(false) }

    BackHandler(enabled = pinPlacement != null) {
        viewModel.cancelPinPlacement()
    }
    
    val debugOverlayEnabled by viewModel.debugOverlayEnabled.collectAsState(initial = false)
    val debugBboxSegmentIds by viewModel.debugBboxSegmentIds.collectAsState()
    val matchRadius by viewModel.matchRadius.collectAsState(initial = 15)
    val unitSystem by viewModel.unitSystem.collectAsState(initial = UnitSystem.IMPERIAL)
    val showCompletedSegments by viewModel.showCompletedSegments.collectAsState(initial = true)
    val showUncompletedSegments by viewModel.showUncompletedSegments.collectAsState(initial = true)
    val showGpxTraces by viewModel.showGpxTraces.collectAsState(initial = true)
    val hiddenRegionIds by viewModel.hiddenRegionIds.collectAsState(initial = emptySet())
    val visibleGpxTraces by viewModel.visibleGpxTraces.collectAsState(initial = emptyList())
    val allImportedGpxFiles by viewModel.allImportedGpxFiles.collectAsState(initial = emptyList())
    val lastDebugTrace by viewModel.lastDebugTrace.collectAsState(initial = null)

    val sheetState = rememberModalBottomSheetState()
    val pinSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val pinDetailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showLayersSheet by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(viewModel) {
        launch(start = CoroutineStart.UNDISPATCHED) {
            viewModel.snackbarMessages.collect { message ->
                snackbarHostState.showSnackbar(message)
            }
        }
        viewModel.checkRecoveredRide()
    }

    LaunchedEffect(importGpxViewModel) {
        importGpxViewModel.toastMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    if (showStopConfirmationDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissStopRideDialog() },
            title = { Text("Finish Ride?") },
            text = { Text("Stop recording and save this ride to GPX?") },
            confirmButton = {
                Button(onClick = { viewModel.confirmSaveRide() }) {
                    Text("Save Ride")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissStopRideDialog() }) {
                    Text("Cancel")
                }
            }
        )
    }

    pinDraft?.let { draft ->
        ModalBottomSheet(
            onDismissRequest = { viewModel.dismissDropPinSheet() },
            sheetState = pinSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .imePadding()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Drop OSM Pin",
                    style = MaterialTheme.typography.titleLarge
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = String.format(Locale.US, "%.5f, %.5f", draft.lat, draft.lon),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    TextButton(
                        onClick = {
                            val lat = draft.lat
                            val lon = draft.lon
                            viewModel.adjustPinLocation()
                            mapLibreMap?.animateCamera(
                                CameraUpdateFactory.newLatLng(LatLng(lat, lon)),
                                300
                            )
                        }
                    ) {
                        Icon(
                            imageVector = Icons.Default.EditLocationAlt,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Move Pin")
                    }
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    OsmPinCategory.entries.forEach { category ->
                        val selected = draft.selectedCategory == category
                        FilterChip(
                            selected = selected,
                            onClick = { viewModel.selectPinCategory(category) },
                            label = { Text(category.label) },
                            leadingIcon = if (selected) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null
                                    )
                                }
                            } else null
                        )
                    }
                }
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { viewModel.updatePinNote(it) },
                    label = { Text("Note (optional)") },
                    placeholder = { Text("Add details for OpenStreetMap...") },
                    singleLine = false,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = { viewModel.saveOsmPin() },
                    enabled = draft.selectedCategory != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Save Pin")
                }
            }
        }
    }

    selectedPin?.let { pin ->
        val categoryLabel = remember(pin.category) {
            OsmPinCategory.fromCategoryName(pin.category)?.label ?: pin.category
        }
        val sdf = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
        val timestampText = remember(pin.createdAt) { sdf.format(Date(pin.createdAt)) }

        ModalBottomSheet(
            onDismissRequest = { viewModel.clearSelectedPin() },
            sheetState = pinDetailSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = categoryLabel,
                    style = MaterialTheme.typography.titleLarge
                )
                Text(
                    text = String.format(Locale.US, "%.5f, %.5f", pin.lat, pin.lon),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "Created: $timestampText",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (pin.note.isNotBlank()) {
                    Text(
                        text = pin.note,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = { viewModel.deleteSelectedPin() },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Delete")
                    }
                    Button(
                        onClick = { viewModel.resolveSelectedPin() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Mark Resolved")
                    }
                }
            }
        }
    }
    
    if (showLayersSheet) {
        ModalBottomSheet(
            onDismissRequest = { showLayersSheet = false },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
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
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Regions",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f)
                    )
                    if (regions.size > 1) {
                        val anyHidden = regions.any { it.regionId in hiddenRegionIds }
                        TextButton(onClick = { viewModel.setAllRegionsVisibility(anyHidden) }) {
                            Text(if (anyHidden) "Show All" else "Hide All")
                        }
                    }
                }
                if (regions.isEmpty()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "No regions downloaded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val sortedRegions = remember(regions) {
                        regions.sortedBy { it.name.lowercase() }
                    }
                    sortedRegions.forEach { region ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = region.name,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(end = 12.dp)
                            )
                            Switch(
                                checked = region.regionId !in hiddenRegionIds,
                                onCheckedChange = { isVisible ->
                                    viewModel.toggleRegionVisibility(region.regionId, isVisible)
                                }
                            )
                        }
                    }
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
                    text = "Length: ${GpxMetadataFormatter.formatDistance(selectedSegment?.lengthMeters ?: 0.0, unitSystem)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                val isBboxEnabled = selectedSegment?.let { debugBboxSegmentIds.contains(it.segmentId) } ?: false
                if (debugOverlayEnabled) {
                    val coverageInfo = remember(selectedSegment, visibleGpxTraces, allImportedGpxFiles, lastDebugTrace, matchRadius) {
                        val segment = selectedSegment ?: return@remember null
                        val decodedPairs = try { PolylineUtils.decode(segment.geometryEncoded) } catch (_: Exception) { emptyList() }
                        if (decodedPairs.size < 2) return@remember null
                        val segGpsPoints = decodedPairs.map { GpsPoint(it.first, it.second) }
                        val interpolatedSubPoints = SegmentMatcherEngine.interpolateSegmentWithBearing(segGpsPoints, 5.0)
                        if (interpolatedSubPoints.isEmpty()) return@remember null

                        val radiusMeters = matchRadius.toDouble()
                        val padLat = radiusMeters / 111000.0
                        val latRad = Math.toRadians((segment.minLat + segment.maxLat) / 2.0)
                        val cosLat = if (kotlin.math.cos(latRad) > 0.1) kotlin.math.cos(latRad) else 1.0
                        val padLon = radiusMeters / (111000.0 * cosLat)

                        val segMinLat = segment.minLat - padLat
                        val segMaxLat = segment.maxLat + padLat
                        val segMinLon = segment.minLon - padLon
                        val segMaxLon = segment.maxLon + padLon

                        val allTraceSegments = mutableListOf<com.example.mopedtrackv2.domain.engine.TraceSegment>()
                        val allTracesToEvaluate = (visibleGpxTraces + allImportedGpxFiles).distinctBy { it.fileHash }
                        allTracesToEvaluate.forEach { trace ->
                            try {
                                val pts = PolylineUtils.decode(trace.encodedTrace).map { GpsPoint(it.first, it.second) }
                                if (pts.size >= 2) {
                                    val spatialIndex = com.example.mopedtrackv2.domain.engine.TraceSpatialIndex(pts)
                                    allTraceSegments.addAll(spatialIndex.getCandidateTraceSegments(segMinLat, segMaxLat, segMinLon, segMaxLon))
                                }
                            } catch (_: Exception) {}
                        }
                        lastDebugTrace?.let { trace ->
                            try {
                                val pts = PolylineUtils.decode(trace.pointsEncoded).map { GpsPoint(it.first, it.second) }
                                if (pts.size >= 2) {
                                    val spatialIndex = com.example.mopedtrackv2.domain.engine.TraceSpatialIndex(pts)
                                    allTraceSegments.addAll(spatialIndex.getCandidateTraceSegments(segMinLat, segMaxLat, segMinLon, segMaxLon))
                                }
                            } catch (_: Exception) {}
                        }

                        var coveredCount = 0
                        interpolatedSubPoints.forEach { subPtObj ->
                            val isCovered = SegmentMatcherEngine.isSubPointCovered(
                                subPt = subPtObj.point,
                                subPtBearing = subPtObj.bearing,
                                traceSegments = allTraceSegments,
                                matchRadiusMeters = radiusMeters
                            )
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
                        val radiusLabel = GpxMetadataFormatter.formatShortDistanceMeters(matchRadius.toDouble(), unitSystem)
                        Text(
                            text = "$covered of $total points covered within $radiusLabel match radius",
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
    LaunchedEffect(isFineLocationGranted, mapLibreMap, isCameraRestored) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isCameraRestored) return@LaunchedEffect
        if (isFineLocationGranted) {
            configureMapLocationComponent(map, context, recordingState)
            
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
                    viewModel.saveCameraPosition(targetPos)
                    map.animateCamera(CameraUpdateFactory.newCameraPosition(targetPos), 1000)
                }
            }
        }
    }

    // Effect to update map source when segments change
    LaunchedEffect(roadSegments, debugBboxSegmentIds, debugOverlayEnabled, matchRadius, visibleGpxTraces, allImportedGpxFiles, lastDebugTrace, showCompletedSegments, showUncompletedSegments, showGpxTraces, hiddenRegionIds, mapLibreMap) {
        val map = mapLibreMap ?: return@LaunchedEffect
        map.style?.let { style ->
            withContext(Dispatchers.Default) {
                val features = roadSegments.mapNotNull { segment ->
                    if (
                        !shouldRenderRoadSegment(
                            segment = segment,
                            showCompletedSegments = showCompletedSegments,
                            showUncompletedSegments = showUncompletedSegments,
                            hiddenRegionIds = hiddenRegionIds
                        )
                    ) {
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
                        it.regionId !in hiddenRegionIds &&
                            (debugBboxSegmentIds.contains(it.segmentId) || (it.pairedSegmentId != null && debugBboxSegmentIds.contains(it.pairedSegmentId)))
                    }

                    if (targetSegments.isNotEmpty()) {
                        val radiusMeters = matchRadius.toDouble()
                        val padLat = radiusMeters / 111000.0
                        val targetMinLat = targetSegments.minOf { it.minLat } - padLat
                        val targetMaxLat = targetSegments.maxOf { it.maxLat } + padLat
                        val latRad = Math.toRadians((targetMinLat + targetMaxLat) / 2.0)
                        val cosLat = if (kotlin.math.cos(latRad) > 0.1) kotlin.math.cos(latRad) else 1.0
                        val padLon = radiusMeters / (111000.0 * cosLat)
                        val targetMinLon = targetSegments.minOf { it.minLon } - padLon
                        val targetMaxLon = targetSegments.maxOf { it.maxLon } + padLon

                        // Pre-decode ALL trace line segments for target bounding box
                        val allTraceSegments = mutableListOf<com.example.mopedtrackv2.domain.engine.TraceSegment>()
                        val allTracesToEvaluate = (visibleGpxTraces + allImportedGpxFiles).distinctBy { it.fileHash }
                        allTracesToEvaluate.forEach { trace ->
                            try {
                                val pts = PolylineUtils.decode(trace.encodedTrace).map { GpsPoint(it.first, it.second) }
                                if (pts.size >= 2) {
                                    val spatialIndex = com.example.mopedtrackv2.domain.engine.TraceSpatialIndex(pts)
                                    allTraceSegments.addAll(spatialIndex.getCandidateTraceSegments(targetMinLat, targetMaxLat, targetMinLon, targetMaxLon))
                                }
                            } catch (_: Exception) {}
                        }
                        lastDebugTrace?.let { trace ->
                            try {
                                val pts = PolylineUtils.decode(trace.pointsEncoded).map { GpsPoint(it.first, it.second) }
                                if (pts.size >= 2) {
                                    val spatialIndex = com.example.mopedtrackv2.domain.engine.TraceSpatialIndex(pts)
                                    allTraceSegments.addAll(spatialIndex.getCandidateTraceSegments(targetMinLat, targetMaxLat, targetMinLon, targetMaxLon))
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
                                val interpolatedSubPoints = SegmentMatcherEngine.interpolateSegmentWithBearing(segGpsPoints, 5.0)

                                val coveredFlags = interpolatedSubPoints.map { subPtObj ->
                                    segment.isCompleted || SegmentMatcherEngine.isSubPointCovered(
                                        subPt = subPtObj.point,
                                        subPtBearing = subPtObj.bearing,
                                        traceSegments = allTraceSegments,
                                        matchRadiusMeters = radiusMeters
                                    )
                                }

                                // Sub-point circles along the road
                                interpolatedSubPoints.forEachIndexed { idx, subPtObj ->
                                    val isCovered = coveredFlags[idx]
                                    val subPtFeature = Feature.fromGeometry(Point.fromLngLat(subPtObj.point.lon, subPtObj.point.lat))
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
    LaunchedEffect(regions, mapLibreMap, isCameraRestored) {
        val map = mapLibreMap ?: return@LaunchedEffect
        if (!isCameraRestored) return@LaunchedEffect
        
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

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen && selectedPanelSection != HamburgerPanelSection.SETTINGS,
        drawerContent = {
            ModalDrawerSheet(
                drawerState = drawerState,
                modifier = Modifier.fillMaxWidth(0.85f)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        Surface(
                            onClick = { selectorExpanded = true },
                            shape = MaterialTheme.shapes.medium,
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = selectedPanelSection.label,
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f)
                                )
                                Icon(
                                    imageVector = Icons.Default.ArrowDropDown,
                                    contentDescription = "Select section"
                                )
                            }
                        }

                        DropdownMenu(
                            expanded = selectorExpanded,
                            onDismissRequest = { selectorExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Traces") },
                                trailingIcon = if (selectedPanelSection == HamburgerPanelSection.MANAGE_GPX) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    selectorExpanded = false
                                    selectedPanelSection = HamburgerPanelSection.MANAGE_GPX
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Manage Regions") },
                                trailingIcon = if (selectedPanelSection == HamburgerPanelSection.MANAGE_REGIONS) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    selectorExpanded = false
                                    selectedPanelSection = HamburgerPanelSection.MANAGE_REGIONS
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Route Planner") },
                                trailingIcon = if (selectedPanelSection == HamburgerPanelSection.ROUTE_PLANNER) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    selectorExpanded = false
                                    selectedPanelSection = HamburgerPanelSection.ROUTE_PLANNER
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("OSM Pins") },
                                trailingIcon = if (selectedPanelSection == HamburgerPanelSection.OSM_PINS) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    selectorExpanded = false
                                    selectedPanelSection = HamburgerPanelSection.OSM_PINS
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Stats") },
                                trailingIcon = if (selectedPanelSection == HamburgerPanelSection.STATS) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    selectorExpanded = false
                                    selectedPanelSection = HamburgerPanelSection.STATS
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Settings") },
                                trailingIcon = if (selectedPanelSection == HamburgerPanelSection.SETTINGS) {
                                    { Icon(Icons.Default.Check, contentDescription = null) }
                                } else null,
                                onClick = {
                                    selectorExpanded = false
                                    selectedPanelSection = HamburgerPanelSection.SETTINGS
                                }
                            )
                        }
                    }

                    IconButton(onClick = { scope.launch { drawerState.close() } }) {
                        Icon(Icons.Default.Close, contentDescription = "Close Menu")
                    }
                }
                HorizontalDivider()
                when (selectedPanelSection) {
                    HamburgerPanelSection.MANAGE_GPX -> {
                        ManageGpxPanelContent(
                            viewModel = importGpxViewModel,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HamburgerPanelSection.MANAGE_REGIONS -> {
                        ManageRegionsPanelContent(
                            viewModel = manageRegionsViewModel,
                            modifier = Modifier
                                .weight(1f)
                                .imePadding()
                        )
                    }
                    HamburgerPanelSection.SETTINGS -> {
                        SettingsPanelContent(
                            viewModel = settingsViewModel,
                            importGpxViewModel = importGpxViewModel,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HamburgerPanelSection.OSM_PINS -> {
                        OsmPinsPanelContent(
                            viewModel = osmPinsViewModel,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HamburgerPanelSection.STATS -> {
                        StatsPanelContent(
                            viewModel = statsViewModel,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    HamburgerPanelSection.ROUTE_PLANNER -> {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${selectedPanelSection.label} coming soon",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    ) {
    val centerMapOnMyLocation: () -> Unit = {
        if (isFineLocationGranted) {
            mapLibreMap?.let { map ->
                configureMapLocationComponent(map, context, recordingState)
                val locationComponent = map.locationComponent
                if (locationComponent.isLocationComponentActivated) {
                    val location = try {
                        locationComponent.lastKnownLocation
                    } catch (_: Exception) {
                        null
                    }
                    val latestTrackPoint = activeTrace.lastOrNull()
                    val targetLatLng = when {
                        latestTrackPoint != null && recordingState != RideRecordingState.IDLE ->
                            LatLng(latestTrackPoint.lat, latestTrackPoint.lon)
                        location != null ->
                            LatLng(location.latitude, location.longitude)
                        else -> null
                    }
                    if (targetLatLng != null) {
                        map.animateCamera(
                            CameraUpdateFactory.newCameraPosition(
                                CameraPosition.Builder()
                                    .target(targetLatLng)
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

        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        floatingActionButton = {
            if (pinPlacement == null) {
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    FloatingActionButton(
                        onClick = {
                            val gpsLocation = if (isFineLocationGranted) {
                                try {
                                    mapLibreMap?.locationComponent?.let { component ->
                                        if (component.isLocationComponentActivated) {
                                            component.lastKnownLocation
                                        } else {
                                            null
                                        }
                                    }
                                } catch (_: Exception) {
                                    null
                                }
                            } else {
                                null
                            }
                            val activeCameraTarget = mapLibreMap?.cameraPosition
                                ?.takeIf { it.zoom > 2.0 }
                                ?.target
                                ?: viewModel.lastCameraPosition?.target
                            viewModel.startPinPlacement(
                                cameraLat = activeCameraTarget?.latitude,
                                cameraLon = activeCameraTarget?.longitude,
                                gpsLat = gpsLocation?.latitude,
                                gpsLon = gpsLocation?.longitude
                            )
                        },
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                        contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                    ) {
                        Icon(Icons.Default.AddLocationAlt, contentDescription = "Drop OSM Pin")
                    }

                    FloatingActionButton(
                        onClick = { showLayersSheet = true },
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = "Map Layers")
                    }

                    FloatingActionButton(
                        onClick = centerMapOnMyLocation
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Center on My Location")
                    }

                    when (recordingState) {
                        RideRecordingState.IDLE -> {
                            FloatingActionButton(
                                onClick = onStartRideClick,
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Start Ride")
                            }
                        }
                        RideRecordingState.RECORDING -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FloatingActionButton(
                                    onClick = { viewModel.pauseRide() },
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                                ) {
                                    Icon(Icons.Default.Pause, contentDescription = "Pause Ride")
                                }
                                FloatingActionButton(
                                    onClick = { viewModel.requestStopRide() },
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop Ride")
                                }
                            }
                        }
                        RideRecordingState.PAUSED -> {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FloatingActionButton(
                                    onClick = { viewModel.resumeRide() },
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary
                                ) {
                                    Icon(Icons.Default.PlayArrow, contentDescription = "Resume Ride")
                                }
                                FloatingActionButton(
                                    onClick = { viewModel.requestStopRide() },
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                                ) {
                                    Icon(Icons.Default.Stop, contentDescription = "Stop Ride")
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { paddingValues ->
        val density = LocalDensity.current
        val layoutDirection = LocalLayoutDirection.current
        val compassTopMarginPx = with(density) { (paddingValues.calculateTopPadding() + 64.dp).roundToPx() }
        val compassLeftMarginPx = with(density) {
            (paddingValues.calculateLeftPadding(layoutDirection) + 16.dp).roundToPx()
        }
        val compassRightMarginPx = with(density) {
            (paddingValues.calculateRightPadding(layoutDirection) + 16.dp).roundToPx()
        }

        LaunchedEffect(mapLibreMap, compassTopMarginPx, compassLeftMarginPx, compassRightMarginPx) {
            mapLibreMap?.uiSettings?.setCompassMargins(
                compassLeftMarginPx,
                compassTopMarginPx,
                compassRightMarginPx,
                0
            )
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = paddingValues.calculateBottomPadding()),
                contentAlignment = Alignment.Center
            ) {
                MapLibreCompose(
                    modifier = Modifier.fillMaxSize(),
                    styleUrl = "https://tiles.openfreemap.org/styles/liberty",
                    recordingState = recordingState,
                    activeTrace = activeTrace,
                    unresolvedPins = unresolvedPins,
                    isLocationPermissionGranted = isFineLocationGranted,
                onMapReady = { map ->
                    map.uiSettings.setCompassMargins(
                        compassLeftMarginPx,
                        compassTopMarginPx,
                        compassRightMarginPx,
                        0
                    )
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
                    
                    map.addOnCameraMoveStartedListener {
                        if (currentPinPlacement != null) {
                            isPinPanning = true
                        }
                    }

                    map.addOnCameraMoveListener {
                        if (currentPinPlacement != null) {
                            map.cameraPosition.target?.let { target ->
                                viewModel.updatePinPlacementCoordinates(
                                    target.latitude,
                                    target.longitude
                                )
                            }
                        }
                    }

                    map.addOnCameraIdleListener {
                        isPinPanning = false
                        if (currentPinPlacement != null) {
                            map.cameraPosition.target?.let { target ->
                                viewModel.updatePinPlacementCoordinates(
                                    target.latitude,
                                    target.longitude
                                )
                            }
                        }
                        if (map.cameraPosition.zoom > 2.0) {
                            viewModel.saveCameraPosition(map.cameraPosition)
                        }
                        triggerViewportQuery(map, viewModel)
                    }

                    // Trigger initial viewport query on map load
                    triggerViewportQuery(map, viewModel)

                    // Tap on map: either center pin in placement mode or open an existing OSM pin marker
                    map.addOnMapClickListener { point ->
                        if (currentPinPlacement != null) {
                            map.animateCamera(
                                CameraUpdateFactory.newLatLng(point),
                                300
                            )
                            return@addOnMapClickListener true
                        }
                        val pointF = map.projection.toScreenLocation(point)
                        val hitTolerance = 48f
                        val touchBox = RectF(
                            pointF.x - hitTolerance,
                            pointF.y - hitTolerance,
                            pointF.x + hitTolerance,
                            pointF.y + hitTolerance
                        )
                        val pinFeatures = map.queryRenderedFeatures(touchBox, OSM_PINS_LAYER_ID)
                        val clickedPinId = pinFeatures.firstOrNull()?.let { feat ->
                            feat.getStringProperty("pinId") ?: feat.id()
                        }
                        if (!clickedPinId.isNullOrEmpty()) {
                            viewModel.selectPin(clickedPinId)
                            true
                        } else {
                            false
                        }
                    }

                    // Long tap on a road segment selects the segment
                    map.addOnMapLongClickListener { point ->
                        if (currentPinPlacement != null) {
                            return@addOnMapLongClickListener false
                        }
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

                    if (isFineLocationGranted) {
                        configureMapLocationComponent(map, context, recordingState)
                    }
                }
            )

                if (pinPlacement != null) {
                    PinDropCrosshairOverlay(
                        isPanning = isPinPanning,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }
            }

            if (pinPlacement == null) {
                SmallFloatingActionButton(
                    onClick = { scope.launch { drawerState.open() } },
                    shape = CircleShape,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(
                            top = paddingValues.calculateTopPadding() + 8.dp,
                            start = paddingValues.calculateStartPadding(layoutDirection) + 16.dp
                        )
                ) {
                    Icon(Icons.Default.Menu, contentDescription = "Menu")
                }
            } else {
                val placement = pinPlacement!!
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    tonalElevation = 6.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(
                            top = paddingValues.calculateTopPadding() + 8.dp,
                            start = 16.dp,
                            end = 16.dp
                        )
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Move map or tap to place pin",
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = String.format(Locale.US, "%.5f, %.5f", placement.lat, placement.lon),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            bottom = paddingValues.calculateBottomPadding() + 16.dp
                        ),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    SmallFloatingActionButton(
                        onClick = centerMapOnMyLocation,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface
                    ) {
                        Icon(Icons.Default.MyLocation, contentDescription = "Center on My Location")
                    }

                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                        tonalElevation = 6.dp,
                        shadowElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedButton(
                                onClick = { viewModel.cancelPinPlacement() },
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("Cancel")
                            }
                            Button(
                                onClick = {
                                    val target = mapLibreMap?.cameraPosition?.target
                                    viewModel.confirmPinPlacement(
                                        lat = target?.latitude,
                                        lon = target?.longitude
                                    )
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Place Pin")
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun PinDropCrosshairOverlay(
    isPanning: Boolean,
    modifier: Modifier = Modifier
) {
    val pinLift by animateDpAsState(
        targetValue = if (isPanning) (-10).dp else 0.dp,
        label = "pinLift"
    )
    val pinColor = Color(0xFF7C4DFF)
    val pinWidth = 36.dp
    val pinHeight = 46.dp

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        // Target halo & precision center dot at the exact coordinate under the pin tip
        Canvas(modifier = Modifier.size(24.dp)) {
            val center = Offset(size.width / 2f, size.height / 2f)
            drawCircle(
                color = pinColor.copy(alpha = if (isPanning) 0.30f else 0.20f),
                radius = if (isPanning) 10.dp.toPx() else 8.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color.Black.copy(alpha = if (isPanning) 0.25f else 0.15f),
                radius = 4.5.dp.toPx(),
                center = center
            )
            drawCircle(
                color = pinColor,
                radius = 3.5.dp.toPx(),
                center = center
            )
            drawCircle(
                color = Color.White,
                radius = 3.5.dp.toPx(),
                center = center,
                style = Stroke(width = 1.5.dp.toPx())
            )
        }

        // Teardrop pin whose bottom tip rests on the exact center dot (and lifts slightly while panning)
        Canvas(
            modifier = Modifier
                .offset { IntOffset(0, (-(pinHeight / 2) + pinLift).roundToPx()) }
                .size(width = pinWidth, height = pinHeight)
        ) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val r = w * 0.40f
            val cy = r + 2.dp.toPx()
            val tipY = h

            val startAngleDeg = 145f
            val sweepAngleDeg = 250f
            val startAngleRad = Math.toRadians(startAngleDeg.toDouble())
            val startX = cx + (r * kotlin.math.cos(startAngleRad)).toFloat()
            val startY = cy + (r * kotlin.math.sin(startAngleRad)).toFloat()

            val pinPath = Path().apply {
                arcTo(
                    rect = Rect(cx - r, cy - r, cx + r, cy + r),
                    startAngleDegrees = startAngleDeg,
                    sweepAngleDegrees = sweepAngleDeg,
                    forceMoveTo = false
                )
                quadraticTo(
                    cx + r * 0.85f,
                    cy + (tipY - cy) * 0.52f,
                    cx,
                    tipY
                )
                quadraticTo(
                    cx - r * 0.85f,
                    cy + (tipY - cy) * 0.52f,
                    startX,
                    startY
                )
                close()
            }

            drawPath(
                path = pinPath,
                color = pinColor
            )
            drawPath(
                path = pinPath,
                color = Color.White,
                style = Stroke(width = 2.5.dp.toPx(), join = StrokeJoin.Round)
            )
            drawCircle(
                color = Color.White,
                radius = r * 0.38f,
                center = Offset(cx, cy)
            )
        }
    }
}
