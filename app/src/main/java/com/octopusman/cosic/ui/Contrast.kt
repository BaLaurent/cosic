package com.octopusman.cosic.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** The WCAG contrast ratio of two opaque colours, from 1 (the same) to 21 (black on white). */
internal fun contrastRatio(a: Color, b: Color): Float {
    val la = a.luminance()
    val lb = b.luminance()
    return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
}

/**
 * This colour when it reads on [background] with at least [minRatio] of contrast; otherwise the first colour on the way from it to white (on a dark
 * background) or to black (on a light one), in steps of a twentieth, that does. For colours a program picks, which can be unreadable on the theme.
 */
internal fun Color.readableOn(background: Color, minRatio: Float = 4.5f): Color {
    if (contrastRatio(this, background) >= minRatio) return this
    val target = if (background.luminance() < 0.5f) Color.White else Color.Black
    for (step in 1..20) {
        val candidate = lerp(this, target, step * 0.05f)
        if (contrastRatio(candidate, background) >= minRatio) return candidate
    }
    return target
}
