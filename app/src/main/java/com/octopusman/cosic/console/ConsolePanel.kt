package com.octopusman.cosic.console

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.CosicColors
import com.octopusman.cosic.ui.readableOn
import com.octopusman.cosic.ui.tagged
import cosic.io.ConsoleLog
import cosic.io.LogEntry
import kotlinx.coroutines.delay

/** How often the panel looks whether a line was written, while it is shown. */
private const val POLL_MILLIS = 250L

/**
 * The console of the engine: what programs and the engine write, as the console of the web REPL shows it (ConsoleTab.jsx): the last 40 lines in monospace type, the newest
 * at the bottom, where the list scrolls to when a line is written. An error is inverted, a highlight underlined, a line that was written again right
 * after itself has `(n)` after it, addresses are links, and a line that was written for a hap is in the colour of that hap ([parseColor] turns the CSS
 * colour of the hap into a colour; a line is not tinted when it returns null). A warning is amber and a debug line dimmed.
 *
 * [console]'s lines are read at intervals while the panel is shown, as it is a list that changes when it is written to.
 */
@Composable
fun ConsolePanel(console: ConsoleLog, modifier: Modifier = Modifier, parseColor: (String) -> Color? = { null }) {
    val entries by produceState(console.entries, console) {
        while (true) {
            value = console.entries
            delay(POLL_MILLIS)
        }
    }
    ConsoleLines(entries, modifier, parseColor)
}

/** The lines of a console: see [ConsolePanel]. */
@Composable
internal fun ConsoleLines(entries: List<LogEntry>, modifier: Modifier = Modifier, parseColor: (String) -> Color? = { null }) {
    val listState = rememberLazyListState()
    LaunchedEffect(entries) {
        if (entries.isNotEmpty()) listState.scrollToItem(entries.lastIndex)
    }
    Surface(modifier.tagged("console_panel", stringResource(R.string.console_title)), color = MaterialTheme.colorScheme.surface) {
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(16.dp)) {
                Text(
                    text = stringResource(R.string.console_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), state = listState, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(entries, key = { it.id }) { entry -> ConsoleLine(entry, parseColor) }
            }
        }
    }
}

@Composable
private fun ConsoleLine(entry: LogEntry, parseColor: (String) -> Color?) {
    val colors = MaterialTheme.colorScheme
    val inverted = entry.type == "error"
    val base = when {
        inverted -> colors.surface
        entry.type == "warning" -> CosicColors.Warning
        entry.type == "debug" -> colors.onSurfaceVariant
        else -> colors.onSurface
    }
    val tint = entry.color?.let(parseColor)
    val textColor = tint?.readableOn(if (inverted) colors.onSurface else colors.surface) ?: base
    val linkColor = if (inverted) colors.primaryContainer else colors.primary
    val text = remember(entry, textColor, linkColor) { lineText(entry, textColor, linkColor) }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        modifier = Modifier
            .fillMaxWidth()
            .let { if (inverted) it.background(colors.onSurface) else it }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** [entry] as the text of a line: links are underlined and open the address, a highlight is underlined whole. */
internal fun lineText(entry: LogEntry, color: Color, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val whole = if (entry.type == "highlight") SpanStyle(color = color, textDecoration = TextDecoration.Underline) else SpanStyle(color = color)
    withStyle(whole) {
        for (part in Linkify.parts(entry.message)) {
            if (part.href == null) {
                append(part.text)
            } else {
                withLink(LinkAnnotation.Url(part.href, TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)))) { append(part.text) }
            }
        }
        if (entry.count > 1) append(" (${entry.count})")
    }
}
