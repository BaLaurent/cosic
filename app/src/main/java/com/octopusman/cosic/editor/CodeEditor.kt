package com.octopusman.cosic.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.CosicColors
import com.octopusman.cosic.ui.tagged
import cosic.SourceRange
import cosic.script.TokenKind
import cosic.script.TokenSpan
import cosic.script.tokenize

/**
 * The code field: monospace text coloured by token kind, with the steps that are sounding right
 * now highlighted. [activeRanges] is read while composing this function, so a change of the
 * highlights recomposes only the editor.
 */
@Composable
fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    activeRanges: () -> List<SourceRange>,
    modifier: Modifier = Modifier,
) {
    val tokens = remember(value.text) { tokenize(value.text) }
    val transformation = remember(tokens, activeRanges()) { SyntaxTransformation(tokens, activeRanges()) }

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.tagged("code_editor", stringResource(R.string.editor_code)),
        textStyle = TextStyle(
            color = MaterialTheme.colorScheme.onBackground,
            fontFamily = FontFamily.Monospace,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Ascii,
        ),
        visualTransformation = transformation,
        // The padding lives inside the field, so touching it still focuses the editor.
        decorationBox = { field -> Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) { field() } },
    )
}

/** Colours the text by token kind and marks the active steps, without moving a single character. */
private class SyntaxTransformation(
    private val tokens: List<TokenSpan>,
    private val active: List<SourceRange>,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val length = text.length
        val styled = buildAnnotatedString {
            append(text)
            for (token in tokens) {
                token.kind.color()?.let { addSpan(SpanStyle(color = it), token.start, token.end, length) }
            }
            for (range in active) {
                addSpan(SpanStyle(background = CosicColors.ActiveStep), range.start, range.end, length)
            }
        }
        return TransformedText(styled, OffsetMapping.Identity)
    }

    // Offsets are clamped: the ranges were computed for text that may be a keystroke older than this.
    private fun AnnotatedString.Builder.addSpan(style: SpanStyle, start: Int, end: Int, length: Int) {
        val from = start.coerceIn(0, length)
        val to = end.coerceIn(0, length)
        if (from < to) addStyle(style, from, to)
    }
}

/** Identifiers and punctuation keep the text color. */
private fun TokenKind.color(): Color? = when (this) {
    TokenKind.COMMENT -> CosicColors.Comment
    TokenKind.MINI, TokenKind.STRING -> CosicColors.Mini
    TokenKind.NUMBER -> CosicColors.Number
    TokenKind.CALL -> CosicColors.Call
    TokenKind.LABEL -> CosicColors.Label
    TokenKind.IDENT, TokenKind.PUNCT -> null
}
