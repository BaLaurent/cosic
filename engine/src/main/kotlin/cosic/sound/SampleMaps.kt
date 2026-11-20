package cosic.sound

import cosic.BankGroup
import cosic.SampleBank
import cosic.SampleMapException
import cosic.SampleMapOptions
import cosic.SoundEntry
import cosic.WaveTableBank
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.noteToMidi

/** The frame length wavetables are cut into when the registration says nothing (`params?.frameLen ?? 2048`, wavetable.mjs:177). */
internal const val DEFAULT_FRAME_LENGTH = 2048

/**
 * Sample maps as Strudel reads them (`sampler.mjs:152-258`): the pseudo URLs `samples()` accepts, and the conversion of a map in
 * the form of a `strudel.json` into the sound entries of its banks.
 */
internal object SampleMaps {
    private const val GITHUB = "github:"

    /** `resolveSpecialPaths`: `bubo:name` is the repository `Bubobubobubobubo/dough-<name>`; only the second `:`-part is used. */
    fun resolveSpecialPaths(base: String): String {
        if (base.startsWith("bubo:")) {
            val repo = base.split(":").getOrNull(1) ?: "undefined"
            return "github:Bubobubobubobubo/dough-$repo"
        }
        return base
    }

    /**
     * `githubPath`: `github:user/repo/branch/sub/dir` as the raw file URL of [subpath] in it. `repo` defaults to `samples` and
     * `branch` to `main`; a trailing `/` is dropped first. With the empty [subpath] the result ends in `/`.
     */
    fun githubPath(base: String, subpath: String = ""): String {
        require(base.startsWith(GITHUB)) { "expected \"github:\" at the start of pseudoUrl" }
        var path = base.substring(GITHUB.length)
        if (path.endsWith("/")) path = path.dropLast(1)
        val components = path.split("/")
        val user = components[0]
        val repo = if (components.size >= 2) components[1] else "samples"
        val branch = if (components.size >= 3) components[2] else "main"
        val other = components.drop(3) + subpath
        return "https://raw.githubusercontent.com/$user/$repo/$branch/${other.joinToString("/")}"
    }

    /**
     * The URL a `samples(...)` string stands for (`fetchSampleMap`, sampler.mjs:214-247), checked in this order: `bubo:`, `github:`
     * (the file `strudel.json`), `local:` (the sample server of the dev machine, `http://localhost:5432`), `shabda:<path>` and
     * `shabda/speech[/language/gender]:<words>` (`gender` and `language` default to `f` and `en-GB`; a gender that is missing from a
     * given pair reads `undefined`, and the URL ends with the stray `'` Strudel sends); anything else is the URL itself.
     */
    fun mapUrl(spec: String): String {
        var url = resolveSpecialPaths(spec)
        if (url.startsWith(GITHUB)) url = githubPath(url, "strudel.json")
        if (url.startsWith("local:")) url = "http://localhost:5432"
        if (url.startsWith("shabda:")) {
            val path = url.split("shabda:").getOrElse(1) { "undefined" }
            url = "https://shabda.ndre.gr/$path.json?strudel=1"
        }
        if (url.startsWith("shabda/speech")) {
            var path = url.split("shabda/speech").getOrElse(1) { "undefined" }
            if (path.startsWith("/")) path = path.substring(1)
            val parts = path.split(":")
            val params = parts[0]
            val words = parts.getOrElse(1) { "undefined" }
            var gender = "f"
            var language = "en-GB"
            if (params.isNotEmpty()) {
                val pair = params.split("/")
                language = pair[0]
                gender = pair.getOrElse(1) { "undefined" }
            }
            url = "https://shabda.ndre.gr/speech/$words.json?gender=$gender&language=$language&strudel=1'"
        }
        return url
    }

    private val ORIGIN = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*://[^/?#]*)([^?#]*)")

