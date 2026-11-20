package cosic.audio

/*
 * Web Audio's AudioParam automation (spec §1.6.2-1.6.3, Chromium's AudioParamTimeline), which superdough builds every
 * envelope, steal ramp and cut on. [ParamTimeline] is the event list, [AParam] a parameter made of a base value, a timeline
 * and the modulators connected to it.
 */

private const val SET = 0
private const val LINEAR = 1
private const val EXPONENTIAL = 2

/**
 * The events of one parameter: `setValueAtTime`, `linearRampToValueAtTime` and `exponentialRampToValueAtTime`, kept sorted by time
 * (events at the same time stay in insertion order). A ramp interpolates from the event before it to its own time; an
 * exponential ramp from a value of 0, or between values of opposite signs, holds the first value until the ramp's time. Outside
 * the events the value is the one of the nearest event (the last one after them), or the parameter's `base` before the first.
 * Times are absolute seconds.
 *
 * Inserting never allocates until the [capacity] is used up; then the arrays double. Give a parameter that gets an event per
 * note for ever (a delay time) a [pruneBefore] call every now and then.
 */
internal class ParamTimeline(capacity: Int = 12) {
    private var times = DoubleArray(capacity)
    private var values = DoubleArray(capacity)
    private var types = ByteArray(capacity)
    private var count = 0

    /** Index of the last event at or before the time [fillBlock] evaluated last, -1 when there was none. */
    private var cursor = -1

    val isEmpty: Boolean get() = count == 0

    /** The number of events. */
    val size: Int get() = count

    /** The time of the last event, or negative infinity when there is none. */
    val lastTime: Double get() = if (count == 0) Double.NEGATIVE_INFINITY else times[count - 1]

    fun clear() {
        count = 0
        cursor = -1
    }

    /** `setValueAtTime(v, t)`. Events with a time or value that is not a finite number are ignored. */
    fun setValueAtTime(v: Double, t: Double) {
        if (!usable(v, t)) return
        insert(SET, v, t)
    }

    /**
     * `linearRampToValueAtTime(v, t)`. When no event comes before the ramp, it starts from [base] at [now], as if `setValueAtTime(base, now)`
     * had been called first (the ramp starts at [now] and so never later than [t]).
     */
    fun linearRampToValueAtTime(v: Double, t: Double, now: Double, base: Double) {
        if (!usable(v, t)) return
        insertRamp(LINEAR, v, t, now, base)
    }

    /** `exponentialRampToValueAtTime(v, t)`; throws [IllegalArgumentException] for a target of 0, which the Web Audio API rejects too. */
    fun exponentialRampToValueAtTime(v: Double, t: Double, now: Double, base: Double) {
        require(v != 0.0) { "exponentialRampToValueAtTime: the target value must not be 0" }
        if (!usable(v, t)) return
        insertRamp(EXPONENTIAL, v, t, now, base)
    }

    /** `cancelScheduledValues(t)`: drops every event at or after [t]. */
    fun cancelScheduledValues(t: Double) {
        var k = count
        while (k > 0 && times[k - 1] >= t) k--
        count = k
        cursor = -1
    }

    /**
     * Drops the events that can no longer matter at or after time [t]: everything before the last event that is at or before
     * [t], which is the one that sets the value there.
     */
    fun pruneBefore(t: Double) {
        var k = -1
        while (k + 1 < count && times[k + 1] <= t) k++
        if (k <= 0) return
        System.arraycopy(times, k, times, 0, count - k)
        System.arraycopy(values, k, values, 0, count - k)
        System.arraycopy(types, k, types, 0, count - k)
        count -= k
        cursor = -1
    }

    /** The value at time [t] (§1.9.1); [base] is what the parameter holds before the first event. */
    fun valueAt(t: Double, base: Double): Double {
        if (count == 0) return base
        var lo = 0
        var hi = count - 1
        var k = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) {
                k = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return segmentValue(k, t, base)
    }

