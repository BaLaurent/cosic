package com.octopusman.cosic.editor

import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.structuralEqualityPolicy
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.sp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.CosicColors
import com.octopusman.cosic.ui.tagged
import cosic.script.TokenKind
import cosic.script.TokenSpan
import cosic.script.tokenize
import kotlin.math.ceil
import kotlinx.coroutines.delay

/**
 * The code field of the editor: monospace text coloured by token kind, with the widgets of the program under the lines that make them, the sliders in front of their numbers, and
 * the steps that are sounding right now marked by an outline (INLINE D-E1: the field is an ordinary text field whose transformation makes room for the widgets, and the widgets
 * are laid over that room, so the keyboard and the selection behave as they do in any text field).
 *
 *  - [highlights] and [flashUntilMs] are read in the draw phase only: a change of what sounds redraws, and never lays the text out again. A `markcss` that styles the text itself
 *    does, for as long as it is on.
 *  - [widgets] say where the widgets and sliders of the program are in [value]; [blockContent] shows a widget (told whether it is near the screen) and [sliderValues] holds
 *    the numbers the sliders are at. A drag of a slider reports [onSliderStart], [onSliderChange] and [onSliderEnd].
 *  - [modifier] is the root's (size, focus changes) and [fieldModifier] the field's (the focus requester). [fontSizeSp] and [fontFamily] are what a program asked for with
 *    `fontSize()` and `fontFamily()`; null is the editor's own.
 *
 * The editor scrolls itself, vertically; the field is as tall as its text.
 */
