package com.example.mopedtrackv2.presentation.home

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.TrackPoint
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.engine.LocationEngineRequest
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleOpacity
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineOpacity
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

const val ACTIVE_RIDE_TRACE_SOURCE_ID = "active-ride-trace-source"
const val ACTIVE_RIDE_TRACE_LAYER_ID = "active-ride-trace-layer"
const val OSM_PINS_SOURCE_ID = "osm-pins-source"
const val OSM_PINS_LAYER_ID = "osm-pins-layer"

internal const val ACTIVE_RIDE_TRACE_COLOR_HEX = "#00E5FF"
internal const val ACTIVE_RIDE_TRACE_WIDTH_DP = 4.5f
internal const val OSM_PIN_COLOR_HEX = "#7C4DFF"
internal const val OSM_PIN_RADIUS_DP = 9.0f
internal const val OSM_PIN_STROKE_WIDTH_DP = 2.5f

internal const val PASSIVE_LOCATION_INTERVAL_MS = 10_000L
internal const val PASSIVE_LOCATION_FASTEST_INTERVAL_MS = 5_000L
internal const val PASSIVE_LOCATION_DISPLACEMENT_METERS = 10f
internal const val RECORDING_MAP_LOCATION_INTERVAL_MS = 1_000L
internal const val RECORDING_MAP_LOCATION_FASTEST_INTERVAL_MS = 500L

internal fun buildMapLocationEngineRequest(recordingState: RideRecordingState): LocationEngineRequest {
    return if (recordingState == RideRecordingState.RECORDING) {
        LocationEngineRequest.Builder(RECORDING_MAP_LOCATION_INTERVAL_MS)
            .setFastestInterval(RECORDING_MAP_LOCATION_FASTEST_INTERVAL_MS)
            .setPriority(LocationEngineRequest.PRIORITY_HIGH_ACCURACY)
            .build()
    } else {
        LocationEngineRequest.Builder(PASSIVE_LOCATION_INTERVAL_MS)
            .setFastestInterval(PASSIVE_LOCATION_FASTEST_INTERVAL_MS)
            .setDisplacement(PASSIVE_LOCATION_DISPLACEMENT_METERS)
            .setPriority(LocationEngineRequest.PRIORITY_BALANCED_POWER_ACCURACY)
            .build()
    }
}

@SuppressLint("MissingPermission")
fun configureMapLocationComponent(
    map: MapLibreMap,
    context: Context,
    recordingState: RideRecordingState
) {
    val style = map.style ?: return
    val locationComponent = map.locationComponent
    val engineRequest = buildMapLocationEngineRequest(recordingState)

    try {
        if (!locationComponent.isLocationComponentActivated) {
            val options = LocationComponentActivationOptions.builder(context, style)
                .useDefaultLocationEngine(true)
                .locationEngineRequest(engineRequest)
                .build()
            locationComponent.activateLocationComponent(options)
            locationComponent.isLocationComponentEnabled = true
            locationComponent.cameraMode = CameraMode.NONE
            locationComponent.renderMode = RenderMode.COMPASS
        } else {
            locationComponent.isLocationComponentEnabled = true
            locationComponent.locationEngineRequest = engineRequest
        }

        // Seed last-known location passively when not actively recording so the puck appears
        // immediately without waking high-accuracy GPS hardware.
        if (recordingState != RideRecordingState.RECORDING && locationComponent.lastKnownLocation == null) {
            LocationServices.getFusedLocationProviderClient(context.applicationContext)
                .lastLocation
                .addOnSuccessListener { lastLocation ->
                    if (lastLocation != null && locationComponent.isLocationComponentActivated) {
                        try {
                            locationComponent.forceLocationUpdate(lastLocation)
                        } catch (_: Exception) {
                        }
                    }
                }
        }
    } catch (_: SecurityException) {
        // Permission not granted or revoked
    } catch (_: Exception) {
        // Safety fallback
    }
}

internal fun ensureActiveRideTraceLayer(style: Style) {
    if (style.getSource(ACTIVE_RIDE_TRACE_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(ACTIVE_RIDE_TRACE_SOURCE_ID))
    }

    if (style.getLayer(ACTIVE_RIDE_TRACE_LAYER_ID) == null) {
        val activeTraceLayer = LineLayer(ACTIVE_RIDE_TRACE_LAYER_ID, ACTIVE_RIDE_TRACE_SOURCE_ID).apply {
            setProperties(
                lineCap(Property.LINE_CAP_ROUND),
                lineJoin(Property.LINE_JOIN_ROUND),
                lineColor(Color.parseColor(ACTIVE_RIDE_TRACE_COLOR_HEX)),
                lineWidth(ACTIVE_RIDE_TRACE_WIDTH_DP),
                lineOpacity(1.0f)
            )
        }

        val firstSymbolLayer = style.layers.firstOrNull { layer ->
            layer is SymbolLayer ||
                layer.id.contains("label", ignoreCase = true) ||
                layer.id.contains("symbol", ignoreCase = true)
        }

        when {
            style.getLayer("segments-layer") != null -> {
                style.addLayerAbove(activeTraceLayer, "segments-layer")
            }
            firstSymbolLayer != null -> {
                style.addLayerBelow(activeTraceLayer, firstSymbolLayer.id)
            }
            else -> {
                style.addLayer(activeTraceLayer)
            }
        }
    }
}

