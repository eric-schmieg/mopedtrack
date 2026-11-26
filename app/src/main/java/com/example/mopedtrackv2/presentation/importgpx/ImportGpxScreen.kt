package com.example.mopedtrackv2.presentation.importgpx

import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import com.example.mopedtrackv2.data.local.entity.ImportedGpxFileEntity
import com.example.mopedtrackv2.domain.gpx.GpxMetadataFormatter
import com.example.mopedtrackv2.domain.model.UnitSystem
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
    var showMenu by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.toastMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

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
                title = { Text("Traces") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
        ManageGpxPanelContent(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

@Composable
fun ManageGpxPanelContent(
    viewModel: ImportGpxViewModel,
    modifier: Modifier = Modifier
) {
    val importState by viewModel.importState.collectAsState()
    val progressData by viewModel.progressData.collectAsState()
    val importedFiles by viewModel.importedFiles.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    var tracePendingDeletion by remember { mutableStateOf<ImportedGpxFileEntity?>(null) }

    tracePendingDeletion?.let { trace ->
        AlertDialog(
            onDismissRequest = { tracePendingDeletion = null },
            title = { Text("Delete Trace?") },
            text = { Text("Are you sure you want to delete \"${trace.fileName}\"?") },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.deleteTrace(trace)
                        tracePendingDeletion = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { tracePendingDeletion = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            FilledTonalButton(
                onClick = { viewModel.rescanTraces() },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "Re-scan Traces",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        HorizontalDivider()

        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (importedFiles.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.setAllVisibility(true) },
                            enabled = importedFiles.any { !it.isVisibleOnMap },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Enable All",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.setAllVisibility(false) },
                            enabled = importedFiles.any { it.isVisibleOnMap },
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = "Disable All",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                }
            }

            if (importedFiles.isEmpty()) {
                item {
                    Text(
                        text = "No GPX files imported yet.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            items(importedFiles, key = { it.fileHash }) { file ->
                GpxFileItem(
                    file = file,
                    unitSystem = unitSystem,
                    onToggleVisibility = { viewModel.toggleVisibility(file) },
                    onDeleteClick = { tracePendingDeletion = file }
                )
            }
        }

        if (importState == WorkInfo.State.ENQUEUED ||
            importState == WorkInfo.State.RUNNING ||
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
                            Spacer(modifier = Modifier.height(12.dp))
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

@Composable
fun GpxFileItem(
    file: ImportedGpxFileEntity,
    unitSystem: UnitSystem = UnitSystem.IMPERIAL,
    onToggleVisibility: (Boolean) -> Unit,
    onDeleteClick: () -> Unit = {}
) {
    Card(
        onClick = { onToggleVisibility(!file.isVisibleOnMap) },
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = file.fileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val sdf = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
                val displayTimestamp = file.recordedAt ?: file.importedAt
                val dateLabel = if (file.recordedAt != null) "Recorded" else "Imported"
                val date = remember(displayTimestamp) { sdf.format(Date(displayTimestamp)) }
                Text(
                    text = "$dateLabel: $date",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                val resolvedDistanceMeters = remember(file.distanceMeters, file.encodedTrace, file.trackPointCount) {
                    if (file.distanceMeters > 0.0 || file.trackPointCount < 2) {
                        file.distanceMeters
                    } else {
                        GpxMetadataFormatter.computeDistanceFromEncodedTrace(file.encodedTrace)
                    }
                }
                val distanceText = remember(resolvedDistanceMeters, unitSystem) {
                    GpxMetadataFormatter.formatDistance(resolvedDistanceMeters, unitSystem)
                }
                val durationText = remember(file.durationMillis) {
                    GpxMetadataFormatter.formatDuration(file.durationMillis)
                }
                Text(
                    text = "Distance: $distanceText | Time: $durationText",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = "Points: ${file.trackPointCount} | Segments: ${file.segmentsCompletedCount}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = file.isVisibleOnMap,
                onCheckedChange = onToggleVisibility
            )
            IconButton(onClick = onDeleteClick) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete Trace",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
