package com.octopusman.cosic.editor

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.octopusman.cosic.visual.parseCssColor

/** How the line of an `outline` declaration is drawn. */
internal enum class OutlineStyle { SOLID, DASHED, DOTTED, DOUBLE }

/** The outline a mark is drawn with: [color] null is the colour of the text, [widthDp] the width of the line. */
internal data class MarkOutline(val color: Color?, val widthDp: Float, val style: OutlineStyle = OutlineStyle.SOLID)

/** What a `markcss` string says: a [span] style for the text it marks, and an [outline] drawn around it. Both are null for a string with nothing the editor understands. */
internal data class MarkStyle(val span: SpanStyle?, val outline: MarkOutline?) {
    companion object {
        val NONE = MarkStyle(null, null)
    }
}

/** What an outline is when a mark has no `markcss`: Strudel's `outline: solid 2px <color>`. */
internal fun defaultOutline(color: Color) = MarkOutline(color, 2f)

/**
 * The declarations of [css] that mean something to a marked piece of code: `color`, `background` and `background-color`, `text-decoration` (`underline`, `line-through`, `none`),
 * `font-weight` (`bold`, `normal`, 100 to 900), `font-style` (`italic`, `oblique`, `normal`), `letter-spacing` and `font-size` (`px` and `em`), and `outline` (`none`, or a style
 * of `solid`, `dashed`, `dotted` or `double`, a width in `px` and a colour, in any order; with no style there is no outline, as in a browser). Whatever else is there is ignored.
 * Strudel gives the mark this string as its whole style, so a `markcss` without `outline` has no outline.
 */
internal fun parseMarkCss(css: String): MarkStyle {
    var color: Color? = null
    var background: Color? = null
    var decoration: TextDecoration? = null
    var weight: FontWeight? = null
    var fontStyle: FontStyle? = null
    var letterSpacing: TextUnit = TextUnit.Unspecified
    var fontSize: TextUnit = TextUnit.Unspecified
    var outline: MarkOutline? = null
    for (declaration in css.split(';')) {
        val colon = declaration.indexOf(':')
        if (colon <= 0) continue
        val property = declaration.substring(0, colon).trim().lowercase()
        val value = declaration.substring(colon + 1).trim().removeSuffix("!important").trim()
        when (property) {
            "color" -> parseCssColor(value)?.let { color = it }
            "background", "background-color" -> cssWords(value).firstNotNullOfOrNull(::parseCssColor)?.let { background = it }
            "text-decoration", "text-decoration-line" -> decoration = parseDecoration(value) ?: decoration
            "font-weight" -> parseWeight(value)?.let { weight = it }
            "font-style" -> when (value.lowercase()) {
                "italic", "oblique" -> fontStyle = FontStyle.Italic
                "normal" -> fontStyle = FontStyle.Normal
            }
            "letter-spacing" -> parseLength(value)?.let { letterSpacing = it }
            "font-size" -> parseLength(value)?.let { fontSize = it }
            "outline" -> outline = parseOutline(value)
        }
    }
    val span = SpanStyle(
        color = color ?: Color.Unspecified,
        background = background ?: Color.Unspecified,
        textDecoration = decoration,
        fontWeight = weight,
        fontStyle = fontStyle,
        letterSpacing = letterSpacing,
        fontSize = fontSize,
    )
    return MarkStyle(span.takeIf { it != EMPTY_SPAN }, outline)
}

private val EMPTY_SPAN = SpanStyle()

/** The whitespace separated words of [value], where whitespace inside parentheses (`rgb(1, 2, 3)`) belongs to its word. */
private fun cssWords(value: String): List<String> {
    val words = ArrayList<String>()
    val word = StringBuilder()
    var depth = 0
    for (c in value) {
        if (c == '(') depth++
        if (c == ')' && depth > 0) depth--
        if (c.isWhitespace() && depth == 0 && c != '(') {
            if (word.isNotEmpty()) {
                words += word.toString()
                word.setLength(0)
            }
        } else {
            word.append(c)
        }
    }
    if (word.isNotEmpty()) words += word.toString()
    return words
}

private fun parseDecoration(value: String): TextDecoration? {
    var result: TextDecoration? = null
    for (word in cssWords(value.lowercase())) {
        val next = when (word) {
            "underline" -> TextDecoration.Underline
            "line-through" -> TextDecoration.LineThrough
            "none" -> TextDecoration.None
            else -> null
        } ?: continue
        result = if (result == null || next == TextDecoration.None) next else TextDecoration.combine(listOf(result, next))
    }
    return result
}

private fun parseWeight(value: String): FontWeight? = when (val v = value.lowercase()) {
    "bold" -> FontWeight.Bold
    "normal" -> FontWeight.Normal
    else -> v.toIntOrNull()?.takeIf { it in 100..900 }?.let { FontWeight(it) }
}

/** A length in `px` (a unit of text size here) or `em`; 0 may be written without a unit. */
private fun parseLength(value: String): TextUnit? {
    val v = value.trim().lowercase()
    return when {
        v.endsWith("px") -> v.removeSuffix("px").trim().toFloatOrNull()?.sp
        v.endsWith("em") -> v.removeSuffix("em").trim().toFloatOrNull()?.em
        else -> if (v.toFloatOrNull() == 0f) 0f.sp else null
    }
}

private fun parseOutline(value: String): MarkOutline? {
    var style: OutlineStyle? = null
    var none = false
    var width: Float? = null
    var color: Color? = null
    for (word in cssWords(value)) {
        val w = word.lowercase()
        when {
            w == "none" || w == "hidden" -> none = true
            w == "solid" -> style = OutlineStyle.SOLID
            w == "dashed" -> style = OutlineStyle.DASHED
            w == "dotted" -> style = OutlineStyle.DOTTED
            w == "double" -> style = OutlineStyle.DOUBLE
            w == "thin" -> width = 1f
            w == "medium" -> width = 3f
            w == "thick" -> width = 5f
            w.endsWith("px") -> w.removeSuffix("px").toFloatOrNull()?.let { width = it }
            else -> parseCssColor(word)?.let { color = it }
        }
    }
    if (none || style == null) return null
    return MarkOutline(color, (width ?: 3f).coerceAtLeast(0f), style)
}
