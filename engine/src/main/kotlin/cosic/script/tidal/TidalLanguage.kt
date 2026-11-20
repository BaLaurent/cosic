package cosic.script.tidal

import cosic.SourceRange
import cosic.mini.getLeafLocations
import cosic.mini.m
import cosic.pattern.CosicError
import cosic.pattern.JsFn
import cosic.pattern.Undefined
import cosic.pattern.jsStringToNumber
import cosic.script.Bridge
import cosic.script.EmbeddedLanguage
import cosic.script.PatternValue
import cosic.script.Realm
import cosic.script.attributing
import cosic.script.bridge
import cosic.script.js.ErrorKind
import cosic.script.js.JsFunction
import cosic.script.js.JsObject
import cosic.script.js.JsThrow

/*
 * `tidal`: patterns written in Tidal's Haskell, as packages/tidal/tidal.mjs makes them possible. The code is parsed into
 * the tree of tree-sitter-haskell ([parseHs]) and evaluated like hs2js evaluates it ([HsEval]); a name is a global of the
 * program (so `d1 $ every 2 (fast 2) $ s "bd sn"` calls the functions `d1`, `every`, `fast` and `s` of the catalogue, one
 * argument after the other as they are curried), a string is a mini-notation pattern whose atoms keep their place in the
 * program, and the operators are those of [TidalOps].
 *
 * ``tidal`...` `` is what the transpiler turns into `tidal(code, offset)`; here the tag is an [EmbeddedLanguage] and
 * `tidal(code, offset)` of a program is the same call. In Strudel one has to `await initTidal()` first, which loads the
 * parser and defines `d1` to `d9`; here the parser is always there and a program that did not call `initTidal` has the same
 * `d1` to `d9`.
 */
internal object TidalLanguage : EmbeddedLanguage {
    override fun evaluate(code: String, codeStart: Int, realm: Realm, range: SourceRange): Any? =
        evaluateTidal(realm, code, codeStart, range, nodeRanges = true)

    override fun locations(code: String, codeStart: Int): List<SourceRange> = collectHaskellMiniLocations(code, codeStart)
}

/**
 * `tidal(code, offset)`: [offset] is the position of the code in the program, which the atoms of the strings are placed
 * from. A failure is reported at the node of the code that failed ([nodeRanges]) or, when the code is not a part of the
 * program that is being run, at [range].
 */
internal fun evaluateTidal(realm: Realm, code: String, offset: Int, range: SourceRange?, nodeRanges: Boolean): Any? {
    val tree = parseHs(preprocessHs(code))
    val host = TidalHost(realm, offset, range, nodeRanges)
    try {
        return HsEval(host).evaluate(tree)
    } catch (e: CosicError) {
        throw e.withRange(range)
    } catch (e: JsThrow) {
        throw e.withRange(range)
    }
}

/**
 * The places of the atoms of every string of Haskell [code] (the `collectHaskellMiniLocations` of plugin-mini.mjs): the
 * quotes are paired in the order they come, whatever else is around them.
 */
internal fun collectHaskellMiniLocations(code: String, offset: Int): List<SourceRange> {
    val pairs = ArrayList<MutableList<Int>>()
    for (i in code.indices) {
        if (code[i] != '"') continue
        if (pairs.isEmpty() || pairs.last().size > 1) pairs.add(mutableListOf(i + 1)) else pairs.last().add(i)
    }
    val locations = ArrayList<SourceRange>()
    for (pair in pairs) {
        val start = pair[0]
        val end = pair.getOrNull(1) ?: code.length
        val mini = code.substring(start.coerceAtMost(code.length), end.coerceIn(start.coerceAtMost(code.length), code.length))
        for (location in getLeafLocations("\"$mini\"", offset + start - 1)) locations.add(SourceRange(location[0], location[1]))
    }
    return locations
}

/** What a constructor of the Haskell code is: the syntax node itself, a value no function can use (hs2js reaches `Object.prototype.constructor` for the type). */
internal class HsNodeValue(realm: Realm, val node: HsNode) : JsObject(realm.objectPrototype) {
    override val className: String get() = "SyntaxNode"
}

