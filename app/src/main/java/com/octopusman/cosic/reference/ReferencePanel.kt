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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.audio.BankSummary
import com.octopusman.cosic.ui.tagged
import cosic.script.reference
import cosic.script.scaleModes

/** A line of the list: a category heading, or an item under it. */
private sealed interface Row {
    data class Heading(val category: String) : Row

    data class Entry(val item: ReferenceItem) : Row
}

/**
 * The language reference as a panel that lives inside the editor, next to the code: every function,
 * every sound and every scale, searchable, with a row of groups to jump to. Insert adds an example at
 * the cursor, the arrow on a sound plays it. It opens on what the cursor of [codeAtOpen] is on; inside
 * `s(…)`, `bank(…)` or `scale(…)` that is the list of sounds or scales. The music keeps playing.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReferencePanel(
    banks: List<BankSummary>,
    codeAtOpen: TextFieldValue,
    onInsert: (String) -> Unit,
    onPreview: (name: String, pitched: Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
    val items = remember(banks) { referenceItems(reference(), banks, scaleModes(), texts) }
    val openOn = remember(items) {
        items.openOn(functionAtCursor(codeAtOpen.text, codeAtOpen.selection.start) { items.named(it) != null })
    }
    val selected = (openOn as? OpenOn.Item)?.item

    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(items, query) {
        items.filter { it.matches(query) }.groupBy { it.category }.flatMap { (category, group) ->
            listOf<Row>(Row.Heading(category)) + group.map { Row.Entry(it) }
        }
    }
    val headings = remember(rows) { rows.filterIsInstance<Row.Heading>().map { it.category } }

    val listState = rememberLazyListState()
    // An item is shown with room above it for the heading that sticks to the top.
    val headingRoom = with(LocalDensity.current) { 36.dp.roundToPx() }
    // Where the list should scroll to next: first what the cursor was on, then the groups picked.
    var target by remember { mutableStateOf(openOn) }
    fun showGroup(category: String) {
        // A group hidden by the search is still reachable: the search is cleared first.
        if (Row.Heading(category) !in rows) query = ""
        target = OpenOn.Group(category)
    }
    LaunchedEffect(target, rows) {
        val index = when (val goal = target) {
            is OpenOn.Item -> rows.indexOf(Row.Entry(goal.item))
            is OpenOn.Group -> rows.indexOf(Row.Heading(goal.category))
            null -> return@LaunchedEffect
        }
        if (index < 0) return@LaunchedEffect
        listState.scrollToItem(index, if (target is OpenOn.Item) -headingRoom else 0)
        target = null
    }

    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            SearchBar(query, onQueryChange = { query = it }, onClose = onClose)
            GroupChips(headings, onPick = ::showGroup)
            if (rows.isEmpty()) {
                Text(
                    text = stringResource(R.string.reference_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    for (row in rows) {
                        when (row) {
                            is Row.Heading -> stickyHeader(key = "heading-${row.category}") { Heading(row.category) }
                            is Row.Entry -> item(key = "${row.item.category}/${row.item.name}") {
                                EntryCard(row.item, isSelected = row.item == selected, onInsert, onPreview, onShowValues = ::showGroup)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One chip per group of the list; a tap scrolls to that group. */
@Composable
private fun GroupChips(categories: List<String>, onPick: (String) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(categories, key = { it }) { category ->
            AssistChip(
                onClick = { onPick(category) },
                label = { Text(category) },
                modifier = Modifier.tagged("group_$category", category),
            )
        }
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit, onClose: () -> Unit) {
    Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            label = { Text(stringResource(R.string.reference_search)) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }, modifier = Modifier.tagged("clear_search", stringResource(R.string.reference_clear_search))) {
                        Icon(Icons.Filled.Close, contentDescription = null)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.weight(1f).tagged("reference_search", stringResource(R.string.reference_search)),
        )
        IconButton(onClick = onClose, modifier = Modifier.tagged("reference_close", stringResource(R.string.reference_close))) {
            Icon(Icons.Filled.Close, contentDescription = null)
        }
    }
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
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun EntryCard(
    item: ReferenceItem,
    isSelected: Boolean,
    onInsert: (String) -> Unit,
    onPreview: (name: String, pitched: Boolean) -> Unit,
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
                item.sound?.let { sound ->
                    IconButton(
                        onClick = { onPreview(sound.name, sound.pitched) },
                        modifier = Modifier.tagged("preview_reference_${item.category}_${item.name}", stringResource(R.string.sounds_preview, item.name)),
                    ) { Icon(Icons.Filled.PlayArrow, contentDescription = null) }
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
