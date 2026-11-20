package cosic.script.catalogue

import cosic.BankGroup
import cosic.SampleMapException
import cosic.SampleMapOptions
import cosic.SoundLibrary
import cosic.WaveTableBank
import cosic.pattern.Pattern
import cosic.pattern.Undefined
import cosic.pattern.jsMathRound
import cosic.pattern.jsMerge
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.logger
import cosic.pattern.noteToMidi
import cosic.pattern.callMethod
import cosic.pattern.valueToMidi
import cosic.script.PatternValue
import cosic.sound.RegistryLibrary
import cosic.sound.SampleMaps
import java.io.IOException

/*
 * The functions of a program that reach the sound library (packages/superdough/sampler.mjs, superdough.mjs, wavetable.mjs,
 * fontloader.mjs and website/src/repl/prebake.mjs): `samples`, `soundAlias`, `aliasBank`, `getDuration`/`getDur`, `tables`,
 * `setSoundfontUrl` and `Pattern.prototype.piano`. The library is the one of the host (`ScriptHost.library`), the one the
 * engine plays from, so what a program registers is what its patterns find.
 *
 * Strudel's versions are async: they return a promise that the program awaits (`await samples(...)`, `await getDuration(...)`).
 * Here they run to the end before they return, and `await` returns its operand, so a program that does not await still has
 * its sounds registered at the next line (in Strudel it races). A failure is an error of the program with Strudel's message
 * (`error loading "<url>"`, `wrong sample map format for <key>`).
 */
internal fun registerLibrary() = catalogue("Library") {
    // samples(sampleMap, baseUrl = sampleMap._base || '', options = {})
    function(listOf("samples"), 1) { ctx ->
        val library = ctx.host.library
        val map = ctx.kernelArg(0)
        val baseUrl = ctx.kernelArg(1) as? String
        val options = sampleMapOptions(ctx.kernelArg(2))
        libraryCall(ctx) {
            when (map) {
                is String -> library.loadSampleMap(map, baseUrl, options)
                is Map<*, *> -> library.loadSampleMap(stringKeyed(map), baseUrl, options)
                is List<*> -> library.loadSampleMap(indexed(map), baseUrl, options) // Object.entries(array)
                null, Undefined -> ctx.fail("Cannot read properties of ${if (map == null) "null" else "undefined"} (reading '_base')")
                else -> Unit // Object.entries of a number or a boolean is empty
            }
        }
        Undefined
    }

    // soundAlias(original, alias)
    function(listOf("soundAlias"), 2) { ctx ->
        ctx.host.library.soundAlias(jsToString(ctx.kernelArg(0)), jsToString(ctx.kernelArg(1)))
        Undefined
    }

    // aliasBank(url) | aliasBank({bank: alias | [aliases]}) | aliasBank(bank, alias | [aliases])
    function(listOf("aliasBank"), 0) { ctx ->
        val library = ctx.host.library
        val args = ctx.args.map { ctx.bridge.toKernel(it) }
        libraryCall(ctx) {
            when (args.size) {
                1 -> when (val one = args[0]) {
                    is String -> library.aliasBank(one)
                    is Map<*, *> -> library.aliasBank(stringKeyed(one))
                    else -> ctx.fail("aliasBank expects a url or an object of banks")
                }
                2 -> library.aliasBank(jsToString(args[0]), args[1] ?: Undefined)
                else -> ctx.fail("aliasMap expects 1 or 2 arguments, received ${args.size}")
            }
        }
        Undefined
    }

    // getDuration(sampleName, n = 0): the length in seconds of a sample of a bank
    function(listOf("getDuration", "getDur"), 1) { ctx ->
        val name = ctx.kernelArg(0) as? String ?: ctx.fail("Cannot read properties of undefined (reading '${jsToString(ctx.kernelArg(0))}')")
        val n = ctx.kernelArg(1).let { if (it === Undefined) 0.0 else jsToNumber(it) }
        libraryCall(ctx) { ctx.host.library.duration(name, n) }
    }

    // tables(url, frameLen, json): wavetables, a sample map of .wav files each of which is cut into frames
    function(listOf("tables"), 1) { ctx ->
        val library = ctx.host.library
        val url = ctx.kernelArg(0)
        val frameLen = ctx.kernelArg(1)
        val json = ctx.kernelArg(2)
        libraryCall(ctx) {
            val frames = (frameLen as? Double)?.toInt() ?: DEFAULT_TABLE_FRAME
            if (json !== Undefined) {
                val map = json as? Map<*, *> ?: ctx.fail("Cannot convert undefined or null to object")
                processTables(library, stringKeyed(map), jsToString(url), frames)
            } else {
                var location = url as? String ?: ctx.fail("Cannot read properties of ${if (url == null) "null" else "undefined"} (reading 'startsWith')")
                if (location.startsWith("github:")) location = tablesGithubPath(location, "strudel.json")
                if (location.startsWith("local:")) location = "http://localhost:5432"
                val store = (library as? RegistryLibrary)?.sampleStore ?: ctx.fail("error loading \"$location\"")
                val parsed = try {
                    cosic.sound.LooseJson.parse(store.fetchText(location))
                } catch (_: IOException) {
                    null
                } catch (_: IllegalArgumentException) {
                    null
                }
                val map = parsed as? Map<*, *> ?: ctx.fail("error loading \"$location\"")
                processTables(library, stringKeyed(map), SampleMaps.baseUrlOf(location), frames)
            }
        }
        Undefined
    }

    // setSoundfontUrl(url): where the files of the soundfonts come from
    function(listOf("setSoundfontUrl"), 1) { ctx ->
        ctx.host.library.setSoundfontUrl(jsToString(ctx.kernelArg(0)))
        Undefined
    }

    // pat.piano(): website/src/repl/prebake.mjs:164-174, the variant the app's prebake installs (clip defaults to 1, the oracle's piano.mjs sets it)
    method(listOf("piano"), 0) { ctx ->
        piano((ctx.self as? PatternValue)?.pattern ?: ctx.fail("piano() is a method of a pattern"))
    }
}

