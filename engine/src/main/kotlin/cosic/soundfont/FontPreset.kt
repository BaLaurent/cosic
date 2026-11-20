package cosic.soundfont

import cosic.pattern.jsStringToNumber
import cosic.sound.LooseJson

/** A soundfont file that is not in the shape the loader reads (no `zones`, not an object literal). */
internal class FontFormatException(message: String) : RuntimeException(message)

/**
 * A zone of a font file as it is written there (`{ midi:0 ,originalPitch:2400 ,keyRangeLow:0 ,keyRangeHigh:27 ,loopStart:133802 ,loopEnd:135153 ,coarseTune:0
 * ,fineTune:0 ,sampleRate:44100 ,ahdsr:true ,file:'<base64 mp3>' ,anchor:0.0132 }`). A number that is missing is NaN, as `undefined` is in the arithmetic
 * of the JavaScript loader; [file] and [sample] are null when they are missing or empty (what JavaScript calls falsy).
 */
internal class FontPresetZone(
    val keyRangeLow: Double,
    val keyRangeHigh: Double,
    val originalPitch: Double,
    val coarseTune: Double,
    val fineTune: Double,
    val loopStart: Double,
    val loopEnd: Double,
    val sampleRate: Double,
    val file: String?,
    val sample: String?,
)

/**
 * The zones of a font file, in file order. The file is JavaScript, not JSON: `console.log('load _tone_0000_JCLive_sf2_file'); var _tone_0000_JCLive_sf2_file={ zones:[
 * ... ] };`, and [parse] reads it the way `loadFont` of Strudel's soundfont package does: the text between the first `={` and the next one is made an object literal
 * and its `zones` are what counts. The base64 payloads are kept as they are until a zone is played ([cosic.soundfont.FontLoader] decodes them).
 */
internal class FontPreset(val zones: List<FontPresetZone>) {
    /**
     * `findZone` (fontloader.mjs:99-103): the index of the first zone with `keyRangeLow <= midi && keyRangeHigh + 1 >= midi`, so a note one above the
     * range of a zone still belongs to it when no earlier zone takes it, and a note that is not a number (NaN) has none; -1 when there is no zone.
     */
    fun findZone(midi: Double): Int {
        for (i in zones.indices) {
            val zone = zones[i]
            if (zone.keyRangeLow <= midi && zone.keyRangeHigh + 1 >= midi) return i
        }
        return -1
    }

    /** The approximate size of the font in memory, in characters of payload: what the loader's cache budget counts. */
    val weight: Int = zones.sumOf { (it.file?.length ?: 0) + (it.sample?.length ?: 0) } + 1

    companion object {
        /** Reads a font file; throws [FontFormatException] when it has no `zones` or no object literal. */
        fun parse(text: String): FontPreset {
            val first = text.indexOf("={")
            if (first < 0) throw FontFormatException("not a soundfont: no `={` in the file")
            val next = text.indexOf("={", first + 2)
            val literal = text.substring(first + 1, if (next < 0) text.length else next)
            val value = try {
                LooseJson.parse(literal, allowTrailing = true)
            } catch (e: IllegalArgumentException) {
                throw FontFormatException("not a soundfont: ${e.message}")
            }
            val zones = (value as? Map<*, *>)?.get("zones") as? List<*> ?: throw FontFormatException("not a soundfont: the file has no zones")
            return FontPreset(
                zones.map { zone ->
                    val map = zone as? Map<*, *> ?: throw FontFormatException("not a soundfont: a zone is not an object")
                    FontPresetZone(
                        keyRangeLow = number(map["keyRangeLow"]),
                        keyRangeHigh = number(map["keyRangeHigh"]),
                        originalPitch = number(map["originalPitch"]),
                        coarseTune = number(map["coarseTune"]),
                        fineTune = number(map["fineTune"]),
                        loopStart = number(map["loopStart"]),
                        loopEnd = number(map["loopEnd"]),
                        sampleRate = number(map["sampleRate"]),
                        file = (map["file"] as? String)?.takeIf { it.isNotEmpty() },
                        sample = (map["sample"] as? String)?.takeIf { it.isNotEmpty() },
                    )
                },
            )
        }

        /** A number the way JavaScript's arithmetic reads a value: a number is itself, a string is converted, anything else is NaN. */
        private fun number(v: Any?): Double = when (v) {
            is Double -> v
            is String -> jsStringToNumber(v)
            is Boolean -> if (v) 1.0 else 0.0
            else -> Double.NaN
        }
    }
}
