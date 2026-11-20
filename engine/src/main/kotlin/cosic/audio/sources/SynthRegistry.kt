package cosic.audio.sources

import cosic.WavetableFrames
import cosic.pattern.logger

/**
 * A group of built-in sounds that the engine makes itself, registered with [SynthRegistry]: the oscillators, noises and worklet sounds of synth.mjs and noise.mjs
 * ([StockSynths]), and any other source that is a sound of its own (ZZFX, `zzfx` and `z_sine`...). A sound is made for one event from its [SourceSpec].
 */
internal interface SynthFamily {
    /** The canonical names, in the order superdough registers them. */
    val names: List<String>

    /** The other names of some of the sounds: alias to canonical name. */
    val aliases: Map<String, String>

    /** The canonical name of the sound [name] stands for (the registry's normalisation: lower case, white space as `_`, aliases resolved), or null when it is none of the family's. */
    fun canonical(name: String): String?

    /**
     * The source of sound [name] for the event [spec], or null when [name] is not one of the family's. [fm] builds the frequency modulation of the sources that have it.
     * A control that cannot be used throws a [cosic.pattern.CosicError] with the message the same sound gives in Strudel.
     */
    fun create(name: String, spec: SourceSpec, fm: FmFactory? = null): SoundSource?
}

/**
 * The sounds the engine plays itself, behind the library's registry (the resolver asks the library first, so a bank or a sound a program registered under the same name
 * wins, as the last `registerSound` does in superdough): what `registerSynthSounds`, `registerNoiseSounds` and the like put in `soundMap`. Each is a [SoundSource] made
 * per event from the event's controls, with the vibrato, pitch envelope ([PitchModulation]) and FM ([FmBank]) of the sources that have them already attached.
 *
 * The wavetables (`wt_*`) are not here: they are banks of the library, and [wavetable] makes the source that plays one of their tables.
 */
internal object SynthRegistry {
    /**
     * The families of built-in sounds, in the order they are registered; the first that knows a name makes it (their names do not overlap). ZZFX (`zzfx`, `z_sine`...)
     * is a family of its own: it is added here.
     */
    private val families: List<SynthFamily> = listOf(StockSynths, ZzfxSynths)

    /** Every canonical sound name, in display order. */
    val names: List<String> = families.flatMap { it.names }.distinct()

    /** The alternative names: `tri`, `sqr`, `saw`, `sin`. */
    val aliases: Map<String, String> = families.flatMap { it.aliases.entries }.associate { it.key to it.value }

    /** The frequency modulation of an event (`applyFM`); what it could not make of the controls goes to the console, as the warnings of superdough do. */
    private val fm = FmFactory { spec, carrierHz, holdSec ->
        FmBank.create(spec, carrierHz, holdSec)?.also { bank -> for (warning in bank.warnings) logger(warning, "warning", null) }
    }

    /** The canonical name of the built-in sound [name] stands for (lower case, white space as `_`, aliases resolved), or null when the engine has no such sound. */
    fun canonical(name: String): String? {
        for (family in families) family.canonical(name)?.let { return it }
        return null
    }

    /**
     * The source of the built-in sound [name] for one event, or null when there is none of that name. It is made for [spec] only: started once, rendered until it is
     * finished, then dropped. A wrong `note` (a name that is no note) and a partial count that cannot be made throw the error the sound throws in Strudel.
     */
    fun create(name: String, spec: SourceSpec): SoundSource? {
        for (family in families) {
            if (family.canonical(name) != null) return family.create(name, spec, fm)
        }
        return null
    }

    /**
     * The source that plays [frames], the table of the wavetable bank the sound lookup picked (`onTriggerSynth` of wavetable.mjs), with its vibrato, pitch envelope and
     * FM. The pitch envelope is held until the note ends (shortened by `clip`, which counts a second time here as in Strudel), FM for `duration`.
     */
    fun wavetable(frames: WavetableFrames, spec: SourceSpec): SoundSource {
        val c = spec.controls
        val duration = if (spec.durationSec > 0.0) spec.durationSec else 0.0
        val clip = c.number("clip")
        val hold = if (clip != null) Math.min(clip * duration, duration) else duration
        val pitch = PitchModulation.create(spec, hold)
        val frequency = fm.create(spec, asParam(frequencyFromValue(c)), duration)
        return WavetableSource(spec, frames, pitch, frequency)
    }

    /**
     * Builds what the first note of each sound would build (the four stock waves and the noise loops at [sampleRate], 3 to 30 ms each), so that no note has to do it on
     * the audio thread. Safe from any thread.
     */
    fun warmUp(sampleRate: Int) {
        for (wave in BasicWave.entries) PeriodicWave.basic(wave, sampleRate)
        NoiseBuffers.get(NoiseType.WHITE, sampleRate)
        NoiseBuffers.get(NoiseType.PINK, sampleRate)
        NoiseBuffers.get(NoiseType.BROWN, sampleRate)
        SbdSource.saturationCurve(sampleRate)
    }
}
