package com.example.mopedtrackv2.data.service

import android.content.Context
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.TrackPoint
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RideTrackingController(
    private val onStartService: (() -> Unit)? = null,
    private val traceStore: RideTraceStore? = null
) {
    private val _recordingState = MutableStateFlow(RideRecordingState.IDLE)
    val recordingState: StateFlow<RideRecordingState> = _recordingState.asStateFlow()

    private val _activeTrace = MutableStateFlow<List<TrackPoint>>(emptyList())
    val activeTrace: StateFlow<List<TrackPoint>> = _activeTrace.asStateFlow()

    private var completedSegmentCount: Int = 0
    private var recoveredPointCount: Int = 0

    init {
        restoreFromDisk()
    }

    private fun restoreFromDisk() {
        val store = traceStore ?: return
        val session = store.loadSession() ?: return
        if ((session.state == RideRecordingState.RECORDING || session.state == RideRecordingState.PAUSED) &&
            session.points.isNotEmpty()
        ) {
            _activeTrace.value = session.points
            _recordingState.value = RideRecordingState.PAUSED
            completedSegmentCount = session.completedSegmentCount
            recoveredPointCount = session.points.size
            store.updateState(RideRecordingState.PAUSED, completedSegmentCount)
        } else {
            store.clear()
        }
    }

    @Synchronized
    fun consumeRecoveredPointCount(): Int {
        val count = recoveredPointCount
        recoveredPointCount = 0
        return count
    }

    @Synchronized
    fun getCompletedSegmentCount(): Int = completedSegmentCount

    @Synchronized
    fun addCompletedSegments(delta: Int) {
        if (delta <= 0) return
        completedSegmentCount += delta
        if (_recordingState.value != RideRecordingState.IDLE) {
            traceStore?.updateState(_recordingState.value, completedSegmentCount)
        }
    }

    @Synchronized
    fun startRecording(notifyService: Boolean = true) {
        if (_recordingState.value != RideRecordingState.IDLE) return
        _activeTrace.value = emptyList()
        completedSegmentCount = 0
        recoveredPointCount = 0
        _recordingState.value = RideRecordingState.RECORDING
        traceStore?.startSession(RideRecordingState.RECORDING, 0)
        if (notifyService) {
            onStartService?.invoke()
        }
    }

    @Synchronized
    fun pauseRecording() {
        if (_recordingState.value != RideRecordingState.RECORDING) return
        _recordingState.value = RideRecordingState.PAUSED
        traceStore?.updateState(RideRecordingState.PAUSED, completedSegmentCount)
    }

    @Synchronized
    fun resumeRecording(notifyService: Boolean = true) {
        if (_recordingState.value != RideRecordingState.PAUSED) return
        _recordingState.value = RideRecordingState.RECORDING
        traceStore?.updateState(RideRecordingState.RECORDING, completedSegmentCount)
        if (notifyService) {
            onStartService?.invoke()
        }
    }

    @Synchronized
    fun stopRecording() {
        if (_recordingState.value == RideRecordingState.IDLE) return
        _recordingState.value = RideRecordingState.IDLE
        traceStore?.updateState(RideRecordingState.IDLE, completedSegmentCount)
    }

    @Synchronized
    fun discardRecording() {
        _recordingState.value = RideRecordingState.IDLE
        _activeTrace.value = emptyList()
        completedSegmentCount = 0
        recoveredPointCount = 0
        traceStore?.clear()
    }

    fun reset() {
        discardRecording()
    }

    @Synchronized
    fun appendTrackPoint(point: TrackPoint) {
        if (_recordingState.value != RideRecordingState.RECORDING) return
        if (!point.lat.isFinite() || !point.lon.isFinite()) return
        _activeTrace.value = _activeTrace.value + point
        traceStore?.appendPoint(point)
    }

    companion object {
        @Volatile
        private var INSTANCE: RideTrackingController? = null

        fun getInstance(context: Context): RideTrackingController {
            return INSTANCE ?: synchronized(this) {
                val appContext = context.applicationContext
                val traceStore = FileRideTraceStore(File(appContext.filesDir, "active_ride"))
                val instance = RideTrackingController(
                    onStartService = { RideTrackingService.start(appContext) },
                    traceStore = traceStore
                )
                INSTANCE = instance
                instance
            }
        }
    }
}
