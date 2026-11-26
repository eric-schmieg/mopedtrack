package com.example.mopedtrackv2.presentation.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.domain.model.OsmPinCategory
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.RoadSegment
import com.example.mopedtrackv2.domain.model.TrackPoint
import com.example.mopedtrackv2.domain.repository.MopedRepository
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

data class PinPlacementState(
    val lat: Double,
    val lon: Double,
    val draftCategory: OsmPinCategory? = null,
    val draftNote: String = ""
)

data class OsmPinDraft(
    val lat: Double,
    val lon: Double,
    val selectedCategory: OsmPinCategory? = null,
    val note: String = ""
)

internal fun resolveInitialPinPlacementCoordinates(
    cameraLat: Double?,
    cameraLon: Double?,
    recordingState: RideRecordingState,
    activeTrace: List<TrackPoint>,
    gpsLat: Double?,
    gpsLon: Double?
): Pair<Double, Double>? {
    if (cameraLat != null && cameraLon != null && cameraLat.isFinite() && cameraLon.isFinite()) {
        return Pair(cameraLat, cameraLon)
    }
    if (recordingState == RideRecordingState.RECORDING) {
        activeTrace.lastOrNull()?.let { return Pair(it.lat, it.lon) }
    }
    if (gpsLat != null && gpsLon != null && gpsLat.isFinite() && gpsLon.isFinite()) {
        return Pair(gpsLat, gpsLon)
    }
    return null
}

