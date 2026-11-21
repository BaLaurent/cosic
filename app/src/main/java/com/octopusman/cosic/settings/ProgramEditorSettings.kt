package com.octopusman.cosic.settings

import androidx.compose.ui.text.font.FontFamily
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What a program asks of the editor: `fontSize(18)` and `fontFamily('serif')` (and `theme(...)`, which the app has no use for: it has one theme). They
 * are not kept: they last until another project is opened ([reset]), and the program that asks is evaluated again when its project is.
 *
 * The engine's output thread calls [set] (once for every hap of the pattern the setting is attached to), so a value that is already
 * the current one changes nothing, and reading [fontSizeSp] and [fontFamily] from the UI thread is safe.
 */
class ProgramEditorSettings {
    private val _fontSizeSp = MutableStateFlow<Float?>(null)
    private val _fontFamily = MutableStateFlow<FontFamily?>(null)

    /** The size of the code text in sp, or null for the editor's own. */
    val fontSizeSp: StateFlow<Float?> = _fontSizeSp.asStateFlow()

    /** The family of the code text, or null for the editor's own (monospace). */
    val fontFamily: StateFlow<FontFamily?> = _fontFamily.asStateFlow()

    /** [key] is `theme`, `fontFamily` or `fontSize`; [value] is a [String] or a [Double]. */
    fun set(key: String, value: Any?) {
        when (key) {
            "fontSize" -> (value as? Double)?.takeIf { it.isFinite() }?.let { _fontSizeSp.value = it.toFloat().coerceIn(MIN_FONT_SP, MAX_FONT_SP) }
            "fontFamily" -> _fontFamily.value = familyOf(value as? String)
        }
    }

    /** Takes back everything programs asked for: another project starts with the editor's own look. */
    fun reset() {
        _fontSizeSp.value = null
        _fontFamily.value = null
    }

    companion object {
        const val MIN_FONT_SP = 8f
        const val MAX_FONT_SP = 40f

        /** The generic families a program can name; any other name (a font the device may not have) is the editor's own. */
        internal fun familyOf(name: String?): FontFamily? = when (name?.trim()?.lowercase()) {
            "monospace" -> FontFamily.Monospace
            "serif" -> FontFamily.Serif
            "sans-serif" -> FontFamily.SansSerif
            "cursive" -> FontFamily.Cursive
            else -> null
        }
    }
}
