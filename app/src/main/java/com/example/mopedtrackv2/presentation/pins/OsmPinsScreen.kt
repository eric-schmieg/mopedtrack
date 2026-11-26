package com.example.mopedtrackv2.presentation.pins

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.mopedtrackv2.data.local.entity.OsmPinEntity
import com.example.mopedtrackv2.domain.model.OsmPinCategory
import com.example.mopedtrackv2.domain.pins.OsmPinExportFormat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class PinFilter(val label: String) {
    ALL("All"),
    UNRESOLVED("Unresolved"),
    RESOLVED("Resolved")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OsmPinsScreen(
    viewModel: OsmPinsViewModel,
    onBack: () -> Unit
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val pins by viewModel.pins.collectAsState()
    var showExportDialog by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel) {
        viewModel.snackbarMessages.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("OSM Pins") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { showExportDialog = true },
                        enabled = pins.isNotEmpty()
                    ) {
                        Icon(
                            imageVector = Icons.Default.FileUpload,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Export")
                    }
                }
            )
        }
    ) { paddingValues ->
        OsmPinsPanelContent(
            viewModel = viewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
            showExportDialogExternal = showExportDialog,
            onDismissExportDialogExternal = { showExportDialog = false },
            onOpenExportDialogExternal = { showExportDialog = true }
        )
    }
}

