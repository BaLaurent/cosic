package cosic.audio

import kotlin.math.abs

/**
 * What the controls of an event say about how a sample plays: the numbers of `getSampleBufferSource` and `onTriggerSample`
 * (sampler.mjs:57-108, 292-376), computed without allocating (the audio thread calls them when it starts a voice).
 */
internal object Sampler {
    /**
     * The `playbackRate` of the buffer source: `abs(speed) * 2^(transpose / 12)`, times the length of the sample in seconds when `unit` is
     * `c` (the sample then lasts one cycle at `speed 1`: `loopAt`, `fit` and `splice` set it). Zero when `speed` is 0. A negative speed plays
     * the sample backwards (see [SamplePlayer]) at this rate.
     */
    fun playbackRate(p: SampleSourceParams, main: ParamSet): Double {
        val honours = p.honoursSampleControls
        val speed = if (honours) main.or(Param.speed, 1.0) else 1.0
        var rate = abs(speed) * Math.pow(2.0, p.transposeSemitones / 12.0)
        if (honours && main.str("unit") == "c") rate *= p.data.durationSeconds
        return rate
    }

    /** True when the gate of the voice is the hap's duration; false when it is the length of the slice that `begin` and `end` cut (the default). */
    fun gateIsHap(p: SampleSourceParams, main: ParamSet): Boolean =
        !p.honoursSampleControls || main.has(Param.clip) || main.has(Param.loop) || main.has(Param.release)

    /** `(end - begin) * duration / rate`: how long the slice between `begin` and `end` plays at [rate] (default `begin` 0, `end` 1). */
    fun sliceSeconds(p: SampleSourceParams, main: ParamSet, rate: Double): Double =
        (main.or(Param.end, 1.0) - main.or(Param.begin, 0.0)) * (p.data.durationSeconds / rate)

    /**
     * Whether an event can make the sample sound at all, which is checked before a voice is claimed so that an event that cannot
     * sound disturbs no other. It cannot when the sample is empty, when `speed` is 0 (Strudel's sampler returns), when the
     * rate is not a finite number, when `begin` is negative (the browser refuses such an offset) and, for the default gate, when
     * `end` is not after `begin` (the slice would last no time). A `begin` of 1 or more is allowed and plays silence.
     */
    fun canSound(p: SampleSourceParams, main: ParamSet): Boolean {
        if (p.data.frames == 0) return false
        val rate = playbackRate(p, main)
        if (!(rate > 0.0 && rate < Double.POSITIVE_INFINITY)) return false
        if (!p.honoursSampleControls) return true
        if (main.or(Param.begin, 0.0) < 0.0) return false
        if (!gateIsHap(p, main)) {
            val slice = sliceSeconds(p, main, rate)
            if (!(slice > 0.0 && slice < Double.POSITIVE_INFINITY)) return false
        }
        return true
    }
}

/**
 * Plays a sample the way an `AudioBufferSourceNode` does, for one voice: linear interpolation between frames, mono or stereo (the first
 * two channels of a file with more), reversed when `speed` is negative, with a loop region and an optional delay.
 *
 * Positions count frames of the file, in the order they are played: a negative `speed` plays the file backwards by reading `frames - 1 -
 * position`, which is exactly what reversing the buffer first does, so `begin`, `end` and `loopBegin`/`loopEnd` apply to the reversed file
 * (`begin 0.25` with a negative speed starts a quarter before the end). A file is read from `begin` to its end and not
 * only to `end`: `end` only decides how long the gate lasts (see [Sampler.sliceSeconds]), the release goes on playing past it.
 *
 * The loop region (control `loop` truthy, or the file's own for a soundfont) follows the Web Audio rule: from `loopBegin` to `loopEnd`
 * (fractions of the file, 0 and 1 by default) when that is a proper region, else the whole file; the position wraps whenever it reaches
 * the end of the region, even at the start.
 *
 * Never allocates after construction; [start] reads what it needs from the [ParamSet] of the event.
 */
internal class SamplePlayer(private val sampleRate: Int) {
    private var data = EMPTY
    private var channels = 1
    private var frames = 0
    private var playing = false
    private var base = 0
    private var direction = 1
    private var position = 0.0
    private var step = 1.0
    private var level = 1f
    private var looping = false
    private var loopStart = 0.0
    private var loopEnd = 0.0
    private var delay = 0

