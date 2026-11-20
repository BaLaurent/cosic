package cosic.script.mondo

import cosic.SourceRange
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.patternOf
import cosic.script.Bridge
import cosic.script.EmbeddedLanguage
import cosic.script.Realm
import cosic.script.attributing
import cosic.script.bridge
import cosic.script.js.JsFunction

/*
 * Mondo as a language of tagged templates: ``mondo`s bd hh` `` and ``mondolang`s bd hh` `` are patterns, and the atoms of
 * their code are highlighted while they play. `mondo` knows where its code is in the program, so what is wrong with it is
 * reported there and its atoms are marked at their own positions; `mondolang` counts from zero (the original made it for
 * code that stands on its own, as in the documentation), so the positions it finds mean nothing in the program: its
 * errors are reported for its code (while the program is read) or for the whole template (while it is evaluated).
 */
internal class MondoLanguage(private val withOffset: Boolean) : EmbeddedLanguage {
    override fun evaluate(code: String, codeStart: Int, realm: Realm, range: SourceRange): Any? {
        val bridge = realm.bridge
        val pattern = try {
            mondoPattern(bridge, code, if (withOffset) codeStart else 0)
        } catch (e: CosicError) {
            throw if (withOffset) e.withRange(range) else CosicError(e.message, range)
        }
        // an error found while the pattern is queried points at the template
        return bridge.wrap(attributing(pattern, range))
    }

    override fun locations(code: String, codeStart: Int): List<SourceRange> {
        val codeRange = SourceRange(codeStart, codeStart + code.length)
        try {
            return MondoParser().getLocations(code, if (withOffset) codeStart else 0).map { SourceRange(it[0], it[1]) }
        } catch (e: CosicError) {
            throw if (withOffset) e.withRange(codeRange) else CosicError(e.message, codeRange)
        }
    }
}

/**
 * `mondo(code, offset)` of mondough.mjs: the pattern [code] is, whose atoms carry their positions (plus [offset]) and
 * are marked with an underline while they play (`markcss`, where the realm has it).
 */
internal fun mondoPattern(bridge: Bridge, code: String, offset: Int): Pattern {
    val pattern = MondoStrudel(bridge).run(code, offset)
    val markcss = bridge.realm.patternPrototype.get("markcss")
    if (markcss !is JsFunction) return pattern
    return patternOf(bridge.toKernel(markcss.call(bridge.wrap(pattern), listOf(MONDO_MARK_STYLE))))
}
