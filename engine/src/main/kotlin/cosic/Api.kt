package cosic

/** Absolute character offsets into the evaluated code; [end] is exclusive. */
data class SourceRange(val start: Int, val end: Int)

/**
 * Decoded audio: [samples] holds [channels] interleaved channels (mono, stereo, up to 8) at [sampleRate] Hz. Immutable by
 * convention: the engine and the library share instances and never write to them.
 */
class SampleData(val samples: FloatArray, val sampleRate: Int, val channels: Int = 1) {
    init {
        require(sampleRate > 0) { "sample rate must be positive, not $sampleRate" }
        require(channels in 1..MAX_SAMPLE_CHANNELS && samples.size % channels == 0) {
            "$channels channels cannot interleave ${samples.size} values"
        }
    }

    /** Frames: [samples] per channel. */
    val frames: Int get() = samples.size / channels

    val durationSeconds: Double get() = frames.toDouble() / sampleRate

    companion object {
        const val MAX_SAMPLE_CHANNELS = 8
    }
}

/**
 * A wavetable cut into single-cycle frames (`wavetable.mjs:getPayload`): channel 0 of the file, `floor(total / frameLen)` frames of
 * [frameLen] values (one shorter frame when the file is shorter than that), at the file's own [sampleRate].
 */
class WavetableFrames(val frames: List<FloatArray>, val frameLen: Int, val sampleRate: Int)

/**
 * The samples of one entry of a bank. A bank made of a list of files (`bd: [a.wav, b.wav]`) has one group with a null [noteName];
 * a pitched bank (`piano: {c2: [..], c4: [..]}`) has one group per note key, in the order of the map. [midi] is the key's MIDI number,
 * NaN when the key is not a note name (the bank cannot be played then, as in Strudel). [urls] are complete (the base is applied).
 */
class BankGroup(val noteName: String?, val midi: Double?, val urls: List<String>)

/**
 * A named sound of the library: what `s("<name>")` finds. Not sealed: new kinds (soundfonts) are added by their own tasks.
 * [tag] groups sounds that belong together in lists (`drum-machines`), [prebake] tells the sounds the default library loaded
 * from those a program or the user added.
 */
interface SoundEntry {
    val name: String
    val tag: String?
    val prebake: Boolean
}

/** A bank of recorded samples: a list of files, or a pitched map from note names to lists of files. */
class SampleBank(override val name: String, val groups: List<BankGroup>, override val tag: String?, override val prebake: Boolean) : SoundEntry {
    /** True for a map from note names to files. */
    val pitched: Boolean get() = groups.firstOrNull()?.noteName != null

    /** The number of files, over all groups. */
    val count: Int get() = groups.sumOf { it.urls.size }
}

/** A bank of wavetables (`wt_*`): WAV files whose channel 0 is cut into [frameLen]-sized frames. Chosen like the samples of a [SampleBank]. */
class WaveTableBank(
    override val name: String,
    val groups: List<BankGroup>,
    val frameLen: Int,
    override val tag: String?,
    override val prebake: Boolean,
) : SoundEntry

/** A sound the engine makes itself (a synth, a drum of the built-in kit); the host lists them as the `builtins` of its library. */
class BuiltinSound(override val name: String) : SoundEntry {
    override val tag: String? = null
    override val prebake: Boolean = true
}

/** `{prebake, tag}` of `samples()`: where a map comes from, as the sound lists show it. */
class SampleMapOptions(val prebake: Boolean = false, val tag: String? = null)

/** A sample map or alias map that cannot be loaded or has the wrong shape; the message is what Strudel's error says. */
class SampleMapException(message: String) : RuntimeException(message)

/**
 * Where samples and map files come from: the network and the disk in the app, a directory or a table in tests. Implementations
 * apply Strudel's URL rule before they fetch (`url.replace('#', '%23')`, the first `#` only) and cache what they load.
 */
