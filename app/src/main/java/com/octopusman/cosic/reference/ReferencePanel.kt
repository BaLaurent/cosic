package com.octopusman.cosic.reference

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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

/** A line of the list: a category heading, or an item under it. */
private sealed interface Row {
    data class Heading(val category: String) : Row

    data class Entry(val item: ReferenceItem) : Row
}

/**
 * The language reference as a panel that lives inside the editor, next to the code: every function
 * and every sound, searchable. Insert adds an example at the cursor, the arrow on a sound plays it.
 * It opens on the function or sound at the cursor of [codeAtOpen], if there is one. The music keeps
 * playing while it is open.
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
    val texts = SoundTexts(
        synth = SoundText(stringResource(R.string.sounds_synths), stringResource(R.string.reference_doc_synth)),
        drum = SoundText(stringResource(R.string.sounds_drums), stringResource(R.string.reference_doc_drum)),
        instrument = SoundText(stringResource(R.string.sounds_instruments), stringResource(R.string.reference_doc_instrument)),
        percussion = SoundText(stringResource(R.string.sounds_percussion), stringResource(R.string.reference_doc_percussion)),
        imported = SoundText(stringResource(R.string.sounds_imported), stringResource(R.string.reference_doc_imported)),
    )
    val items = remember(banks) { referenceItems(reference(), banks, texts) }
    val selected = remember(items) {
        functionAtCursor(codeAtOpen.text, codeAtOpen.selection.start) { items.named(it) != null }?.let(items::named)
    }

    var query by rememberSaveable { mutableStateOf("") }
    val rows = remember(items, query) {
        items.filter { it.matches(query) }.groupBy { it.category }.flatMap { (category, group) ->
            listOf<Row>(Row.Heading(category)) + group.map { Row.Entry(it) }
        }
    }

    // Opens on the item at the cursor, with room above it for the heading that sticks to the top.
    val listState = rememberLazyListState()
    val headingRoom = with(LocalDensity.current) { 36.dp.roundToPx() }
    LaunchedEffect(Unit) {
        val index = rows.indexOfFirst { it is Row.Entry && it.item == selected }
        if (index >= 0) listState.scrollToItem(index, -headingRoom)
    }

    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            SearchBar(query, onQueryChange = { query = it }, onClose = onClose)
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
                                EntryCard(row.item, isSelected = row.item == selected, onInsert, onPreview)
                            }
                        }
                    }
                }
            }
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
        }
    }
}
