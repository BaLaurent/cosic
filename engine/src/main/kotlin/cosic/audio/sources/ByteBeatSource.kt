package cosic.audio.sources

import cosic.audio.BLOCK_FRAMES
import cosic.pattern.Undefined
import cosic.pattern.jsToNumber

/** The fifteen beats `s("bytebeat:n")` plays when the pattern gives no `byteBeatExpression` (synth.mjs:223-237). */
internal val DEFAULT_BYTE_BEATS: List<String> = listOf(
    "(t%255 >= t/255%255)*255",
    "(t*(t*8%60 <= 300)|(-t)*(t*4%512 < 256))+t/400",
    "t",
    "t*(t >> 10^t)",
    "t&128",
    "t&t>>8",
    "((t%255+t%128+t%64+t%32+t%16+t%127.8+t%64.8+t%32.8+t%16.8)/3)",
    "((t%64+t%63.8+t%64.15+t%64.35+t%63.5)/1.25)",
    "(t&(t>>7)-t)",
    "(sin(t*PI/128)*127+127)",
    "((t^t/2+t+64*(sin((t*PI/64)+(t*PI/32768))+64))%128*2)",
    "((t^t/2+t+64*(cos >> 0))%127.85*2)",
    "((t^t/2+t+64)%128*2)",
    "(((t * .25)^(t * .25)/100+(t * .25))%128)*2",
    "((t^t/2+t+64)%7 * 24)",
)

/**
 * The sound `bytebeat` (synth.mjs:219-293, the `byte-beat-processor` worklet of worklets.mjs:852-985): a one-line program of `t` (see [compileByteBeat]) whose value
 * modulo 256 is the sample, `(value & 255) / 127.5 - 1`, times 0.2, the same on both channels.
 *
 * - **What plays.** `byteBeatExpression`, or the default beat number `n % 15` (`n` is 0 when not set; a negative or fractional `n` is no beat: the voice is silent).
 *   An expression that does not compile is silent too, as is one that makes `br()` throw.
 * - **The clock.** `t` is a counter that advances one per frame. It starts at `begin * sampleRate` (the AudioContext's time at the note's start, which for Cosic is
 *   the start frame, through the float32 of the `begin` parameter) when the pattern sets no `byteBeatStartTime`, and at 0 when it does; then the program sees
 *   `(256 / sampleRate) * frequency * t + floor(byteBeatStartTime)`, so a frequency of 187.5 Hz at 48 kHz advances one step per frame.
 * - **Timing.** Like the other source worklets it only runs on whole render quanta ([WorkletGate]); the counter starts with the first one that sounds.
 * - **Envelope.** `getParamADSR(0..1)` with the defaults `[0.001, 0.05, 0.6, 0.01]`, held for the duration of the note; the source stops 10 ms after the release.
 *
 * There is no pitch envelope, vibrato or FM on this source. Stereo.
 */
internal class ByteBeatSource(spec: SourceSpec) : BlockSource() {
    private val sampleRate = spec.sampleRate
    private val invSampleRate = 1.0 / spec.sampleRate
    private val durationSec = if (spec.durationSec.isNaN()) 0.0 else spec.durationSec
    private val program: ByteBeatFn?
    private val step: Double
    private val hasStartTime: Boolean
    private val startOffset: Double
    private val envelope = SourceEnvelope(sampleRate, spec.controls, 1.0)
    private val gate = WorkletGate(sampleRate)
    private val gains = FloatArray(BLOCK_FRAMES)

    private var beginParam = 0.0
    private var time = 0.0
    private var timeSet = false
    private var failed = false

    override val channels: Int get() = 2

    init {
        val c = spec.controls
        val n = c.number("n") ?: 0.0
        val frequency = asParam(frequencyFromValue(c))
        val raw = c["byteBeatExpression"]
        val expression: String? = when {
            raw == null || raw === Undefined -> defaultBeat(n)
            raw is String -> raw
            else -> null // `codeText.trim` is not a function
        }
        val rng = SourceRng(spec.seed)
        program = compileByteBeat(expression) { rng.nextDouble() }
        step = 256.0 * invSampleRate * frequency
        val startTime = c["byteBeatStartTime"]
        hasStartTime = startTime != null && startTime !== Undefined
        startOffset = if (hasStartTime) Math.floor(jsToNumber(startTime)) else 0.0
    }

    override fun start(startFrame: Long) {
        frame = startFrame
        val t = startFrame.toDouble() / sampleRate
        val end = envelope.schedule(t, durationSec)
        endSeconds = end
        gate.open(t, end, startFrame)
        endFrame = gate.to
        beginParam = asParam(t)
        time = 0.0
        timeSet = hasStartTime
        failed = false
    }

    override fun steal(tNew: Double) = envelope.steal(tNew)

    override fun renderBlock(left: FloatArray, right: FloatArray, offset: Int, m: Int) {
        val from = (gate.from - frame).coerceIn(0L, m.toLong()).toInt()
        val to = (gate.to - frame).coerceIn(0L, m.toLong()).toInt()
        java.util.Arrays.fill(left, offset, offset + m, 0f)
        val program = this.program
        if (to > from && program != null && !failed) {
            if (!timeSet) { // `this.t = params.begin[0] * sampleRate` in the first quantum that runs
                time = beginParam * sampleRate
                timeSet = true
            }
            try {
                var t = time
                for (k in from until to) {
                    val value = program.eval(step * t + startOffset)
                    val signal = (toInt32NoAlloc(value) and 255) / 127.5 - 1.0
                    left[offset + k] = Math.min(Math.max(signal * 0.2, -0.4), 0.4).toFloat()
                    t += 1.0
                    time = t
                }
            } catch (e: ByteBeatFailure) {
                failed = true // the processor throws: it is gone, and the node is silent
            }
        }
        envelope.fill(gains, frame, m)
        for (i in 0 until m) {
            val v = left[offset + i] * gains[i]
            left[offset + i] = v
            right[offset + i] = v
        }
    }

    private companion object {
        /** `defaultBeats[n % 15]`: undefined (so no program) unless the remainder is a whole number from 0 to 14. */
        fun defaultBeat(n: Double): String? {
            val index = n % DEFAULT_BYTE_BEATS.size
            return if (index >= 0.0 && index < DEFAULT_BYTE_BEATS.size && index == Math.floor(index)) DEFAULT_BYTE_BEATS[index.toInt()] else null
        }
    }
}
