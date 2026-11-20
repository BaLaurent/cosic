package com.octopusman.cosic.reference

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How many suggestions the row shows, the best ones. */
internal const val MAX_CHIPS = 8

/**
 * The [Completer] of the app over the cards of the documentation and the names a program can call: made when first needed, off the main thread (it reads a
 * quarter of a megabyte of JSON and has the engine list its names), null until it is there.
 */
@Composable
internal fun rememberCompleter(): Completer? {
    val completer by produceState<Completer?>(null) {
        value = withContext(Dispatchers.Default) { Completer(DocJson.cards) { cosic.script.isDefined(it) } }
    }
    return completer
}

/**
 * The row of suggestions for the word being typed: the best [MAX_CHIPS], each a chip that inserts its name ([onPick]) with an info icon that shows
 * its documentation ([onInfo]). The part of a name that the word matched is in bold. It is for a place above the keyboard, where the word is being typed.
 */
@Composable
internal fun AutocompleteRow(suggestions: List<Suggestion>, onPick: (Suggestion) -> Unit, onInfo: (Suggestion) -> Unit, modifier: Modifier = Modifier) {
    val shown = remember(suggestions) { suggestions.take(MAX_CHIPS) }
    LazyRow(
        modifier = modifier.tagged("autocomplete_row", stringResource(R.string.autocomplete_suggestions)),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        itemsIndexed(shown, key = { _, s -> s.label }) { _, suggestion -> SuggestionChip(suggestion, onPick, onInfo) }
    }
}

@Composable
private fun SuggestionChip(suggestion: Suggestion, onPick: (Suggestion) -> Unit, onInfo: (Suggestion) -> Unit) {
    val pickLabel = stringResource(R.string.autocomplete_pick, suggestion.label)
    val infoLabel = stringResource(R.string.autocomplete_info, suggestion.label)
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = highlighted(suggestion),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .clickable(onClickLabel = pickLabel) { onPick(suggestion) }
                    .tagged("autocomplete_${suggestion.label}", pickLabel)
                    .padding(start = 12.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
            )
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = infoLabel,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clickable(onClickLabel = infoLabel) { onInfo(suggestion) }
                    .tagged("autocomplete_info_${suggestion.label}", infoLabel)
                    .padding(start = 4.dp, end = 10.dp, top = 6.dp, bottom = 6.dp)
                    .size(18.dp),
            )
        }
    }
}

/** The label of [suggestion] with the stretches the word matched in bold. */
internal fun highlighted(suggestion: Suggestion): AnnotatedString = buildAnnotatedString {
    val label = suggestion.label
    var at = 0
    val ranges = suggestion.matched
    var i = 0
    while (i + 1 < ranges.size) {
        val from = ranges[i].coerceIn(at, label.length)
        val to = ranges[i + 1].coerceIn(from, label.length)
        append(label.substring(at, from))
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(label.substring(from, to)) }
        at = to
        i += 2
    }
    append(label.substring(at))
}
