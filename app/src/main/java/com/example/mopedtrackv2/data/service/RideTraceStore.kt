package com.example.mopedtrackv2.data.service

import android.util.Log
import com.example.mopedtrackv2.domain.model.RideRecordingState
import com.example.mopedtrackv2.domain.model.TrackPoint
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class PersistedRideSession(
    val state: RideRecordingState,
    val points: List<TrackPoint>,
    val completedSegmentCount: Int
)

interface RideTraceStore {
    fun loadSession(): PersistedRideSession?
    fun startSession(state: RideRecordingState, completedSegmentCount: Int = 0)
    fun appendPoint(point: TrackPoint)
    fun updateState(state: RideRecordingState, completedSegmentCount: Int)
    fun clear()
}

class FileRideTraceStore(
    private val directory: File,
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RideTraceDiskWriter").apply { isDaemon = true }
    }
) : RideTraceStore {

    private val pointsFile: File
        get() = File(directory, POINTS_FILE_NAME)

    private val stateFile: File
        get() = File(directory, STATE_FILE_NAME)

    private val stateTempFile: File
        get() = File(directory, STATE_TEMP_FILE_NAME)

    override fun loadSession(): PersistedRideSession? {
        return try {
            if (!directory.exists()) return null

            var state = RideRecordingState.IDLE
            var completedCount = 0
            val hasStateFile = stateFile.exists()

            if (hasStateFile) {
                for (line in stateFile.readText(Charsets.UTF_8).lines()) {
                    val eqIdx = line.indexOf('=')
                    if (eqIdx <= 0) continue
                    val key = line.substring(0, eqIdx).trim()
                    val value = line.substring(eqIdx + 1).trim()
                    when (key) {
                        KEY_STATE -> {
                            state = runCatching { RideRecordingState.valueOf(value) }
                                .getOrDefault(RideRecordingState.IDLE)
                        }
                        KEY_COMPLETED_COUNT -> {
                            completedCount = value.toIntOrNull()?.coerceAtLeast(0) ?: 0
                        }
                    }
                }
            }

            val points = readPointsFile()
            if (!hasStateFile && points.isEmpty()) {
                return null
            }

            // If points exist on disk without a state file (e.g. crash before state rename),
            // treat as PAUSED so the trace can still be recovered safely.
            val effectiveState = if (!hasStateFile) {
                RideRecordingState.PAUSED
            } else {
                state
            }

            PersistedRideSession(
                state = effectiveState,
                points = points,
                completedSegmentCount = completedCount
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted ride session", e)
            null
        }
    }

    override fun startSession(state: RideRecordingState, completedSegmentCount: Int) {
        executor.execute {
            try {
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                if (pointsFile.exists()) {
                    pointsFile.delete()
                }
                writeStateAtomically(state, completedSegmentCount)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start persisted ride session", e)
            }
        }
    }

    override fun appendPoint(point: TrackPoint) {
        if (!point.lat.isFinite() || !point.lon.isFinite()) return
        val altStr = point.altitude?.takeIf { it.isFinite() }?.toString() ?: ""
        val speedStr = point.speed?.takeIf { it.isFinite() }?.toString() ?: ""
        val accuracyStr = point.accuracy?.takeIf { it.isFinite() }?.toString() ?: ""
        val line = "${point.timestamp}\t${point.lat}\t${point.lon}\t$altStr\t$speedStr\t$accuracyStr\n"

        executor.execute {
            try {
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                FileOutputStream(pointsFile, true).use { fos ->
                    fos.write(line.toByteArray(Charsets.UTF_8))
                    fos.flush()
                    fos.fd.sync()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to append track point to disk", e)
            }
        }
    }

    override fun updateState(state: RideRecordingState, completedSegmentCount: Int) {
        executor.execute {
            try {
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                writeStateAtomically(state, completedSegmentCount)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update persisted ride state", e)
            }
        }
    }

    override fun clear() {
        executor.execute {
            try {
                if (stateFile.exists()) stateFile.delete()
                if (stateTempFile.exists()) stateTempFile.delete()
                if (pointsFile.exists()) pointsFile.delete()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear persisted ride session", e)
            }
        }
    }

    internal fun awaitIdle() {
        executor.submit {}.get()
    }

    private fun writeStateAtomically(state: RideRecordingState, completedSegmentCount: Int) {
        val content = "$KEY_STATE=${state.name}\n$KEY_COMPLETED_COUNT=${completedSegmentCount.coerceAtLeast(0)}\n"
        FileOutputStream(stateTempFile, false).use { fos ->
            fos.write(content.toByteArray(Charsets.UTF_8))
            fos.flush()
            fos.fd.sync()
        }
        if (stateFile.exists() && !stateTempFile.renameTo(stateFile)) {
            stateFile.writeText(content, Charsets.UTF_8)
            stateTempFile.delete()
        } else if (!stateFile.exists()) {
            if (!stateTempFile.renameTo(stateFile)) {
                stateFile.writeText(content, Charsets.UTF_8)
                stateTempFile.delete()
            }
        }
    }

    private fun readPointsFile(): List<TrackPoint> {
        if (!pointsFile.exists()) return emptyList()
        val raw = pointsFile.readText(Charsets.UTF_8)
        val lastNewline = raw.lastIndexOf('\n')
        if (lastNewline <= 0) return emptyList()

        val completeText = raw.substring(0, lastNewline)
        val points = ArrayList<TrackPoint>()
        for (line in completeText.split('\n')) {
            if (line.isEmpty()) continue
            val parts = line.split('\t')
            if (parts.size != 6) continue

            val timestamp = parts[0].toLongOrNull() ?: continue
            val lat = parts[1].toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
            val lon = parts[2].toDoubleOrNull()?.takeIf { it.isFinite() } ?: continue
            val altitude = parts[3].takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.takeIf { it.isFinite() }
            val speed = parts[4].takeIf { it.isNotEmpty() }?.toFloatOrNull()?.takeIf { it.isFinite() }
            val accuracy = parts[5].takeIf { it.isNotEmpty() }?.toFloatOrNull()?.takeIf { it.isFinite() }

            points.add(
                TrackPoint(
                    lat = lat,
                    lon = lon,
                    altitude = altitude,
                    timestamp = timestamp,
                    speed = speed,
                    accuracy = accuracy
                )
            )
        }
        return points
    }

    companion object {
        private const val TAG = "FileRideTraceStore"
        private const val POINTS_FILE_NAME = "active_ride_points.tsv"
        private const val STATE_FILE_NAME = "active_ride_state.txt"
        private const val STATE_TEMP_FILE_NAME = "active_ride_state.tmp"
        private const val KEY_STATE = "state"
        private const val KEY_COMPLETED_COUNT = "completedCount"
    }
}