private const val DEFAULT_TABLE_FRAME = 2048

/** `maxPan` of prebake.mjs: the pitch that pans all the way. */
private val MAX_PAN = noteToMidi("C8")

private fun piano(pat: Pattern): Pattern =
    pat.fmap { v ->
        // ({ ...v, clip: v.clip ?? 1 })
        val map = v as? Map<*, *>
        val clip = map?.get("clip")
        jsMerge(stringKeyed(map ?: emptyMap<String, Any?>()), mapOf("clip" to if (clip == null || clip === Undefined) 1.0 else clip))
    }
        .callMethod("s", "piano")
        .callMethod("release", 0.1)
        .fmap { value ->
            val midi = valueToMidi(value)
            val width = 0.5
            val pan = minOf(jsMathRound(midi) / MAX_PAN, 1.0) * width + (1 - width) / 2
            val old = (value as? Map<*, *>)?.get("pan")
            jsMerge(stringKeyed(value as Map<*, *>), mapOf("pan" to (if (jsTruthy(old)) jsToNumber(old) else 1.0) * pan))
        }

/** The options of `samples`: `{prebake, tag}`. */
private fun sampleMapOptions(v: Any?): SampleMapOptions {
    val map = v as? Map<*, *> ?: return SampleMapOptions()
    return SampleMapOptions(jsTruthy(map["prebake"]), map["tag"] as? String)
}

@Suppress("UNCHECKED_CAST")
private fun stringKeyed(map: Map<*, *>): Map<String, Any?> = map as Map<String, Any?>

/** `Object.entries(array)`: the items keyed by their index. */
private fun indexed(list: List<*>): Map<String, Any?> {
    val out = LinkedHashMap<String, Any?>(list.size * 2)
    for ((i, item) in list.withIndex()) out[i.toString()] = item
    return out
}

/** Runs [block]; what the library throws is an error of the program, with the message Strudel's own error has. */
private inline fun <T> libraryCall(ctx: CallContext, block: () -> T): T = try {
    block()
} catch (e: SampleMapException) {
    ctx.fail(e.message ?: "error loading sample map")
} catch (e: IllegalArgumentException) {
    ctx.fail(e.message ?: e.toString())
} catch (e: IOException) {
    ctx.fail(e.message ?: e.toString())
}

/**
 * `githubPath` of wavetable.mjs:130-140, which is not the one of sampler.mjs: a path of two parts gets `/main`, any other one is kept as
 * it is, and [subpath] follows after a `/`.
 */
private fun tablesGithubPath(base: String, subpath: String): String {
    var path = base.substring("github:".length)
    if (path.endsWith("/")) path = path.dropLast(1)
    if (path.split("/").size == 2) path += "/main"
    return "https://raw.githubusercontent.com/$path/$subpath"
}

/** `_processTables` (wavetable.mjs:142-171): every entry of [json] that lists `.wav` files becomes a wavetable bank. */
private fun processTables(library: SoundLibrary, json: Map<String, Any?>, url: String, frameLen: Int) {
    val own = json["_base"]
    val baseUrl = if (jsTruthy(own)) jsToString(own) else url
    for ((key, value) in json) {
        if (key == "_base") continue
        val tables: Any? = if (value is String) listOf(value) else value
        if (tables !is List<*>) {
            if (tables is Map<*, *>) throw IllegalArgumentException("tables.map is not a function")
            throw IllegalArgumentException("wrong json format for $key")
        }
        val resolved = if (baseUrl.startsWith("github:")) tablesGithubPath(baseUrl, "") else baseUrl
        val urls = ArrayList<String>(tables.size)
        for (t in tables) {
            val full = resolved + jsToString(t)
            if (full.lowercase().endsWith(".wav")) urls.add(full)
            else logger("[wavetable] skipping $full -- wavetables must be \".wav\" format", "log", null)
        }
        if (urls.isNotEmpty()) library.register(WaveTableBank(key, listOf(BankGroup(null, null, urls)), frameLen, null, false))
    }
}
