package cosic.dough

import cosic.SampleData
import cosic.SampleStore
import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.logger
import cosic.pattern.noteToMidi
import cosic.sound.LooseJson
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/*
 * What Strudel's `@strudel/dough` does around the engine (packages/dough/{dough,lib}.mjs and dough.js of dough-synth@0.4.1): a hap becomes an event (`hap2dough`), the event is
 * written as text (`Dough.encodeEvent`) and read by the engine's parser, a sound name that is not an oscillator is looked up in the map `doughsamples()` filled (`maybeLoadFile`)
 * and the sample it names is loaded, converted to the engine's rate and registered as a file.
 */

/** The control `.dough()` puts on the values of its haps: the scheduler plays such a hap with the dough engine and not with the main one. */
internal const val ENGINE_KEY = "_cosic_engine"
internal const val ENGINE_DOUGH = "dough"

/** True for the controls of a hap that `.dough()` marked. */
internal fun isDoughEvent(controls: Map<String, Any?>): Boolean = controls[ENGINE_KEY] == ENGINE_DOUGH

/** The sounds the engine makes itself (`sources` of dough.js): they need no file. */
private val SOURCES = setOf("triangle", "tri", "sine", "sawtooth", "saw", "zawtooth", "zaw", "pulse", "square", "pulze", "zquare", "white", "pink", "brown")

/** Strudel's `Dough.encodeEvent`: `key/value` pairs joined with `/`, every value as JavaScript's `String(value)`. */
internal fun encodeEvent(event: Map<String, Any?>): String {
    val out = StringBuilder()
    var first = true
    for ((key, value) in event) {
        if (!first) out.append('/')
        first = false
        out.append(key).append('/').append(jsToString(value))
    }
    return out.toString()
}

/**
 * `hap2dough` (lib.mjs:16-28) and the event of `Dough.prepare` for a hap with the [controls] and a length of [durationSeconds] seconds (`hap.duration / cps`): the text of
 * `{dough: 'play', ...controls, duration}` with a note name turned into a MIDI number. `time` is not written: the caller starts the event at its frame. Controls of Cosic's own
 * (`_cosic_...`) are left out. Throws `not a note: "x"` for a note name that is none.
 */
internal fun hapToDoughEvent(controls: Map<String, Any?>, durationSeconds: Double): LinkedHashMap<String, Any?> {
    val event = LinkedHashMap<String, Any?>(controls.size + 3)
    event["dough"] = "play"
    for ((key, value) in controls) if (!key.startsWith("_cosic_")) event[key] = value
    event["duration"] = durationSeconds
    val note = event["note"]
    if (note is String) event["note"] = noteToMidi(note)
    return event
}

/** The event of a hap's text, as the engine reads it. */
internal fun parseDoughEvent(text: String): DoughEvent {
    val e = DoughEvent()
    e.reset()
    parseEvent(e, text)
    return e
}

/** An event of the dough engine the scheduler queued: it starts at engine frame [startFrame]. */
internal class DoughStart(val startFrame: Long, val event: DoughEvent)

/** A file the engine plays from a sample of the library: the sample converted to the engine's rate, with at most two channels (dough plays the first two). */
internal fun toDoughFile(data: SampleData, sampleRate: Int): DoughFile {
    val channels = minOf(data.channels, DOUGH_CHANNELS)
    val srcFrames = data.frames
    if (srcFrames == 0) return DoughFile(FloatArray(0), 0, channels, 65.46f)
    val pcm: FloatArray
    val frames: Int
    if (data.sampleRate == sampleRate && data.channels == channels) {
        pcm = data.samples
        frames = srcFrames
    } else {
        // linear interpolation between neighbouring frames
        val ratio = data.sampleRate.toDouble() / sampleRate.toDouble()
        frames = if (data.sampleRate == sampleRate) srcFrames else maxOf(1, Math.round(srcFrames / ratio).toInt())
        pcm = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val pos = i * ratio
            val i0 = pos.toInt().coerceAtMost(srcFrames - 1)
            val i1 = (i0 + 1).coerceAtMost(srcFrames - 1)
            val t = (pos - i0).toFloat()
            for (c in 0 until channels) {
                val a = data.samples[i0 * data.channels + c]
                val b = data.samples[i1 * data.channels + c]
                pcm[i * channels + c] = a + (b - a) * t
            }
        }
    }
    // Strudel registers every file with the frequency 65.46 (Dough.registerFile)
    return DoughFile(pcm, frames, channels, 65.46f)
}

