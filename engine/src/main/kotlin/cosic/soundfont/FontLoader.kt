package cosic.soundfont

import cosic.SampleStore
import cosic.pattern.jsNumberToString
import cosic.pattern.logger
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReferenceArray

/** A font or zone that could not be loaded is tried again after this long; Strudel keeps the failed promise and never does. */
private const val RETRY_NANOS = 30_000_000_000L

/** The most characters of font payload (base64) kept for zones that may be played again. A font file is up to about 1.2 million characters. */
private const val MAX_PRESET_CHARS = 20_000_000L

/** The most frames of decoded zones kept (4 bytes each); a decoded zone that a voice plays stays alive until the voice ends. */
private const val MAX_DECODED_FRAMES = 6_000_000L

/** The reports of a loader that are remembered so that they are told once; a loader that is told more starts over. */
private const val MAX_REPORTED = 512

/** The threads that fetch and decode soundfonts: two, daemons, so that waiting for the network never holds up the audio thread or the program. */
internal object FontThreads {
    private val counter = AtomicInteger()

    val executor: Executor by lazy {
        Executors.newFixedThreadPool(2) { task -> Thread(task, "cosic-soundfont-${counter.incrementAndGet()}").apply { isDaemon = true } }
    }
}

/**
 * Loads the soundfont files of the `gm_*` sounds as they are asked for, and decodes the zones that are played, the way `loadFont`, `getFontPitch` and `getBuffer` of
 * Strudel's soundfont package do (fontloader.mjs:21-142): the font file is fetched from `<url>/<font>.js`, read ([FontPreset]) and kept; a zone is found by pitch and its
 * `file` (base64 audio) is decoded once ([SampleStore.decode]) and kept.
 *
 * [zone] is the only entry and never blocks (the audio thread calls it): it returns the decoded zone or null, and the first call that needs something starts the job
 * ([executor]) that gets it. A job that fails reports it once through the pattern logger and leaves the font or the zone for [RETRY_NANOS]; after that the next call
 * tries again. What is kept is bounded: the fonts by the length of their payloads, the decoded zones by their frames; the least recently used go first.
 */
