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
import com.example.mopedtrackv2.presentation.regions.ManageRegionsScreen
import com.example.mopedtrackv2.presentation.regions.ManageRegionsViewModel
import com.example.mopedtrackv2.presentation.settings.SettingsScreen
import com.example.mopedtrackv2.presentation.settings.SettingsViewModel

sealed interface MopedRoute {
    data object Home : MopedRoute
    data object ManageRegions : MopedRoute
    data object Settings : MopedRoute
    data object ImportGpx : MopedRoute
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
                    HomeScreen(
                        viewModel = viewModel,
                        onManageRegionsClick = { backStack.add(MopedRoute.ManageRegions) },
                        onSettingsClick = { backStack.add(MopedRoute.Settings) },
                        onImportGpxClick = { backStack.add(MopedRoute.ImportGpx) }
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
                    SettingsScreen(
                        viewModel = viewModel,
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
                else -> error("Unknown route: $key")
            }
        }
    )
}
