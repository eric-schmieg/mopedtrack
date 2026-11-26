package com.example.mopedtrackv2.data.gpx

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.domain.gpx.GpxWriter
import com.example.mopedtrackv2.domain.model.TrackPoint
import com.example.mopedtrackv2.utils.HashUtils
import com.example.mopedtrackv2.utils.PolylineUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class GpxExportResult(
    val fileName: String,
    val contentBytes: ByteArray,
    val usedSafTree: Boolean
)

data class ExternalTraceCopySummary(
    val totalTraces: Int,
    val copiedCount: Int,
    val skippedExistingCount: Int,
    val failedCount: Int,
    val folderWritable: Boolean
)

interface GpxBatchStorageSession {
    suspend fun storeTrace(fileName: String, fileHash: String, contentBytes: ByteArray)
}

object NoOpGpxBatchStorageSession : GpxBatchStorageSession {
    override suspend fun storeTrace(fileName: String, fileHash: String, contentBytes: ByteArray) = Unit
}

interface GpxExporter {
    suspend fun exportGpx(
        fileName: String,
        content: String,
        treeUriString: String?
    ): GpxExportResult

    suspend fun storeTrace(
        fileName: String,
        fileHash: String,
        contentBytes: ByteArray,
        treeUriString: String?
    ) = Unit

    suspend fun openBatchStorageSession(treeUriString: String?): GpxBatchStorageSession =
        object : GpxBatchStorageSession {
            override suspend fun storeTrace(fileName: String, fileHash: String, contentBytes: ByteArray) {
                this@GpxExporter.storeTrace(fileName, fileHash, contentBytes, treeUriString)
            }
        }

    suspend fun copyTracesToExternalFolder(
        traces: List<ImportedGpxFileEntity>,
        treeUriString: String,
        onProgress: (Int, Int) -> Unit = { _, _ -> }
    ): ExternalTraceCopySummary = ExternalTraceCopySummary(
        totalTraces = traces.size,
        copiedCount = 0,
        skippedExistingCount = 0,
        failedCount = 0,
        folderWritable = true
    )

    suspend fun deleteInternalTrace(fileHash: String) = Unit

    suspend fun readInternalTraceBytes(fileHash: String): ByteArray? = null
}

/**
 * Stores raw GPX trace bytes inside app-private internal storage keyed by SHA-256 hash.
 */
class InternalGpxTraceStore(private val tracesDir: File) {

    fun save(fileHash: String, contentBytes: ByteArray) {
        if (fileHash.isBlank()) return
        if (!tracesDir.exists()) {
            tracesDir.mkdirs()
        }
        val target = fileFor(fileHash)
        if (target.exists() && target.length() == contentBytes.size.toLong()) {
            return
        }
        val tempFile = File(tracesDir, "$fileHash.gpx.tmp")
        tempFile.writeBytes(contentBytes)
        if (!tempFile.renameTo(target)) {
            target.writeBytes(contentBytes)
            tempFile.delete()
        }
    }

    fun read(fileHash: String): ByteArray? {
        if (fileHash.isBlank()) return null
        val file = fileFor(fileHash)
        return if (file.exists() && file.isFile) file.readBytes() else null
    }

    fun delete(fileHash: String) {
        if (fileHash.isBlank()) return
        val file = fileFor(fileHash)
        if (file.exists()) {
            file.delete()
        }
    }

    fun exists(fileHash: String): Boolean {
        if (fileHash.isBlank()) return false
        val file = fileFor(fileHash)
        return file.exists() && file.isFile
    }

    private fun fileFor(fileHash: String): File = File(tracesDir, "$fileHash.gpx")
}

