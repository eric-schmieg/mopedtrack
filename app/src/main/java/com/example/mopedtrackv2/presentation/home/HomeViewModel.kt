package com.example.mopedtrackv2.presentation.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.model.RoadSegment
import com.example.mopedtrackv2.domain.repository.MopedRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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

    val debugOverlayEnabled = repository.debugOverlayEnabledFlow
    val matchRadius = repository.matchRadiusFlow
    val showCompletedSegments = repository.showCompletedSegmentsFlow
    val showUncompletedSegments = repository.showUncompletedSegmentsFlow
    val showGpxTraces = repository.showGpxTracesFlow
    val visibleGpxTraces = repository.getVisibleGpxFilesFlow()
    val allImportedGpxFiles = repository.getImportedFilesFlow()
    val lastDebugTrace = repository.getLastDebugTraceFlow()

    var hasPerformedInitialZoom: Boolean = false
    var lastCameraPosition: org.maplibre.android.camera.CameraPosition? = null

    private var queryJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getAllRegions().collect {
                _regions.value = it
            }
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
        val foundInMemory = _roadSegments.value.find { it.segmentId == segmentId }
        if (foundInMemory != null) {
            Log.d("HomeViewModel", "Found segment in memory list: ${foundInMemory.name}")
            _selectedSegment.value = foundInMemory
            toggleSegmentDebugBbox(foundInMemory.segmentId, true, foundInMemory.pairedSegmentId)
        } else {
            viewModelScope.launch {
                val foundInDb = repository.getRoadSegmentById(segmentId)
                Log.d("HomeViewModel", "Queried DB for segmentId $segmentId, result: ${foundInDb?.name}")
                _selectedSegment.value = foundInDb
                if (foundInDb != null) {
                    toggleSegmentDebugBbox(foundInDb.segmentId, true, foundInDb.pairedSegmentId)
                }
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