    /**
     * Writes the value at frame `startFrame + i` (time `(startFrame + i) / sampleRate`) into `out[i]` for `i` in `[0, n)`.
     * Keeps a cursor, so successive blocks cost O(frames + events).
     */
    fun fillBlock(out: FloatArray, startFrame: Long, sampleRate: Int, n: Int, base: Double) {
        if (count == 0) {
            java.util.Arrays.fill(out, 0, n, base.toFloat())
            return
        }
        var c = cursor
        val first = startFrame.toDouble() / sampleRate
        if (c >= 0 && times[c] > first) c = -1
        for (i in 0 until n) {
            val tau = (startFrame + i).toDouble() / sampleRate
            while (c + 1 < count && times[c + 1] <= tau) c++
            out[i] = segmentValue(c, tau, base).toFloat()
        }
        cursor = c
    }

    private fun usable(v: Double, t: Double): Boolean = v - v == 0.0 && t - t == 0.0

    private fun insertRamp(type: Int, v: Double, t: Double, now: Double, base: Double) {
        if (positionFor(t) == 0 && base - base == 0.0 && now - now == 0.0) insert(SET, base, Math.min(now, t))
        insert(type, v, t)
    }

    /** Where an event at [t] goes: after every event that is not later than it. */
    private fun positionFor(t: Double): Int {
        var pos = count
        while (pos > 0 && times[pos - 1] > t) pos--
        return pos
    }

    private fun insert(type: Int, v: Double, t: Double) {
        if (count == times.size) grow()
        val pos = positionFor(t)
        if (pos < count) {
            System.arraycopy(times, pos, times, pos + 1, count - pos)
            System.arraycopy(values, pos, values, pos + 1, count - pos)
            System.arraycopy(types, pos, types, pos + 1, count - pos)
        }
        times[pos] = t
        values[pos] = v
        types[pos] = type.toByte()
        count++
        cursor = -1
    }

    private fun grow() {
        val size = times.size * 2
        times = times.copyOf(size)
        values = values.copyOf(size)
        types = types.copyOf(size)
    }

    /** The value at [t] given that event [k] is the last one at or before it (-1: none). */
    private fun segmentValue(k: Int, t: Double, base: Double): Double {
        if (k < 0) return base
        val next = k + 1
        if (next < count) {
            val type = types[next].toInt()
            if (type != SET) {
                val v0 = values[k]
                val v1 = values[next]
                val x = (t - times[k]) / (times[next] - times[k])
                if (type == LINEAR) return v0 + (v1 - v0) * x
                return if (v0 == 0.0 || v0 * v1 < 0.0) v0 else v0 * Math.pow(v1 / v0, x)
            }
        }
        return values[k]
    }
}

/**
 * A signal that adds itself to a parameter: an LFO, an envelope or a bus tap connected to an [AParam].
 *
 * [addInto] adds the signal of the block `ctx` into `out[0 until ctx.n]`. It may be called any number of times for the same
 * block (one modulator feeds several parameters, and a block-rate reader may look at it as well) and must add the same signal
 * every time, which is what [BlockModulator] provides.
 */
internal fun interface Modulator {
    fun addInto(out: FloatArray, ctx: BlockContext)
}

/**
 * A [Modulator] that renders its block once, into [buf], and adds that copy for every further request for the same block.
 * Subclasses fill `buf[0 until ctx.n]` in [render] and call [invalidate] when they are restarted.
 */
internal abstract class BlockModulator(capacity: Int = BLOCK_FRAMES) : Modulator {
    protected val buf = FloatArray(capacity)
    private var cachedStart = Long.MIN_VALUE
    private var cachedN = -1

    final override fun addInto(out: FloatArray, ctx: BlockContext) {
        if (cachedStart != ctx.startFrame || cachedN != ctx.n) {
            cachedStart = ctx.startFrame
            cachedN = ctx.n
            render(ctx)
        }
        for (i in 0 until ctx.n) out[i] += buf[i]
    }

