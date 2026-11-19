package com.octopusman.cosic.reference

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged
import cosic.script.ReferenceEntry
import cosic.script.reference

/**
 * The language reference as a panel that lives inside the editor, next to the code: search it,
 * and insert an example at the cursor with one tap. The music keeps playing while it is open.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReferencePanel(onInsert: (String) -> Unit, onClose: () -> Unit, modifier: Modifier = Modifier) {
    val entries = remember { reference() }
    var query by rememberSaveable { mutableStateOf("") }
    val byCategory = remember(entries, query) { entries.filter { it.matches(query) }.groupBy { it.category } }

    Surface(modifier, color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.reference_search)) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { query = "" }, modifier = Modifier.tagged("clear_search", stringResource(R.string.reference_clear_search))) {
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
            if (byCategory.isEmpty()) {
                Text(
                    text = stringResource(R.string.reference_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    for ((category, group) in byCategory) {
                        stickyHeader {
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
                        items(group) { entry -> EntryCard(entry, onInsert) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: ReferenceEntry, onInsert: (String) -> Unit) {
    val insertLabel = stringResource(R.string.reference_insert_example)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(text = entry.signature, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
                TextButton(
                    onClick = { onInsert(entry.snippet) },
                    modifier = Modifier.tagged("insert_example_${entry.name}", insertLabel),
                ) { Text(insertLabel) }
            }
            if (entry.aliases.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.reference_aliases, entry.aliases.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = entry.doc, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(end = 8.dp))
            Text(
                text = entry.snippet,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    .clickable(onClickLabel = insertLabel) { onInsert(entry.snippet) }
                    .padding(8.dp),
            )
        }
    }
}

/** True when [query], ignoring case and surrounding spaces, occurs in the name or in an alias. */
internal fun ReferenceEntry.matches(query: String): Boolean {
    val needle = query.trim()
    return needle.isEmpty() || name.contains(needle, ignoreCase = true) || aliases.any { it.contains(needle, ignoreCase = true) }
}