private val D_NAME = Regex("d[1-9]")

/** `d1` to `d9` of `initTidal`: `(pat) => pat.p('dN')`. */
internal fun definePatternLabels(realm: Realm) {
    for (i in 1..9) realm.global.set("d$i", labelFunction(realm, "d$i"), realm.global)
}

private fun labelFunction(realm: Realm, name: String): JsFunction = realm.function(name, 1) { _, args ->
    val pattern = args.getOrElse(0) { Undefined }
    val p = when (pattern) {
        is JsObject -> pattern.get("p", pattern)
        is String -> realm.stringPrototype.get("p", pattern)
        is Double -> realm.numberPrototype.get("p", pattern)
        is Boolean -> realm.booleanPrototype.get("p", pattern)
        null -> throw realm.typeError("Cannot read properties of null (reading 'p')")
        Undefined -> throw realm.typeError("Cannot read properties of undefined (reading 'p')")
        else -> Undefined
    }
    if (p !is JsFunction && p !is JsFn) throw realm.typeError("pat.p is not a function")
    realm.callFunction(p, pattern, listOf(name))
}

private class TidalHost(
    private val realm: Realm,
    private val offset: Int,
    private val outer: SourceRange?,
    private val nodeRanges: Boolean,
) : HsHost {
    private val bridge: Bridge = realm.bridge
    private val labels = HashMap<String, JsFunction>()

    private fun rangeOf(node: HsNode?): SourceRange? =
        if (node != null && nodeRanges) SourceRange(offset + node.start, offset + node.end) else outer

    /** Gives an error that [block] throws without a place the place of [at]. */
    private inline fun <T> placed(at: HsNode?, block: () -> T): T {
        try {
            return block()
        } catch (e: CosicError) {
            throw e.withRange(rangeOf(at))
        } catch (e: JsThrow) {
            throw e.withRange(rangeOf(at))
        }
    }

    /** A pattern a call made is attributed to the place of the call, so that an error while it is queried points at the code that made it. */
    private fun attributed(value: Any?, at: HsNode?): Any? {
        val range = rangeOf(at)
        return if (value is PatternValue && range != null) bridge.wrap(attributing(value.pattern, range)) else value
    }

    override fun lookup(name: String): Any? {
        val global = realm.global
        if (global.has(name)) return global.get(name, global)
        if (D_NAME.matches(name)) return labels.getOrPut(name) { labelFunction(realm, name) }
        return Undefined
    }

    override fun assign(name: String, value: Any?) {
        realm.global.set(name, value, realm.global)
    }

    override fun number(text: String): Any? = jsStringToNumber(text)

    override fun string(node: HsNode): Any? = placed(node) {
        val text = node.text
        bridge.wrap(m(text.substring(1, maxOf(1, text.length - 1)), offset + node.start))
    }

    override fun function(body: (Any?) -> Any?): Any? = realm.function("", 1) { _, args -> body(args.getOrElse(0) { Undefined }) }

    override fun isFunction(value: Any?): Boolean = value is JsFunction || value is JsFn

    override fun call(fn: Any?, arg: Any?, at: HsNode?): Any? = placed(at) {
        attributed(realm.callFunction(fn, Undefined, listOf(arg)), at)
    }

    override fun list(items: List<Any?>): Any? = realm.newArray(items)

    override fun operator(symbol: String): HsOperator? {
        val op = TidalOps.table[symbol] ?: return null
        return HsOperator { left, right, at -> placed(at) { attributed(TidalOps.apply(bridge, op, left, right), at) } }
    }

    override fun nodeValue(node: HsNode): Any? = HsNodeValue(realm, node)

    override fun error(message: String, at: HsNode?): RuntimeException = realm.error(ErrorKind.ERROR, message).withRange(rangeOf(at))

    override fun typeError(message: String, at: HsNode?): RuntimeException = realm.error(ErrorKind.TYPE, message).withRange(rangeOf(at))
}