@Composable
fun OsmPinsPanelContent(
    viewModel: OsmPinsViewModel,
    modifier: Modifier = Modifier,
    showExportDialogExternal: Boolean? = null,
    onDismissExportDialogExternal: (() -> Unit)? = null,
    onOpenExportDialogExternal: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pins by viewModel.pins.collectAsState()

    var selectedFilter by remember { mutableStateOf(PinFilter.ALL) }
    var internalShowExportDialog by remember { mutableStateOf(false) }
    var selectedFormat by remember { mutableStateOf(OsmPinExportFormat.GPX) }
    var exportUnresolvedOnly by remember { mutableStateOf(true) }
    var pendingSavePayload by remember { mutableStateOf<OsmPinExportPayload?>(null) }

    val isExportDialogVisible = showExportDialogExternal ?: internalShowExportDialog
    val openExportDialog = onOpenExportDialogExternal ?: { internalShowExportDialog = true }
    val closeExportDialog = onDismissExportDialogExternal ?: { internalShowExportDialog = false }

    val writePayloadToUri: (Uri, OsmPinExportPayload) -> Unit = { uri, payload ->
        scope.launch {
            val success = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(payload.content.toByteArray(Charsets.UTF_8))
                        out.flush()
                        true
                    } ?: false
                } catch (_: Exception) {
                    false
                }
            }
            if (success) {
                viewModel.notifyMessage("Exported ${payload.pinCount} pin(s) as ${payload.format.extension.uppercase(Locale.US)}")
            } else {
                viewModel.notifyMessage("Failed to export pins")
            }
        }
    }

    val gpxCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(OsmPinExportFormat.GPX.mimeType)
    ) { uri ->
        val payload = pendingSavePayload
        pendingSavePayload = null
        if (uri != null && payload != null) {
            writePayloadToUri(uri, payload)
        }
    }

    val geoJsonCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(OsmPinExportFormat.GEOJSON.mimeType)
    ) { uri ->
        val payload = pendingSavePayload
        pendingSavePayload = null
        if (uri != null && payload != null) {
            writePayloadToUri(uri, payload)
        }
    }

    val csvCreateLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(OsmPinExportFormat.CSV.mimeType)
    ) { uri ->
        val payload = pendingSavePayload
        pendingSavePayload = null
        if (uri != null && payload != null) {
            writePayloadToUri(uri, payload)
        }
    }

    val unresolvedCount = remember(pins) { pins.count { !it.isResolved } }
    val resolvedCount = remember(pins) { pins.count { it.isResolved } }
    val filteredPins = remember(pins, selectedFilter) {
        when (selectedFilter) {
            PinFilter.ALL -> pins
            PinFilter.UNRESOLVED -> pins.filter { !it.isResolved }
            PinFilter.RESOLVED -> pins.filter { it.isResolved }
        }
    }

    if (isExportDialogVisible) {
        val exportCount = if (exportUnresolvedOnly) unresolvedCount else pins.size
        AlertDialog(
            onDismissRequest = closeExportDialog,
            title = { Text("Export OSM Pins") },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Choose a format to open in OpenStreetMap.org, JOSM, or iD editor.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OsmPinExportFormat.entries.forEach { format ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedFormat == format,
                                onClick = { selectedFormat = format }
                            )
                            Text(
                                text = format.label,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Unresolved pins only",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "$exportCount pin(s) selected",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = exportUnresolvedOnly,
                            onCheckedChange = { exportUnresolvedOnly = it }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            val payload = viewModel.buildExportPayload(
                                format = selectedFormat,
                                unresolvedOnly = exportUnresolvedOnly
                            )
                            if (payload != null) {
                                pendingSavePayload = payload
                                closeExportDialog()
                                when (selectedFormat) {
                                    OsmPinExportFormat.GPX -> gpxCreateLauncher.launch(payload.fileName)
                                    OsmPinExportFormat.GEOJSON -> geoJsonCreateLauncher.launch(payload.fileName)
                                    OsmPinExportFormat.CSV -> csvCreateLauncher.launch(payload.fileName)
                                }
                            }
                        },
                        enabled = exportCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Save to File")
                    }
                    OutlinedButton(
                        onClick = {
                            val payload = viewModel.buildExportPayload(
                                format = selectedFormat,
                                unresolvedOnly = exportUnresolvedOnly
                            )
                            if (payload != null) {
                                closeExportDialog()
                                scope.launch {
                                    shareExportedPins(context, payload, onError = {
                                        viewModel.notifyMessage("Unable to share pins")
                                    })
                                }
                            }
                        },
                        enabled = exportCount > 0,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Share")
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = closeExportDialog) {
                    Text("Cancel")
                }
            }
        )
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${pins.size} Saved Pin(s)",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "$unresolvedCount unresolved · $resolvedCount resolved",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            FilledTonalButton(
                onClick = openExportDialog,
                enabled = pins.isNotEmpty()
            ) {
                Icon(
                    imageVector = Icons.Default.FileUpload,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Export Pins")
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PinFilter.entries.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = { selectedFilter = filter },
                    label = { Text(filter.label) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        HorizontalDivider()

        if (filteredPins.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = when {
                        pins.isEmpty() -> "No OSM pins saved yet. Tap the Drop Pin button on the map to mark missing roads or map issues."
                        selectedFilter == PinFilter.UNRESOLVED -> "No unresolved pins."
                        selectedFilter == PinFilter.RESOLVED -> "No resolved pins."
                        else -> "No pins."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(filteredPins, key = { it.pinId }) { pin ->
                    OsmPinListItem(
                        pin = pin,
                        onToggleResolved = { viewModel.togglePinResolved(pin) },
                        onDelete = { viewModel.deletePin(pin) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OsmPinListItem(
    pin: OsmPinEntity,
    onToggleResolved: () -> Unit,
    onDelete: () -> Unit
) {
    val categoryLabel = remember(pin.category) {
        OsmPinCategory.fromCategoryName(pin.category)?.label ?: pin.category
    }
    val sdf = remember { SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault()) }
    val timestampText = remember(pin.createdAt) { sdf.format(Date(pin.createdAt)) }
    val coordsText = remember(pin.lat, pin.lon) {
        String.format(Locale.US, "%.5f, %.5f", pin.lat, pin.lon)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = if (pin.isResolved) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = if (pin.isResolved) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = categoryLabel,
                            style = MaterialTheme.typography.labelMedium,
                            color = if (pin.isResolved) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            },
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                    if (pin.isResolved) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Resolved",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                if (pin.note.isNotBlank()) {
                    Text(
                        text = pin.note,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Text(
                    text = coordsText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = timestampText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onToggleResolved) {
                Icon(
                    imageVector = if (pin.isResolved) {
                        Icons.Filled.CheckCircle
                    } else {
                        Icons.Outlined.CheckCircle
                    },
                    contentDescription = if (pin.isResolved) "Mark unresolved" else "Mark resolved",
                    tint = if (pin.isResolved) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Delete pin",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

private suspend fun shareExportedPins(
    context: Context,
    payload: OsmPinExportPayload,
    onError: () -> Unit
) {
    val shareUri = withContext(Dispatchers.IO) {
        try {
            val exportDir = File(context.cacheDir, "exports")
            if (!exportDir.exists()) exportDir.mkdirs()
            val outFile = File(exportDir, payload.fileName)
            outFile.writeText(payload.content, Charsets.UTF_8)
            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                outFile
            )
        } catch (_: Exception) {
            null
        }
    }
    if (shareUri == null) {
        onError()
        return
    }
    val sendIntent = Intent(Intent.ACTION_SEND).apply {
        type = payload.format.mimeType
        putExtra(Intent.EXTRA_STREAM, shareUri)
        putExtra(Intent.EXTRA_SUBJECT, payload.fileName)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(sendIntent, "Export OSM Pins")
    context.startActivity(chooser)
}
