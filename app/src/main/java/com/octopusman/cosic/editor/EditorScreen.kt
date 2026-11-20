package com.octopusman.cosic.editor

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octopusman.cosic.R
import com.octopusman.cosic.monetization.AdsController
import com.octopusman.cosic.reference.OpenOn
import com.octopusman.cosic.reference.PanelTab
import com.octopusman.cosic.reference.ReferencePanel
import com.octopusman.cosic.reference.functionAtCursor
import com.octopusman.cosic.reference.named
import com.octopusman.cosic.reference.openOn
import com.octopusman.cosic.reference.rememberReferenceItems
import com.octopusman.cosic.reference.tabOnOpen
import com.octopusman.cosic.ui.AdBannerBar
import com.octopusman.cosic.ui.BackButton
import com.octopusman.cosic.ui.CosicColors
import com.octopusman.cosic.ui.RenameDialog
import com.octopusman.cosic.ui.findActivity
import com.octopusman.cosic.ui.shareText
import com.octopusman.cosic.ui.tagged

/** The least height the side panel opens at in portrait: its tabs, search, group chips and a few lines of its list. */
private val MIN_PANEL_HEIGHT = 380.dp

/**
 * The code editor for one project. [onBack] pops the screen; it is called after the music has been
 * stopped, the project saved and, when one is due, an interstitial started.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(
    vm: EditorViewModel,
    ads: AdsController,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (vm.missing) {
        LaunchedEffect(Unit) { onBack() }
        return
    }

    val context = LocalContext.current
    val activity = context.findActivity()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val focusRequester = remember { FocusRequester() }
    var renaming by remember { mutableStateOf(false) }
    var codeFocused by remember { mutableStateOf(false) }
    val playing = vm.playing

    // System back and the top bar's back arrow take the same way out. A second tap while the screen
    // is already on its way out must not leave twice.
    val leave = {
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            val wasPlaying = vm.onLeave()
            ads.maybeShowInterstitial(activity, wasPlaying)
            onBack()
        }
    }
    BackHandler(onBack = leave)

    // The side panel: closed (null), or showing its reference or sounds tab.
    var panelTab by rememberSaveable { mutableStateOf<PanelTab?>(null) }
    var panelTarget by remember { mutableStateOf<OpenOn?>(null) }
    val banks by vm.banks.collectAsStateWithLifecycle()
    val soundsStatus by vm.soundsStatus.collectAsStateWithLifecycle()
    val referenceItems by rememberUpdatedState(rememberReferenceItems(banks))
    var referenceShare by rememberSaveable { mutableFloatStateOf(ReferenceSplit.DEFAULT) }
    var splitHeight by remember { mutableIntStateOf(0) }
    // Room for the tabs, the search, the group chips and a few lines of the list.
    val minPanelPx = with(LocalDensity.current) { MIN_PANEL_HEIGHT.toPx() }

    // A button of the top bar closes the panel when it shows that button's tab, and switches to that
    // tab when it shows the other one. When the panel opens, it opens on what the cursor is on; on the
    // sounds whenever the cursor is on a sound or inside s(…) or bank(…).
    fun togglePanel(button: PanelTab) {
        when (panelTab) {
            button -> panelTab = null
            null -> {
                val code = vm.textValue
                val hit = functionAtCursor(code.text, code.selection.start) { referenceItems.named(it) != null }
                panelTarget = referenceItems.openOn(hit)
                panelTab = referenceItems.tabOnOpen(button, panelTarget)
                referenceShare = ReferenceSplit.onOpen(referenceShare, minPanelPx, splitHeight.toFloat())
            }
            else -> {
                panelTarget = null
                panelTab = button
            }
        }
    }

    // Nothing keeps playing in the background. A rotation restarts the activity but is not leaving.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        if (!activity.isChangingConfigurations) vm.onBackground()
    }

    val view = LocalView.current
    DisposableEffect(view, playing) {
        view.keepScreenOn = playing
        onDispose { view.keepScreenOn = false }
    }

    // The highlight loop: one engine read per frame, and only while there is music to follow.
    LaunchedEffect(playing) {
        if (playing) {
            while (true) withFrameNanos { vm.onFrame() }
        }
    }

    Scaffold(
        modifier = Modifier.onPreviewKeyEvent { handleShortcut(it, vm) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = vm.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .clickable(onClickLabel = stringResource(R.string.editor_rename_project)) { renaming = true }
                            .tagged("project_name", vm.name),
                    )
                },
                navigationIcon = { BackButton(leave) },
                actions = {
                    PlayButton(playing = playing, onClick = vm::evaluateAndPlay)
                    IconButton(onClick = vm::stop, modifier = Modifier.tagged("stop", stringResource(R.string.editor_stop))) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                    }
                    PanelToggle(PanelTab.SOUNDS, panelTab, onClick = { togglePanel(PanelTab.SOUNDS) })
                    PanelToggle(PanelTab.REFERENCE, panelTab, onClick = { togglePanel(PanelTab.REFERENCE) })
                    OverflowMenu(
                        onShare = { context.shareText(vm.textValue.text) },
                        onSettings = onOpenSettings,
                    )
                },
            )
        },
        bottomBar = {
            // The keyboard takes the place of the banner. While the code is edited the symbol bar sits
            // right above it; while the panel's search is typed in, only room for the keyboard is kept.
            // The banner stays composed (collapsed and paused) so it is not reloaded on every edit.
            Column {
                if (WindowInsets.isImeVisible) {
                    if (codeFocused) {
                        SymbolBar(
                            canUndo = vm.canUndo,
                            canRedo = vm.canRedo,
                            onUndo = vm::undo,
                            onRedo = vm::redo,
                            onSymbol = vm::insertSymbol,
                            modifier = Modifier.navigationBarsPadding().imePadding(),
                        )
                    } else {
                        Spacer(Modifier.navigationBarsPadding().imePadding())
                    }
                }
                AdBannerBar(ads)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            StatusLine(vm, onErrorClick = {
                vm.selectError()
                focusRequester.requestFocus()
            })
            // Movable, so opening or closing the reference (or a rotation) keeps the editor's focus and keyboard.
            val editor = remember {
                movableContentOf<Modifier> { modifier ->
                    CodeEditor(
                        value = vm.textValue,
                        onValueChange = vm::onTextChange,
                        activeRanges = { vm.activeRanges },
                        modifier = modifier.focusRequester(focusRequester).onFocusChanged { codeFocused = it.hasFocus },
                    )
                }
            }
            val reference = remember {
                movableContentOf<Modifier> { modifier ->
                    ReferencePanel(
                        items = referenceItems,
                        tab = panelTab ?: PanelTab.REFERENCE,
                        onTabChange = { panelTab = it },
                        openOn = panelTarget,
                        onInsert = vm::insertEntry,
                        onInsertExample = vm::insertSnippet,
                        onPreview = vm::previewSound,
                        onImportFolder = vm::importFolder,
                        onRemoveFolder = vm::removeFolder,
                        soundsStatus = soundsStatus,
                        onRetrySounds = vm::retrySounds,
                        onClose = { panelTab = null },
                        modifier = modifier,
                    )
                }
            }
            val sideBySide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
            // The area under the status line, measured even while the panel is closed so that it can
            // open at a size that fits.
            val area = Modifier.weight(1f).fillMaxWidth().onSizeChanged { splitHeight = it.height }
            // Typing in the panel's search: the keyboard leaves little room, so the panel takes all of
            // it. Only the sizes change, never the layout, so the search field keeps its focus.
            val searchingInPanel = WindowInsets.isImeVisible && !codeFocused
            when {
                panelTab == null -> editor(area)
                sideBySide -> Row(area) {
                    editor(Modifier.weight(1f).fillMaxHeight())
                    reference(Modifier.weight(1f).fillMaxHeight())
                }
                // Portrait: the reference grows upwards when its handle is dragged.
                else -> Column(area) {
                    val panelShare = if (searchingInPanel) 1f else referenceShare
                    editor(if (searchingInPanel) Modifier.height(0.dp) else Modifier.weight(1f - panelShare).fillMaxWidth())
                    ReferenceHandle(
                        onDrag = { referenceShare = ReferenceSplit.dragged(referenceShare, it, splitHeight.toFloat()) },
                        onTap = { referenceShare = ReferenceSplit.toggled(referenceShare) },
                    )
                    reference(Modifier.weight(panelShare).fillMaxWidth())
                }
            }
        }
    }

    if (renaming) {
        RenameDialog(
            initial = vm.name,
            onConfirm = {
                vm.rename(it)
                renaming = false
            },
            onDismiss = { renaming = false },
        )
    }
}

@Composable
private fun PlayButton(playing: Boolean, onClick: () -> Unit) {
    // Evaluating again while playing swaps the new code in without a restart, hence "update".
    val label = stringResource(if (playing) R.string.editor_update else R.string.editor_play)
    IconButton(onClick = onClick, modifier = Modifier.tagged("play", label)) {
        Icon(if (playing) Icons.Filled.Refresh else Icons.Filled.PlayArrow, contentDescription = null)
    }
}

/** The book (reference) or the note (sounds) of the top bar; lit while the panel shows its tab. */
@Composable
private fun PanelToggle(tab: PanelTab, shownTab: PanelTab?, onClick: () -> Unit) {
    val shown = shownTab == tab
    val label = stringResource(
        when (tab) {
            PanelTab.REFERENCE -> if (shown) R.string.editor_reference_hide else R.string.editor_reference_show
            PanelTab.SOUNDS -> if (shown) R.string.editor_sounds_hide else R.string.editor_sounds_show
        },
    )
    val icon = if (tab == PanelTab.SOUNDS) Icons.Filled.LibraryMusic else Icons.AutoMirrored.Filled.MenuBook
    IconButton(onClick = onClick, modifier = Modifier.tagged(if (tab == PanelTab.SOUNDS) "sounds_panel" else "reference", label)) {
        Icon(icon, contentDescription = null, tint = if (shown) MaterialTheme.colorScheme.primary else LocalContentColor.current)
    }
}

