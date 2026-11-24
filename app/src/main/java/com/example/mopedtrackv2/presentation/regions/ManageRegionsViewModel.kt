package com.example.mopedtrackv2.presentation.regions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.mopedtrackv2.data.remote.OverpassException
import com.example.mopedtrackv2.domain.model.Region
import com.example.mopedtrackv2.domain.repository.MopedRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(FlowPreview::class)
class ManageRegionsViewModel(
    private val repository: MopedRepository
) : ViewModel() {

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<Region>>(emptyList())
    val searchResults: StateFlow<List<Region>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _importingRegionId = MutableStateFlow<String?>(null)
    val importingRegionId: StateFlow<String?> = _importingRegionId.asStateFlow()

    private val _importingRegionName = MutableStateFlow<String?>(null)
    val importingRegionName: StateFlow<String?> = _importingRegionName.asStateFlow()

    private val _importProgress = MutableStateFlow(0f)
    val importProgress: StateFlow<Float> = _importProgress.asStateFlow()

    private val _importStatusMessage = MutableStateFlow("")
    val importStatusMessage: StateFlow<String> = _importStatusMessage.asStateFlow()

    val isLoading: StateFlow<Boolean> = combine(_isSearching, _isImporting) { searching, importing ->
        searching || importing
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _savedRegions = MutableStateFlow<List<Region>>(emptyList())
    val savedRegions: StateFlow<List<Region>> = _savedRegions.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private var searchJob: Job? = null

    init {
        viewModelScope.launch {
            repository.getAllRegions().collect {
                _savedRegions.value = it
            }
        }

        viewModelScope.launch {
            _searchQuery
                .debounce(400L)
                .distinctUntilChanged()
                .collect { query ->
                    val trimmed = query.trim()
                    if (trimmed.length >= 2) {
                        searchRegions(trimmed)
                    } else {
                        _searchResults.value = emptyList()
                    }
                }
        }
    }

    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
    }

    fun updateSearchQuery(query: String) {
        _searchQuery.value = query
    }

    fun searchRegions(query: String) {
        val trimmed = query.trim()
        if (trimmed.length < 2) {
            _searchResults.value = emptyList()
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            _isSearching.value = true
            _errorMessage.value = null
            try {
                val results = repository.searchRegions(trimmed)
                _searchResults.value = results
                if (results.isEmpty()) {
                    _errorMessage.value = "No regions found for '$trimmed'."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.printStackTrace()
                val message = if (e is OverpassException || e.message?.contains("Overpass") == true || e.message?.contains("502") == true) {
                    "Overpass servers are currently busy (HTTP 502). Please try again in a moment."
                } else {
                    e.localizedMessage ?: "Failed to search regions. Check your network connection."
                }
                _errorMessage.value = message
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun importRegion(region: Region) {
        if (_isImporting.value) return
        viewModelScope.launch {
            _isImporting.value = true
            _importingRegionId.value = region.regionId
            _importingRegionName.value = region.name
            _importProgress.value = 0f
            _importStatusMessage.value = "Starting download..."
            _errorMessage.value = null
            try {
                repository.importRegion(region) { progress, status ->
                    _importProgress.value = progress
                    _importStatusMessage.value = status
                }
                _searchResults.value = emptyList() // clear results after importing
            } catch (e: Exception) {
                e.printStackTrace()
                val message = if (e is OverpassException || e.message?.contains("Overpass") == true || e.message?.contains("502") == true) {
                    "Overpass servers are currently busy (HTTP 502). Please try again in a moment."
                } else {
                    e.localizedMessage ?: "Failed to import region '${region.name}'."
                }
                _errorMessage.value = message
            } finally {
                _isImporting.value = false
                _importingRegionId.value = null
                _importingRegionName.value = null
                _importProgress.value = 0f
                _importStatusMessage.value = ""
            }
        }
    }

    fun refreshRegion(region: Region) {
        if (_isImporting.value) return
        viewModelScope.launch {
            _isImporting.value = true
            _importingRegionId.value = region.regionId
            _importingRegionName.value = region.name
            _importProgress.value = 0f
            _importStatusMessage.value = "Starting refresh..."
            _errorMessage.value = null
            try {
                repository.importRegion(region) { progress, status ->
                    _importProgress.value = progress
                    _importStatusMessage.value = status
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val message = if (e is OverpassException || e.message?.contains("Overpass") == true || e.message?.contains("502") == true) {
                    "Overpass servers are currently busy (HTTP 502). Please try again in a moment."
                } else {
                    e.localizedMessage ?: "Failed to refresh region '${region.name}'."
                }
                _errorMessage.value = message
            } finally {
                _isImporting.value = false
                _importingRegionId.value = null
                _importingRegionName.value = null
                _importProgress.value = 0f
                _importStatusMessage.value = ""
            }
        }
    }

    fun refreshAllRegions() {
        if (_isImporting.value) return
        val regionsToRefresh = _savedRegions.value
        if (regionsToRefresh.isEmpty()) return

        viewModelScope.launch {
            _isImporting.value = true
            _importProgress.value = 0f
            _importStatusMessage.value = "Starting full refresh..."
            _errorMessage.value = null
            val total = regionsToRefresh.size
            try {
                regionsToRefresh.forEachIndexed { index, region ->
                    _importingRegionId.value = region.regionId
                    _importingRegionName.value = region.name
                    repository.importRegion(region) { progress, status ->
                        val overallProgress = (index + progress) / total
                        _importProgress.value = overallProgress
                        _importStatusMessage.value = "Region ${index + 1} of $total (${region.name}): $status"
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val message = if (e is OverpassException || e.message?.contains("Overpass") == true || e.message?.contains("502") == true) {
                    "Overpass servers are currently busy (HTTP 502). Please try again in a moment."
                } else {
                    e.localizedMessage ?: "Failed to refresh all regions."
                }
                _errorMessage.value = message
            } finally {
                _isImporting.value = false
                _importingRegionId.value = null
                _importingRegionName.value = null
                _importProgress.value = 0f
                _importStatusMessage.value = ""
            }
        }
    }

    fun deleteRegion(region: Region) {
        viewModelScope.launch {
            _errorMessage.value = null
            try {
                repository.deleteRegion(region.regionId)
            } catch (e: Exception) {
                e.printStackTrace()
                _errorMessage.value = e.localizedMessage ?: "Failed to delete region '${region.name}'."
            }
        }
    }

    fun clearErrorMessage() {
        _errorMessage.value = null
    }
}
