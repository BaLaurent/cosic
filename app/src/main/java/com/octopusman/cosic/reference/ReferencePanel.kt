package com.octopusman.cosic.reference

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.audio.BankSummary
import com.octopusman.cosic.audio.SoundsStatus
import com.octopusman.cosic.ui.ConfirmDialog
import com.octopusman.cosic.ui.tagged
import cosic.script.reference
import cosic.script.scaleModes

/** Every function, sound and scale the panel lists, with the texts of the current language. */
@Composable
internal fun rememberReferenceItems(banks: List<BankSummary>): List<ReferenceItem> {
    val texts = ReferenceTexts(
        drumMachines = GroupText(stringResource(R.string.sounds_drum_machines), stringResource(R.string.reference_doc_drum_machines)),
        synth = GroupText(stringResource(R.string.sounds_synths), stringResource(R.string.reference_doc_synth)),
        drum = GroupText(stringResource(R.string.sounds_drums), stringResource(R.string.reference_doc_drum)),
        instrument = GroupText(stringResource(R.string.sounds_instruments), stringResource(R.string.reference_doc_instrument)),
        percussion = GroupText(stringResource(R.string.sounds_percussion), stringResource(R.string.reference_doc_percussion)),
        wavetable = GroupText(stringResource(R.string.sounds_wavetables), stringResource(R.string.reference_doc_wavetable)),
        imported = GroupText(stringResource(R.string.sounds_imported), stringResource(R.string.reference_doc_imported)),
        scale = GroupText(stringResource(R.string.reference_scales), stringResource(R.string.reference_doc_scale)),
        showSounds = stringResource(R.string.reference_show_sounds),
        showDrumMachines = stringResource(R.string.reference_show_drum_machines),
        showScales = stringResource(R.string.reference_show_scales),
    )
    return remember(banks) { referenceItems(reference(), banks, scaleModes(), texts) }
}

/**
 * The panel the editor shows next to the code, with two tabs: the reference of the language
 * (functions and scales) and the sounds. Each tab can be searched and has a row of groups to jump
 * to. Insert adds an example under the cursor's line; tapping a sound plays it. The Sounds tab also
 * says how the loading of sounds goes ([soundsStatus]), and imports folders of samples and removes them
 * again. The panel scrolls to [openOn] when it opens.
 * The music keeps playing.
 */
