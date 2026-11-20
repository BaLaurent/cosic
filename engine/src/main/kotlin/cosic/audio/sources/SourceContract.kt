package cosic.audio.sources

import cosic.pattern.Undefined
import cosic.pattern.jsToNumber

/**
 * Read access to the controls of one event, as a sound source sees them: by Strudel's canonical name, with the values the pattern
 * produced (`Double`, `String`, `Boolean`, `List`, or null when the control is absent).
 */
internal interface SourceControls {
    operator fun get(key: String): Any?
}

/** The controls of a hap value as [SourceControls]. */
internal fun Map<String, Any?>.asSourceControls(): SourceControls = object : SourceControls {
    override fun get(key: String): Any? = this@asSourceControls[key]
}

/** The control as a number: a `Double`, a string that reads as one, `true` and `false` as 1 and 0; null when absent, NaN or anything else. */
internal fun SourceControls.number(key: String): Double? {
    val v = get(key)
    if (v == null || v === Undefined) return null
    val d = when (v) {
        is Boolean, is Number, is String -> jsToNumber(v)
        else -> return null
    }
    return if (d.isNaN()) null else d
}

/** The control as text, or null when it is not a string. */
internal fun SourceControls.text(key: String): String? = get(key) as? String

/** The control as a list of numbers (a list value, each element read as a number), or null when it is not a list. */
internal fun SourceControls.list(key: String): DoubleArray? {
    val v = get(key) as? List<*> ?: return null
    return DoubleArray(v.size) { jsToNumber(v[it]) }
}

/** True when the control is present (not null and not undefined). */
internal fun SourceControls.isSet(key: String): Boolean = get(key).let { it != null && it !== Undefined }

/**
 * What a source needs to be created: the engine's [sampleRate], the event's [controls], the length of the event in seconds ([durationSec],
 * `Hap.duration / cps`, `clip` included), the tempo and a [seed] that makes noise reproducible.
 */
internal class SourceSpec(val sampleRate: Int, val controls: SourceControls, val durationSec: Double, val cps: Double, val seed: Long)

/**
 * One sound source of the engine: what `onTrigger` of a registered sound returns (an oscillator, a worklet voice, a wavetable),
 * as audio the voice then runs through its chain. A source renders everything of its own: its envelope and its constant gains. It is
 * created for one event, started once, rendered until it is [isFinished], and dropped.
 */
internal interface SoundSource {
    /** 1 when the source writes only `left`, 2 when it writes both channels. */
    val channels: Int

    /** True once the source has produced its last frame. */
    val isFinished: Boolean

    /** Starts the source; [startFrame] is the absolute engine frame of the first frame it renders. Allocation-free. */
    fun start(startFrame: Long)

    /** Overwrites `left[0 until n]` (and `right`, when stereo) and returns how many frames it produced; fewer than [n] means it finished. */
    fun render(left: FloatArray, right: FloatArray, n: Int): Int
}
