package cosic.kabel

/*
 * The ugens of @kabelsalat/lib@0.4.1 ugens.js, ported literally (AGPL-3.0-or-later): one object per node of the graph that needs state. Each reads its
 * inputs from the register file ([update]: the registers `r[a[o]] .. r[a[o + n - 1]]`) and returns its output for one sample. Where the original reads the
 * global `sampleRate` or a constant fixed at 48 kHz (`ISR`, the unit of `lag`), so do these.
 */

/** What every ugen of the sample being computed can see: the time of the sample, the input of the voice and whether a ugen has failed. */
internal class SampleCtx {
    @JvmField var time = 0.0
    @JvmField var input = 0.0

    /** A ugen of the original throws (the two-pole filter on NaN): the voice is dead from here on. */
    @JvmField var fault = false
}

internal abstract class Ugen {
    abstract fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double
}

/** `ISR = 1 / 48000` of ugens.js:11, used by `dust` and `slew` whatever the sample rate. */
internal const val ISR = 1.0 / 48000.0

internal class AdsrU : Ugen() {
    private var state = 0 // 0 off, 1 attack, 2 decay, 3 sustain, 4 release
    private var startTime = 0.0
    private var startVal = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val curTime = c.time
        val gate = r[a[o]]
        val attack = r[a[o + 1]]
        val decay = r[a[o + 2]]
        val susVal = r[a[o + 3]]
        val release = r[a[o + 4]]
        when (state) {
            0 -> {
                if (gate > 0) {
                    state = 1
                    startTime = curTime
                    startVal = 0.0
                }
                return 0.0
            }
            1 -> {
                val time = curTime - startTime
                if (time > attack) {
                    state = 2
                    startTime = curTime
                    return 1.0
                }
                return lerpS(time / attack, startVal, 1.0)
            }
            2 -> {
                val time = curTime - startTime
                val curVal = lerpS(time / decay, 1.0, susVal)
                if (gate <= 0) {
                    state = 4
                    startTime = curTime
                    startVal = curVal
                    return curVal
                }
                if (time > decay) {
                    state = 3
                    startTime = curTime
                    return susVal
                }
                return curVal
            }
            3 -> {
                if (gate <= 0) {
                    state = 4
                    startTime = curTime
                    startVal = susVal
                }
                return susVal
            }
            else -> {
                val time = curTime - startTime
                if (time > release) {
                    state = 0
                    return 0.0
                }
                val curVal = lerpS(time / release, startVal, 0.0)
                if (gate > 0) {
                    state = 1
                    startTime = curTime
                    startVal = curVal
                }
                return curVal
            }
        }
    }
}

internal class ClockU(sampleRate: Int) : Ugen() {
    private val sampleTime = 1.0 / sampleRate
    private var phase = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val freq = (24.0 * r[a[o]]) / 60.0
        phase += sampleTime * freq
        val cyclePos = phase % 1.0
        return if (cyclePos < 0.5) 1.0 else -1.0 // high first, so that it triggers at once
    }
}

internal class ClockDivU : Ugen() {
    private var inSgn = true
    private var outSgn = true
    private var clockCnt = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val curSgn = r[a[o]] > 0
        if (inSgn != curSgn) {
            clockCnt++
            if (clockCnt >= r[a[o + 1]]) {
                clockCnt = 0.0
                outSgn = !outSgn
            }
        }
        inSgn = curSgn
        return if (outSgn) 1.0 else -1.0
    }
}

internal class DistortU : Ugen() {
    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val amount = Math.min(Math.max(r[a[o + 1]], 0.0), 1.0) - 0.01
        val k = (2 * amount) / (1 - amount)
        val x = r[a[o]]
        return ((1 + k) * x) / (1 + k * Math.abs(x))
    }
}

internal class HoldU : Ugen() {
    private var value = 0.0
    private var trigSgn = false

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val trig = r[a[o + 1]]
        if (!trigSgn && trig > 0) value = r[a[o]]
        trigSgn = trig > 0
        return value
    }
}

