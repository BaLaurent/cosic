package com.octopusman.cosic.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged
import kotlin.math.abs

private val KEY_HEIGHT = 48.dp
private val TRACKPAD_STEP = 16.dp
private const val REPEAT_DELAY_MS = 400L
private const val REPEAT_INTERVAL_MS = 60L
private const val DISABLED_ALPHA = 0.38f

/** Which row of keys is above the keyboard while the code is edited. */
enum class KeyRow { SYMBOLS, EDIT, LINES }

/**
 * The row for moving the cursor and the selection with a finger: back to the symbols, a character left and right (hold to
 * repeat, slide sideways for a trackpad), the previous and next word or token, a larger and a smaller selection, and the lines row.
 * Every action is handed to [onEdit] as a transform of the text field value.
 */
@Composable
fun EditBar(
    onEdit: ((TextFieldValue) -> TextFieldValue) -> Unit,
    onSymbols: () -> Unit,
    onLines: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ladder = remember { SelectionLadder() }
    KeyRowSurface(modifier) {
        BarKey("mode_symbols", stringResource(R.string.editor_mode_symbols), onClick = onSymbols) {
            Text("{ }", fontFamily = FontFamily.Monospace, fontSize = 16.sp)
        }
        CursorKey("cursor_left", stringResource(R.string.editor_cursor_left), direction = -1, onStep = { d -> onEdit { it.moveCursor(d) } }) {
            Text("‹", fontSize = 24.sp)
        }
        CursorKey("cursor_right", stringResource(R.string.editor_cursor_right), direction = 1, onStep = { d -> onEdit { it.moveCursor(d) } }) {
            Text("›", fontSize = 24.sp)
        }
        BarKey("jump_left", stringResource(R.string.editor_jump_left), onClick = { onEdit { it.jumpToken(-1) } }) { Text("«", fontSize = 24.sp) }
        BarKey("jump_right", stringResource(R.string.editor_jump_right), onClick = { onEdit { it.jumpToken(1) } }) { Text("»", fontSize = 24.sp) }
        BarKey("select_expand", stringResource(R.string.editor_select_expand), onClick = { onEdit { ladder.expand(it) } }) {
            Text(stringResource(R.string.editor_select_expand_label), fontSize = 13.sp)
        }
        BarKey("select_shrink", stringResource(R.string.editor_select_shrink), onClick = { onEdit { ladder.shrink(it) } }) {
            Text(stringResource(R.string.editor_select_shrink_label), fontSize = 13.sp)
        }
        BarKey("lines_menu", stringResource(R.string.editor_lines_menu), onClick = onLines) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
    }
}

/**
 * The row of line actions: back to the edit row, duplicate, comment or uncomment, move up, move down, delete the lines the
 * selection touches, and undo.
 */
@Composable
fun LineBar(
    onEdit: ((TextFieldValue) -> TextFieldValue) -> Unit,
    canUndo: Boolean,
    onUndo: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    KeyRowSurface(modifier) {
        BarKey("line_back", stringResource(R.string.editor_lines_back), onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null) }
        BarKey("line_duplicate", stringResource(R.string.editor_line_duplicate), onClick = { onEdit { it.duplicateLines() } }) {
            Icon(Icons.Filled.ContentCopy, contentDescription = null)
        }
        BarKey("line_comment", stringResource(R.string.editor_line_comment), onClick = { onEdit { it.toggleLineComment() } }) {
            Text("//", fontFamily = FontFamily.Monospace, fontSize = 18.sp)
        }
        BarKey("line_up", stringResource(R.string.editor_line_up), onClick = { onEdit { it.moveLinesUp() } }) { Icon(Icons.Filled.ArrowUpward, contentDescription = null) }
        BarKey("line_down", stringResource(R.string.editor_line_down), onClick = { onEdit { it.moveLinesDown() } }) { Icon(Icons.Filled.ArrowDownward, contentDescription = null) }
        BarKey("line_delete", stringResource(R.string.editor_line_delete), onClick = { onEdit { it.deleteLines() } }) { Icon(Icons.Filled.Delete, contentDescription = null) }
        BarKey("line_undo", stringResource(R.string.editor_undo), onClick = onUndo, enabled = canUndo) {
            Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
        }
    }
}

@Composable
private fun KeyRowSurface(modifier: Modifier, keys: @Composable RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), content = keys)
        }
    }
}

@Composable
private fun RowScope.BarKey(tag: String, description: String, onClick: () -> Unit, enabled: Boolean = true, content: @Composable () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .height(KEY_HEIGHT)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else DISABLED_ALPHA)
            .tagged(tag, description),
    ) { content() }
}

/** A key that moves the cursor one character toward [direction]; see [cursorGestures]. */
@Composable
private fun RowScope.CursorKey(tag: String, description: String, direction: Int, onStep: (Int) -> Unit, content: @Composable () -> Unit) {
    val step by rememberUpdatedState(onStep)
    val stepPx = with(LocalDensity.current) { TRACKPAD_STEP.toPx() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .weight(1f)
            .height(KEY_HEIGHT)
            .pointerInput(direction, stepPx) { cursorGestures(direction, stepPx) { step(it) } }
            .semantics {
                role = Role.Button
                onClick { step(direction); true }
            }
            .tagged(tag, description),
    ) { content() }
}

/**
 * A tap steps once toward [direction]. Holding the key still steps again after [REPEAT_DELAY_MS], then every [REPEAT_INTERVAL_MS].
 * Sliding sideways past the touch slop turns the key into a trackpad: one step per [stepPx] of travel beyond the slop, in the
 * direction of the finger (either way), and the repeat stops.
 */
private suspend fun PointerInputScope.cursorGestures(direction: Int, stepPx: Float, step: (Int) -> Unit) {
    awaitEachGesture {
        val down = awaitFirstDown()
        down.consume()
        val slop = viewConfiguration.touchSlop
        var stepped = false
        var dragging = false
        var travel = 0f
        var now = down.uptimeMillis
        var repeatAt = now + REPEAT_DELAY_MS
        while (true) {
            val event = if (dragging) awaitPointerEvent() else withTimeoutOrNull((repeatAt - now).coerceAtLeast(1L)) { awaitPointerEvent() }
            if (event == null) {
                step(direction)
                stepped = true
                now = repeatAt
                repeatAt += REPEAT_INTERVAL_MS
                continue
            }
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            now = change.uptimeMillis
            if (!change.pressed) {
                change.consume()
                if (!stepped && !dragging) step(direction)
                break
            }
            travel += change.positionChange().x
            change.consume()
            if (!dragging && abs(travel) > slop) {
                dragging = true
                travel -= if (travel > 0) slop else -slop
            }
            if (dragging) {
                while (abs(travel) >= stepPx) {
                    val sign = if (travel > 0) 1 else -1
                    step(sign)
                    stepped = true
                    travel -= sign * stepPx
                }
            }
        }
    }
}
