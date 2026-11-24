package com.example.mopedtrackv2.presentation.importgpx

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.worker.GpxBulkImportWorker
import com.example.mopedtrackv2.domain.repository.MopedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ImportGpxViewModel(
    private val repository: MopedRepository,
    private val appContext: Context
) : ViewModel() {

    private val _importState = MutableStateFlow<WorkInfo.State?>(null)
    val importState: StateFlow<WorkInfo.State?> = _importState

    private val _progressData = MutableStateFlow<Data?>(null)
    val progressData: StateFlow<Data?> = _progressData

    val importedFiles = repository.getImportedFilesFlow().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )
    
    init {
        viewModelScope.launch {
            repository.deleteLegacyTraces()
        }
    }

    fun startImport(uri: Uri) {
        val request = OneTimeWorkRequestBuilder<GpxBulkImportWorker>()
            .setInputData(Data.Builder().putString("FOLDER_URI", uri.toString()).build())
            .build()

        val workManager = WorkManager.getInstance(appContext)
        workManager.enqueue(request)

        workManager.getWorkInfoByIdLiveData(request.id).observeForever { workInfo ->
            if (workInfo != null) {
                _importState.value = workInfo.state
                _progressData.value = workInfo.progress
            }
        }
    }
    
    fun rescanTraces() {
        val request = OneTimeWorkRequestBuilder<com.example.mopedtrackv2.data.worker.GpxRescanWorker>().build()
        val workManager = WorkManager.getInstance(appContext)
        workManager.enqueue(request)
        
        workManager.getWorkInfoByIdLiveData(request.id).observeForever { workInfo ->
            if (workInfo != null) {
                _importState.value = workInfo.state
                _progressData.value = workInfo.progress
            }
        }
    }

    fun toggleVisibility(file: ImportedGpxFileEntity) {
        viewModelScope.launch {
            repository.toggleGpxVisibility(file.fileHash, !file.isVisibleOnMap)
        }
    }

    fun setDebugTrace(file: ImportedGpxFileEntity, onComplete: () -> Unit) {
        viewModelScope.launch {
            repository.insertDebugTrace(
                DebugTraceEntity(
                    traceId = "last_trace",
                    pointsEncoded = file.encodedTrace,
                    pointCount = file.trackPointCount,
                    createdAt = System.currentTimeMillis()
                )
            )
            repository.setDebugOverlayEnabled(true)
            onComplete()
        }
    }
}