interface SampleStore {
    /** The text at [url] (a sample map, an alias map, a soundfont file). Blocking; throws [java.io.IOException]. */
    fun fetchText(url: String): String

    /**
     * The decoded sample at [url], or null while it is still being fetched and decoded and after that failed. Never blocks (the audio
     * thread calls it): the first call starts loading, so asking once also preloads it.
     */
    fun sample(url: String): SampleData?

    /** The sample at [url], waiting for it: blocking, throws [java.io.IOException] when it cannot be loaded. */
    fun awaitSample(url: String): SampleData

    /** Decodes audio file [bytes] (a soundfont zone); [nameHint] names the data in errors. Blocking; throws [java.io.IOException]. */
    fun decode(bytes: ByteArray, nameHint: String): SampleData
}

/**
 * The sounds a program can play: a registry of named entries (`soundMap` of superdough) plus what it takes to load them. The
 * audio side ([sound], [sample], [wavetable], [irSample]) never blocks; the script side (everything after [awaitReady]) is called by
 * the program evaluation, which may wait for the network. Lookups are case-insensitive ([sound]); the last registration of a
 * name wins, whoever made it.
 */
interface SoundLibrary {
    /** The sound called [name] (case-insensitive), or null when none is registered. */
    fun sound(name: String): SoundEntry?

    /** The decoded file [index] of group [group] of [bank], or null while it loads (the library starts loading it). */
    fun sample(bank: SampleBank, group: Int, index: Int): SampleData?

    /** The frames of the wavetable file [index] of group [group] of [bank], or null while it loads. */
    fun wavetable(bank: WaveTableBank, group: Int, index: Int): WavetableFrames?

    /** The registered sounds, each once, in the order they were first registered (aliases and built-ins are not listed). */
    fun entries(): List<SoundEntry>

    /** Changes with every registration, alias and removal: a list that shows [entries] redraws when it differs from the last seen. */
    val revision: Long

    /** Waits until the default sounds are installed (or failed to be), at most [timeoutMillis]; true when they are. */
    fun awaitReady(timeoutMillis: Long): Boolean

    /** Registers [entry] under its name, replacing the sound of that name. */
    fun register(entry: SoundEntry)

    /**
     * `samples(url)`: loads the sample map at [url] (a `https:` URL, `github:user/repo/branch`, `bubo:name`, `local:`, `shabda:` or
     * `shabda/speech`; see `SampleMaps.mapUrl`) and registers its banks. [baseUrl] replaces the map's own `_base`.
     * Throws [SampleMapException] (`error loading "<url>"`).
     */
    fun loadSampleMap(url: String, baseUrl: String? = null, options: SampleMapOptions = SampleMapOptions())

    /** `samples({...})`: registers the banks of [map], which is in the form of a `strudel.json` (`name: file | [files] | {note: file | [files]}`). */
    fun loadSampleMap(map: Map<String, Any?>, baseUrl: String? = null, options: SampleMapOptions = SampleMapOptions())

    /** `soundAlias(original, alias)`: another name for a sound, stored as written (a name with capitals cannot be found again). False when [original] is unknown. */
    fun soundAlias(original: String, alias: String): Boolean

    /** `aliasBank({bank: alias | [aliases]})`: for every registered `<bank>_<sound>` the names `<alias>_<sound>`; sounds registered later are not aliased. */
    fun aliasBank(map: Map<String, Any?>)

    /** `aliasBank(url)`: [aliasBank] of the JSON map at [url]. */
    fun aliasBank(url: String)

    /** `aliasBank(bank, alias)`; [alias] is a string or a list of strings. */
    fun aliasBank(bank: String, alias: Any)

    /** `getDuration(name, n)`: the length in seconds of sample [n] of the bank [name] (not lowercased: as Strudel); waits for it to load. */
    fun duration(name: String, n: Double = 0.0): Double

    /** The sample a reverb convolves with (`ir`/`iresponse` and index [i]), or null when there is none or it is not loaded yet. */
    fun irSample(name: String, i: Int): SampleData?

