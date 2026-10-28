package com.fujikon.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.fujikon.app.FujiKonApp
import com.fujikon.app.ui.screens.EditorScreen
import com.fujikon.app.ui.screens.GalleryScreen
import com.fujikon.app.ui.screens.PresetDetailScreen
import com.fujikon.app.ui.screens.PresetsScreen
import com.fujikon.app.ui.screens.SettingsScreen
import com.fujikon.app.ui.viewmodel.FujiKonViewModelFactory
import com.fujikon.app.ui.viewmodel.PresetsViewModel

object Routes {
    const val PRESETS = "presets"
    const val PRESET_DETAIL = "preset/{id}"
    const val GALLERY = "gallery"
    const val EDITOR = "editor"
    const val SETTINGS = "settings"

    fun presetDetail(id: String) = "preset/$id"
}

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val container = (context.applicationContext as FujiKonApp).container
    val factory = remember { FujiKonViewModelFactory(container) }
    val presetsViewModel: PresetsViewModel = viewModel(factory = factory)

    NavHost(navController = navController, startDestination = Routes.PRESETS) {
        composable(Routes.PRESETS) {
            PresetsScreen(
                navController = navController,
                viewModel = presetsViewModel,
                factory = factory
            )
        }
        composable(Routes.PRESET_DETAIL) { entry ->
            PresetDetailScreen(
                navController = navController,
                presetId = entry.arguments?.getString("id"),
                viewModel = presetsViewModel,
                factory = factory
            )
        }
        composable(Routes.GALLERY) {
            GalleryScreen(
                navController = navController,
                factory = factory
            )
        }
        composable(Routes.EDITOR) {
            EditorScreen(
                navController = navController,
                factory = factory
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                navController = navController,
                presetsViewModel = presetsViewModel
            )
        }
    }
}
