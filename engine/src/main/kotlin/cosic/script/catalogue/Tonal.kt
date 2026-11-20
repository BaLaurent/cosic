package cosic.script.catalogue

import cosic.pattern.CosicError
import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.pattern.Voicings
import cosic.pattern.arp
import cosic.pattern.arpWith
import cosic.pattern.callFn
import cosic.pattern.jsKeyOrder
import cosic.pattern.reg1
import cosic.pattern.reg2
import cosic.pattern.rootNotes
import cosic.pattern.scale
import cosic.pattern.scaleTranspose
import cosic.pattern.transpose
import cosic.pattern.voicing
import cosic.pattern.voicings
import cosic.script.Bridge
import cosic.script.js.JsArray
import cosic.script.js.JsObject
import cosic.script.js.Prop
import cosic.script.js.PropFlags
import cosic.script.js.describe
import cosic.script.js.toStr

/*
 * The tonal functions (`@strudel/tonal` and `arp` of pattern.mjs): scales and transposition, chords and voicings, arpeggios. The
 * behaviour is in pattern/Tonal.kt and pattern/Voicings.kt; this file makes them callable from programs. `voicingRegistry`, the
 * object of voicing dictionaries Strudel exports, is a view of the registry the functions use.
 */

internal fun registerTonal() {
    Registry.reg2("scale", keep = true) { scale, pat -> pat.scale(scale) }
    Registry.reg2("transpose", "trans") { amount, pat -> pat.transpose(amount) }
    Registry.reg2("scaleTranspose", "scaleTrans", "strans") { offset, pat -> pat.scaleTranspose(offset) }

    Registry.reg2("arpWith") { func, pat -> pat.arpWith { haps -> callFn(func, haps) } }
    // `arp` does not patternify its argument: the indices are the pattern the arpeggio is played with
    Registry.reg2("arp", patternify = false) { indices, pat -> pat.arp(indices) }

    Registry.reg1("voicing") { pat -> pat.voicing() }
    Registry.reg2("voicings") { dictionary, pat -> pat.voicings(dictionary) }
    Registry.reg2("rootNotes") { octave, pat -> pat.rootNotes(octave) }

    Registry.function(listOf("addVoicings")) { a ->
        Voicings.add(argument(a, 0), argument(a, 1), argument(a, 2))
        Undefined
    }
    Registry.function(listOf("registerVoicings")) { a ->
        Voicings.register(argument(a, 0), argument(a, 1), argument(a, 2))
        Undefined
    }
    Registry.function(listOf("setVoicingRange")) { a ->
        Voicings.setRange(argument(a, 0), argument(a, 1))
        Undefined
    }
    Registry.function(listOf("setDefaultVoicings")) { a -> Voicings.setDefault(argument(a, 0)) }
    Registry.function(listOf("resetVoicings")) {
        Voicings.reset()
        Undefined
    }

    catalogue("Tonal") {
        global("voicingRegistry") { bridge -> VoicingRegistryObject(bridge) }
        // voicingAlias(symbol, alias, sets): in each dictionary, `alias` becomes the voicings of `symbol`
        function(listOf("voicingAlias"), 3) { ctx ->
            val symbol = ctx.realm.toStr(ctx.arg(0))
            val alias = ctx.realm.toStr(ctx.arg(1))
            val sets = ctx.arg(2)
            for (set in if (sets is JsArray) sets.items else listOf(sets)) {
                if (set !is JsObject) throw ctx.realm.typeError("Cannot create property '$alias' on ${ctx.realm.describe(set)}")
                set.set(alias, set.get(symbol))
            }
            Undefined
        }
    }
}

private fun argument(args: List<Any?>, i: Int): Any? = args.getOrElse(i) { Undefined }

/**
 * `voicingRegistry`: the entries of [Voicings]. An entry (and its dictionary) is a view of the registry's own map: a property that is
 * assigned or deleted is changed in the registry, so `voicingRegistry.mine = {dictionary: {...}}` adds a dictionary and
 * `voicingAlias('^7', 'maj7', voicingRegistry.ireal.dictionary)` adds a symbol to one. Arrays (`range`) are copies when read.
 */
private class VoicingRegistryObject(private val bridge: Bridge) : JsObject(bridge.realm.objectPrototype) {
    override fun getOwnProp(key: String): Prop? = Voicings.entry(key)?.let { Prop(LiveMapObject(bridge, it), PropFlags.ALL) }

    override fun defineOwn(key: String, value: Any?, flags: Int) {
        @Suppress("UNCHECKED_CAST")
        val entry = bridge.toKernel(value) as? Map<String, Any?> ?: throw CosicError("voicingRegistry.$key: expected an object with a dictionary")
        Voicings.put(key, entry)
    }

    override fun set(key: String, value: Any?, receiver: Any?): Boolean {
        defineOwn(key, value, PropFlags.ALL)
        return true
    }

    override fun delete(key: String): Boolean {
        Voicings.remove(key)
        return true
    }

    override fun ownKeys(): List<String> = jsKeyOrder(Voicings.names())
}

/** An object that is a view of [map]: reading a key reads the map (a map in it is a view as well), writing and deleting change it. */
private class LiveMapObject(private val bridge: Bridge, private val map: MutableMap<String, Any?>) : JsObject(bridge.realm.objectPrototype) {
    override fun getOwnProp(key: String): Prop? {
        if (!map.containsKey(key)) return null
        @Suppress("UNCHECKED_CAST")
        val value = map[key].let { if (it is MutableMap<*, *>) LiveMapObject(bridge, it as MutableMap<String, Any?>) else bridge.toJs(it) }
        return Prop(value, PropFlags.ALL)
    }

    override fun defineOwn(key: String, value: Any?, flags: Int) {
        @Suppress("UNCHECKED_CAST")
        map[key] = bridge.toKernel(value).let { if (it is Map<*, *>) Voicings.synchronizedCopy(it as Map<String, Any?>) else it }
    }

    override fun set(key: String, value: Any?, receiver: Any?): Boolean {
        defineOwn(key, value, PropFlags.ALL)
        return true
    }

    override fun delete(key: String): Boolean {
        map.remove(key)
        return true
    }

    override fun ownKeys(): List<String> = jsKeyOrder(synchronized(map) { map.keys.toList() })
}