    /** True when the voice produces two channels: the sample has at least two. */
    var stereo = false
        private set

    /** Starts the sample [p] for an event with the controls [main]; the first [fill] begins at the event's frame. A sample that cannot sound (see [Sampler.canSound]) plays nothing. */
    fun start(p: SampleSourceParams, main: ParamSet) {
        val file = p.data
        data = file.samples
        channels = file.channels
        frames = file.frames
        stereo = channels >= 2
        val honours = p.honoursSampleControls
        val rate = Sampler.playbackRate(p, main)
        step = rate * file.sampleRate / sampleRate
        level = p.peakGain.toFloat()
        val reversed = honours && main.or(Param.speed, 1.0) < 0.0
        base = if (reversed) frames - 1 else 0
        direction = if (reversed) -1 else 1
        val begin = if (honours) main.or(Param.begin, 0.0) else 0.0
        position = begin * frames
        playing = frames > 0 && rate > 0.0 && step.isFinite() && begin >= 0.0 && position < frames
        looping = false
        if (honours) {
            if (main.has(Param.loop) && main[Param.loop] != 0.0) {
                setLoop(main.or(Param.loopBegin, 0.0) * frames, main.or(Param.loopEnd, 1.0) * frames)
            }
        } else if (!p.loopStartFrame.isNaN() && !p.loopEndFrame.isNaN()) {
            setLoop(p.loopStartFrame, p.loopEndFrame)
        }
        val delayFrames = Math.round(p.nudgeSeconds.coerceIn(0.0, MAX_DELAY_SECONDS) * sampleRate)
        delay = if (honours) delayFrames.toInt() else 0
    }

    /** The loop region `[start, end)` in frames: that one when `start >= 0`, `end > 0` and `start < end`, clipped to the file; else all of the file. */
    private fun setLoop(start: Double, end: Double) {
        looping = true
        loopStart = 0.0
        loopEnd = frames.toDouble()
        if (start >= 0.0 && end > 0.0 && start < end) {
            val clipped = minOf(end, frames.toDouble())
            if (start < clipped) {
                loopStart = start
                loopEnd = clipped
            }
        }
    }

    /**
     * Writes the next [n] frames into `l[0 until n]` (and `r` when [stereo]) and returns how many it wrote: fewer than [n] means the
     * sample has ended. The delay of `nudge` comes first as zeros. [detuneCents], when given, is added to the pitch per frame (the
     * vibrato and pitch envelope of the voice), indexed like the block.
     */
    fun fill(l: FloatArray, r: FloatArray, n: Int, detuneCents: DoubleArray? = null): Int {
        if (!playing) return 0
        var count = 0
        if (delay > 0) {
            count = minOf(delay, n)
            delay -= count
            l.fill(0f, 0, count)
            if (stereo) r.fill(0f, 0, count)
        }
        val samples = data
        val frameCount = frames
        val level = level
        val base = base
        val direction = direction
        var pos = position
        while (count < n) {
            if (looping) {
                if (pos >= loopEnd) pos = loopStart + (pos - loopStart) % (loopEnd - loopStart)
            } else if (pos >= frameCount) {
                playing = false
                break
            }
            val i0 = pos.toInt()
            val frac = (pos - i0).toFloat()
            var i1 = i0 + 1
            if (looping) {
                if (i1 >= loopEnd) i1 = loopStart.toInt()
            } else if (i1 >= frameCount) {
                i1 = i0
            }
            val s0 = base + direction * i0
            val s1 = base + direction * i1
            if (stereo) {
                val a = s0 * channels
                val b = s1 * channels
                l[count] = (samples[a] + (samples[b] - samples[a]) * frac) * level
                r[count] = (samples[a + 1] + (samples[b + 1] - samples[a + 1]) * frac) * level
            } else {
                val a = samples[s0]
                l[count] = (a + (samples[s1] - a) * frac) * level
            }
            count++
            pos += if (detuneCents == null) step else step * Math.pow(2.0, detuneCents[count - 1] / CENTS_PER_OCTAVE)
        }
        position = pos
        return count
    }

    private companion object {
        val EMPTY = FloatArray(0)

        /** A nudge is clamped to this many seconds so that its frame count stays an Int. */
        const val MAX_DELAY_SECONDS = 3600.0

        const val CENTS_PER_OCTAVE = 1200.0
    }
}