    /** `setSoundfontUrl(url)`: where soundfont files are fetched from. */
    fun setSoundfontUrl(url: String)
}

/** A [SampleStore] with nothing in it: every fetch fails and no sample ever loads. For libraries that only hold built-in sounds. */
object NoSampleStore : SampleStore {
    override fun fetchText(url: String): String = throw java.io.IOException("no sample store: cannot fetch $url")

    override fun sample(url: String): SampleData? = null

    override fun awaitSample(url: String): SampleData = throw java.io.IOException("no sample store: cannot load $url")

    override fun decode(bytes: ByteArray, nameHint: String): SampleData = throw java.io.IOException("no sample store: cannot decode $nameHint")
}

sealed interface EvalReport {
    data class Ok(val cps: Double, val warnings: List<String>, val program: ProgramInfo) : EvalReport

    /** [line] and [column] are 1-based; 0 when no range is known. */
    data class Failed(val message: String, val range: SourceRange?, val line: Int, val column: Int) : EvalReport
}

/**
 * An event the editor may highlight while it sounds: from frame [startFrame] to [endFrame], marking the atoms of the code at [ranges]. [color]
 * and [markcss] are the `color` and `markcss` controls of the event: the colour of the mark (white by default) and a style that replaces it.
 */
class HighlightEvent(
    val startFrame: Long,
    val endFrame: Long,
    val ranges: List<SourceRange>,
    val color: String? = null,
    val markcss: String? = null,
)

/** A call of a widget method (`pat._scope()`): where it is and which widget it makes. */
public data class WidgetConfig(
    /** The method name, for example `_pianoroll`. */
    val type: String,
    /** The range of the whole call. */
    val from: Int,
    val to: Int,
    /** How many widgets of the same [type] were recorded before this one; the walk meets the outermost call of a chain first. */
    val index: Int,
    /** The `id` option the program was transpiled with. */
    val id: String?,
) {
    /** The id the call receives as its first argument: `<id>_widget_<type>_<index>` (plugin-widgets.mjs:86-93). */
    val widgetId: String get() = "${id ?: ""}_widget_${type}_$index"
}

/** A `slider(value, min, max, step)` call. */
public data class SliderConfig(
    /** The range of the first argument, the one the UI rewrites when the slider moves. */
    val from: Int,
    val to: Int,
    /** The source text of the first argument when it is a literal, else null. */
    val value: String?,
    /** `min` and `max` are the second and third argument when they are number literals, else 0 and 1. */
    val min: Double,
    val max: Double,
    /** The fourth argument when it is a number literal. */
    val step: Double?,
    /** `slider_<from>`: the first argument of the `sliderWithID` call the transpiler makes. */
    val id: String,
)

/**
 * What a program is as the editor needs to know it: where the atoms of its mini-notation strings are, the sliders and widgets it makes,
 * the [visuals] its visual builtins made (null when none did), the [metadata] its comments declare (`// @title ...`; empty when the engine
 * has no [CosicEngine.metadataParser]) and the [inputsUsed] the program reads (the app listens to those only).
 */
public data class ProgramInfo(
    val miniLocations: List<SourceRange>,
    val sliders: List<SliderConfig>,
    val widgets: List<WidgetConfig>,
    val visuals: ProgramVisuals? = null,
    val metadata: Map<String, Any> = emptyMap(),
    val inputsUsed: Set<InputKind> = emptySet(),
)

class EngineSnapshot(
    val sampleRate: Int,
    val playing: Boolean,
    val idle: Boolean,
    val cps: Double,
    val anchorFrame: Long,
    val anchorCycle: Double,
    val renderedFrames: Long,
    val activeVoices: Int,
    val highlights: List<HighlightEvent>,
    val runtimeError: String?,
    val warnings: List<String>,
) {
    fun cycleAt(frame: Long): Double = anchorCycle + (frame - anchorFrame).toDouble() / sampleRate * cps
}
