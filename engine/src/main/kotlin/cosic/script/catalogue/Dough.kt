package cosic.script.catalogue

import cosic.dough.ENGINE_DOUGH
import cosic.dough.ENGINE_KEY
import cosic.pattern.Controls
import cosic.pattern.CosicError
import cosic.pattern.Registry
import cosic.pattern.Undefined
import cosic.pattern.jsMerge
import cosic.pattern.reify
import cosic.pattern.setWith
import cosic.pattern.stack
import cosic.pattern.pure

/*
 * `@strudel/dough` (packages/dough/{dough,lib}.mjs): dough, a second audio engine, as the sound of a pattern.
 *
 *  - `pat.dough()` makes the pattern play with dough: the engine is always there, so unlike Strudel's (`initDough(); ensureMinimalOutput()`) it has nothing to start. It marks the
 *    values of the haps with the control `_cosic_engine: "dough"`, which the scheduler routes by (the hap then does not sound in the main engine, as `onSchedule` is dominant) and which
 *    parity snapshots and the other sinks leave out; a hap whose value is not an object is left alone (it is an error either way, with the hint to append `.note()` or `.s()`).
 *  - `initDough()` does nothing (and returns nothing to await).
 *  - `doughsamples(map | url, baseUrl)` fills the map of sounds dough can name besides its oscillators and noises (see [cosic.dough.DoughSounds]). It is its own map: `samples()`
 *    does not feed dough and `doughsamples()` does not feed the main engine.
 *  - the controls `group`, `voices` and `glide`, and `groupmod`/`gmod`, which turns the group of what it modulates into an `update_group` event.
 */
internal fun registerDough() {
    for (name in listOf("group", "voices", "glide")) {
        val spec = Controls.registerCustom(listOf(name), false, emptyList())
        Registry.dual(
            listOf(name),
            global = { a -> Controls.applyControl(spec, a.getOrElse(0) { Undefined }, a.getOrNull(1)) },
            method = { pat, a -> Controls.applyControl(spec, a.getOrElse(0) { Undefined }, pat) },
        )
    }

    // groupmod(modulator, pat): `register('groupmod', ...)` of lib.mjs patternifies its first argument, which hands the function a hap value where it uses a pattern (`modulator.withValue`),
    // so as written it throws when queried. The modulator is a pattern here, as the function means it.
    Registry.register(listOf("groupmod", "gmod"), 2, patternify = false) { args ->
        val modulator = reify(args[0])
        val pat = reify(args[1])
        val modpat = modulator
            .withValue { vm -> pat.withValue { v -> jsMerge(asControls(vm), mapOf("group" to (v as? Map<*, *>)?.get("group"))) } }
            .outerJoin()
            .setWith(pure(linkedMapOf("dough" to "update_group")))
        stack(pat.setWith(modulator), modpat)
    }

    catalogue("Dough") {
        method(listOf("dough"), 0) { ctx -> ctx.bridge.receiverOf(ctx.self, "dough").fmap(::markDough) }

        function(listOf("initDough"), 0) { Undefined }

        // doughsamples(sampleMap, baseUrl): a map of name -> [files], or the URL of one
        function(listOf("doughsamples"), 2) { ctx ->
            val dough = ctx.effects.outputs.dough ?: ctx.fail("dough is not available")
            when (val map = ctx.kernelArg(0)) {
                is String -> try {
                    dough.sounds.registerUrl(map)
                } catch (e: CosicError) {
                    ctx.fail(e.message)
                }
                is Map<*, *> -> dough.sounds.register(map, ctx.kernelArg(1))
                null, Undefined -> ctx.fail("Cannot convert undefined or null to object")
                else -> Unit // Object.entries of a number or a boolean is empty
            }
            Undefined
        }
    }
}

private val DOUGH_MARK = mapOf(ENGINE_KEY to ENGINE_DOUGH)

@Suppress("UNCHECKED_CAST")
private fun asControls(v: Any?): Map<String, Any?> = (v as? Map<String, Any?>) ?: emptyMap()

/** The value of a hap of a pattern that plays with dough. */
private fun markDough(v: Any?): Any? = if (v is Map<*, *>) jsMerge(asControls(v), DOUGH_MARK) else v
