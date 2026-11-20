package cosic.audio.sources

import java.lang.management.ManagementFactory

private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

/**
 * The fewest bytes the current thread allocates over [runs] runs of [block]. Code that allocates does so on every run; a run that has the JVM compile or
 * deoptimise something in the middle can allocate a few bytes once, which this leaves out.
 */
internal fun steadyAllocation(runs: Int = 12, block: () -> Unit): Long {
    var least = Long.MAX_VALUE
    repeat(runs) {
        val before = threads.currentThreadAllocatedBytes
        block()
        least = minOf(least, threads.currentThreadAllocatedBytes - before)
    }
    return least
}

/**
 * Plays [frames] frames of [wave] at [hz] in blocks of [blockSize]. [fillBus] is called at the start of every block (after the bus was cleared) with the first
 * frame of the block and its length, to add the pitch inputs of that block.
 */
internal fun renderOscillator(
    wave: PeriodicWave,
    hz: Double,
    frames: Int,
    blockSize: Int = 256,
    bus: PitchBus? = null,
    fillBus: ((bus: PitchBus, firstFrame: Int, n: Int) -> Unit)? = null,
): FloatArray {
    val osc = PeriodicOscillator(wave)
    val out = FloatArray(frames)
    val block = FloatArray(blockSize)
    var frame = 0
    while (frame < frames) {
        val n = minOf(blockSize, frames - frame)
        if (bus != null) {
            bus.clear(n)
            fillBus?.invoke(bus, frame, n)
        }
        osc.render(block, n, hz, bus)
        System.arraycopy(block, 0, out, frame, n)
        frame += n
    }
    return out
}

/**
 * The coefficients `PeriodicWave`s of the Chromium oracle were made of when its `noiseCount` is not 0 (`noise` in tools/strudel-ref/oscillator-oracle.mjs): a
 * linear congruential generator (`seed * 1664525 + 1013904223` modulo 2^32) gives `count` numbers `x / 2^32 - 0.5` for `real` and then `count` for `imag`,
 * each rounded to float32.
 */
internal fun noiseCoefficients(seed: Long, count: Int): Pair<DoubleArray, DoubleArray> {
    var state = seed.toInt()
    fun next(): Double {
        state = state * 1664525 + 1013904223
        return ((state.toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0 - 0.5).toFloat().toDouble()
    }
    val real = DoubleArray(count) { next() }
    val imag = DoubleArray(count) { next() }
    return real to imag
}

/** The wave an oracle case was rendered with. */
internal fun OscillatorOracleCase.wave(): PeriodicWave = when (wave) {
    "custom" -> {
        val (r, i) = if (noiseCount > 0) noiseCoefficients(noiseSeed, noiseCount) else real to imag
        PeriodicWave.custom(r, i, sampleRate)
    }
    else -> PeriodicWave.basic(BasicWave.valueOf(wave.uppercase()), sampleRate)
}

/**
 * Renders an oracle case the way [OscillatorOracleVectors] describes it: the frequency, the constant detune, and the modulators as pitch inputs of the bus
 * (a sine oscillator times a gain, added into `frequencyAddHz` or `detuneCents`; the detune ramp).
 */
internal fun renderOracleCase(c: OscillatorOracleCase, blockSize: Int = 256): FloatArray {
    val bus = PitchBus(blockSize)
    val sine = PeriodicWave.basic(BasicWave.SINE, c.sampleRate)
    val fm = if (c.fmGain != 0.0) PeriodicOscillator(sine) else null
    val vibrato = if (c.vibratoCents != 0.0) PeriodicOscillator(sine) else null
    val tmp = FloatArray(blockSize)
    return renderOscillator(c.wave(), c.frequencyHz, c.frames, blockSize, bus) { b, first, n ->
        if (fm != null) {
            fm.render(tmp, n, c.fmHz, null)
            for (i in 0 until n) b.frequencyAddHz[i] += tmp[i] * c.fmGain
            b.frequencyActive = true
        }
        if (vibrato != null) {
            vibrato.render(tmp, n, c.vibratoHz, null)
            for (i in 0 until n) b.detuneCents[i] += tmp[i] * c.vibratoCents
            b.detuneActive = true
        }
        if (c.detuneCents != 0.0) {
            for (i in 0 until n) b.detuneCents[i] += c.detuneCents
            b.detuneActive = true
        }
        if (c.rampEndSec > 0.0) {
            for (i in 0 until n) {
                val t = (first + i).toDouble() / c.sampleRate
                b.detuneCents[i] += if (t >= c.rampEndSec) c.rampToCents else c.rampFromCents + (c.rampToCents - c.rampFromCents) * t / c.rampEndSec
            }
            b.detuneActive = true
        }
    }
}