@Composable
private fun OverflowMenu(onShare: () -> Unit, onSettings: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = Modifier.tagged("editor_menu", stringResource(R.string.action_more))) {
            Icon(Icons.Filled.MoreVert, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            MenuItem("menu_share", stringResource(R.string.action_share)) { expanded = false; onShare() }
            MenuItem("menu_settings", stringResource(R.string.action_settings)) { expanded = false; onSettings() }
        }
    }
}

@Composable
private fun MenuItem(tag: String, label: String, onClick: () -> Unit) {
    DropdownMenuItem(text = { Text(label) }, onClick = onClick, modifier = Modifier.tagged(tag, label))
}

/**
 * One line under the top bar. An error beats everything and selects its code when tapped; then
 * runtime errors, then warnings, and while playing the tempo and the current cycle.
 */
@Composable
private fun StatusLine(vm: EditorViewModel, onErrorClick: () -> Unit) {
    val status = vm.evalStatus
    val playback = vm.playback
    val warnings = (((status as? EvalStatus.Ok)?.warnings).orEmpty() + playback?.warnings.orEmpty()).distinct()
    val colors = MaterialTheme.colorScheme

    val modifier = Modifier.fillMaxWidth().heightIn(min = 32.dp).padding(horizontal = 12.dp, vertical = 6.dp)
    when {
        status is EvalStatus.Error -> {
            // Without a position there is no "Line 0:0" to show.
            val text = if (status.line > 0) {
                stringResource(R.string.editor_status_error, status.line, status.column, status.message)
            } else {
                status.message
            }
            StatusText(text, colors.error, modifier.clickable(onClick = onErrorClick))
        }
        playback?.runtimeError != null -> StatusText(playback.runtimeError, colors.error, modifier)
        warnings.isNotEmpty() -> StatusText(warnings.joinToString(" · "), CosicColors.Warning, modifier)
        playback != null -> StatusText(
            stringResource(R.string.editor_status_playing, formatCpm(playback.cps), playback.cycle),
            colors.onSurfaceVariant,
            modifier,
        )
        else -> Box(modifier)
    }
}

@Composable
private fun StatusText(text: String, color: Color, modifier: Modifier) {
    Text(
        text = text,
        color = color,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.tagged("status_line", text),
    )
}

/** Hardware keyboard: Ctrl+Enter evaluates, Ctrl+. stops, Ctrl+Z undoes, Ctrl+Shift+Z and Ctrl+Y redo. */
private fun handleShortcut(event: KeyEvent, vm: EditorViewModel): Boolean {
    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) return false
    when (event.key) {
        Key.Enter, Key.NumPadEnter -> vm.evaluateAndPlay()
        Key.Period -> vm.stop()
        Key.Z -> if (event.isShiftPressed) vm.redo() else vm.undo()
        Key.Y -> vm.redo()
        else -> return false
    }
    return true
}
