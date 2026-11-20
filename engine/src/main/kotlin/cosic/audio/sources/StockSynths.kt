package cosic.audio.sources

/**
 * Builds the frequency modulation (FM, `applyFM`) of a pitched source: the [carrierHz] is the value of its `frequency` parameter, [holdSec] how long the note is held
 * (the duration). Null when the event has no FM. The FM bank of Task 3.12 (`FmBank.create`) is the implementation.
 */
internal fun interface FmFactory {
    fun create(spec: SourceSpec, carrierHz: Double, holdSec: Double): FrequencyModulator?
}

/**
 * The sounds of synth.mjs and noise.mjs that are plain sources, by name: what the sound registry registers for them. [create] builds the source of one event from its
 * [SourceSpec], including the pitch modulation ([PitchModulation]: vibrato and the pitch envelope) of the sources that have it; the FM of Task 3.12 comes through [fm],
 * since a registry knows the FM bank and this file does not.
 *
 * Names are those of the registry: lowercase, with `tri`, `sqr`, `saw` and `sin` as aliases. The wavetables (`wt_*`), ZZFX (`zzfx`, `z_*`), `bus` and the sample
 * banks are other sources.
 */
internal object StockSynths : SynthFamily {
    /** The canonical names, in the order superdough registers them. */
    override val names: List<String> = listOf(
        "triangle", "square", "sawtooth", "sine", "user", "one", "sbd", "supersaw", "bytebeat", "pulse", "pink", "white", "brown", "crackle",
    )

    /** The alternative names of the oscillator sounds. */
    override val aliases: Map<String, String> = mapOf("tri" to "triangle", "sqr" to "square", "saw" to "sawtooth", "sin" to "sine")

    private val whitespace = Regex("\\s+")

    /** The canonical name of a sound that this object builds (the registry's normalisation: lowercase, whitespace becomes `_`, aliases resolved), or null. */
    override fun canonical(name: String): String? {
        val normal = name.lowercase().replace(whitespace, "_")
        val actual = aliases[normal] ?: normal
        return if (actual in names) actual else null
    }

    /** Whether the pitch inputs (vibrato, pitch envelope, FM) reach this sound: the oscillators, the supersaw and the pulse. */
    fun isPitched(name: String): Boolean = when (canonical(name)) {
        "triangle", "square", "sawtooth", "sine", "user", "supersaw", "pulse" -> true
        else -> false
    }

    /**
     * The source of sound [name] for the event [spec], or null when [name] is not one of [names]. A wrong note (`note: "x"`) throws the `not a note` error of
     * `getFrequencyFromValue`, a negative partial count the RangeError of the typed array, as the sound would throw in Strudel. A bytebeat whose program does not
     * compile is a source like any other: it is silent (and, like the worklet, still counts for as long as its envelope).
     */
    override fun create(name: String, spec: SourceSpec, fm: FmFactory?): SoundSource? {
        val sound = canonical(name) ?: return null
        return when (sound) {
            "triangle", "square", "sawtooth", "sine", "user" -> {
                val (pitch, fmod) = modulators(spec, fm)
                OscillatorSource(spec, sound, pitch, fmod)
            }
            "one" -> OscillatorSource(spec, sound, null, null)
            "supersaw" -> {
                val (pitch, fmod) = modulators(spec, fm)
                SupersawSource(spec, pitch, fmod)
            }
            "pulse" -> {
                val (pitch, fmod) = modulators(spec, fm)
                PulseSource(spec, pitch, fmod)
            }
            "bytebeat" -> ByteBeatSource(spec)
            "sbd" -> SbdSource(spec)
            "pink" -> NoiseSource(spec, NoiseType.PINK)
            "white" -> NoiseSource(spec, NoiseType.WHITE)
            "brown" -> NoiseSource(spec, NoiseType.BROWN)
            else -> NoiseSource(spec, NoiseType.CRACKLE)
        }
    }

    /** The vibrato/pitch-envelope and the FM of a pitched event: the note is held for the duration of the event. */
    private fun modulators(spec: SourceSpec, fm: FmFactory?): Pair<PitchModulator?, FrequencyModulator?> {
        val hold = if (spec.durationSec.isNaN()) 0.0 else spec.durationSec
        val pitch = PitchModulation.create(spec, hold)
        val fmod = fm?.create(spec, asParam(frequencyFromValue(spec.controls)), hold)
        return pitch to fmod
    }
}