internal class FontLoader(
    private val store: SampleStore,
    private val urlBase: () -> String,
    private val executor: () -> Executor,
    private val nanoTime: () -> Long = System::nanoTime,
    private val maxPresetChars: Long = MAX_PRESET_CHARS,
    private val maxDecodedFrames: Long = MAX_DECODED_FRAMES,
) {
    /** A font that has been read: its zones, and for each of them the decoded [FontZone], when it is decoded, and whether a decode is under way or has failed. */
    private class Loaded(val preset: FontPreset) {
        val zones = AtomicReferenceArray<FontZone>(preset.zones.size)
        val frames = AtomicLongArray(preset.zones.size)
        val used = AtomicLongArray(preset.zones.size)
        val busy = AtomicIntegerArray(preset.zones.size)
        val retryAt = AtomicLongArray(preset.zones.size)
        val failed = AtomicIntegerArray(preset.zones.size)

        @Volatile
        var lastUsed = 0L
    }

    private val fonts = ConcurrentHashMap<String, Loaded>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val fontRetryAt = ConcurrentHashMap<String, Long>()
    private val reported = ConcurrentHashMap.newKeySet<String>()
    private val clock = AtomicLong()

    /** The zone of [font] that plays MIDI note [midi], decoded, or null while it is not (see the class). */
    fun zone(font: String, midi: Double): FontZone? {
        val loaded = fonts[font]
        if (loaded == null) {
            requestFont(font, midi)
            return null
        }
        val index = loaded.preset.findZone(midi)
        if (index < 0) {
            report("no soundfont zone found for preset $font pitch ${jsNumberToString(midi)}")
            return null
        }
        val tick = clock.incrementAndGet()
        loaded.lastUsed = tick
        val zone = loaded.zones.get(index)
        if (zone != null) {
            loaded.used.set(index, tick)
            return zone
        }
        requestZone(font, loaded, index, midi)
        return null
    }

    // ---- fonts -----------------------------------------------------------------------------------------------------------------------------------------

    private fun requestFont(font: String, midi: Double) {
        val retry = fontRetryAt[font]
        if (retry != null && nanoTime() - retry < 0L) return
        if (!loading.add(font)) return
        val url = "${urlBase()}/$font.js"
        try {
            executor().execute { loadFont(font, url, midi) }
        } catch (e: RuntimeException) {
            loading.remove(font)
            failFont(font, e)
        }
    }

    private fun loadFont(font: String, url: String, midi: Double) {
        try {
            val loaded = Loaded(FontPreset.parse(store.fetchText(url)))
            loaded.lastUsed = clock.incrementAndGet()
            // the zone that asked is claimed before the font is visible, so that a call that finds the font does not start a second decode of it
            val index = loaded.preset.findZone(midi)
            if (index >= 0) loaded.busy.set(index, 1)
            fonts[font] = loaded
            fontRetryAt.remove(font)
            evictFonts(keep = font)
            if (index >= 0) decodeZone(font, loaded, index, midi)
            else report("no soundfont zone found for preset $font pitch ${jsNumberToString(midi)}")
        } catch (e: Exception) {
            failFont(font, e)
        } finally {
            loading.remove(font)
        }
    }

    private fun failFont(font: String, e: Exception) {
        fontRetryAt[font] = nanoTime() + RETRY_NANOS
        report("could not load soundfont $font: ${e.message ?: e.javaClass.simpleName}")
    }

    // ---- zones -----------------------------------------------------------------------------------------------------------------------------------------

    private fun requestZone(font: String, loaded: Loaded, index: Int, midi: Double) {
        if (loaded.failed.get(index) != 0 && nanoTime() - loaded.retryAt.get(index) < 0L) return
        if (!loaded.busy.compareAndSet(index, 0, 1)) return
        try {
            executor().execute { decodeZone(font, loaded, index, midi) }
        } catch (e: RuntimeException) {
            loaded.busy.set(index, 0)
            failZone(font, loaded, index, e)
        }
    }

    /** Decodes zone [index] of [loaded], which the caller has claimed (`busy`), unless it is decoded already; runs on a loader thread. */
    private fun decodeZone(font: String, loaded: Loaded, index: Int, midi: Double) {
        try {
            if (loaded.zones.get(index) != null) return
            val zone = loaded.preset.zones[index]
            // getBuffer (fontloader.mjs:106-142): a `sample` takes the raw branch, which makes a buffer and returns nothing, so the zone has none
            val bytes = when {
                zone.sample != null -> null
                zone.file != null -> Base64.getMimeDecoder().decode(zone.file)
                else -> null
            } ?: throw FontFormatException("no soundfont buffer found for preset $font, pitch: ${jsNumberToString(midi)}")
            val data = store.decode(bytes, "$font#$index")
            loaded.frames.set(index, data.frames.toLong())
            loaded.used.set(index, clock.incrementAndGet())
            loaded.zones.set(index, FontZone(data, zone.originalPitch, zone.coarseTune, zone.fineTune, zone.loopStart, zone.loopEnd, zone.sampleRate))
            loaded.failed.set(index, 0)
            evictZones()
        } catch (e: Exception) {
            failZone(font, loaded, index, e)
        } finally {
            loaded.busy.set(index, 0)
        }
    }

    private fun failZone(font: String, loaded: Loaded, index: Int, e: Exception) {
        loaded.retryAt.set(index, nanoTime() + RETRY_NANOS)
        loaded.failed.set(index, 1)
        report("could not load a zone of soundfont $font: ${e.message ?: e.javaClass.simpleName}")
    }

    // ---- what is kept ------------------------------------------------------------------------------------------------------------------------------------

    /** Drops the fonts used longest ago while their payloads add up to more than [maxPresetChars]; [keep], the font that was just read, stays. */
    private fun evictFonts(keep: String) {
        var total = 0L
        for (loaded in fonts.values) total += loaded.preset.weight
        if (total <= maxPresetChars) return
        val oldest = fonts.entries.filter { it.key != keep }.sortedBy { it.value.lastUsed }
        for ((name, loaded) in oldest) {
            if (total <= maxPresetChars) break
            if (fonts.remove(name, loaded)) total -= loaded.preset.weight
        }
    }

    /** Drops the decoded zones used longest ago while they add up to more than [maxDecodedFrames] frames. */
    private fun evictZones() {
        var total = 0L
        val all = ArrayList<Triple<Loaded, Int, Long>>()
        for (loaded in fonts.values) {
            for (i in 0 until loaded.zones.length()) {
                if (loaded.zones.get(i) == null) continue
                total += loaded.frames.get(i)
                all.add(Triple(loaded, i, loaded.used.get(i)))
            }
        }
        if (total <= maxDecodedFrames) return
        all.sortBy { it.third }
        for ((loaded, i, _) in all.dropLast(1)) {
            if (total <= maxDecodedFrames) break
            total -= loaded.frames.get(i)
            loaded.zones.set(i, null)
        }
    }

    /** Tells the program's logger once: a failing note comes back with every event, and so does its failure. */
    private fun report(message: String) {
        if (reported.size >= MAX_REPORTED) reported.clear()
        if (reported.add(message)) logger(message, "error", null)
    }
}
