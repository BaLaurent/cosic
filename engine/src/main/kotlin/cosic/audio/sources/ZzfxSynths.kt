package cosic.audio.sources

/**
 * The ZZFX sounds of superdough (`registerZZFXSounds`, zzfx.mjs:67-87), by name, shaped like [StockSynths]: what the sound registry registers for them. All of them are
 * built from the same 20 parameters (see [zzfxParams]); the name only chooses the waveform: `z_sine`, `z_sawtooth`, `z_triangle`, `z_tan`, `z_noise`, and the triangle for
 * `zzfx` and `z_square` (which is a triangle with `curve` 0). Vibrato, the pitch envelope and FM do not reach them (the buffer is played as it is), and there is no envelope:
 * a voice must not add the amplitude envelope of a synth to these sounds, only the gain, the filters and the effects of its chain.
 */
internal object ZzfxSynths {
    /** The names, in the order superdough registers them. */
    val names: List<String> = listOf("zzfx", "z_sine", "z_sawtooth", "z_triangle", "z_square", "z_tan", "z_noise")

    private val whitespace = Regex("\\s+")

    /** The canonical name of a sound that this object builds (the registry's normalisation: lowercase, whitespace becomes `_`), or null. */
    fun canonical(name: String): String? {
        val normal = name.lowercase().replace(whitespace, "_")
        return if (normal in names) normal else null
    }

    /**
     * The source of sound [name] for the event [spec], or null when [name] is not one of [names]. A `note` that is a string but no note throws the `not a note` error, as
     * `getZZFX` does. [fm] is accepted to call this as [StockSynths.create]; FM does not reach ZZFX.
     */
    @Suppress("UNUSED_PARAMETER")
    fun create(name: String, spec: SourceSpec, fm: FmFactory? = null): SoundSource? {
        val sound = canonical(name) ?: return null
        return ZzfxSource(spec, sound)
    }
}
