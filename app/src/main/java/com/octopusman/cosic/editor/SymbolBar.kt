package com.octopusman.cosic.editor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged

private const val TAB = "  "

// The punctuation live code needs most, which phone keyboards bury on secondary pages.
private val SYMBOLS = listOf(
    "\"", "(", ")", ".", "[", "]", "<", ">", "~", "*", ",", ":",
    "$", "{", "}", "!", "?", "@", "/", "=", "'", "_", "-",
)

/**
 * A scrolling row of undo, redo, tab and code symbols. Put it right above the keyboard: [modifier]
 * should carry the window insets padding.
 */
@Composable
fun SymbolBar(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onSymbol: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
        ) {
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.tagged("undo", stringResource(R.string.editor_undo))) {
                Icon(Icons.AutoMirrored.Filled.Undo, contentDescription = null)
            }
            IconButton(onClick = onRedo, enabled = canRedo, modifier = Modifier.tagged("redo", stringResource(R.string.editor_redo))) {
                Icon(Icons.AutoMirrored.Filled.Redo, contentDescription = null)
            }
            SymbolKey(label = stringResource(R.string.editor_tab), onClick = { onSymbol(TAB) })
            for (symbol in SYMBOLS) {
                SymbolKey(label = symbol, monospace = true, onClick = { onSymbol(symbol) })
            }
        }
    }
}

@Composable
private fun SymbolKey(label: String, onClick: () -> Unit, monospace: Boolean = false) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 12.dp),
        modifier = Modifier.defaultMinSize(minWidth = 40.dp),
    ) {
        Text(
            text = label,
            fontFamily = if (monospace) FontFamily.Monospace else null,
            fontSize = if (monospace) 18.sp else 14.sp,
        )
    }
}