internal fun buildOsmPinsFeatureCollection(pins: List<OsmPinEntity>): FeatureCollection {
    val features = pins.mapNotNull { pin ->
        if (pin.isResolved || !pin.lat.isFinite() || !pin.lon.isFinite()) {
            return@mapNotNull null
        }
        Feature.fromGeometry(Point.fromLngLat(pin.lon, pin.lat)).apply {
            addStringProperty("pinId", pin.pinId)
            addStringProperty("category", pin.category)
            addStringProperty("note", pin.note)
            addNumberProperty("createdAt", pin.createdAt)
        }
    }
    return FeatureCollection.fromFeatures(features)
}

internal fun ensureOsmPinsLayer(style: Style) {
    if (style.getSource(OSM_PINS_SOURCE_ID) == null) {
        style.addSource(GeoJsonSource(OSM_PINS_SOURCE_ID))
    }

    if (style.getLayer(OSM_PINS_LAYER_ID) == null) {
        val pinLayer = CircleLayer(OSM_PINS_LAYER_ID, OSM_PINS_SOURCE_ID).apply {
            setProperties(
                circleRadius(OSM_PIN_RADIUS_DP),
                circleColor(Color.parseColor(OSM_PIN_COLOR_HEX)),
                circleStrokeColor(Color.WHITE),
                circleStrokeWidth(OSM_PIN_STROKE_WIDTH_DP),
                circleOpacity(1.0f)
            )
        }
        style.addLayer(pinLayer)
    }
}

@Composable
fun MapLibreCompose(
    modifier: Modifier = Modifier,
    styleUrl: String,
    recordingState: RideRecordingState = RideRecordingState.IDLE,
    activeTrace: List<TrackPoint> = emptyList(),
    unresolvedPins: List<OsmPinEntity> = emptyList(),
    isLocationPermissionGranted: Boolean = false,
    onMapReady: (MapLibreMap) -> Unit
) {
    val context = LocalContext.current
    var readyMap by remember { mutableStateOf<MapLibreMap?>(null) }

    val mapView = remember {
        MapView(context).apply {
            getMapAsync { map ->
                map.setStyle(styleUrl) { style ->
                    onMapReady(map)
                    ensureActiveRideTraceLayer(style)
                    ensureOsmPinsLayer(style)
                    readyMap = map
                }
            }
        }
    }

    LaunchedEffect(readyMap, isLocationPermissionGranted, recordingState) {
        val map = readyMap ?: return@LaunchedEffect
        if (isLocationPermissionGranted) {
            configureMapLocationComponent(map, context, recordingState)
        }
    }

    LaunchedEffect(readyMap, recordingState, activeTrace) {
        val map = readyMap ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        ensureActiveRideTraceLayer(style)

        val featureCollection = if (recordingState == RideRecordingState.IDLE || activeTrace.size < 2) {
            FeatureCollection.fromFeatures(emptyArray())
        } else {
            withContext(Dispatchers.Default) {
                val points = activeTrace.map { pt -> Point.fromLngLat(pt.lon, pt.lat) }
                val lineString = LineString.fromLngLats(points)
                FeatureCollection.fromFeature(Feature.fromGeometry(lineString))
            }
        }

        style.getSourceAs<GeoJsonSource>(ACTIVE_RIDE_TRACE_SOURCE_ID)?.setGeoJson(featureCollection)
    }

    LaunchedEffect(readyMap, unresolvedPins) {
        val map = readyMap ?: return@LaunchedEffect
        val style = map.style ?: return@LaunchedEffect
        ensureOsmPinsLayer(style)

        val featureCollection = withContext(Dispatchers.Default) {
            buildOsmPinsFeatureCollection(unresolvedPins)
        }

        style.getSourceAs<GeoJsonSource>(OSM_PINS_SOURCE_ID)?.setGeoJson(featureCollection)
    }

    val lifecycleObserver = rememberMapLifecycleObserver(mapView)
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle) {
        lifecycle.addObserver(lifecycleObserver)
        onDispose {
            lifecycle.removeObserver(lifecycleObserver)
        }
    }

    androidx.compose.ui.viewinterop.AndroidView(
        modifier = modifier,
        factory = { mapView }
    )
}

@Composable
private fun rememberMapLifecycleObserver(mapView: MapView): LifecycleEventObserver =
    remember(mapView) {
        LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
    }
