package com.octopusman.cosic.reference

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.octopusman.cosic.ui.tagged
import cosic.script.reference
import cosic.script.scaleModes

/** A line of a list: a group heading, the group's description (sounds only), or an item. */
private sealed interface Row {
    data class Heading(val category: String) : Row

    data class Doc(val category: String, val text: String) : Row

    data class Entry(val item: ReferenceItem) : Row
}

/** Every function, sound and scale the panel lists, with the texts of the current language. */
@Composable
internal fun rememberReferenceItems(banks: List<BankSummary>): List<ReferenceItem> {
    val texts = ReferenceTexts(
        synth = GroupText(stringResource(R.string.sounds_synths), stringResource(R.string.reference_doc_synth)),
        drum = GroupText(stringResource(R.string.sounds_drums), stringResource(R.string.reference_doc_drum)),
        instrument = GroupText(stringResource(R.string.sounds_instruments), stringResource(R.string.reference_doc_instrument)),
        percussion = GroupText(stringResource(R.string.sounds_percussion), stringResource(R.string.reference_doc_percussion)),
        imported = GroupText(stringResource(R.string.sounds_imported), stringResource(R.string.reference_doc_imported)),
        scale = GroupText(stringResource(R.string.reference_scales), stringResource(R.string.reference_doc_scale)),
        showSounds = stringResource(R.string.reference_show_sounds),
        showScales = stringResource(R.string.reference_show_scales),
    )
    return remember(banks) { referenceItems(reference(), banks, scaleModes(), texts) }
}

/**
 * The panel the editor shows next to the code, with two tabs: the reference of the language
 * (functions and scales) and the sounds. Each tab can be searched and has a row of groups to jump
 * to. Insert adds an example under the cursor's line; tapping a sound plays it. The panel scrolls to
 * [openOn] when it opens. The music keeps playing.
 */
@Composable
internal fun ReferencePanel(
    items: List<ReferenceItem>,
    tab: PanelTab,
    onTabChange: (PanelTab) -> Unit,
    openOn: OpenOn?,
    onInsert: (String) -> Unit,
    onPreview: (name: String, pitched: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var referenceQuery by rememberSaveable { mutableStateOf("") }
    var soundQuery by rememberSaveable { mutableStateOf("") }
    val referenceList = rememberLazyListState()
    val soundList = rememberLazyListState()
    // Where the list of the current tab should scroll to next: first [openOn], then the groups picked.
    var target by remember { mutableStateOf(openOn) }

    // "Show sounds" on s() and bank(), "Show scales" on scale(): the tab that lists them, at their group.
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
                ) { item, selected -> FunctionCard(item, selected, onInsert, onShowValues = ::showGroup) }
                PanelTab.SOUNDS -> TabContent(
                    items = items.inTab(PanelTab.SOUNDS),
                    query = soundQuery,
                    onQueryChange = { soundQuery = it },
                    listState = soundList,
                    target = target,
                    onTargetReached = { target = null },
                    onPickGroup = { target = OpenOn.Group(it) },
                    chips = { it },
                ) { item, selected -> SoundRow(item, selected, onInsert, onPreview) }
            }
        }
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

/** A searchable list of [items] grouped under their headings, with a chip per group to jump to it. */
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
    entry: @Composable (item: ReferenceItem, selected: Boolean) -> Unit,
) {
    val rows = remember(items, query) {
        items.filter { it.matches(query) }.groupBy { it.category }.flatMap { (category, group) ->
            val doc = group.first().takeIf { it.sound != null }?.doc
            listOfNotNull(Row.Heading(category), doc?.let { Row.Doc(category, it) }) + group.map { Row.Entry(it) }
        }
    }
    val categories = remember(rows) { chips(rows.filterIsInstance<Row.Heading>().map { it.category }) }
    // The item the panel opened on stays outlined after the list has scrolled to it.
    var outlined by remember { mutableStateOf((target as? OpenOn.Item)?.item) }
    // An item is shown with room above it for the heading that sticks to the top.
    val headingRoom = with(LocalDensity.current) { 36.dp.roundToPx() }
    LaunchedEffect(target, rows) {
        val index = when (target) {
            is OpenOn.Item -> rows.indexOf(Row.Entry(target.item))
            is OpenOn.Group -> rows.indexOf(Row.Heading(target.category))
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
                is Row.Heading -> stickyHeader(key = "heading-${row.category}") { Heading(row.category) }
                // The description scrolls away; only the group name stays pinned, so the list keeps its room.
                is Row.Doc -> item(key = "doc-${row.category}") { GroupDoc(row.text) }
                is Row.Entry -> item(key = "${row.item.category}/${row.item.name}") { entry(row.item, row.item == outlined) }
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

/** A sound in one line: tap it to hear it, Insert to add a line that plays it. */
@Composable
private fun SoundRow(
    item: ReferenceItem,
    isSelected: Boolean,
    onInsert: (String) -> Unit,
    onPreview: (name: String, pitched: Boolean) -> Unit,
) {
    val sound = item.sound ?: return
    val previewLabel = stringResource(R.string.sounds_preview, item.name)
    val insertLabel = stringResource(R.string.reference_insert_example)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .background(
                if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .clickable(onClickLabel = previewLabel) { onPreview(sound.name, sound.pitched) }
            .padding(start = 8.dp)
            .tagged("sound_${item.category}_${item.name}", item.name),
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            text = item.name,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp),
        )
        if (sound.count != null) {
            Text(
                text = pluralStringResource(R.plurals.sounds_sample_count, sound.count, sound.count),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp).weight(1f),
            )
        } else {
            Row(Modifier.weight(1f)) {}
        }
        TextButton(
            onClick = { onInsert(item.snippet) },
            modifier = Modifier.tagged("insert_example_${item.category}_${item.name}", insertLabel),
        ) { Text(insertLabel) }
    }
}

@Composable
private fun FunctionCard(
    item: ReferenceItem,
    isSelected: Boolean,
    onInsert: (String) -> Unit,
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
                TextButton(
                    onClick = { onInsert(item.snippet) },
                    modifier = Modifier.tagged("insert_example_${item.category}_${item.name}", insertLabel),
                ) { Text(insertLabel) }
            }
            if (item.aliases.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.reference_aliases, item.aliases.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = item.doc, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = item.snippet,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = insertLabel) { onInsert(item.snippet) }
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
