package cosic.visual

/**
 * The colours the visuals take when a program names none (`getTheme()` of draw.mjs). They are the defaults of Strudel's own theme
 * (packages/codemirror/themes/strudel-theme.mjs), which the REPL installs at startup: the foreground is white, so a pianoroll of a
 * program that sets no colour is white on whatever is behind it. The app may set them to the colours of the editor.
 */
object VisualTheme {
    @Volatile
    var foreground: String = "#fff"

    @Volatile
    var gutterForeground: String = "#8a919966"

    @Volatile
    var background: String = "#222"
}
