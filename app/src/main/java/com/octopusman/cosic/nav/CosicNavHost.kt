package com.octopusman.cosic.nav

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
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
import com.octopusman.cosic.BuildConfig
import com.octopusman.cosic.CosicApp
import com.octopusman.cosic.R
import com.octopusman.cosic.editor.EditorScreen
import com.octopusman.cosic.editor.EditorViewModel
import com.octopusman.cosic.library.LibraryScreen
import com.octopusman.cosic.library.LibraryViewModel
import com.octopusman.cosic.monetization.PurchaseUiState
import com.octopusman.cosic.settings.CreditsScreen
import com.octopusman.cosic.settings.SettingsScreen
import com.octopusman.cosic.share.ImportResult
import com.octopusman.cosic.ui.findActivity
import com.octopusman.cosic.ui.toast
import kotlinx.coroutines.launch

/** All screens of the app and the ways between them. [app] supplies what they need. */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun CosicNavHost(app: CosicApp, modifier: Modifier = Modifier) {
    val navController = rememberNavController()
    val activity = LocalContext.current.findActivity()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // A link to a shared pattern or text that another app shared, and the clipboard text the editor's menu imports, all become a project that is opened.
    val importText: (String) -> Unit = { text -> scope.launch { importShared(app, navController, context, text) } }
    LaunchedEffect(app) { app.incomingSharedTexts.collect { importText(it) } }

    NavHost(
        navController = navController,
        startDestination = Library,
        // Lets UI automation find controls by their test tag; in debug builds only.
        modifier = if (BuildConfig.DEBUG) modifier.semantics { testTagsAsResourceId = true } else modifier,
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
                env = app.editorEnvironment,
                ads = app.ads,
                // The screen has already checked that it is the one being shown.
                onBack = { navController.popBackStack() },
                onOpenSettings = { navController.open(Settings) },
                onImportText = importText,
            )
        }
        composable<Settings> {
            SettingsScreen(
                entitlements = app.entitlements,
                ads = app.ads,
                editorSettings = app.editorSettings,
                oscSettings = app.oscSettings,
                midi = app.midi,
                onBack = { navController.back() },
                onOpenCredits = { navController.open(Credits) },
            )
        }
        composable<Credits> {
            CreditsScreen(onBack = { navController.back() })
        }
    }
}

/** Makes a project of [text] (a link to a shared pattern, or code) and opens it, in place of the editor that is open; says so when that cannot be done. */
private suspend fun importShared(app: CosicApp, navController: NavController, context: Context, text: String) {
    when (val result = app.importer.import(text)) {
        is ImportResult.Created -> navController.navigate(Editor(result.projectId)) {
            // every screen above the Library goes (the editor that is open and Settings): the project is saved and the music stops when its screen is left
            popUpTo<Library>()
            launchSingleTop = true
        }
        ImportResult.NotALink -> context.toast(R.string.import_not_a_link)
        ImportResult.NotFound -> context.toast(R.string.import_not_found)
        is ImportResult.Failed -> context.toast(R.string.import_failed, result.message)
    }
}

/** Opens [route], unless it is already on top: a double tap must not stack a screen twice. */
private fun NavController.open(route: Any) = navigate(route) { launchSingleTop = true }

/** Goes back, but only once the current screen has settled: a double tap must not pop two screens. */
private fun NavController.back() {
    if (currentBackStackEntry?.lifecycle?.currentState == Lifecycle.State.RESUMED) popBackStack()
}