    /**
     * `getBaseURL` (util.mjs:120-128): for a URL the directory of its path, with dot segments resolved and without the one trailing slash
     * (`https://a/b/c.json` is `https://a/b`, `http://localhost:5432` stays as it is, `https://x//a//b.json` is `https://x//a/`); for anything
     * else (a pseudo URL) all but the last `/`-part. The path is not percent-encoded here: the store does that when it fetches.
     */
    fun baseUrlOf(url: String): String {
        val m = ORIGIN.find(url) ?: return url.split("/").dropLast(1).joinToString("/")
        val origin = m.groupValues[1]
        val segments = m.groupValues[2].split("/").drop(1).ifEmpty { listOf("") } // the path starts with '/': the first element is empty
        val path = ArrayList<String>(segments.size)
        for ((i, segment) in segments.withIndex()) {
            val last = i == segments.lastIndex
            when (segment) {
                "." -> if (last) path.add("")
                ".." -> {
                    if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                    if (last) path.add("")
                }
                else -> path.add(segment)
            }
        }
        val directories = path.dropLast(1)
        val directory = if (directories.isEmpty()) "/" else "/" + directories.joinToString("/") + "/" // what `new URL('.', url)` resolves to
        return origin + directory.removeSuffix("/")
    }

    /**
     * `processSampleMap` (sampler.mjs:170-197): the entries of the banks of [map], in the order of the map.
     *
     * A value is a file (`bd: 'a.wav'`), a list of files, or a pitched map from note names to a file or a list. Every file gets the
     * base in front of it, as text. A bank may carry its own `_base`, which then also holds for the banks after it (Strudel
     * reassigns the one variable); `bubo:` and `github:` bases are turned into URLs. Strudel registers the top-level `_base` string as a
     * sound too; this does not, and it leaves out the `_`-keys of pitched maps (their lookup skips them). A key that starts with `wt_`
     * (as written) is a wavetable bank, cut into [DEFAULT_FRAME_LENGTH] frames; anything else a [SampleBank]. A value that is neither
     * a string, a list nor a map throws [SampleMapException] (`wrong sample map format for <key>`).
     */
    fun process(map: Map<String, Any?>, baseUrl: String, options: SampleMapOptions): List<SoundEntry> {
        val out = ArrayList<SoundEntry>(map.size)
        processInto(map, baseUrl, options, out)
        return out
    }

    /** [process] that adds each bank to [out] as soon as it is made, so that [out] holds the banks before one that throws (Strudel registers them too). */
    fun processInto(map: Map<String, Any?>, baseUrl: String, options: SampleMapOptions, out: MutableList<SoundEntry>) {
        var base = baseUrl
        for ((key, raw) in map) {
            if (key == "_base" && raw is String) continue
            val value: Any? = if (raw is String) listOf(raw) else raw
            val groups: List<BankGroup>
            when (value) {
                is List<*> -> {
                    base = nextBase(base, null)
                    groups = listOf(BankGroup(null, null, value.map { base + jsToString(it) }))
                }
                is Map<*, *> -> {
                    base = nextBase(base, value["_base"])
                    val notes = ArrayList<BankGroup>(value.size)
                    for ((note, files) in value) {
                        val noteName = note as String
                        if (noteName.startsWith("_")) continue
                        val list: List<*> = when (files) {
                            is String -> listOf(files)
                            is List<*> -> files
                            else -> throw SampleMapException("wrong sample map format for $key")
                        }
                        notes.add(BankGroup(noteName, midiOf(noteName), list.map { base + jsToString(it) }))
                    }
                    groups = notes
                }
                else -> throw SampleMapException("wrong sample map format for $key")
            }
            out.add(
                if (key.startsWith("wt_")) WaveTableBank(key, groups, DEFAULT_FRAME_LENGTH, options.tag, options.prebake)
                else SampleBank(key, groups, options.tag, options.prebake),
            )
        }
    }

    /** `baseUrl = value._base || baseUrl`, then the pseudo URL forms are resolved (sampler.mjs:178-182). */
    private fun nextBase(current: String, own: Any?): String {
        var base = if (own is String && jsTruthy(own)) own else current
        base = resolveSpecialPaths(base)
        if (base.startsWith(GITHUB)) base = githubPath(base, "")
        return base
    }

    /** The MIDI number of a pitched map's key, NaN when it is not a note name: the bank then fails when it is played, as in Strudel. */
    private fun midiOf(noteName: String): Double = try {
        noteToMidi(noteName)
    } catch (_: cosic.pattern.CosicError) {
        Double.NaN
    }
}