@Composable
internal fun CodeEditor(
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    highlights: () -> List<ActiveHighlight>,
    widgets: EditorAnchors,
    sliderValues: Map<String, Double>,
    onSliderStart: (String) -> Unit,
    onSliderChange: (String, Double) -> Unit,
    onSliderEnd: () -> Unit,
    blockContent: @Composable (PlannedBlock, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    fieldModifier: Modifier = Modifier,
    flashUntilMs: Long = 0L,
    fontSizeSp: Float? = null,
    fontFamily: FontFamily? = null,
) {
    val density = LocalDensity.current
    val text = value.text
    val tokens = remember(text) { tokenize(text) }
    var viewport by remember { mutableStateOf(IntSize.Zero) }
    val size = fontSizeSp ?: DEFAULT_FONT_SP
    val lineHeightSp = size * EditorMetrics.LINE_HEIGHT_PER_FONT
    val naturalRowPx = with(density) { ceil(lineHeightSp.sp.toPx()).toInt() }
    // Until the layout says what a row is, it is what a line of the text style is.
    var rowPx by remember(naturalRowPx) { mutableIntStateOf(naturalRowPx) }
    val padLeftPx = with(density) { EditorMetrics.PadX.roundToPx() }
    val contentWidthDp = with(density) { (viewport.width - 2 * padLeftPx).coerceAtLeast(0).toDp().value }
    val plan = remember(text, tokens, widgets, contentWidthDp, rowPx, density.density) {
        planEditor(text, tokens, widgets, contentWidthDp, density.density, rowPx)
    }
    val styles = remember { MarkStyles() }
    val marks by remember(highlights, styles) { derivedStateOf(structuralEqualityPolicy()) { textStyledHighlights(highlights(), styles) } }
    val transformation = remember(tokens, plan, marks) { SyntaxTransformation(tokens, plan.map, marks) }
    val geometry = remember { EditorGeometry() }
    val scroll = rememberScrollState()

    var flashing by remember { mutableStateOf(false) }
    LaunchedEffect(flashUntilMs) {
        val remaining = flashUntilMs - SystemClock.uptimeMillis()
        if (remaining > 0) {
            flashing = true
            delay(remaining)
            flashing = false
        }
    }

    val padTopPx = with(density) { EditorMetrics.PadY.roundToPx() }
    FollowSelection(value, plan, geometry, scroll, viewport.height, padTopPx, rowPx)

    Box(modifier.onSizeChanged { viewport = it }) {
        Box(Modifier.fillMaxSize().verticalScroll(scroll)) {
            EditorLayout(
                plan = plan,
                geometry = geometry,
                scroll = scroll,
                viewportHeightPx = viewport.height,
                rowPx = rowPx,
                onRowPx = { rowPx = it },
                highlights = highlights,
                flashing = { flashing },
            ) {
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = fieldModifier
                        .fieldSlot()
                        .fillMaxWidth()
                        .heightIn(min = with(density) { viewport.height.toDp() })
                        .tagged("code_editor", stringResource(R.string.editor_code)),
                    textStyle = TextStyle(
                        color = MaterialTheme.colorScheme.onBackground,
                        fontFamily = fontFamily ?: FontFamily.Monospace,
                        fontSize = size.sp,
                        lineHeight = lineHeightSp.sp,
                        // every row is as high as the line height says, the first and the last too: the widgets are laid out in whole rows
                        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrectEnabled = false,
                        keyboardType = KeyboardType.Ascii,
                    ),
                    visualTransformation = transformation,
                    onTextLayout = { geometry.layout = it },
                    // The padding lives inside the field, so touching it still focuses the editor.
                    decorationBox = { field -> Box(Modifier.padding(horizontal = EditorMetrics.PadX, vertical = EditorMetrics.PadY)) { field() } },
                )
                for (block in plan.blocks) BlockFrame(block, geometry, blockContent)
                for (slider in plan.sliders) {
                    key(slider.id) {
                        SliderHost(
                            slider = slider,
                            values = sliderValues,
                            onStart = onSliderStart,
                            onChange = onSliderChange,
                            onEnd = onSliderEnd,
                            modifier = Modifier.sliderSlot(slider.id),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Keeps the caret on the screen when it moves (typing, arrow keys, a selection that grows, a handle that is dragged): the outer scroll does not follow the text field by itself.
 * Does nothing when the caret is on the screen already and two rows (as far as the screen has room for) from its edge.
 */
@Composable
private fun FollowSelection(
    value: TextFieldValue,
    plan: EditorPlan,
    geometry: EditorGeometry,
    scroll: ScrollState,
    viewportHeightPx: Int,
    padTopPx: Int,
    rowPx: Int,
) {
    LaunchedEffect(value.text, value.selection, viewportHeightPx) {
        if (viewportHeightPx <= 0) return@LaunchedEffect
        withFrameNanos { }
        val layout = geometry.layout ?: return@LaunchedEffect
        val length = layout.layoutInput.text.length
        if (length != plan.map.transformedLength) return@LaunchedEffect
        val caret = plan.map.originalToTransformed(value.selection.end).coerceIn(0, length)
        val rect = layout.getCursorRect(caret)
        val margin = minOf(2f * rowPx, maxOf(0f, (viewportHeightPx - rect.height) / 2f))
        val top = rect.top + padTopPx - margin
        val bottom = rect.bottom + padTopPx + margin
        val viewTop = scroll.value.toFloat()
        val viewBottom = viewTop + viewportHeightPx
        val delta = when {
            top < viewTop -> top - viewTop
            bottom > viewBottom -> bottom - viewBottom
            else -> 0f
        }
        if (delta != 0f) scroll.scrollBy(delta)
    }
}

/** The size of the code text, and the height of a line at that size, when no program asked for another. */
private const val DEFAULT_FONT_SP = 14f

/** Colours the text by token kind and the marked steps by their `markcss`, and makes room for the widgets and the sliders without touching a character of the code. */
private class SyntaxTransformation(
    private val tokens: List<TokenSpan>,
    private val map: InsertionMap,
    private val marks: List<MarkSpan>,
) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        // The text is not the one this was made for (a keystroke ahead of the recomposition): show it as it is.
        if (text.length != map.originalLength) return TransformedText(text, OffsetMapping.Identity)
        val shown = map.apply(text.text)
        val length = shown.length
        val styled = buildAnnotatedString {
            append(shown)
            for (token in tokens) {
                val style = token.kind.style() ?: continue
                addSpan(style, map.spanStart(token.start), map.spanEnd(token.end), length)
            }
            for (mark in marks) addSpan(mark.style, map.spanStart(mark.range.start), map.spanEnd(mark.range.end), length)
        }
        return TransformedText(styled, if (map.size == 0) OffsetMapping.Identity else map.offsetMapping)
    }

    // Offsets are clamped: the ranges were computed for text that may be a keystroke older than this.
    private fun AnnotatedString.Builder.addSpan(style: SpanStyle, start: Int, end: Int, length: Int) {
        val from = start.coerceIn(0, length)
        val to = end.coerceIn(0, length)
        if (from < to) addStyle(style, from, to)
    }
}

private val COMMENT_STYLE = SpanStyle(color = CosicColors.Comment)
private val MINI_STYLE = SpanStyle(color = CosicColors.Mini)
private val NUMBER_STYLE = SpanStyle(color = CosicColors.Number)
private val CALL_STYLE = SpanStyle(color = CosicColors.Call)
private val LABEL_STYLE = SpanStyle(color = CosicColors.Label)

/** Identifiers and punctuation keep the text colour. */
private fun TokenKind.style(): SpanStyle? = when (this) {
    TokenKind.COMMENT -> COMMENT_STYLE
    TokenKind.MINI, TokenKind.STRING -> MINI_STYLE
    TokenKind.NUMBER -> NUMBER_STYLE
    TokenKind.CALL -> CALL_STYLE
    TokenKind.LABEL -> LABEL_STYLE
    TokenKind.IDENT, TokenKind.PUNCT -> null
}
