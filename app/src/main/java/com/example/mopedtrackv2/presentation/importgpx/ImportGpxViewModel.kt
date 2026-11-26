package com.example.mopedtrackv2.presentation.importgpx

import android.content.Context
import android.net.Uri
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.example.mopedtrackv2.data.local.entity.DebugTraceEntity
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.data.worker.GpxBulkImportWorker
import com.example.mopedtrackv2.domain.model.UnitSystem
import com.example.mopedtrackv2.domain.repository.MopedRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
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

    private val _toastMessages = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessages: SharedFlow<String> = _toastMessages.asSharedFlow()

    val importedFiles = repository.getImportedFilesFlow().stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList()
    )

    val unitSystem = repository.unitSystemFlow.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), UnitSystem.IMPERIAL
    )
    
    init {
        viewModelScope.launch {
            repository.deleteLegacyTraces()
            repository.backfillGpxMetadata()
        }
    }

    internal fun handleWorkInfoUpdate(state: WorkInfo.State, progress: Data) {
        if (state == WorkInfo.State.SUCCEEDED) {
            _importState.value = null
            _progressData.value = null
            _toastMessages.tryEmit("Import completed successfully!")
        } else {
            _importState.value = state
            _progressData.value = progress
        }
    }

    private fun observeWork(workId: java.util.UUID) {
        val workManager = WorkManager.getInstance(appContext)
        val liveData = workManager.getWorkInfoByIdLiveData(workId)
        val observer = object : Observer<WorkInfo?> {
            override fun onChanged(value: WorkInfo?) {
                if (value != null) {
                    handleWorkInfoUpdate(value.state, value.progress)
                    if (value.state.isFinished) {
                        liveData.removeObserver(this)
                    }
                }
            }
        }
        liveData.observeForever(observer)
    }

    fun startImport(uri: Uri) {
        val request = OneTimeWorkRequestBuilder<GpxBulkImportWorker>()
            .setInputData(Data.Builder().putString("FOLDER_URI", uri.toString()).build())
            .build()

        WorkManager.getInstance(appContext).enqueue(request)
        observeWork(request.id)
    }
    
    fun rescanTraces() {
        val request = OneTimeWorkRequestBuilder<com.example.mopedtrackv2.data.worker.GpxRescanWorker>().build()
        WorkManager.getInstance(appContext).enqueue(request)
        observeWork(request.id)
    }

    fun toggleVisibility(file: ImportedGpxFileEntity) {
        viewModelScope.launch {
            repository.toggleGpxVisibility(file.fileHash, !file.isVisibleOnMap)
        }
    }

    fun setAllVisibility(isVisible: Boolean) {
        viewModelScope.launch {
            repository.setAllGpxVisibility(isVisible)
        }
    }

    fun deleteTrace(file: ImportedGpxFileEntity) {
        viewModelScope.launch {
            repository.deleteImportedFile(file.fileHash)
            _toastMessages.tryEmit("Deleted ${file.fileName}")
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