    /** Fills `buf[0 until ctx.n]` with the signal of the block. Called once per block. */
    protected abstract fun render(ctx: BlockContext)

    /** Forgets the cached block; call it whenever the modulator is started again. */
    protected fun invalidate() {
        cachedStart = Long.MIN_VALUE
        cachedN = -1
    }
}

/**
 * An `AudioParam`: its [base] value (the intrinsic `value`), the automation [timeline] and the [Modulator]s connected to it. The
 * computed value is `timeline (or base) + sum of the inputs`, clamped to `[min, max]` (the nominal range of the parameter).
 * Parameters live as long as their stage; [reset] makes one ready for the next voice.
 */
internal class AParam(var base: Double, val min: Double, val max: Double, capacity: Int = 12) {
    val timeline = ParamTimeline(capacity)
    private val inputs = arrayOfNulls<Modulator>(MAX_MODS)
    private var inputCount = 0

    private val minF = min.toFloat()
    private val maxF = max.toFloat()

    /** True while the parameter has no events and no inputs: its value is [constantValue] for ever. */
    val isConstant: Boolean get() = timeline.isEmpty && inputCount == 0

    /** True when modulators are connected. */
    val hasInputs: Boolean get() = inputCount > 0

    /** The base value, clamped to the range: what the parameter is while it is [isConstant]. */
    val constantValue: Double get() = clampToRange(base)

    /** Back to a plain parameter with value [base]: no events, no inputs. */
    fun reset(base: Double) {
        this.base = base
        timeline.clear()
        clearInputs()
    }

    /** Connects [m]; the 9th input of a parameter is dropped. */
    fun addInput(m: Modulator) {
        if (inputCount < inputs.size) inputs[inputCount++] = m
    }

    fun clearInputs() {
        for (i in 0 until inputCount) inputs[i] = null
        inputCount = 0
    }

    /** The automation at time [t], without the inputs, clamped to the range. */
    fun valueAt(t: Double): Double = clampToRange(timeline.valueAt(t, base))

    /**
     * The value for every frame of the block: `out[i]` is the parameter at frame `ctx.startFrame + i`, for `i` in `[0, ctx.n)`
     * (a-rate). Inputs are added after the timeline, and the sum is clamped to the range.
     */
    fun fill(out: FloatArray, ctx: BlockContext) {
        val n = ctx.n
        timeline.fillBlock(out, ctx.startFrame, ctx.sampleRate, n, base)
        if (inputCount > 0) for (k in 0 until inputCount) inputs[k]!!.addInto(out, ctx)
        if (minF > -3.0e38f || maxF < 3.0e38f || inputCount > 0) {
            for (i in 0 until n) {
                val v = out[i]
                out[i] = if (v < minF) minF else if (v > maxF) maxF else v
            }
        }
    }

    /**
     * The value at frame [i] of the block, inputs included: what a k-rate or block-rate reader (a compressor, a worklet that
     * reads index 0) sees at that frame.
     */
    fun sampleAt(ctx: BlockContext, i: Int): Double {
        if (inputCount == 0) return clampToRange(timeline.valueAt(ctx.time(i), base))
        val tmp = ctx.borrow()
        fill(tmp, ctx)
        val v = tmp[i].toDouble()
        ctx.release()
        return v
    }

    /** The value at the first frame of the block. */
    fun blockStart(ctx: BlockContext): Double = sampleAt(ctx, 0)

    fun setValueAtTime(v: Double, t: Double) = timeline.setValueAtTime(v, t)

    /** The ramp starts from [base] at [now] when no event comes before it. */
    fun linearRampToValueAtTime(v: Double, t: Double, now: Double) = timeline.linearRampToValueAtTime(v, t, now, base)

    fun exponentialRampToValueAtTime(v: Double, t: Double, now: Double) = timeline.exponentialRampToValueAtTime(v, t, now, base)

    fun cancelScheduledValues(t: Double) = timeline.cancelScheduledValues(t)

    private fun clampToRange(v: Double): Double = if (v < min) min else if (v > max) max else v
}