/**
 * The `soundMap` of dough.js and the loading of what it names. `doughsamples(map, baseUrl)` fills it (a map of its own, not the library's: dough has no part in `samples()`); a
 * hap with a `s` that is in it asks for the file `s:n`, which is fetched on a thread of its own, decoded by the [store], converted to the engine's rate and registered with the
 * [engine]. Until then the hap is skipped, as in Strudel where it arrives too late to be played; a file that cannot be loaded is not tried again.
 */
internal class DoughSounds(private val sampleRate: Int, private val store: SampleStore, private val engine: DoughEngine) {
    private sealed interface FileState {
        data object Loading : FileState

        data object Failed : FileState

        class Ready(val index: Int, val durationSeconds: Double) : FileState
    }

    private val soundMap = ConcurrentHashMap<String, List<String>>()
    private val states = ConcurrentHashMap<String, FileState>()
    private val nextIndex = AtomicInteger(0)

    private val executor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        Executors.newFixedThreadPool(LOADER_THREADS) { r -> Thread(r, "cosic-dough-loader").apply { isDaemon = true } }
    }

    /** The URLs `doughsamples` gave [name], or null. */
    fun urls(name: String): List<String>? = soundMap[name]

    /** `doughsamples(map, baseUrl)` for a map (`Object.entries(sampleMap)`): each entry that is a list of files, with [baseUrl] in front of every file. */
    fun register(map: Map<*, *>, baseUrl: Any?) {
        val prefix = jsToString(baseUrl)
        for ((key, urls) in map) {
            if (key == "_base") continue
            val name = jsToString(key)
            if (urls !is List<*>) {
                // `urls.map` throws in Strudel, inside a promise nobody awaits: the entry is not registered
                logger("doughsamples: '$name' is not a list of files, ignored", "warning", null)
                continue
            }
            soundMap[name] = urls.map { prefix + jsToString(it) }
        }
        retryFailures()
    }

    /** `doughsamples(url)`: fetches the map at [spec] (`fetchSampleMap` of dough.js) and registers it with its `_base` or the directory of its URL. Blocking. */
    fun registerUrl(spec: String) {
        val url = mapUrl(spec)
        val json = try {
            LooseJson.parse(store.fetchText(url))
        } catch (_: IOException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
        if (json !is Map<*, *>) throw CosicError("error loading \"$url\"")
        val own = (json["_base"] as? String)?.takeIf { it.isNotEmpty() } ?: url.split("/").dropLast(1).joinToString("/")
        register(json, own)
    }

    /** A file that failed to load is tried again after the map changed (the user may have fixed the URL). */
    private fun retryFailures() {
        states.entries.removeIf { it.value === FileState.Failed }
    }

    /**
     * What `maybeLoadFile` does to [event] (the text of a hap, still as a map): a sound that is not one of the engine's is looked up in the map and its file named with `file`,
     * and with `unit` `c` the speed is scaled to the length of the file. Returns false when the event is to be skipped (the file is still loading); warns through [warn] for a name
     * that is not in the map (the event then plays with the engine's sound of that name, or a triangle) and for a file that cannot be loaded.
     */
    fun resolve(event: MutableMap<String, Any?>, warn: (String) -> Unit): Boolean {
        val s = event["s"].let { if (jsTruthy(it)) it else event["sound"] }
        if (!jsTruthy(s) || (s is String && s in SOURCES)) return true
        val nValue = event["n"].let { if (jsTruthy(it)) it else 0.0 }
        val key = "${jsToString(s)}:${jsToString(nValue)}"
        val urls = (s as? String)?.let { soundMap[it] }
        if (urls == null) {
            warn("sound ${jsToString(s)} not found in soundMap")
            return true
        }
        val state = states[key] ?: run {
            val fresh = FileState.Loading
            val before = states.putIfAbsent(key, fresh)
            if (before == null) start(key, urls, nValue)
            before ?: fresh
        }
        return when (state) {
            FileState.Loading -> false
            FileState.Failed -> {
                warn("error loading sound $key")
                true
            }
            is FileState.Ready -> {
                event["file"] = state.index.toDouble()
                if (event["unit"] == "c") {
                    val speed = event["speed"].let { if (it == null || it === Undefined) 1.0 else jsToNumber(it) }
                    event["speed"] = speed * state.durationSeconds
                }
                true
            }
        }
    }

    /** `loadSound(s, n)`: the file is `urls[n % urls.length]`, which is nothing when that is not a whole number below the length. */
    private fun start(key: String, urls: List<String>, nValue: Any?) {
        val remainder = jsToNumber(nValue) % urls.size.toDouble()
        val url = if (remainder == Math.rint(remainder) && remainder >= 0.0 && remainder < urls.size) urls[remainder.toInt()] else null
        executor.execute {
            states[key] = try {
                val data = store.awaitSample(url ?: throw IOException("no file $key"))
                val file = toDoughFile(data, sampleRate)
                val index = nextIndex.getAndIncrement()
                engine.registerFile(index, file) // a full table refuses the file; its number is used all the same (Dough.registerFile)
                FileState.Ready(index, file.frames.toDouble() / sampleRate)
            } catch (_: IOException) {
                FileState.Failed
            } catch (_: RuntimeException) {
                FileState.Failed
            }
        }
    }

    private companion object {
        const val LOADER_THREADS = 2
    }
}

