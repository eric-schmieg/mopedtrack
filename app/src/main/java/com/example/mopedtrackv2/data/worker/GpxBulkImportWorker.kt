package com.example.mopedtrackv2.data.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.mopedtrackv2.MopedApplication
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.domain.engine.SegmentMatcherEngine
import com.example.mopedtrackv2.domain.gpx.GpxParser
import kotlinx.coroutines.flow.first
import java.security.MessageDigest

class GpxBulkImportWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val folderUriStr = inputData.getString("FOLDER_URI") ?: return Result.failure()
        val folderUri = Uri.parse(folderUriStr)

        val repository = (applicationContext as MopedApplication).repository

        val matchRadius = repository.matchRadiusFlow.first().toDouble()
        val coverageThreshold = repository.coverageThresholdFlow.first().toDouble()

        val rootFolder = DocumentFile.fromTreeUri(applicationContext, folderUri)
            ?: return Result.failure()

        val allGpxFiles = mutableListOf<DocumentFile>()
        collectGpxFiles(rootFolder, allGpxFiles)

        val totalFiles = allGpxFiles.size
        var processedCount = 0
        var skippedCount = 0
        var newlyCompletedSegments = 0

        for (file in allGpxFiles) {
            processedCount++
            setProgress(
                workDataOf(
                    "CURRENT_FILE" to processedCount,
                    "TOTAL_FILES" to totalFiles,
                    "SKIPPED_FILES" to skippedCount,
                    "NEW_SEGMENTS" to newlyCompletedSegments
                )
            )

            try {
                applicationContext.contentResolver.openInputStream(file.uri)?.use { inputStream ->
                    val bytes = inputStream.readBytes()
                    val hash = calculateSha256(bytes)

                    val existing = repository.getImportedFileByHash(hash)
                    if (existing != null && existing.encodedTrace.isNotBlank()) {
                        skippedCount++
                        return@use // Skip already processed and encoded
                    }

                    // Parse
                    val tracePoints = GpxParser.parse(bytes.inputStream())
                    if (tracePoints.isEmpty()) {
                        return@use
                    }

                    // Find bounds
                    val minLat = tracePoints.minOf { it.lat }
                    val maxLat = tracePoints.maxOf { it.lat }
                    val minLon = tracePoints.minOf { it.lon }
                    val maxLon = tracePoints.maxOf { it.lon }

                    val candidates = repository.getCandidateSegmentsForBoundingBox(minLat, maxLat, minLon, maxLon)
                    var fileNewSegments = 0
                    val segmentsToUpdate = mutableListOf<com.example.mopedtrackv2.data.local.entity.RoadSegmentEntity>()

                    for (segment in candidates) {
                        if (!segment.isCompleted) {
                            val isMatched = SegmentMatcherEngine.evaluateSegment(segment, tracePoints, matchRadius, coverageThreshold)
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
                        repository.updateRoadSegments(segmentsToUpdate)
                        newlyCompletedSegments += fileNewSegments
                    }

                    val encodedTrace = com.example.mopedtrackv2.utils.PolylineUtils.encode(tracePoints.map { Pair(it.lat, it.lon) })

                    repository.insertImportedFile(
                        ImportedGpxFileEntity(
                            fileHash = hash,
                            fileName = file.name ?: "unknown.gpx",
                            importedAt = System.currentTimeMillis(),
                            trackPointCount = tracePoints.size,
                            segmentsCompletedCount = fileNewSegments,
                            encodedTrace = encodedTrace,
                            isVisibleOnMap = existing?.isVisibleOnMap ?: false
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("GpxBulkImportWorker", "Error processing file: ${file.name}", e)
            }
        }

        setProgress(
            workDataOf(
                "CURRENT_FILE" to processedCount,
                "TOTAL_FILES" to totalFiles,
                "SKIPPED_FILES" to skippedCount,
                "NEW_SEGMENTS" to newlyCompletedSegments
            )
        )
        return Result.success()
    }

    private fun collectGpxFiles(dir: DocumentFile, list: MutableList<DocumentFile>) {
        if (!dir.isDirectory) return
        for (file in dir.listFiles()) {
            if (file.isDirectory) {
                collectGpxFiles(file, list)
            } else if (file.name?.endsWith(".gpx", ignoreCase = true) == true) {
                list.add(file)
            }
        }
    }

    private fun calculateSha256(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        return digest.fold("") { str, it -> str + "%02x".format(it) }
    }
}
