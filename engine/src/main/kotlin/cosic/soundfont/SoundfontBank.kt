package cosic.soundfont

import cosic.SampleData
import cosic.SoundEntry
import kotlin.math.pow

/**
 * One of the `gm_*` sounds (`gm_piano`, `gm_flute`, ...): a General MIDI instrument that exists as several soundfont files, [fonts], from which `n` picks one
 * (`registerSoundfonts` of Strudel's soundfont package, `fontloader.mjs`). The files are fetched on first use (see [cosic.SoundLibrary.fontZone]) and
 * give the sound its zones: a sample for each range of notes, with its tuning and its loop.
 */
class SoundfontBank(override val name: String, fonts: List<String>) : SoundEntry {
    /** The font files, without extension (`0000_JCLive_sf2_file`), in the order `n` indexes them. */
    val fonts: List<String> = fonts.toList()

    override val tag: String? = null

    override val prebake: Boolean = true

    init {
        require(this.fonts.isNotEmpty()) { "the soundfont $name has no fonts" }
    }
}

/**
 * The zone of a soundfont preset that plays one range of notes: the decoded [data] and the numbers of the font file that say how to play it
 * (`getFontBufferSource`, fontloader.mjs:37-71). [originalPitch] is the pitch the sample was recorded at in cents above MIDI note 0; [coarseTune]
 * (semitones) and [fineTune] (cents) correct it; [loopStart] and [loopEnd] are the loop in frames of the font's own [sampleRate] (not those of
 * [data], whose rate is the one the decoder made).
 */
class FontZone(
    val data: SampleData,
    val originalPitch: Double,
    val coarseTune: Double,
    val fineTune: Double,
    val loopStart: Double,
    val loopEnd: Double,
    val sampleRate: Double,
) {
    /** `originalPitch - 100 * coarseTune - fineTune`: the pitch the sample sounds at, in cents. */
    val baseDetune: Double get() = originalPitch - 100.0 * coarseTune - fineTune

    /** Whether the zone loops: `loopStart > 1 && loopStart < loopEnd`. The `-1`, `-2` of fonts without a loop do not. */
    val loops: Boolean get() = loopStart > 1 && loopStart < loopEnd

    /** The playback rate for MIDI note [midi]: `2 ^ ((100 midi - baseDetune) / 1200)`. */
    fun playbackRate(midi: Double): Double = 2.0.pow((100.0 * midi - baseDetune) / 1200.0)

    /** How many semitones above the recording MIDI note [midi] plays: `(100 midi - baseDetune) / 100`. */
    fun transposeSemitones(midi: Double): Double = (100.0 * midi - baseDetune) / 100.0

    /** The start of the loop in frames of [data], or NaN when the zone does not loop. */
    val loopStartFrame: Double get() = if (loops) loopStart / sampleRate * data.sampleRate else Double.NaN

    /** The end of the loop in frames of [data], or NaN when the zone does not loop. */
    val loopEndFrame: Double get() = if (loops) loopEnd / sampleRate * data.sampleRate else Double.NaN
}
