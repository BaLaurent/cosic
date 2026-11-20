package com.octopusman.cosic.reference

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged

/**
 * The documentation of a function in a sheet, as the editor of Strudel shows it next to a suggestion (`Autocomplete` of autocomplete.mjs): the name, its
 * synonyms, the description, the parameters with their types, and the examples, each of which Insert puts into the code ([onInsertExample] gets its snippet,
 * the example with the label a program with labels needs). Tapping an example inserts it too.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DocCardSheet(card: DocEntry, onInsertExample: (String) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.tagged("doc_card_sheet", stringResource(R.string.autocomplete_info, card.name)),
    ) {
        DocCardContent(card, onInsertExample, Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp))
    }
}

/** The contents of the card of [card]: see [DocCardSheet]. */
@Composable
internal fun DocCardContent(card: DocEntry, onInsertExample: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(card.name, style = MaterialTheme.typography.titleLarge, fontFamily = FontFamily.Monospace, color = colors.primary)
        if (card.synonyms.isNotEmpty()) {
            Text(
                text = stringResource(R.string.doc_card_synonyms, card.synonymsText),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = colors.onSurfaceVariant,
            )
        }
        if (card.description.isNotBlank()) DocBlocks(remember(card.description) { DocHtml.parse(card.description) })
        if (card.params.isNotEmpty()) {
            SectionTitle(stringResource(R.string.doc_card_parameters))
            for (param in card.params) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(param.name, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Text(
                            text = param.types.joinToString(" | "),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = colors.tertiary,
                        )
                    }
                    if (param.description.isNotBlank()) {
                        Text(
                            text = remember(param.description) { DocHtml.plainText(param.description) },
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (card.examples.isNotEmpty()) {
            SectionTitle(stringResource(R.string.doc_card_examples))
            for ((i, example) in card.examples.withIndex()) {
                val insertLabel = stringResource(R.string.doc_card_insert_example)
                Row(verticalAlignment = androidx.compose.ui.Alignment.Top) {
                    Text(
                        text = example.code,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .weight(1f)
                            .background(colors.background, RoundedCornerShape(8.dp))
                            .clickable(onClickLabel = insertLabel) { onInsertExample(example.snippet) }
                            .horizontalScroll(rememberScrollState())
                            .padding(8.dp),
                    )
                    TextButton(
                        onClick = { onInsertExample(example.snippet) },
                        modifier = Modifier.tagged("doc_card_insert_${card.name}_$i", insertLabel),
                    ) { Text(stringResource(R.string.doc_card_insert)) }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

/** The blocks of a description: paragraphs, list items with their bullets, and blocks of code. */
@Composable
internal fun DocBlocks(blocks: List<DocBlock>) {
    val colors = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in blocks) {
            when (block) {
                is DocBlock.Paragraph -> Text(
                    text = annotated(block.spans, colors.primary, colors.surfaceVariant),
                    style = style,
                    modifier = if (block.quote) Modifier.padding(start = 12.dp) else Modifier,
                    color = if (block.quote) colors.onSurfaceVariant else Color.Unspecified,
                )
                is DocBlock.Item -> Row(Modifier.padding(start = 8.dp)) {
                    Text(block.marker, style = style, modifier = Modifier.width(24.dp))
                    Text(annotated(block.spans, colors.primary, colors.surfaceVariant), style = style)
                }
                is DocBlock.Code -> Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(colors.background, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                )
            }
        }
    }
}

/** [spans] as text with the style of each: code in monospace on a background, bold, italic, and links that open their address. */
internal fun annotated(spans: List<DocSpan>, linkColor: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            background = if (span.code) codeBackground else Color.Unspecified,
        )
        if (span.href != null) {
            withLink(LinkAnnotation.Url(span.href, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) {
                withStyle(style) { append(span.text) }
            }
        } else {
            withStyle(style) { append(span.text) }
        }
    }
}
