package com.octopusman.cosic.audio

import cosic.SampleMapOptions
import cosic.sound.RegistryLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** The tag of the sample banks that are drum machines and kits (`<machine>_<sound>`, or plain `bd`, `sd`). */
internal const val DRUM_MACHINES_TAG = "drum-machines"

/**
 * The sounds every program can play: the sample library `prebake()` of the REPL loads at start (`website/src/repl/prebake.mjs:13-159`),
 * from the same public URLs. Nothing is bundled: the sample maps are read from the network (and from the copy a map left on disk), and the
 * files they name are fetched when a sound is played.
 *
 * | Pack | Sounds | Source |
 * |---|---|---|
 * | `piano` | `piano` (pitched, 29 notes) | Salamander Grand Piano V3, Alexander Holm, CC BY 3.0 |
 * | `vcsl` | 128 instruments and percussion | Versilian Community Sample Library, CC0 |
 * | `drum-machines` | `<Machine>_<type>`, 683 sounds of 71 machines | tidal-drum-machines |
 * | `uzu-drumkit` | `bd`, `sd`, `hh`... | Unlicense |
 * | `wavetables` | `wt_*` | uzu-wavetables |
 * | `mridangam` | `mridangam_*` | CC BY-SA |
 * | `dirt` | `casio crow insect wind jazz metal east space numbers num` | Dirt-Samples |
 *
 * Then `aliasBank` of the drum machine map adds the short names (`TR909_bd` for `RolandTR909_bd`).
 *
 * The `gm_*` soundfonts of the same prebake are not packs of files: the library registers them from their own list.
 */
object DefaultLibrary {
    /** Where the packs are. */
    const val CDN = "https://strudel.b-cdn.net"

    private const val ALIAS_MAP = "tidal-drum-machines-alias.json"

    /** One map of banks: the JSON file at [file] (or the [inline] map) and the folder its sample files are in, both under the CDN. */
    internal class Pack(val name: String, val file: String?, val directory: String, val tag: String? = null, val inline: Map<String, Any?>? = null)

    /** The packs, in the order `prebake()` lists them. */
    internal val packs: List<Pack> = listOf(
        Pack("piano", "piano.json", "piano/"),
        Pack("vcsl", "vcsl.json", "VCSL/"),
        Pack("drum-machines", "tidal-drum-machines.json", "tidal-drum-machines/machines/", DRUM_MACHINES_TAG),
        Pack("uzu-drumkit", "uzu-drumkit.json", "uzu-drumkit/", DRUM_MACHINES_TAG),
        Pack("wavetables", "uzu-wavetables.json", "uzu-wavetables/"),
        Pack("mridangam", "mridangam.json", "mrid/", DRUM_MACHINES_TAG),
        Pack("dirt", null, "Dirt-Samples/", inline = DIRT_SAMPLES),
    )

    /** The name [load] reports when the alias map cannot be read. */
    internal const val ALIASES = "aliases"

    /**
     * Loads [only] (by name; all by default) of the [packs] from [cdn], all at once, then the alias map of the drum machines, and returns the
     * names of what could not be loaded (a pack by its name, the map of aliases as [ALIASES]). A pack that fails does not stop the others.
     */
    internal suspend fun load(library: RegistryLibrary, cdn: String = CDN, only: Set<String>? = null): List<String> = coroutineScope {
        cosic.soundfont.GmFonts.register(library)
        val wanted = packs.filter { only == null || it.name in only }
        val failed = wanted.map { pack -> async(Dispatchers.IO) { pack.name.takeIf { !attempt { register(library, pack, cdn) } } } }.awaitAll().filterNotNull().toMutableList()
        // The aliases are made for the sounds that are registered when they are read: after the maps, not along with them.
        if (!attempt { library.aliasBank("$cdn/$ALIAS_MAP") }) failed.add(ALIASES)
        failed
    }

    /**
     * Loads the packs in the background (see [load]), then calls [onLoaded] with what failed, then tells the library that its default
     * sounds are there ([RegistryLibrary.markReady]), also when something went wrong; a program that is evaluated before that waits for it.
     */
    fun install(library: RegistryLibrary, scope: CoroutineScope, onLoaded: suspend (failed: List<String>) -> Unit = {}): Job = scope.launch {
        try {
            val failed = load(library)
            onLoaded(failed)
        } finally {
            library.markReady()
        }
    }

