package cosic.script.next

import cosic.SourceRange
import java.util.concurrent.ConcurrentHashMap

/**
 * A language written inside a tagged template, ``mondo`c3 e3` `` or ``tidal`...` ``. The tag is the name the language is
 * registered under in [EmbeddedLanguages]. The program keeps the tagged template as it is; the interpreter evaluates it
 * by handing the language its first piece, and the transpiler asks the language for the locations of its atoms.
 * Only the first piece is used: `${}` and everything after it is dropped, as in plugin-mini.mjs:18-19.
 */
internal fun interface EmbeddedLanguage {
    /**
     * Runs [code], the raw text of the first piece of the template, and returns its value. [codeStart] is the offset of
     * the first character of [code] in the program, that is just after the opening backtick; [range] is that of the
     * whole tagged template, for errors. [realm] is the realm the program runs in.
     */
    fun evaluate(code: String, codeStart: Int, realm: Realm, range: SourceRange): Any?

    /**
     * The source ranges of the atoms of [code], for highlighting while it plays (the `getLocations` the original
     * registers with `registerLanguage`). They are offsets in the program, so [codeStart] is added to every position.
     */
    fun locations(code: String, codeStart: Int): List<SourceRange> = emptyList()
}

/** The languages that tagged templates can be written in, by tag. */
internal object EmbeddedLanguages {
    private val languages = ConcurrentHashMap<String, EmbeddedLanguage>()

    /** Makes ``tag`...` `` a program in [lang]; a later registration of the same tag replaces the earlier one. */
    fun register(tag: String, lang: EmbeddedLanguage) {
        languages[tag] = lang
    }

    fun lookup(tag: String): EmbeddedLanguage? = languages[tag]
}
