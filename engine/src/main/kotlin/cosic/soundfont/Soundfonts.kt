package cosic.soundfont

import cosic.audio.VoiceSource
import cosic.pattern.CosicError
import cosic.pattern.ControlMap
import cosic.pattern.Pattern
import cosic.pattern.Undefined
import cosic.pattern.isNote
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.jsTypeof
import cosic.pattern.jsonStringify
import cosic.pattern.jsMerge
import cosic.pattern.logger
import cosic.pattern.midiToFreq
import cosic.pattern.noteToMidi
import cosic.pattern.jsNumberToString
import cosic.player.SoundLookup
import cosic.soundfont.sf2.Sf2File
import cosic.soundfont.sf2.Sf2Preset
import cosic.soundfont.sf2.presetNote
import java.net.URL

/** The controls `.soundfont()` adds to a hap: the preset to play, and the note it plays or why it has none. Keys that start with `_cosic_` are not printed. */
internal const val PRESET_CONTROL = "_cosic_sfpreset"
internal const val PRESET_NOTE_CONTROL = "_cosic_sfnote"
internal const val PRESET_ERROR_CONTROL = "_cosic_sferror"

/** A preset of a soundfont the program loaded: what `.soundfont(sf, n)` puts in the controls of a hap. */
internal class PresetRef(val preset: Sf2Preset)

/**
 * `getPlayableNoteValue` (core/util.mjs:102-118) for the value of a hap: the note name it plays, or the error that says why it has none. A control map gives its `note`, else its
 * `n`, else its `value`, whichever is truthy first (the last as it is); a number is no note, whatever the map says (the function turns it into a frequency of the hap's value, which for a
 * map is NaN); anything else must be a note name.
 */
internal fun playableNote(value: Any?): Pair<String?, String?> {
    var note: Any? = value
    val fromMap = jsTypeof(value) == "object" && value !is List<*>
    if (fromMap) {
        val map = value as? Map<*, *> ?: return null to "Cannot read properties of null (reading 'note')"
        note = map["note"].takeIf { jsTruthy(it) } ?: map["n"].takeIf { jsTruthy(it) } ?: map["value"] ?: Undefined
        if (note === Undefined) return null to "cannot find a playable note for ${jsonStringify(value)}"
    }
    if (note is Number) return null to "not a note: \"${if (fromMap) "NaN" else jsNumberToString(midiToFreq(note.toDouble()))}\""
    if (note !is String || !isNote(note)) return null to "not a note: ${jsonStringify(note)}"
    return note to null
}

/**
 * `Pattern.prototype.soundfont = function (sf, n = 0)` (soundfonts/sfumato.mjs): every hap of [pattern] plays the note it names on [preset] of a soundfont, instead of the
 * default output. The values of the haps stay what they were, with the preset and the note added under keys the output does not print; the sound is made by [resolvePreset].
 */
internal fun soundfontPattern(pattern: Pattern, preset: Sf2Preset): Pattern {
    val ref = PresetRef(preset)
    return pattern.withValue { value ->
        val (note, error) = playableNote(value)
        val extra = LinkedHashMap<String, Any?>(3)
        extra[PRESET_CONTROL] = ref
        if (note != null) extra[PRESET_NOTE_CONTROL] = note else extra[PRESET_ERROR_CONTROL] = error
        @Suppress("UNCHECKED_CAST")
        if (value is Map<*, *>) jsMerge(value as Map<String, Any?>, extra) else extra
    }
}

/**
 * What an event of [soundfontPattern] plays: the zones of its preset that cover the note (`startPresetNote`). A note that is none, or that no zone covers, plays nothing; the
 * first is reported on the console (the hook of Strudel throws, and the error is logged).
 */
internal fun resolvePreset(ref: PresetRef, controls: ControlMap): SoundLookup {
    val error = controls[PRESET_ERROR_CONTROL]
    if (error is String) {
        logger(error, "error", null)
        return SoundLookup.Silent
    }
    val zones = presetNote(ref.preset, noteToMidi(controls[PRESET_NOTE_CONTROL]))
    return if (zones.isEmpty()) SoundLookup.Silent else SoundLookup.Ready(VoiceSource.Preset(zones))
}

/**
 * `loadSoundfont(url)` (soundfonts/sfumato.mjs): fetches and reads a SoundFont 2 file, once for every url. Blocking; throws a [CosicError] when the file cannot be fetched or is
 * no SoundFont 2 file. [fetch] gets the bytes of a url; the default reads it with `java.net` (the host may replace it with a cached or restricted one, tests with a table).
 */
internal object SoundfontFiles {
    @Volatile
    var fetch: (String) -> ByteArray = ::download

    /** A soundfont larger than this is refused: it would not fit in memory next to its decoded samples. */
    private const val MAX_BYTES = 256L * 1024 * 1024

    private fun download(url: String): ByteArray = URL(url.replaceFirst("#", "%23")).openStream().use { stream ->
        val out = java.io.ByteArrayOutputStream()
        val chunk = ByteArray(1 shl 16)
        var total = 0L
        while (true) {
            val n = stream.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_BYTES) throw java.io.IOException("the file is larger than ${MAX_BYTES / (1024 * 1024)} MB")
            out.write(chunk, 0, n)
        }
        out.toByteArray()
    }

    /** The files read last: a soundfont can be tens of megabytes, so only a few stay. */
    private const val KEPT = 2

    private val cache = object : LinkedHashMap<String, Sf2File>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Sf2File>): Boolean = size > KEPT
    }

    fun load(url: String): Sf2File {
        synchronized(cache) { cache[url] }?.let { return it }
        val file = try {
            Sf2File.parse(fetch(url))
        } catch (e: java.io.IOException) {
            throw CosicError("could not load the soundfont $url: ${e.message ?: e.javaClass.simpleName}")
        } catch (e: cosic.soundfont.sf2.Sf2FormatException) {
            throw CosicError("could not read the soundfont $url: ${e.message}")
        }
        return synchronized(cache) { cache.getOrPut(url) { file } }
    }

    /** Forgets what was loaded; for tests. */
    fun clear() = synchronized(cache) { cache.clear() }
}

/** `String(url)` of the argument of `loadSoundfont`. */
internal fun soundfontUrl(arg: Any?): String = jsToString(arg)