/** `NoiseOsc`: white noise; draws once when it is made, then whenever `next` is not 0. */
internal class NoiseU(private val rng: KabelRandom) : Ugen() {
    private var value = rng.nextDouble() * 2 - 1

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        if (!truthy(r[a[o]])) return value
        value = rng.nextDouble() * 2 - 1
        return value
    }
}

/** `LcgNoise`: a linear congruential generator whose seed is the number of generators made so far in the worklet (`NOISE_CHANNELS`). */
internal class LcgNoiseU(runtime: KabelRuntime) : Ugen() {
    private val startSeed: Long
    private var state: Long
    private var value = 0.0

    init {
        val channel = runtime.noiseChannels++
        startSeed = (340L * (channel + 1)) and 0xFFFFFFFFL
        state = startSeed
    }

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        if (!truthy(r[a[o]])) return value
        if (truthy(r[a[o + 1]])) state = startSeed
        state = (state * 1664525L + 1013904223L) and 0xFFFFFFFFL
        val y = (state and 0x00FFFFFFL).toDouble() * (1.0 / 16777216.0)
        value = y * 2 - 1
        return value
    }
}

internal class DustU(private val rng: KabelRandom) : Ugen() {
    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double =
        if (rng.nextDouble() < r[a[o]] * ISR) rng.nextDouble() else 0.0
}

internal class BrownU(private val rng: KabelRandom) : Ugen() {
    private var out = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val white = rng.nextDouble() * 2 - 1
        out = (out + 0.02 * white) / 1.02
        return out
    }
}

internal class PinkU(private val rng: KabelRandom) : Ugen() {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var b3 = 0.0
    private var b4 = 0.0
    private var b5 = 0.0
    private var b6 = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val white = rng.nextDouble() * 2 - 1
        b0 = 0.99886 * b0 + white * 0.0555179
        b1 = 0.99332 * b1 + white * 0.0750759
        b2 = 0.969 * b2 + white * 0.153852
        b3 = 0.8665 * b3 + white * 0.3104856
        b4 = 0.55 * b4 + white * 0.5329522
        b5 = -0.7616 * b5 - white * 0.016898
        val pink = b0 + b1 + b2 + b3 + b4 + b5 + b6 + white * 0.5362
        b6 = white * 0.115926
        return pink * 0.11
    }
}

internal class ImpulseU(sampleRate: Int) : Ugen() {
    private val sampleTime = 1.0 / sampleRate
    private var phase = 1.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        phase += sampleTime * r[a[o]]
        val v = if (phase >= 1) 1.0 else 0.0
        phase %= 1.0
        return v
    }
}

internal class PulseU(sampleRate: Int) : Ugen() {
    private val sampleTime = 1.0 / sampleRate
    private var phase = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        phase += sampleTime * r[a[o]]
        val cyclePos = phase % 1.0
        return if (cyclePos < r[a[o + 1]]) 1.0 else -1.0
    }
}

internal class ZawU(sampleRate: Int) : Ugen() {
    private val sampleTime = 1.0 / sampleRate
    private var phase = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        phase += sampleTime * r[a[o]]
        return (phase % 1.0) * 2 - 1
    }
}

/** `SawOsc`: a sawtooth with polyBLEP, starting at a random phase and reading the global sample rate. */
internal class SawU(private val sampleRate: Int, rng: KabelRandom) : Ugen() {
    private var phase = rng.nextDouble()

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val dt = r[a[o]] / sampleRate
        val p = polyBlep(phase, dt)
        val s = 2 * phase - 1 - p
        phase += dt
        if (phase > 1) phase -= 1
        return s
    }
}

internal class SineU(sampleRate: Int) : Ugen() {
    private val sampleTime = 1.0 / sampleRate
    private var phase = 0.0
    private var syncSgn = false

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val sync = r[a[o + 1]]
        if (!syncSgn && sync > 0) phase = 0.0
        syncSgn = sync > 0
        val cyclePos = (phase + r[a[o + 2]]) % 1.0
        phase += sampleTime * r[a[o]]
        return Math.sin(cyclePos * 2 * Math.PI) // the phase before this sample's increment
    }
}