@Composable
internal fun ReferencePanel(
    items: List<ReferenceItem>,
    tab: PanelTab,
    onTabChange: (PanelTab) -> Unit,
    openOn: OpenOn?,
    onInsert: (InsertText) -> Unit,
    onInsertExample: (String) -> Unit,
    onPreview: (name: String, index: Int, pitched: Boolean) -> Unit,
    onImportFolder: (Uri) -> Unit,
    onRemoveFolder: (Uri) -> Unit,
    soundsStatus: SoundsStatus,
    onRetrySounds: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var referenceQuery by rememberSaveable { mutableStateOf("") }
    var soundQuery by rememberSaveable { mutableStateOf("") }
    val referenceList = rememberLazyListState()
    val soundList = rememberLazyListState()
    // Where the list of the current tab should scroll to next: first [openOn], then the groups picked.
    var target by remember { mutableStateOf(openOn) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) onImportFolder(uri)
    }
    // The imported folder the user is being asked to confirm the removal of.
    var removing by remember { mutableStateOf<Uri?>(null) }

    // "Show sounds" on s(), "Show drum machines" on bank() and "Show scales" on scale(): the tab that
    // lists them, at their group.
    fun showGroup(category: String) {
        val groupTab = items.tabFor(OpenOn.Group(category))
        if (groupTab == PanelTab.SOUNDS) soundQuery = "" else referenceQuery = ""
        target = OpenOn.Group(category)
        onTabChange(groupTab)
    }

    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Tabs(tab, onTabChange, onClose)
            when (tab) {
                PanelTab.REFERENCE -> TabContent(
                    items = items.inTab(PanelTab.REFERENCE),
                    query = referenceQuery,
                    onQueryChange = { referenceQuery = it },
                    listState = referenceList,
                    target = target,
                    onTargetReached = { target = null },
                    onPickGroup = { target = OpenOn.Group(it) },
                    chips = { categories -> chipOrder(categories, reference().map { it.category }.toSet()) },
                ) { item, selected -> FunctionCard(item, selected, onInsert, onInsertExample, onShowValues = ::showGroup) }
                PanelTab.SOUNDS -> {
                    SoundsStatusRow(soundsStatus, onRetrySounds)
                    TabContent(
                        items = items.inTab(PanelTab.SOUNDS),
                        query = soundQuery,
                        onQueryChange = { soundQuery = it },
                        listState = soundList,
                        target = target,
                        onTargetReached = { target = null },
                        onPickGroup = { target = OpenOn.Group(it) },
                        chips = { it },
                        importCategory = stringResource(R.string.sounds_imported),
                        importRow = { ImportRow(onImport = { folderPicker.launch(null) }) },
                    ) { item, selected -> SoundRow(item, selected, onInsert, onInsertExample, onPreview, onRemove = { removing = it }) }
                }
            }
        }
    }

    removing?.let { folder ->
        ConfirmDialog(
            title = stringResource(R.string.sounds_remove_title),
            message = stringResource(R.string.sounds_remove_message),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = {
                // A folder can hold several sounds and a kit; they all go with it.
                onRemoveFolder(folder)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

/**
 * One line about the loading of sounds, above the list of the Sounds tab: the default sounds are being loaded, or some could not be (with a
 * button to try again), or sample files are being downloaded. Nothing when everything is there.
 */
@Composable
private fun SoundsStatusRow(status: SoundsStatus, onRetry: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val rowModifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
    when {
        status.loading -> StatusRow(stringResource(R.string.sounds_status_loading), rowModifier)
        status.failed.isNotEmpty() -> {
            val text = stringResource(R.string.sounds_status_failed)
            Row(rowModifier.tagged("sounds_status", text), verticalAlignment = Alignment.CenterVertically) {
                Text(text, style = MaterialTheme.typography.bodySmall, color = colors.error, modifier = Modifier.weight(1f))
                TextButton(onClick = onRetry, modifier = Modifier.tagged("sounds_retry", stringResource(R.string.sounds_status_retry))) {
                    Text(stringResource(R.string.sounds_status_retry))
                }
            }
        }
        status.downloading > 0 -> StatusRow(
            pluralStringResource(R.plurals.sounds_status_downloading, status.downloading, status.downloading),
            rowModifier,
        )
    }
}

/** [text] next to a small spinner: something is going on. */
@Composable
private fun StatusRow(text: String, modifier: Modifier) {
    Row(modifier.tagged("sounds_status", text), verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Tabs(tab: PanelTab, onTabChange: (PanelTab) -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        SecondaryTabRow(selectedTabIndex = tab.ordinal, modifier = Modifier.weight(1f), containerColor = Color.Transparent) {
            for (each in PanelTab.entries) {
                val label = stringResource(if (each == PanelTab.SOUNDS) R.string.panel_tab_sounds else R.string.panel_tab_reference)
                Tab(
                    selected = tab == each,
                    onClick = { onTabChange(each) },
                    text = { Text(label) },
                    modifier = Modifier.tagged("panel_tab_${each.name.lowercase()}", label),
                )
            }
        }
        IconButton(onClick = onClose, modifier = Modifier.tagged("reference_close", stringResource(R.string.reference_close))) {
            Icon(Icons.Filled.Close, contentDescription = null)
        }
    }
}

/**
 * A searchable list of [items] grouped under their headings, with a chip per group to jump to it. The
 * group [importCategory], if there is one, is always listed and has [importRow] under its description.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabContent(
    items: List<ReferenceItem>,
    query: String,
    onQueryChange: (String) -> Unit,
    listState: LazyListState,
    target: OpenOn?,
    onTargetReached: () -> Unit,
    onPickGroup: (String) -> Unit,
    chips: (List<String>) -> List<String>,
    importCategory: String? = null,
    importRow: @Composable () -> Unit = {},
    entry: @Composable (item: ReferenceItem, selected: Boolean) -> Unit,
) {
    val rows = remember(items, query, importCategory) { items.rows(query, importCategory) }
    val categories = remember(rows) { chips(rows.filterIsInstance<ListRow.Heading>().map { it.category }) }
    // The item the panel opened on stays outlined after the list has scrolled to it.
    var outlined by remember { mutableStateOf((target as? OpenOn.Item)?.item) }
    // An item is shown with room above it for the heading that sticks to the top.
    val headingRoom = with(LocalDensity.current) { 36.dp.roundToPx() }
    LaunchedEffect(target, rows) {
        val index = when (target) {
            is OpenOn.Item -> rows.indexOf(ListRow.Entry(target.item))
            is OpenOn.Group -> rows.indexOf(ListRow.Heading(target.category))
            null -> return@LaunchedEffect
        }
        if (index < 0) return@LaunchedEffect
        if (target is OpenOn.Item) outlined = target.item
        listState.scrollToItem(index, if (target is OpenOn.Item) -headingRoom else 0)
        onTargetReached()
    }

    // One row: the group chips with a search button in front, or the search field while searching.
    var searching by rememberSaveable { mutableStateOf(query.isNotEmpty()) }
    if (searching) {
        SearchBar(query, onQueryChange, onDone = {
            onQueryChange("")
            searching = false
        })
    } else {
        GroupChips(categories, onSearch = { searching = true }, onPick = onPickGroup)
    }
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.reference_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(16.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        for (row in rows) {
            when (row) {
                is ListRow.Heading -> stickyHeader(key = "heading-${row.category}") { Heading(row.category) }
                // The description scrolls away; only the group name stays pinned, so the list keeps its room.
                is ListRow.Doc -> item(key = "doc-${row.category}") { GroupDoc(row.text) }
                is ListRow.Import -> item(key = "import-${row.category}") { importRow() }
                is ListRow.Entry -> item(key = "${row.item.category}/${row.item.name}") { entry(row.item, row.item == outlined) }
            }
        }
    }
}

/** A search button, then one chip per group of the list; a tap on a chip scrolls to that group. */
@Composable
private fun GroupChips(categories: List<String>, onSearch: () -> Unit, onPick: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item(key = "search") {
            IconButton(onClick = onSearch, modifier = Modifier.tagged("reference_search_open", stringResource(R.string.reference_search))) {
                Icon(Icons.Filled.Search, contentDescription = null)
            }
        }
        items(categories, key = { it }) { category ->
            AssistChip(
                onClick = { onPick(category) },
                label = { Text(category) },
                modifier = Modifier.tagged("group_$category", category),
            )
        }
    }
}

/** The search field; [onDone] clears it and brings the group chips back. */
@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onDone: () -> Unit) {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.reference_search)) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onDone, modifier = Modifier.tagged("clear_search", stringResource(R.string.reference_clear_search))) {
                Icon(Icons.Filled.Close, contentDescription = null)
            }
        },
        // The search key closes the keyboard, so the results get the room it took.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            imeAction = ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .focusRequester(focus)
            .tagged("reference_search", stringResource(R.string.reference_search)),
    )
}

