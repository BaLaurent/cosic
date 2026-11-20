package cosic.soundfont

import cosic.SoundLibrary
import cosic.sound.LooseJson

/**
 * The General MIDI sounds of Strudel's soundfont package (`packages/soundfonts/gm.mjs`): 125 names, `gm_piano`, `gm_flute`, `gm_synth_bass_1`..., each with the soundfont files
 * that exist for the instrument (869 in all; `n` picks one, `gm_piano:5`). The first four General MIDI pianos are merged into `gm_piano`, which is why there are 125 and
 * not 128. [all] holds them in the order of the file; `cosic/soundfont/gm.json` is that file as data.
 */
object GmFonts {
    /** Name (`gm_piano`) to font files (`0000_JCLive_sf2_file`), in Strudel's order. */
    val all: Map<String, List<String>> by lazy { read() }

    /**
     * Registers a [SoundfontBank] for every name of [all] (`registerSoundfonts`, fontloader.mjs:144-185), in that order. The last registration of a name wins, as
     * everywhere in the library, so a program's own `samples()` can replace one.
     */
    fun register(library: SoundLibrary) {
        for ((name, fonts) in all) library.register(SoundfontBank(name, fonts))
    }

    private fun read(): Map<String, List<String>> {
        val stream = GmFonts::class.java.getResourceAsStream("/cosic/soundfont/gm.json") ?: throw IllegalStateException("missing resource cosic/soundfont/gm.json")
        val json = LooseJson.parse(stream.use { it.readBytes().toString(Charsets.UTF_8) }) as Map<*, *>
        val map = LinkedHashMap<String, List<String>>(json.size * 2)
        for ((name, fonts) in json) map[name as String] = (fonts as List<*>).map { it as String }
        return map
    }
}