internal class TriU(sampleRate: Int) : Ugen() {
    private val sampleTime = 1.0 / sampleRate
    private var phase = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        phase += sampleTime * r[a[o]]
        val cyclePos = phase % 1.0
        val normVal = if (cyclePos < 0.5) 2 * cyclePos else 1 - 2 * (cyclePos - 0.5)
        return normVal * 2 - 1
    }
}

/** `Lag`: its rate is in units of 4410 samples whatever the sample rate. */
internal class LagU : Ugen() {
    private var s = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        var rate = r[a[o + 1]] * 4410.0
        if (rate < 1) rate = 1.0
        s += (1 / rate) * (r[a[o]] - s)
        return s
    }
}

internal class SlideU : Ugen() {
    private var s = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        var rate = r[a[o + 1]] * 1000
        if (rate < 1) rate = 1.0
        s += (1 / rate) * (r[a[o]] - s)
        return s
    }
}

internal class SlewU : Ugen() {
    private var last = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val upStep = r[a[o + 1]] * ISR
        val downStep = r[a[o + 2]] * ISR
        var delta = r[a[o]] - last
        if (delta > upStep) delta = upStep else if (delta < -downStep) delta = -downStep
        last += delta
        return last
    }
}

/** `Filter` (the output is the second state) and `BPF` (the first): the two-pole filter of synth.js, which throws on a NaN input. */
internal class TwoPoleU(private val band: Boolean) : Ugen() {
    private var s0 = 0.0
    private var s1 = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val s = r[a[o]]
        if (s.isNaN()) {
            c.fault = true // `assert(!isNaN(s))`
            return Double.NaN
        }
        val cutoff = Math.min(r[a[o + 1]], 1.0)
        val resonance = Math.max(r[a[o + 2]], 0.0)
        val cc = Math.pow(0.5, (1 - cutoff) / 0.125)
        val rr = Math.pow(0.5, (resonance + 0.125) / 0.125)
        val mrc = 1 - rr * cc
        var v0 = s0
        var v1 = s1
        v0 = mrc * v0 - cc * v1 + cc * s
        v1 = mrc * v1 + cc * v0
        s0 = v0
        s1 = v1
        return if (band) s0 else s1
    }
}

internal class FoldU : Ugen() {
    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        var rate = r[a[o + 1]]
        if (rate < 0) rate = 0.0
        rate += 1
        val input = r[a[o]] * rate
        return 4 * (Math.abs(0.25 * input + 0.25 - kabelRound(0.25 * input + 0.25)) - 0.25)
    }
}

/** `Sequence`: steps on the rising edge of the clock; `n - 1` steps follow the clock input. */
internal class SequenceU : Ugen() {
    private var clockSgn = true
    private var step = 0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val clock = r[a[o]]
        val len = n - 1
        if (len <= 0) return Double.NaN
        if (!clockSgn && clock > 0) {
            step = (step + 1) % len
            clockSgn = clock > 0
            return 0.0 // the first sample of a step is zero, which retriggers gates
        }
        clockSgn = clock > 0
        return r[a[o + 1 + step]]
    }
}

internal class PickU : Ugen() {
    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val len = n - 1
        if (len <= 0) return Double.NaN
        val i = (r[a[o]] % len) + len // modulo that works with negative numbers
        val k = Math.floor(i) % len
        return if (k.isNaN()) Double.NaN else r[a[o + 1 + k.toInt()]]
    }
}

internal class RemapU : Ugen() {
    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val norm = invLerp(r[a[o]], r[a[o + 1]], r[a[o + 2]])
        return lerpU(norm, r[a[o + 3]], r[a[o + 4]])
    }
}

internal class ClipU : Ugen() {
    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double =
        Math.min(Math.max(r[a[o]], r[a[o + 1]]), r[a[o + 2]])
}

internal class TrigU : Ugen() {
    private var hi = false

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val input = r[a[o]]
        if (!hi && input > 0) {
            hi = true
            return 1.0
        }
        if (hi && input <= 0) hi = false
        return 0.0
    }
}

/** `CC`: a value that something outside sets; here only the gate of the voice (`strudel-gate-*`) is ever set. */
internal class CcU(init: Double) : Ugen() {
    @JvmField var value = init

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double = value
}