@Composable
private fun Heading(category: String) {
    Text(
        text = category,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun GroupDoc(doc: String) {
    Text(
        text = doc,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
    )
}

/** The button that imports a folder of samples, and what to put in the folder. */
@Composable
private fun ImportRow(onImport: () -> Unit) {
    val label = stringResource(R.string.sounds_import_folder)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        OutlinedButton(onClick = onImport, modifier = Modifier.tagged("import_folder", label)) {
            Icon(Icons.Filled.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(label)
        }
        Text(
            text = stringResource(R.string.sounds_import_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * A sound in one line: tap it to hear it, Insert to add a line that plays it. A drum lists its variants
 * under it, each of which can be heard on its own; a drum machine lists its sounds; a folder that was
 * imported can be removed.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SoundRow(
    item: ReferenceItem,
    isSelected: Boolean,
    onInsert: (InsertText) -> Unit,
    onInsertExample: (String) -> Unit,
    onPreview: (name: String, index: Int, pitched: Boolean) -> Unit,
    onRemove: (Uri) -> Unit,
) {
    val sound = item.sound ?: return
    val previewLabel = stringResource(R.string.sounds_preview, item.name)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClickLabel = previewLabel) { onPreview(sound.name, 0, sound.pitched) }
            .padding(start = 8.dp)
            .tagged("sound_${item.category}_${item.name}", item.name),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            // The name and the sample count wrap together when the row is narrow, so the buttons stay in view.
            FlowRow(
                modifier = Modifier.weight(1f).padding(start = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.primary,
                )
                if (sound.count != null) {
                    Text(
                        text = pluralStringResource(R.plurals.sounds_sample_count, sound.count, sound.count),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item.folder?.let { folder ->
                TextButton(
                    onClick = { onRemove(folder) },
                    modifier = Modifier.tagged("remove_${item.name}", stringResource(R.string.sounds_remove_bank, item.name)),
                ) { Text(stringResource(R.string.action_remove)) }
            }
            InsertButtons(item, onInsert, onInsertExample, exampleLabel = R.string.reference_example_short)
        }
        if (item.members.isNotEmpty()) {
            Text(
                text = item.members.joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 32.dp, end = 8.dp, bottom = 6.dp),
            )
        }
        if (sound.variants > 0) {
            FlowRow(
                modifier = Modifier.padding(start = 32.dp, end = 8.dp, bottom = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (variant in 0 until sound.variants) {
                    val label = "${sound.name}:$variant"
                    AssistChip(
                        onClick = { onPreview(sound.name, variant, sound.pitched) },
                        label = { Text(label, fontFamily = FontFamily.Monospace) },
                        modifier = Modifier.tagged("preview_${sound.name}_$variant", stringResource(R.string.sounds_preview, label)),
                    )
                }
            }
        }
    }
}

@Composable
private fun FunctionCard(
    item: ReferenceItem,
    isSelected: Boolean,
    onInsert: (InsertText) -> Unit,
    onInsertExample: (String) -> Unit,
    onShowValues: (category: String) -> Unit,
) {
    val insertLabel = stringResource(R.string.reference_insert_example)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = item.signature, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                InsertButtons(item, onInsert, onInsertExample, exampleLabel = R.string.reference_insert_example)
            }
            if (item.aliases.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.reference_aliases, item.aliases.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = item.doc.inAppWords(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = item.snippet,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = insertLabel) { onInsertExample(item.snippet) }
                    .padding(8.dp),
            )
            item.values?.let { link ->
                OutlinedButton(
                    onClick = { onShowValues(link.category) },
                    modifier = Modifier.padding(end = 8.dp).tagged("show_values_${item.name}", link.label),
                ) { Text(link.label) }
            }
        }
    }
}

/** Insert puts the entry itself at the cursor; the example button adds a whole line that plays it. */
@Composable
private fun InsertButtons(item: ReferenceItem, onInsert: (InsertText) -> Unit, onInsertExample: (String) -> Unit, exampleLabel: Int) {
    val insertLabel = stringResource(R.string.reference_insert)
    val exampleDescription = stringResource(R.string.reference_insert_example)
    TextButton(
        onClick = { onInsert(item.entry) },
        modifier = Modifier.tagged("insert_${item.category}_${item.name}", insertLabel),
    ) { Text(insertLabel) }
    TextButton(
        onClick = { onInsertExample(item.snippet) },
        modifier = Modifier.tagged("insert_example_${item.category}_${item.name}", exampleDescription),
    ) { Text(stringResource(exampleLabel)) }
}