class HomeViewModel(
    private val repository: MopedRepository
) : ViewModel() {

    private val _roadSegments = MutableStateFlow<List<RoadSegment>>(emptyList())
    val roadSegments: StateFlow<List<RoadSegment>> = _roadSegments.asStateFlow()

    private val _selectedSegment = MutableStateFlow<RoadSegment?>(null)
    val selectedSegment: StateFlow<RoadSegment?> = _selectedSegment.asStateFlow()

    private val _regions = MutableStateFlow<List<Region>>(emptyList())
    val regions: StateFlow<List<Region>> = _regions.asStateFlow()

    private val _debugBboxSegmentIds = MutableStateFlow<Set<String>>(emptySet())
    val debugBboxSegmentIds: StateFlow<Set<String>> = _debugBboxSegmentIds.asStateFlow()

    private val _isCameraRestored = MutableStateFlow(false)
    val isCameraRestored: StateFlow<Boolean> = _isCameraRestored.asStateFlow()

    private val _showStopConfirmationDialog = MutableStateFlow(false)

    private val _pinPlacement = MutableStateFlow<PinPlacementState?>(null)
    val pinPlacement: StateFlow<PinPlacementState?> = _pinPlacement.asStateFlow()

    private val _pinDraft = MutableStateFlow<OsmPinDraft?>(null)
    val pinDraft: StateFlow<OsmPinDraft?> = _pinDraft.asStateFlow()

    private val _selectedPin = MutableStateFlow<OsmPinEntity?>(null)
    val selectedPin: StateFlow<OsmPinEntity?> = _selectedPin.asStateFlow()

    private val _snackbarMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessages: SharedFlow<String> = _snackbarMessages.asSharedFlow()

    val recordingState: StateFlow<RideRecordingState> = repository.recordingState
    val activeTrace: StateFlow<List<TrackPoint>> = repository.activeTrace

    val unresolvedPins: StateFlow<List<OsmPinEntity>> = repository.getUnresolvedOsmPinsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val showStopConfirmationDialog: StateFlow<Boolean> = combine(
        _showStopConfirmationDialog,
        recordingState
    ) { showDialog, state ->
        showDialog && state != RideRecordingState.IDLE
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false
    )

    val debugOverlayEnabled = repository.debugOverlayEnabledFlow
    val matchRadius = repository.matchRadiusFlow
    val unitSystem = repository.unitSystemFlow
    val showCompletedSegments = repository.showCompletedSegmentsFlow
    val showUncompletedSegments = repository.showUncompletedSegmentsFlow
    val showGpxTraces = repository.showGpxTracesFlow
    val hiddenRegionIds = repository.hiddenRegionIdsFlow
    val visibleGpxTraces = repository.getVisibleGpxFilesFlow()
    val allImportedGpxFiles = repository.getImportedFilesFlow()
    val lastDebugTrace = repository.getLastDebugTraceFlow()

    var hasPerformedInitialZoom: Boolean = false
    var lastCameraPosition: CameraPosition? = null

    private var queryJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getAllRegions().collect {
                _regions.value = it
            }
        }
        viewModelScope.launch {
            recordingState.collect { state ->
                if (state == RideRecordingState.IDLE) {
                    _showStopConfirmationDialog.value = false
                }
            }
        }
        viewModelScope.launch {
            val savedPos = repository.savedCameraPositionFlow.first()
            if (savedPos != null) {
                lastCameraPosition = CameraPosition.Builder()
                    .target(LatLng(savedPos.latitude, savedPos.longitude))
                    .zoom(savedPos.zoom)
                    .bearing(savedPos.bearing)
                    .tilt(savedPos.tilt)
                    .build()
                hasPerformedInitialZoom = true
            }
            _isCameraRestored.value = true
        }
    }

    fun startRide() {
        _showStopConfirmationDialog.value = false
        repository.startRideRecording()
    }

    fun checkRecoveredRide() {
        val recoveredCount = repository.consumeRecoveredRidePointCount()
        if (recoveredCount > 0) {
            val unit = if (recoveredCount == 1) "point" else "points"
            _snackbarMessages.tryEmit("Recovered unsaved ride ($recoveredCount $unit)")
        }
    }

    fun pauseRide() {
        repository.pauseRideRecording()
    }

    fun resumeRide() {
        repository.resumeRideRecording()
    }

    fun requestStopRide() {
        if (recordingState.value != RideRecordingState.IDLE) {
            _showStopConfirmationDialog.value = true
        }
    }

    fun dismissStopRideDialog() {
        _showStopConfirmationDialog.value = false
    }

    fun confirmSaveRide() {
        _showStopConfirmationDialog.value = false
        viewModelScope.launch {
            try {
                val saved = repository.saveRideRecording()
                if (saved) {
                    _snackbarMessages.tryEmit("Ride saved to GPX")
                } else {
                    _snackbarMessages.tryEmit("No GPS points recorded")
                }
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to save ride GPX", e)
                _snackbarMessages.tryEmit("Failed to save ride GPX")
                repository.discardRideRecording()
            }
        }
    }

    fun startPinPlacement(
        cameraLat: Double?,
        cameraLon: Double?,
        gpsLat: Double?,
        gpsLon: Double?
    ) {
        val coords = resolveInitialPinPlacementCoordinates(
            cameraLat = cameraLat,
            cameraLon = cameraLon,
            recordingState = recordingState.value,
            activeTrace = activeTrace.value,
            gpsLat = gpsLat,
            gpsLon = gpsLon
        )
        if (coords == null) {
            _snackbarMessages.tryEmit("Unable to determine pin location")
            return
        }
        _selectedPin.value = null
        _selectedSegment.value = null
        _pinDraft.value = null
        _pinPlacement.value = PinPlacementState(
            lat = coords.first,
            lon = coords.second
        )
    }

    fun updatePinPlacementCoordinates(lat: Double, lon: Double) {
        if (!lat.isFinite() || !lon.isFinite()) return
        _pinPlacement.value = _pinPlacement.value?.copy(lat = lat, lon = lon)
    }

    fun cancelPinPlacement() {
        _pinPlacement.value = null
    }

    fun confirmPinPlacement(lat: Double? = null, lon: Double? = null) {
        val placement = _pinPlacement.value ?: return
        val finalLat = if (lat != null && lat.isFinite()) lat else placement.lat
        val finalLon = if (lon != null && lon.isFinite()) lon else placement.lon
        _pinPlacement.value = null
        _pinDraft.value = OsmPinDraft(
            lat = finalLat,
            lon = finalLon,
            selectedCategory = placement.draftCategory,
            note = placement.draftNote
        )
    }

    fun adjustPinLocation() {
        val draft = _pinDraft.value ?: return
        _pinDraft.value = null
        _selectedPin.value = null
        _selectedSegment.value = null
        _pinPlacement.value = PinPlacementState(
            lat = draft.lat,
            lon = draft.lon,
            draftCategory = draft.selectedCategory,
            draftNote = draft.note
        )
    }

    fun selectPinCategory(category: OsmPinCategory) {
        _pinDraft.value = _pinDraft.value?.copy(selectedCategory = category)
    }

    fun updatePinNote(note: String) {
        _pinDraft.value = _pinDraft.value?.copy(note = note)
    }

    fun dismissDropPinSheet() {
        _pinDraft.value = null
    }

    fun saveOsmPin() {
        val draft = _pinDraft.value ?: return
        val category = draft.selectedCategory ?: return
        _pinDraft.value = null
        viewModelScope.launch {
            try {
                repository.insertOsmPin(
                    OsmPinEntity(
                        pinId = UUID.randomUUID().toString(),
                        lat = draft.lat,
                        lon = draft.lon,
                        category = category.name,
                        note = draft.note.trim(),
                        createdAt = System.currentTimeMillis(),
                        isResolved = false
                    )
                )
                _snackbarMessages.tryEmit("OSM pin saved")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to save OSM pin", e)
                _snackbarMessages.tryEmit("Failed to save OSM pin")
            }
        }
    }

    fun selectPin(pinId: String) {
        _selectedSegment.value = null
        val foundInMemory = unresolvedPins.value.find { it.pinId == pinId }
        if (foundInMemory != null) {
            _selectedPin.value = foundInMemory
        } else {
            viewModelScope.launch {
                val foundInDb = repository.getOsmPinById(pinId)
                if (foundInDb != null && !foundInDb.isResolved) {
                    _selectedPin.value = foundInDb
                }
            }
        }
    }

    fun clearSelectedPin() {
        _selectedPin.value = null
    }

    fun resolveSelectedPin() {
        val pin = _selectedPin.value ?: return
        _selectedPin.value = null
        viewModelScope.launch {
            try {
                repository.updateOsmPin(pin.copy(isResolved = true))
                _snackbarMessages.tryEmit("Pin marked resolved")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to resolve OSM pin", e)
                _snackbarMessages.tryEmit("Failed to update pin")
            }
        }
    }

    fun deleteSelectedPin() {
        val pin = _selectedPin.value ?: return
        _selectedPin.value = null
        viewModelScope.launch {
            try {
                repository.deleteOsmPin(pin.pinId)
                _snackbarMessages.tryEmit("Pin deleted")
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to delete OSM pin", e)
                _snackbarMessages.tryEmit("Failed to delete pin")
            }
        }
    }

    fun saveCameraPosition(cameraPosition: CameraPosition) {
        val target = cameraPosition.target
        if (target == null || (cameraPosition.zoom <= 2.0 && target.latitude == 0.0 && target.longitude == 0.0)) {
            return
        }
        lastCameraPosition = cameraPosition
        viewModelScope.launch {
            repository.setSavedCameraPosition(
                lat = target.latitude,
                lon = target.longitude,
                zoom = cameraPosition.zoom,
                bearing = cameraPosition.bearing,
                tilt = cameraPosition.tilt
            )
        }
    }

    fun toggleShowCompletedSegments(show: Boolean) {
        viewModelScope.launch {
            repository.setShowCompletedSegments(show)
        }
    }

    fun toggleShowUncompletedSegments(show: Boolean) {
        viewModelScope.launch {
            repository.setShowUncompletedSegments(show)
        }
    }

    fun toggleShowGpxTraces(show: Boolean) {
        viewModelScope.launch {
            repository.setShowGpxTraces(show)
        }
    }

    fun toggleRegionVisibility(regionId: String, isVisible: Boolean) {
        viewModelScope.launch {
            repository.setRegionVisibility(regionId, isVisible)
        }
    }

    fun setAllRegionsVisibility(isVisible: Boolean) {
        viewModelScope.launch {
            if (isVisible) {
                repository.setHiddenRegionIds(emptySet())
            } else {
                repository.setHiddenRegionIds(_regions.value.map { it.regionId }.toSet())
            }
        }
    }

    fun onCameraIdle(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double) {
        queryJob?.cancel()
        queryJob = viewModelScope.launch {
            repository.getRoadSegmentsInBoundingBox(minLat, maxLat, minLon, maxLon)
                .collect { segments ->
                    Log.d("MopedTrack", "HomeViewModel: Received ${segments.size} segments for bounding box [$minLat, $maxLat, $minLon, $maxLon]")
                    _roadSegments.value = segments

                    // Update selected segment if it changed in DB
                    val currentSelectedId = _selectedSegment.value?.segmentId
                    if (currentSelectedId != null) {
                        val updated = segments.find { it.segmentId == currentSelectedId }
                        if (updated != null) {
                            _selectedSegment.value = updated
                        }
                    }
                }
        }
    }

    fun selectSegment(segmentId: String) {
        Log.d("HomeViewModel", "selectSegment called with segmentId: $segmentId")
        _selectedPin.value = null
        val foundInMemory = _roadSegments.value.find { it.segmentId == segmentId }
        if (foundInMemory != null) {
            Log.d("HomeViewModel", "Found segment in memory list: ${foundInMemory.name}")
            _selectedSegment.value = foundInMemory
        } else {
            viewModelScope.launch {
                val foundInDb = repository.getRoadSegmentById(segmentId)
                Log.d("HomeViewModel", "Queried DB for segmentId $segmentId, result: ${foundInDb?.name}")
                _selectedSegment.value = foundInDb
            }
        }
    }

    fun toggleSegmentDebugBbox(segmentId: String, enable: Boolean, pairedSegmentId: String? = null) {
        val current = _debugBboxSegmentIds.value.toMutableSet()
        val idsToUpdate = listOfNotNull(segmentId, pairedSegmentId)
        if (enable) {
            current.addAll(idsToUpdate)
        } else {
            current.removeAll(idsToUpdate.toSet())
        }
        _debugBboxSegmentIds.value = current
    }

    fun clearSelectedSegment() {
        Log.d("HomeViewModel", "clearSelectedSegment called")
        _selectedSegment.value = null
    }

    fun toggleSegmentCompletion(segment: RoadSegment) {
        viewModelScope.launch {
            val isNowCompleted = !segment.isCompleted
            val completedAt = if (isNowCompleted) System.currentTimeMillis() else null
            // Optimistically update _selectedSegment state
            _selectedSegment.value = segment.copy(
                isCompleted = isNowCompleted,
                completedAt = completedAt
            )
            // Optimistically update _roadSegments list so UI and Map update immediately
            val pairedId = segment.pairedSegmentId
            _roadSegments.value = _roadSegments.value.map { item ->
                if (item.segmentId == segment.segmentId || (pairedId != null && item.pairedSegmentId == pairedId) || (pairedId != null && item.segmentId == pairedId)) {
                    item.copy(isCompleted = isNowCompleted, completedAt = completedAt)
                } else {
                    item
                }
            }
            repository.toggleSegmentCompletion(segment.segmentId, isNowCompleted, completedAt)
        }
    }
}
