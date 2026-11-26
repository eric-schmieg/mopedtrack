package com.example.mopedtrackv2.presentation.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mopedtrackv2.domain.gpx.GpxMetadataFormatter
import com.example.mopedtrackv2.domain.model.UnitSystem
import com.example.mopedtrackv2.presentation.importgpx.ImportGpxViewModel
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    importGpxViewModel: ImportGpxViewModel,
    onBack: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                windowInsets = WindowInsets.statusBars,
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        SettingsPanelContent(
            viewModel = viewModel,
            importGpxViewModel = importGpxViewModel,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        )
    }
}

@Composable
fun SettingsPanelContent(
    viewModel: SettingsViewModel,
    importGpxViewModel: ImportGpxViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val matchRadius by viewModel.matchRadius.collectAsState()
    val coverageThreshold by viewModel.coverageThreshold.collectAsState()
    val debugOverlayEnabled by viewModel.debugOverlayEnabled.collectAsState()
    val unitSystem by viewModel.unitSystem.collectAsState()
    val pollingIntervalMs by viewModel.pollingIntervalMs.collectAsState()
    val minDisplacementMeters by viewModel.minDisplacementMeters.collectAsState()
    val gpxExportTreeUri by viewModel.gpxExportTreeUri.collectAsState()
    val weeklyAutoRefresh by viewModel.weeklyAutoRefresh.collectAsState()
    val isCopyingTraces by viewModel.isCopyingTracesToExternal.collectAsState()
    val copyProgressText by viewModel.copyProgressText.collectAsState()

    LaunchedEffect(viewModel) {
        viewModel.toastMessages.collect { message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    val exportFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            viewModel.setGpxExportTreeUri(uri.toString())
        }
    }

    val importFolderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
            }
            importGpxViewModel.startImport(uri)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("Units", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = unitSystem == UnitSystem.IMPERIAL,
                onClick = { viewModel.setUnitSystem(UnitSystem.IMPERIAL) },
                label = { Text("Imperial (mi / ft)") },
                modifier = Modifier.weight(1f)
            )
            FilterChip(
                selected = unitSystem == UnitSystem.METRIC,
                onClick = { viewModel.setUnitSystem(UnitSystem.METRIC) },
                label = { Text("Metric (km / m)") },
                modifier = Modifier.weight(1f)
            )
        }

        HorizontalDivider()

        Text("Segment Matching", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Column {
            val radiusLabel = GpxMetadataFormatter.formatShortDistanceMeters(matchRadius.toDouble(), unitSystem)
            Text("Match Radius: $radiusLabel (${matchRadius}m)", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = matchRadius.toFloat(),
                onValueChange = { viewModel.setMatchRadius(it.roundToInt()) },
                valueRange = 5f..30f,
                steps = 24
            )
        }

        Column {
            Text("Coverage Threshold: ${coverageThreshold}%", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = coverageThreshold.toFloat(),
                onValueChange = { viewModel.setCoverageThreshold(it.roundToInt()) },
                valueRange = 50f..100f,
                steps = 49
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Debug Verification Overlay",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge
            )
            Switch(
                checked = debugOverlayEnabled,
                onCheckedChange = { viewModel.setDebugOverlayEnabled(it) }
            )
        }

        HorizontalDivider()

        Text("Ride Recording GPS", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Column {
            Text("GPS Polling Interval: ${pollingIntervalMs} ms", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = pollingIntervalMs.toFloat(),
                onValueChange = {
                    val snapped = ((it / 500f).roundToInt() * 500L).coerceIn(500L, 5000L)
                    viewModel.setPollingIntervalMs(snapped)
                },
                valueRange = 500f..5000f,
                steps = 8
            )
        }

        Column {
            val dispLabel = GpxMetadataFormatter.formatShortDistanceMeters(minDisplacementMeters.toDouble(), unitSystem)
            Text(
                text = "Min Displacement: $dispLabel (${minDisplacementMeters.roundToInt()}m)",
                style = MaterialTheme.typography.bodyLarge
            )
            Slider(
                value = minDisplacementMeters,
                onValueChange = { viewModel.setMinDisplacementMeters(it.roundToInt().toFloat()) },
                valueRange = 0f..15f,
                steps = 14
            )
        }

        HorizontalDivider()

        Text("GPX Storage & Import", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val exportLabel = gpxExportTreeUri?.let { Uri.parse(it).lastPathSegment ?: it }
                ?: "Not set (Internal storage only)"
            Text(
                text = "External Trace Folder: $exportLabel",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { exportFolderLauncher.launch(null) },
                    enabled = !isCopyingTraces,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        imageVector = Icons.Default.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("External Folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (gpxExportTreeUri != null) {
                    TextButton(
                        onClick = { viewModel.setGpxExportTreeUri(null) },
                        enabled = !isCopyingTraces
                    ) {
                        Text("Reset")
                    }
                }
            }

            if (isCopyingTraces) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                copyProgressText?.let { progressMsg ->
                    Text(
                        text = progressMsg,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            FilledTonalButton(
                onClick = { importFolderLauncher.launch(null) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.FolderOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Bulk Import GPX Folder")
            }
        }

        HorizontalDivider()

        Text("Map Data", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Weekly Auto-Refresh Map Data",
                    style = MaterialTheme.typography.bodyLarge
                )
                Text(
                    text = "Refresh downloaded regions every 7 days over Wi-Fi while preserving completed roads",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = weeklyAutoRefresh,
                onCheckedChange = { viewModel.setWeeklyAutoRefresh(it) }
            )
        }
    }
}
