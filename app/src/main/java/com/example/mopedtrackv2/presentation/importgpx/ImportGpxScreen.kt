package com.example.mopedtrackv2.presentation.importgpx

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportGpxScreen(
    viewModel: ImportGpxViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val importState by viewModel.importState.collectAsState()
    val progressData by viewModel.progressData.collectAsState()
    val importedFiles by viewModel.importedFiles.collectAsState()

    var showMenu by remember { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            viewModel.startImport(uri)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage GPX") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "More options")
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("Import Folder containing .gpx files") },
                            onClick = {
                                showMenu = false
                                launcher.launch(null)
                            }
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Imported Traces", style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { viewModel.rescanTraces() }) {
                            Text("Re-scan Traces")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                if (importedFiles.isEmpty()) {
                    item {
                        Text("No GPX files imported yet.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                items(importedFiles) { file ->
                    GpxFileItem(
                        file = file, 
                        onToggleVisibility = { viewModel.toggleVisibility(file) }
                    )
                }
            }

            if (importState == WorkInfo.State.ENQUEUED ||
                importState == WorkInfo.State.RUNNING ||
                importState == WorkInfo.State.SUCCEEDED ||
                importState == WorkInfo.State.FAILED
            ) {
                Surface(
                    tonalElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        when (importState) {
                            WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING -> {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                val titleText = progressData?.getString("STATUS_TEXT") ?: "Importing..."
                                Text(titleText, style = MaterialTheme.typography.titleMedium)

                                progressData?.let { data ->
                                    val current = data.getInt("CURRENT_FILE", 0)
                                    val total = data.getInt("TOTAL_FILES", 0)
                                    val skipped = data.getInt("SKIPPED_FILES", 0)
                                    val newSegments = data.getInt("NEW_SEGMENTS", 0)

                                    Text("Processing $current of $total files")
                                    if (skipped > 0) {
                                        Text("Skipped (Duplicates): $skipped")
                                    }
                                    Text("New Segments Completed: $newSegments")
                                }
                            }
                            WorkInfo.State.SUCCEEDED -> {
                                Text("Import Completed Successfully!", color = MaterialTheme.colorScheme.primary)
                            }
                            WorkInfo.State.FAILED -> {
                                Text("Import Failed.", color = MaterialTheme.colorScheme.error)
                            }
                            else -> {}
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GpxFileItem(file: ImportedGpxFileEntity, onToggleVisibility: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(file.fileName, style = MaterialTheme.typography.titleMedium)
                val sdf = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
                val date = remember(file.importedAt) { sdf.format(Date(file.importedAt)) }
                Text("Imported: $date", style = MaterialTheme.typography.bodySmall)
                Text("Points: ${file.trackPointCount} | Segments Completed: ${file.segmentsCompletedCount}", style = MaterialTheme.typography.bodySmall)
            }
            Switch(
                checked = file.isVisibleOnMap,
                onCheckedChange = onToggleVisibility
            )
        }
    }
}