/**
 * The URL a `doughsamples(spec)` string stands for (`fetchSampleMap` of dough.js): `github:user/repo[/branch]` is the `strudel.json` of the repository (the branch is `main` when it
 * is not given), `local:` the sample server of the dev machine, `shabda:` and `shabda/speech` those services; anything else is the URL.
 */
internal fun mapUrl(spec: String): String {
    var url = spec
    if (url.startsWith("github:")) url = githubPath(url, "strudel.json")
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

/** `githubPath` of dough.js: `github:user/repo` is on the branch `main`; the path of the file follows. */
internal fun githubPath(base: String, subpath: String = ""): String {
    require(base.startsWith("github:")) { "expected \"github:\" at the start of pseudoUrl" }
    var path = base.substring("github:".length)
    if (path.endsWith("/")) path = path.dropLast(1)
    if (path.split("/").size == 2) path += "/main"
    return "https://raw.githubusercontent.com/$path/$subpath"
}

/**
 * Dough as a second audio engine next to the main one: the [engine], the [sounds] its events can name and what makes events of haps. The scheduler hands it the haps of a pattern
 * that `.dough()` marked ([event]), starts what it made at the frame the hap belongs to ([start]) and the engine's block loop renders it ([render]), adding to what the main mixer
 * wrote. Audio thread, except [sounds] (the script thread, for `doughsamples`).
 */
internal class DoughOutput(val sampleRate: Int, store: SampleStore) {
    val engine = DoughEngine(sampleRate)
    val sounds = DoughSounds(sampleRate, store, engine)

    /** False until something was started, and again once the engine has been quiet for [QUIET_SECONDS]: the delay and the reverb are not run for nothing. */
    private var awake = false
    private var quietFrames = 0

    /** True while anything of this engine can still be heard. */
    val isSounding: Boolean get() = awake

    /**
     * The event of the hap with [controls] and a length of [durationSeconds], or null when it is not to be played now (its file is still loading). [warn] is told what Strudel
     * writes to the console for the hap. Throws `not a note: "x"`.
     */
    fun event(controls: Map<String, Any?>, durationSeconds: Double, warn: (String) -> Unit): DoughEvent? {
        val event = hapToDoughEvent(controls, durationSeconds)
        if (!sounds.resolve(event, warn)) return null
        return parseDoughEvent(encodeEvent(event))
    }

    /** Runs [event] (a `play`, an update, a `hush`...) now. */
    fun start(event: DoughEvent) {
        engine.run(event)
        awake = true
        quietFrames = 0
    }

    /** Adds [frames] frames of the engine to `out` from frame [frameOffset]; nothing at all while the engine is asleep. */
    fun render(out: FloatArray, frames: Int, frameOffset: Int) {
        if (!awake) return
        engine.resetPeak()
        engine.render(out, frames, frameOffset)
        if (engine.activeVoices == 0 && engine.peak < SILENT_PEAK) {
            quietFrames += frames
            if (quietFrames >= (QUIET_SECONDS * sampleRate).toInt()) {
                engine.clearTails()
                awake = false
                quietFrames = 0
            }
        } else {
            quietFrames = 0
        }
    }

    /** Fades the engine out over [fadeFrames] frames and empties it (a stop). */
    fun stop(fadeFrames: Int) {
        if (awake) engine.fadeOut(fadeFrames)
    }

    private companion object {
        /** An engine whose output stays below this (-100 dB) with no voice playing for [QUIET_SECONDS] is asleep: the reverb tail is over, and so is the longest echo (a second). */
        const val SILENT_PEAK = 1e-5f
        const val QUIET_SECONDS = 1.5
    }
}
