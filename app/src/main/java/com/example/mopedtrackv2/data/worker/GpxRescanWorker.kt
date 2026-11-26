package com.example.mopedtrackv2.data.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.mopedtrackv2.MopedApplication
import com.example.mopedtrackv2.domain.engine.GpsPoint
import com.example.mopedtrackv2.domain.engine.SegmentMatcherEngine
import com.example.mopedtrackv2.utils.PolylineUtils
import kotlinx.coroutines.flow.first

class GpxRescanWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val repository = (applicationContext as MopedApplication).repository

        val matchRadius = repository.matchRadiusFlow.first().toDouble()
        val coverageThreshold = repository.coverageThresholdFlow.first().toDouble()

        // Reset all road segment completions and file completion counts back to 0
        repository.resetAllProgress()

        val allTraces = repository.getImportedFilesFlow().first()
        val totalFiles = allTraces.size

        var processedCount = 0
        var totalNewlyCompletedSegments = 0

        setProgress(
            workDataOf(
                "STATUS_TEXT" to "Re-scanning Traces...",
                "CURRENT_FILE" to 0,
                "TOTAL_FILES" to totalFiles,
                "SKIPPED_FILES" to 0,
                "NEW_SEGMENTS" to 0
            )
        )

        for (file in allTraces) {
            processedCount++

            try {
                if (file.encodedTrace.isNotBlank()) {
                    val tracePoints = PolylineUtils.decode(file.encodedTrace).map { GpsPoint(it.first, it.second) }
                    if (tracePoints.isNotEmpty()) {
                        val spatialIndex = com.example.mopedtrackv2.domain.engine.TraceSpatialIndex(tracePoints)

                        val minLat = tracePoints.minOf { it.lat }
                        val maxLat = tracePoints.maxOf { it.lat }
                        val minLon = tracePoints.minOf { it.lon }
                        val maxLon = tracePoints.maxOf { it.lon }

                        val candidates = repository.getCandidateSegmentsForBoundingBox(minLat, maxLat, minLon, maxLon)
                        var fileNewSegments = 0
                        val segmentsToUpdate = mutableListOf<com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity>()

                        for (segment in candidates) {
                            if (!segment.isCompleted) {
                                val isMatched = SegmentMatcherEngine.evaluateSegment(segment, spatialIndex, matchRadius, coverageThreshold)
                                if (isMatched) {
                                    segmentsToUpdate.add(segment.copy(isCompleted = true, completedAt = System.currentTimeMillis()))
                                    fileNewSegments++

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
                            repository.updateRoadSegments(segmentsToUpdate)
                            totalNewlyCompletedSegments += fileNewSegments
                        }

                        val resolvedDistance = if (file.distanceMeters <= 0.0 && tracePoints.size >= 2) {
                            com.example.mopedtrackv2.domain.gpx.GpxParser.computeDistanceMeters(tracePoints)
                        } else {
                            file.distanceMeters
                        }
                        repository.insertImportedFile(
                            file.copy(
                                segmentsCompletedCount = fileNewSegments,
                                distanceMeters = resolvedDistance
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                Log.e("GpxRescanWorker", "Error re-scanning file '${file.fileName}'", e)
            }

            setProgress(
                workDataOf(
                    "STATUS_TEXT" to "Re-scanning Traces...",
                    "CURRENT_FILE" to processedCount,
                    "TOTAL_FILES" to totalFiles,
                    "SKIPPED_FILES" to 0,
                    "NEW_SEGMENTS" to totalNewlyCompletedSegments
                )
            )
        }

        setProgress(
            workDataOf(
                "STATUS_TEXT" to "Re-scan Complete!",
                "CURRENT_FILE" to totalFiles,
                "TOTAL_FILES" to totalFiles,
                "SKIPPED_FILES" to 0,
                "NEW_SEGMENTS" to totalNewlyCompletedSegments
            )
        )

        return Result.success()
    }
}