class AndroidGpxExporter(
    private val context: Context,
    internalTracesDir: File = File(context.filesDir, INTERNAL_TRACES_DIR_NAME)
) : GpxExporter {

    private val internalStore = InternalGpxTraceStore(internalTracesDir)

    override suspend fun exportGpx(
        fileName: String,
        content: String,
        treeUriString: String?
    ): GpxExportResult = withContext(Dispatchers.IO) {
        val bytes = content.toByteArray(Charsets.UTF_8)
        val hash = HashUtils.sha256Hex(bytes)

        // Always persist internally so every recorded ride is backed up locally
        internalStore.save(hash, bytes)

        if (!treeUriString.isNullOrBlank()) {
            val session = createSafWriter(treeUriString)
            if (session != null) {
                when (val outcome = session.writeIfMissing(fileName, hash, bytes)) {
                    is SafWriteOutcome.Written -> {
                        return@withContext GpxExportResult(
                            fileName = outcome.actualName,
                            contentBytes = bytes,
                            usedSafTree = true
                        )
                    }
                    is SafWriteOutcome.SkippedExisting -> {
                        return@withContext GpxExportResult(
                            fileName = outcome.actualName,
                            contentBytes = bytes,
                            usedSafTree = true
                        )
                    }
                    SafWriteOutcome.Failed -> {
                        Log.w(TAG, "SAF export failed; falling back to app Documents/MopedTrack")
                    }
                }
            } else {
                Log.w(TAG, "SAF export URI unavailable or unwritable; falling back to app Documents/MopedTrack")
            }
        }

        val docsDir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, "Documents")
        val targetDir = File(docsDir, "MopedTrack")
        if (!targetDir.exists()) {
            targetDir.mkdirs()
        }
        val outFile = File(targetDir, fileName)
        outFile.writeBytes(bytes)

        GpxExportResult(
            fileName = fileName,
            contentBytes = bytes,
            usedSafTree = false
        )
    }

    override suspend fun storeTrace(
        fileName: String,
        fileHash: String,
        contentBytes: ByteArray,
        treeUriString: String?
    ): Unit = withContext(Dispatchers.IO) {
        internalStore.save(fileHash, contentBytes)
        if (!treeUriString.isNullOrBlank()) {
            createSafWriter(treeUriString)?.writeIfMissing(fileName, fileHash, contentBytes)
        }
    }

    override suspend fun openBatchStorageSession(treeUriString: String?): GpxBatchStorageSession =
        withContext(Dispatchers.IO) {
            val safWriter = if (!treeUriString.isNullOrBlank()) createSafWriter(treeUriString) else null
            object : GpxBatchStorageSession {
                override suspend fun storeTrace(
                    fileName: String,
                    fileHash: String,
                    contentBytes: ByteArray
                ) = withContext(Dispatchers.IO) {
                    internalStore.save(fileHash, contentBytes)
                    safWriter?.writeIfMissing(fileName, fileHash, contentBytes)
                    Unit
                }
            }
        }

    override suspend fun copyTracesToExternalFolder(
        traces: List<ImportedGpxFileEntity>,
        treeUriString: String,
        onProgress: (Int, Int) -> Unit
    ): ExternalTraceCopySummary = withContext(Dispatchers.IO) {
        if (treeUriString.isBlank()) {
            return@withContext ExternalTraceCopySummary(
                totalTraces = traces.size,
                copiedCount = 0,
                skippedExistingCount = 0,
                failedCount = traces.size,
                folderWritable = false
            )
        }

        val safWriter = createSafWriter(treeUriString)
            ?: return@withContext ExternalTraceCopySummary(
                totalTraces = traces.size,
                copiedCount = 0,
                skippedExistingCount = 0,
                failedCount = traces.size,
                folderWritable = false
            )

        var copied = 0
        var skippedExisting = 0
        var failed = 0

        traces.forEachIndexed { index, trace ->
            onProgress(index + 1, traces.size)
            val bytes = resolveBytesForTrace(trace)
            if (bytes == null) {
                failed++
                return@forEachIndexed
            }
            val effectiveHash = HashUtils.sha256Hex(bytes)
            when (safWriter.writeIfMissing(trace.fileName, effectiveHash, bytes)) {
                is SafWriteOutcome.Written -> copied++
                is SafWriteOutcome.SkippedExisting -> skippedExisting++
                SafWriteOutcome.Failed -> failed++
            }
        }

        ExternalTraceCopySummary(
            totalTraces = traces.size,
            copiedCount = copied,
            skippedExistingCount = skippedExisting,
            failedCount = failed,
            folderWritable = true
        )
    }

    override suspend fun deleteInternalTrace(fileHash: String) = withContext(Dispatchers.IO) {
        internalStore.delete(fileHash)
    }

    override suspend fun readInternalTraceBytes(fileHash: String): ByteArray? = withContext(Dispatchers.IO) {
        internalStore.read(fileHash)
    }

    private fun resolveBytesForTrace(trace: ImportedGpxFileEntity): ByteArray? {
        internalStore.read(trace.fileHash)?.let { return it }

        if (trace.encodedTrace.isBlank()) return null
        val decoded = PolylineUtils.decode(trace.encodedTrace)
        if (decoded.isEmpty()) return null

        val baseTimeMs = trace.recordedAt ?: trace.importedAt
        val pointCount = decoded.size
        val durationMs = trace.durationMillis ?: 0L
        val stepMs = if (pointCount > 1 && durationMs > 0L) durationMs / (pointCount - 1) else 1000L
        val trackPoints = decoded.mapIndexed { idx, pair ->
            TrackPoint(
                lat = pair.first,
                lon = pair.second,
                timestamp = baseTimeMs + idx * stepMs
            )
        }
        val gpxXml = GpxWriter.serialize(
            trackPoints = trackPoints,
            trackName = trace.fileName.removeSuffix(".gpx"),
            metadataTimeMs = baseTimeMs
        )
        val bytes = gpxXml.toByteArray(Charsets.UTF_8)
        internalStore.save(trace.fileHash, bytes)
        return bytes
    }

    private fun createSafWriter(treeUriString: String): SafFolderWriter? {
        return try {
            val treeUri = Uri.parse(treeUriString)
            val treeDoc = DocumentFile.fromTreeUri(context, treeUri)
            if (treeDoc != null && treeDoc.exists() && treeDoc.isDirectory && treeDoc.canWrite()) {
                SafFolderWriter(treeDoc)
            } else {
                null
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "SAF folder permission revoked: $treeUriString", e)
            null
        } catch (e: Exception) {
            Log.w(TAG, "Unable to open SAF folder: $treeUriString", e)
            null
        }
    }

    private sealed class SafWriteOutcome {
        data class Written(val actualName: String) : SafWriteOutcome()
        data class SkippedExisting(val actualName: String) : SafWriteOutcome()
        data object Failed : SafWriteOutcome()
    }

    private inner class SafFolderWriter(private val treeDoc: DocumentFile) {
        private val existingByLowerName: MutableMap<String, DocumentFile> = mutableMapOf<String, DocumentFile>().apply {
            for (doc in treeDoc.listFiles()) {
                val name = doc.name
                if (doc.isFile && !name.isNullOrBlank()) {
                    put(name.lowercase(Locale.ROOT), doc)
                }
            }
        }

        fun writeIfMissing(
            desiredFileName: String,
            contentHash: String,
            contentBytes: ByteArray
        ): SafWriteOutcome {
            val sanitizedName = sanitizeGpxFileName(desiredFileName, contentHash)
            val baseName = sanitizedName.removeSuffix(".gpx").removeSuffix(".GPX")
            val shortHash = contentHash.take(8)

            var candidateName = sanitizedName
            var collisionIndex = 0
            while (true) {
                val existing = existingByLowerName[candidateName.lowercase(Locale.ROOT)]
                if (existing == null) {
                    break
                }
                if (matchesHash(existing, contentHash, contentBytes.size.toLong())) {
                    return SafWriteOutcome.SkippedExisting(existing.name ?: candidateName)
                }
                collisionIndex++
                candidateName = if (collisionIndex == 1) {
                    "${baseName}_${shortHash}.gpx"
                } else {
                    "${baseName}_${shortHash}_$collisionIndex.gpx"
                }
            }

            return try {
                val createdFile = treeDoc.createFile("application/gpx+xml", candidateName)
                    ?: return SafWriteOutcome.Failed
                val written = context.contentResolver.openOutputStream(createdFile.uri)?.use { out ->
                    out.write(contentBytes)
                    out.flush()
                    true
                } ?: false

                if (written) {
                    val actualName = createdFile.name ?: candidateName
                    existingByLowerName[actualName.lowercase(Locale.ROOT)] = createdFile
                    SafWriteOutcome.Written(actualName)
                } else {
                    createdFile.delete()
                    SafWriteOutcome.Failed
                }
            } catch (e: SecurityException) {
                Log.w(TAG, "SAF write permission revoked for $candidateName", e)
                SafWriteOutcome.Failed
            } catch (e: Exception) {
                Log.w(TAG, "SAF write failed for $candidateName", e)
                SafWriteOutcome.Failed
            }
        }

        private fun matchesHash(doc: DocumentFile, expectedHash: String, expectedSize: Long): Boolean {
            val docLength = doc.length()
            if (docLength > 0L && docLength != expectedSize) {
                return false
            }
            return try {
                val existingBytes = context.contentResolver.openInputStream(doc.uri)?.use { it.readBytes() }
                    ?: return false
                HashUtils.sha256Hex(existingBytes) == expectedHash
            } catch (_: Exception) {
                false
            }
        }
    }

    private fun sanitizeGpxFileName(rawName: String, fallbackHash: String): String {
        val cleaned = rawName.trim()
            .replace('/', '_')
            .replace('\\', '_')
        val nonBlank = cleaned.ifBlank { "trace_${fallbackHash.take(8)}.gpx" }
        return if (nonBlank.endsWith(".gpx", ignoreCase = true)) nonBlank else "$nonBlank.gpx"
    }

    companion object {
        private const val TAG = "AndroidGpxExporter"
        const val INTERNAL_TRACES_DIR_NAME = "gpx_traces"
    }
}