/** The ugens that read a MIDI device (`midifreq`, `midigate`, `midivel`) output 0 while no note is held, and `midicc` its initial value: no MIDI reaches the worklet. */
internal class ConstU(private val v: Double) : Ugen() {
    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double = v
}

internal class DelayU(private val line: DelayLine) : Ugen() {
    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        line.write(r[a[o]], r[a[o + 1]])
        return line.read()
    }
}

/** `BiquadFilter`: the cookbook filter with its coefficients worked out at every sample from the inputs; the global sample rate. */
internal class BiquadU(private val sampleRate: Double) : Ugen() {
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var a0 = 1.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0

    override fun update(c: SampleCtx, r: DoubleArray, a: IntArray, o: Int, n: Int): Double {
        val input = r[a[o]]
        val type = r[a[o + 1]]
        val freq = r[a[o + 2]]
        var q = r[a[o + 3]]
        val gain = r[a[o + 4]]
        val omega = (2 * Math.PI * freq) / sampleRate
        val sinOmega = Math.sin(omega)
        q = Math.pow(10.0, q / 20) // like web audio
        val alpha = sinOmega / (2.0 * q)
        val cosOmega = Math.cos(omega)
        if (type == 0.0) {
            b1 = 1 - cosOmega
            b0 = b1 / 2
            b2 = b0
            a0 = 1 + alpha
            a1 = -2 * cosOmega
            a2 = 1 - alpha
        } else if (type == 1.0) {
            b0 = (1 + cosOmega) / 2
            b1 = -(1 + cosOmega)
            b2 = b0
            a0 = 1 + alpha
            a1 = -2 * cosOmega
            a2 = 1 - alpha
        } else if (type == 2.0) {
            b0 = sinOmega / 2
            b1 = 0.0
            b2 = -b0
            a0 = 1 + alpha
            a1 = -2 * cosOmega
            a2 = 1 - alpha
        } else if (type == 3.0) {
            b0 = 1.0
            b1 = -2 * cosOmega
            b2 = 1.0
            a0 = 1 + alpha
            a1 = -2 * cosOmega
            a2 = 1 - alpha
        } else if (type == 4.0) {
            b0 = 1 - alpha
            b1 = -2 * cosOmega
            b2 = 1 + alpha
            a0 = 1 + alpha
            a1 = -2 * cosOmega
            a2 = 1 - alpha
        } else if (type == 5.0) {
            val big = Math.pow(10.0, gain / 40)
            b0 = 1 + alpha * big
            b1 = -2 * cosOmega
            b2 = 1 - alpha * big
            a0 = 1 + alpha / big
            a1 = -2 * cosOmega
            a2 = 1 - alpha / big
        } else if (type == 6.0) {
            val big = Math.pow(10.0, gain / 40)
            val sqrt2Aa = 2 * Math.sqrt(big) * alpha
            val am1w0 = (big - 1) * cosOmega
            val ap1w0 = (big + 1) * cosOmega
            b0 = big * (big + 1 - am1w0 + sqrt2Aa)
            b1 = 2 * big * (big - 1 - ap1w0)
            b2 = big * (big + 1 - am1w0 - sqrt2Aa)
            a0 = big + 1 + am1w0 + sqrt2Aa
            a1 = -2 * (big - 1 + ap1w0)
            a2 = big + 1 + am1w0 - sqrt2Aa
        } else if (type == 7.0) {
            val big = Math.pow(10.0, gain / 40)
            val sqrt2Aa = 2 * Math.sqrt(big) * alpha
            val am1w0 = (big - 1) * cosOmega
            val ap1w0 = (big + 1) * cosOmega
            b0 = big * (big + 1 + am1w0 + sqrt2Aa)
            b1 = -2 * big * (big - 1 + ap1w0)
            b2 = big * (big + 1 + am1w0 - sqrt2Aa)
            a0 = big + 1 - am1w0 + sqrt2Aa
            a1 = 2 * (big - 1 - ap1w0)
            a2 = big + 1 - am1w0 - sqrt2Aa
        }
        b0 /= a0
        b1 /= a0
        b2 /= a0
        a1 /= a0
        a2 /= a0
        a0 = 1.0
        val output = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1
        x1 = input
        y2 = y1
        y1 = output
        return output
    }
}