    private fun register(library: RegistryLibrary, pack: Pack, cdn: String) {
        val base = "$cdn/${pack.directory}"
        val options = SampleMapOptions(prebake = true, tag = pack.tag)
        if (pack.inline != null) library.loadSampleMap(pack.inline, base, options) else library.loadSampleMap("$cdn/${pack.file}", base, options)
    }

    /** Runs [block]; false when it threw (cancellation excepted): a pack that cannot be loaded leaves the others alone. */
    private inline fun attempt(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        false
    }
}

/** The Dirt-Samples of `prebake()` (prebake.mjs:44-149), the 10 sounds of the tunes. */
private val DIRT_SAMPLES: Map<String, Any?> = linkedMapOf(
    "casio" to listOf(
        "casio/high.wav",
        "casio/low.wav",
        "casio/noise.wav",
    ),
    "crow" to listOf(
        "crow/000_crow.wav",
        "crow/001_crow2.wav",
        "crow/002_crow3.wav",
        "crow/003_crow4.wav",
    ),
    "insect" to listOf(
        "insect/000_everglades_conehead.wav",
        "insect/001_robust_shieldback.wav",
        "insect/002_seashore_meadow_katydid.wav",
    ),
    "wind" to listOf(
        "wind/000_wind1.wav",
        "wind/001_wind10.wav",
        "wind/002_wind2.wav",
        "wind/003_wind3.wav",
        "wind/004_wind4.wav",
        "wind/005_wind5.wav",
        "wind/006_wind6.wav",
        "wind/007_wind7.wav",
        "wind/008_wind8.wav",
        "wind/009_wind9.wav",
    ),
    "jazz" to listOf(
        "jazz/000_BD.wav",
        "jazz/001_CB.wav",
        "jazz/002_FX.wav",
        "jazz/003_HH.wav",
        "jazz/004_OH.wav",
        "jazz/005_P1.wav",
        "jazz/006_P2.wav",
        "jazz/007_SN.wav",
    ),
    "metal" to listOf(
        "metal/000_0.wav",
        "metal/001_1.wav",
        "metal/002_2.wav",
        "metal/003_3.wav",
        "metal/004_4.wav",
        "metal/005_5.wav",
        "metal/006_6.wav",
        "metal/007_7.wav",
        "metal/008_8.wav",
        "metal/009_9.wav",
    ),
    "east" to listOf(
        "east/000_nipon_wood_block.wav",
        "east/001_ohkawa_mute.wav",
        "east/002_ohkawa_open.wav",
        "east/003_shime_hi.wav",
        "east/004_shime_hi_2.wav",
        "east/005_shime_mute.wav",
        "east/006_taiko_1.wav",
        "east/007_taiko_2.wav",
        "east/008_taiko_3.wav",
    ),
    "space" to listOf(
        "space/000_0.wav",
        "space/001_1.wav",
        "space/002_11.wav",
        "space/003_12.wav",
        "space/004_13.wav",
        "space/005_14.wav",
        "space/006_15.wav",
        "space/007_16.wav",
        "space/008_17.wav",
        "space/009_18.wav",
        "space/010_2.wav",
        "space/011_3.wav",
        "space/012_4.wav",
        "space/013_5.wav",
        "space/014_6.wav",
        "space/015_7.wav",
        "space/016_8.wav",
        "space/017_9.wav",
    ),
    "numbers" to listOf(
        "numbers/0.wav",
        "numbers/1.wav",
        "numbers/2.wav",
        "numbers/3.wav",
        "numbers/4.wav",
        "numbers/5.wav",
        "numbers/6.wav",
        "numbers/7.wav",
        "numbers/8.wav",
    ),
    "num" to listOf(
        "num/00.wav",
        "num/01.wav",
        "num/02.wav",
        "num/03.wav",
        "num/04.wav",
        "num/05.wav",
        "num/06.wav",
        "num/07.wav",
        "num/08.wav",
        "num/09.wav",
        "num/10.wav",
        "num/11.wav",
        "num/12.wav",
        "num/13.wav",
        "num/14.wav",
        "num/15.wav",
        "num/16.wav",
        "num/17.wav",
        "num/18.wav",
        "num/19.wav",
        "num/20.wav",
    ),
)
