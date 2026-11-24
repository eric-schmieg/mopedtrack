package com.example.mopedtrackv2.presentation.regions

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.example.mopedtrackv2.domain.model.Region

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManageRegionsScreen(
    viewModel: ManageRegionsViewModel,
    onBack: () -> Unit
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val searchResults by viewModel.searchResults.collectAsState()
    val savedRegions by viewModel.savedRegions.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val isImporting by viewModel.isImporting.collectAsState()
    val importingRegionId by viewModel.importingRegionId.collectAsState()
    val importingRegionName by viewModel.importingRegionName.collectAsState()
    val importProgress by viewModel.importProgress.collectAsState()
    val importStatusMessage by viewModel.importStatusMessage.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let { message ->
            snackbarHostState.showSnackbar(message)
            viewModel.clearErrorMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Regions") },
                windowInsets = WindowInsets.statusBars,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { viewModel.onSearchQueryChanged(it) },
                label = { Text("Search Regions") },
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    IconButton(
                        onClick = { viewModel.searchRegions(searchQuery) },
                        enabled = (searchQuery.trim().length >= 2) && !isSearching
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = {
                        if (searchQuery.trim().length >= 2) {
                            viewModel.searchRegions(searchQuery)
                        }
                    }
                ),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(16.dp))

            if (isSearching) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Searching regions...", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (isImporting) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (importStatusMessage.isNotBlank()) {
                                importStatusMessage
                            } else if (!importingRegionName.isNullOrBlank()) {
                                "Downloading road segments for $importingRegionName..."
                            } else {
                                "Ingesting OSM map data..."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        LinearProgressIndicator(
                            progress = { importProgress },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "${(importProgress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            if (searchResults.isNotEmpty()) {
                Text("Search Results", style = MaterialTheme.typography.titleMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(searchResults) { region ->
                        val isThisRegionImporting = isImporting && (importingRegionId == region.regionId)
                        RegionItem(
                            region = region,
                            actionIcon = Icons.Default.Add,
                            onActionClick = { viewModel.importRegion(region) },
                            isActionEnabled = !isImporting,
                            isLoadingAction = isThisRegionImporting
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Saved Regions", style = MaterialTheme.typography.titleMedium)
                    if (savedRegions.isNotEmpty()) {
                        IconButton(
                            onClick = { viewModel.refreshAllRegions() },
                            enabled = !isImporting
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh All Regions")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(savedRegions) { region ->
                        val isThisRegionImporting = isImporting && (importingRegionId == region.regionId)
                        SavedRegionItem(
                            region = region,
                            onRefreshClick = { viewModel.refreshRegion(region) },
                            onDeleteClick = { viewModel.deleteRegion(region) },
                            isActionEnabled = !isImporting,
                            isLoadingAction = isThisRegionImporting
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SavedRegionItem(
    region: Region,
    onRefreshClick: () -> Unit,
    onDeleteClick: () -> Unit,
    isActionEnabled: Boolean = true,
    isLoadingAction: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = region.name, style = MaterialTheme.typography.bodyLarge)
                Text(text = "OSM ID: ${region.osmRelationId}", style = MaterialTheme.typography.bodySmall)
            }
            if (isLoadingAction) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else {
                IconButton(
                    onClick = onRefreshClick,
                    enabled = isActionEnabled
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "Refresh Region")
                }
                IconButton(
                    onClick = onDeleteClick,
                    enabled = isActionEnabled
                ) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Region")
                }
            }
        }
    }
}

@Composable
fun RegionItem(
    region: Region,
    actionIcon: androidx.compose.ui.graphics.vector.ImageVector?,
    onActionClick: () -> Unit,
    isActionEnabled: Boolean = true,
    isLoadingAction: Boolean = false
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = region.name, style = MaterialTheme.typography.bodyLarge)
                Text(text = "OSM ID: ${region.osmRelationId}", style = MaterialTheme.typography.bodySmall)
            }
            if (isLoadingAction) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            } else if (actionIcon != null) {
                IconButton(
                    onClick = onActionClick,
                    enabled = isActionEnabled
                ) {
                    Icon(actionIcon, contentDescription = "Action")
                }
            }
        }
    }
}
