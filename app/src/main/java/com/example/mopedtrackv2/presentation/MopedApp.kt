package com.example.mopedtrackv2.presentation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.ui.NavDisplay
import com.example.mopedtrackv2.MopedApplication
import com.example.mopedtrackv2.presentation.home.HomeViewModel
import com.example.mopedtrackv2.presentation.home.HomeScreen
import com.example.mopedtrackv2.presentation.importgpx.ImportGpxScreen
import com.example.mopedtrackv2.presentation.importgpx.ImportGpxViewModel
import com.example.mopedtrackv2.presentation.pins.OsmPinsScreen
import com.example.mopedtrackv2.presentation.pins.OsmPinsViewModel
import com.example.mopedtrackv2.presentation.regions.ManageRegionsScreen
import com.example.mopedtrackv2.presentation.regions.ManageRegionsViewModel
import com.example.mopedtrackv2.presentation.settings.SettingsScreen
import com.example.mopedtrackv2.presentation.settings.SettingsViewModel
import com.example.mopedtrackv2.presentation.stats.StatsScreen
import com.example.mopedtrackv2.presentation.stats.StatsViewModel

sealed interface MopedRoute {
    data object Home : MopedRoute
    data object ManageRegions : MopedRoute
    data object Settings : MopedRoute
    data object ImportGpx : MopedRoute
    data object OsmPins : MopedRoute
    data object Stats : MopedRoute
}

@Composable
fun MopedApp() {
    val backStack = remember { mutableStateListOf<Any>(MopedRoute.Home) }

    val context = LocalContext.current
    val application = context.applicationContext as MopedApplication
    val factory = remember { ViewModelFactory(application.repository, context) }

    NavDisplay(
        backStack = backStack,
        onBack = { backStack.removeLastOrNull() },
        entryProvider = { key ->
            when (key) {
                is MopedRoute.Home -> NavEntry(key) {
                    val viewModel: HomeViewModel = viewModel(factory = factory)
                    val importGpxViewModel: ImportGpxViewModel = viewModel(factory = factory)
                    val manageRegionsViewModel: ManageRegionsViewModel = viewModel(factory = factory)
                    val settingsViewModel: SettingsViewModel = viewModel(factory = factory)
                    val osmPinsViewModel: OsmPinsViewModel = viewModel(factory = factory)
                    val statsViewModel: StatsViewModel = viewModel(factory = factory)
                    HomeScreen(
                        viewModel = viewModel,
                        importGpxViewModel = importGpxViewModel,
                        manageRegionsViewModel = manageRegionsViewModel,
                        settingsViewModel = settingsViewModel,
                        osmPinsViewModel = osmPinsViewModel,
                        statsViewModel = statsViewModel
                    )
                }
                is MopedRoute.ManageRegions -> NavEntry(key) {
                    val viewModel: ManageRegionsViewModel = viewModel(factory = factory)
                    ManageRegionsScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }
                is MopedRoute.Settings -> NavEntry(key) {
                    val viewModel: SettingsViewModel = viewModel(factory = factory)
                    val importGpxViewModel: ImportGpxViewModel = viewModel(factory = factory)
                    SettingsScreen(
                        viewModel = viewModel,
                        importGpxViewModel = importGpxViewModel,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }
                is MopedRoute.ImportGpx -> NavEntry(key) {
                    val viewModel: ImportGpxViewModel = viewModel(factory = factory)
                    ImportGpxScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }
                is MopedRoute.OsmPins -> NavEntry(key) {
                    val viewModel: OsmPinsViewModel = viewModel(factory = factory)
                    OsmPinsScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }
                is MopedRoute.Stats -> NavEntry(key) {
                    val viewModel: StatsViewModel = viewModel(factory = factory)
                    StatsScreen(
                        viewModel = viewModel,
                        onBack = { backStack.removeLastOrNull() }
                    )
                }
                else -> error("Unknown route: $key")
            }
        }
    )
}
