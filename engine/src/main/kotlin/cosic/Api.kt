package cosic

/** Absolute character offsets into the evaluated code; [end] is exclusive. */
data class SourceRange(val start: Int, val end: Int)

/** Mono PCM sample data. */
class SampleData(val samples: FloatArray, val sampleRate: Int)

/** Description of a sample bank. [rootNotes] is ascending for pitched banks, null for unpitched ones. */
class BankInfo(val name: String, val count: Int, val rootNotes: IntArray?)

/** Source of user-visible sample banks. Implementations MUST be non-blocking. */
interface SoundLibrary {
    fun bank(name: String): BankInfo?

    /** Returns null while the sample is not decoded yet; the implementation starts loading it. */
    fun sample(name: String, index: Int): SampleData?
}

sealed interface EvalReport {
    data class Ok(val cps: Double, val warnings: List<String>) : EvalReport

    /** [line] and [column] are 1-based; 0 when no range is known. */
    data class Failed(val message: String, val range: SourceRange?, val line: Int, val column: Int) : EvalReport
}

class HighlightEvent(val startFrame: Long, val endFrame: Long, val ranges: List<SourceRange>)

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

/** What the transpiler found in a program: where the atoms of its mini-notation strings are, and the sliders and widgets it makes. */
public class ProgramInfo(
    val miniLocations: List<SourceRange>,
    val sliders: List<SliderConfig>,
    val widgets: List<WidgetConfig>,
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
