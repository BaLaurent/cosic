package com.octopusman.cosic.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.octopusman.cosic.CosicApp
import com.octopusman.cosic.editor.EditorScreen
import com.octopusman.cosic.editor.EditorViewModel
import com.octopusman.cosic.library.LibraryScreen
import com.octopusman.cosic.library.LibraryViewModel
import com.octopusman.cosic.monetization.PurchaseUiState
import com.octopusman.cosic.settings.CreditsScreen
import com.octopusman.cosic.settings.SettingsScreen
import com.octopusman.cosic.sounds.SoundsScreen
import com.octopusman.cosic.ui.findActivity

/** All screens of the app and the ways between them. [app] supplies what they need. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CosicNavHost(app: CosicApp, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val activity = LocalContext.current.findActivity()

    NavHost(
        navController = navController,
        startDestination = Library,
        // Lets UI automation find controls by their test tag.
        modifier = modifier.semantics { testTagsAsResourceId = true },
    ) {
        composable<Library> {
            val adsRemoved by app.entitlements.adsRemoved.collectAsStateWithLifecycle()
            LibraryScreen(
                viewModel = viewModel(factory = LibraryViewModel.factory(app)),
                ads = app.ads,
                adsRemoved = adsRemoved,
                onRemoveAds = {
                    // Buy right away when the store has the product ready; otherwise Settings says why not.
                    if (app.entitlements.purchaseState.value is PurchaseUiState.Available) {
                        app.entitlements.launchPurchase(activity)
                    } else {
                        navController.open(Settings)
                    }
                },
                onOpenProject = { navController.open(Editor(it)) },
                onOpenSettings = { navController.open(Settings) },
            )
        }
        composable<Editor> { entry ->
            EditorScreen(
                vm = viewModel(factory = EditorViewModel.factory(app, entry.toRoute<Editor>().projectId)),
                ads = app.ads,
                // The screen has already checked that it is the one being shown.
                onBack = { navController.popBackStack() },
                onOpenSounds = { navController.open(Sounds) },
                onOpenSettings = { navController.open(Settings) },
            )
        }
        composable<Sounds> {
            SoundsScreen(app.engine, app.audioOutput, app.sampleRepository, onBack = { navController.back() })
        }
        composable<Settings> {
            SettingsScreen(
                entitlements = app.entitlements,
                ads = app.ads,
                onBack = { navController.back() },
                onOpenCredits = { navController.open(Credits) },
            )
        }
        composable<Credits> {
            CreditsScreen(onBack = { navController.back() })
        }
    }
}

/** Opens [route], unless it is already on top: a double tap must not stack a screen twice. */
private fun NavController.open(route: Any) = navigate(route) { launchSingleTop = true }

/** Goes back, but only once the current screen has settled: a double tap must not pop two screens. */
private fun NavController.back() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) popBackStack()
}
