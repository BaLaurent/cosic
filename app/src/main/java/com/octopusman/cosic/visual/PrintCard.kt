package com.octopusman.cosic.visual

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octopusman.cosic.R
import cosic.visual.VisualTheme
import kotlin.math.floor

/** How many lines of [lineHeight] fit in a box of [height] with [padding] above and below (all in the same unit); at least one. */
internal fun printCapacity(height: Float, lineHeight: Float, padding: Float): Int =
    if (lineHeight <= 0f) 1 else floor(((height - 2 * padding) / lineHeight).toDouble()).toInt().coerceAtLeast(1)

/** The newest [capacity] lines of [lines], oldest first: what a box that shows the end of the output holds. */
internal fun printTail(lines: List<String>, capacity: Int): List<String> = if (lines.size <= capacity) lines else lines.subList(lines.size - capacity, lines.size)

/**
 * The output widget (`._print()`): the last lines the program printed, as many as the box holds, the newest at the bottom. [lines] is read when [revision] changes (the host
 * counts a frame that changed something), so a card for a program that is stopped does nothing. The box is [width] x [height] at most and keeps that shape on a narrower screen.
 */
@Composable
fun PrintCard(lines: () -> List<String>, revision: () -> Long, width: Dp = 600.dp, height: Dp = 100.dp, modifier: Modifier = Modifier) {
    val current by remember(lines, revision) { derivedStateOf(structuralEqualityPolicy()) { revision(); lines() } }
    val padding = 6.dp
    val lineHeight = 16.dp
    val capacity = printCapacity(height.value, lineHeight.value, padding.value)
    val shown = printTail(current, capacity)
    val foreground = parseCssColor(VisualTheme.foreground) ?: MaterialTheme.colorScheme.onSurface
    val backdrop = parseCssColor(VisualTheme.background) ?: Color.Black
    Box(
        modifier
            .widthIn(max = width)
            .fillMaxWidth()
            .aspectRatio(width.value / height.value)
            .background(backdrop)
            .clipToBounds(),
    ) {
        if (shown.isEmpty()) {
            Text(
                stringResource(R.string.visual_print_empty),
                Modifier.padding(padding),
                color = foreground.copy(alpha = 0.5f),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
            )
        } else {
            Column(Modifier.align(Alignment.BottomStart).padding(padding).fillMaxWidth()) {
                for (line in shown) {
                    Text(line, color = foreground, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = lineHeight.value.sp, maxLines = 1, overflow = TextOverflow.Clip)
                }
            }
        }
    }
}
