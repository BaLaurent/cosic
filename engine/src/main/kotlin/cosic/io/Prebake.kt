package cosic.io

/**
 * The prebake script of the web REPL: code that runs once before the first program of a session, to define what every program can use. The
 * REPL keeps it in its settings (`prebakeScript`, website/src/settings.mjs:21-33) and its Share button puts it in front of the code of a link
 * (`includePrebakeScriptInShare`, on by default), so that the links people share carry it. [cosic.CosicEngine.prebake] runs it.
 */
object Prebake {
    /** The script the REPL starts with: it defines `ratchet`, a pattern function that plays every event twice, sometimes. */
    val DEFAULT: String = listOf(
        "// Prebake script",
        "//",
        "// This is code that is loaded before your pattern is run.",
        "// You can use it to define custom functions to use in any pattern.",
        "// ",
        "// This is an initial example script. You can edit it to add ",
        "// your own funtions.",
        "//",
        "// To use a script shared by some other user you can use",
        "// the import-button or paste the script in this editor.",
        "",
        "const ratchet = register('ratchet', (pat) => pat.sometimes(ply(2)))",
        "",
    ).joinToString("\n")
}
