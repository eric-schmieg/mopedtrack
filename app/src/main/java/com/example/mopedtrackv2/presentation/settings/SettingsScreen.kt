package com.example.mopedtrackv2.presentation.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit
) {
    val matchRadius by viewModel.matchRadius.collectAsState()
    val coverageThreshold by viewModel.coverageThreshold.collectAsState()
    val debugOverlayEnabled by viewModel.debugOverlayEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Text("Match Radius: ${matchRadius}m", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = matchRadius.toFloat(),
                onValueChange = { viewModel.setMatchRadius(it.toInt()) },
                valueRange = 5f..30f,
                steps = 25
            )

            Spacer(modifier = Modifier.height(24.dp))

            Text("Coverage Threshold: ${coverageThreshold}%", style = MaterialTheme.typography.titleMedium)
            Slider(
                value = coverageThreshold.toFloat(),
                onValueChange = { viewModel.setCoverageThreshold(it.toInt()) },
                valueRange = 50f..100f,
                steps = 50
            )

            Spacer(modifier = Modifier.height(24.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Debug Verification Overlay", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Switch(
                    checked = debugOverlayEnabled,
                    onCheckedChange = { viewModel.setDebugOverlayEnabled(it) }
                )
            }
        }
    }
}
