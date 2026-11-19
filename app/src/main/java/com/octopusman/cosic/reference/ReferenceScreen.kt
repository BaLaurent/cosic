package com.octopusman.cosic.reference

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.BackTopBar
import com.octopusman.cosic.ui.copyWithConfirmation
import com.octopusman.cosic.ui.tagged
import cosic.script.ReferenceEntry
import cosic.script.reference
import kotlinx.coroutines.launch

/** The language reference: every function with its signature, a one-line description and an example to copy. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun ReferenceScreen(onBack: () -> Unit) {
    val entries = remember { reference() }
    var query by rememberSaveable { mutableStateOf("") }
    val byCategory = remember(entries, query) { entries.filter { it.matches(query) }.groupBy { it.category } }

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.reference_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        // The keyboard shrinks the list instead of covering its end.
        Column(Modifier.padding(padding).consumeWindowInsets(padding).imePadding().fillMaxSize()) {
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
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .tagged("reference_search", stringResource(R.string.reference_search)),
            )
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
                                    .background(MaterialTheme.colorScheme.background)
                                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(group) { entry ->
                            EntryCard(entry) {
                                scope.launch {
                                    clipboard.copyWithConfirmation(entry.example, snackbar, context.getString(R.string.reference_example_copied))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EntryCard(entry: ReferenceEntry, onCopyExample: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.primary,
            )
            if (entry.aliases.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.reference_aliases, entry.aliases.joinToString(", ")),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(text = entry.signature, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Text(text = entry.doc, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = entry.example,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
                    .padding(10.dp),
            )
            TextButton(
                onClick = onCopyExample,
                modifier = Modifier
                    .align(Alignment.End)
                    .tagged("copy_example_${entry.name}", stringResource(R.string.reference_copy_example)),
            ) { Text(stringResource(R.string.reference_copy_example)) }
        }
    }
}

/** True when [query], ignoring case and surrounding spaces, occurs in the name or in an alias. */
internal fun ReferenceEntry.matches(query: String): Boolean {
    val needle = query.trim()
    return needle.isEmpty() || name.contains(needle, ignoreCase = true) || aliases.any { it.contains(needle, ignoreCase = true) }
}
