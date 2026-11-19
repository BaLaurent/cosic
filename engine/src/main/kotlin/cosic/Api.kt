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
