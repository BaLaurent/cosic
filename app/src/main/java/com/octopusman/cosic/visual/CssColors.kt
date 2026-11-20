package com.octopusman.cosic.visual

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import cosic.visual.CssColor

/**
 * The colour a CSS colour string means: a named colour, `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`, `rgb()`, `rgba()`, `hsl()` or `hsla()` (see [CssColor]); null for a string
 * that is no colour. This is the `parseColor` the console takes to show a line in the colour of its hap.
 */
fun parseCssColor(text: String): Color? = CssColor.parse(text)?.let { Color(it) }

/** [this] as the CSS string the visuals read (`VisualTheme`): `#rrggbb`, or `#rrggbbaa` when it is not opaque. */
fun Color.toCssHex(): String {
    val argb = toArgb()
    val a = (argb ushr 24) and 0xFF
    val rgb = argb and 0x00FFFFFF
    return if (a == 0xFF) "#%06x".format(rgb) else "#%06x%02x".format(rgb, a)
}
