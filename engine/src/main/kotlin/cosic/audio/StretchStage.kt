package cosic.audio

import cosic.audio.dsp.PhaseVocoder
import cosic.pattern.Undefined
import cosic.pattern.logger

private const val MAX_PARAM = 3.4e38

/**
 * How many frames before its onset a voice with `stretch` has to start (superdough.mjs:468-473). The phase vocoder delays what goes through it, so
 * superdough starts the whole event 0.04 s early, which is the latency of the worklet at 48 kHz, and the sound comes out where the pattern put it.
 * Here the same is done with the latency of the streaming vocoder, [PhaseVocoder.STREAM_LATENCY_FRAMES] frames (42.7 ms at 48 kHz: the worklet's 1920
 * frames plus the quantum that the streaming form waits for), so the onset is exact at every sample rate. Like superdough it looks at the event's own
 * `stretch` only, not at those of its `FX(...)` elements, and it applies to whatever `stretch` is, 0 included. The scheduler subtracts this from the
 * start frame of the event; the voice then runs on the earlier clock, `t` of every envelope and effect included, as in superdough.
 */
internal fun stretchLeadFrames(controls: Map<String, Any?>): Int {
    val stretch = controls["stretch"]
    return if (stretch != null && stretch !== Undefined) PhaseVocoder.STREAM_LATENCY_FRAMES else 0
}

/**
 * Stage 2 of the chain, the `phase-vocoder-processor` behind `stretch` (superdough.mjs:649-653, worklets.mjs:595-748): a pitch shifter that
 * moves the spectral peaks of the signal, 0 leaving the pitch alone, 1 an octave up, -2 an octave down. It runs as an [PhaseVocoder] taken from the
 * shared pool when the voice starts and given back when it ends. The processor keeps the channels as they are (a mono voice stays mono) and delays the
 * sound by [PhaseVocoder.STREAM_LATENCY_FRAMES] frames; [stretchLeadFrames] is what makes the voice start that much earlier.
 *
 * The control is the AudioParam `pitchFactor` of the node, which other modulators may target (`stretch.pitchFactor`); the processor reads the last value of
 * each render quantum, so a parameter that moves is read at the last frame of the quantum.
 */
internal class StretchStage(@Suppress("UNUSED_PARAMETER") sampleRate: Int, private val pool: GrowablePool<PhaseVocoder>) : ChainStage {
    /** The `pitchFactor` parameter: the control as given; the processor maps it (`+ 1`, a quarter of it when negative). */
    val pitchFactor = AParam(1.0, -MAX_PARAM, MAX_PARAM, 4)

    private var vocoder: PhaseVocoder? = null

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        if (!p.has(Param.stretch)) return false
        val taken = pool.take()
        if (taken == null) {
            logger("[superdough] error: no phase vocoder left for stretch ($VOCODER_LIMIT are in use); the event plays without it", "error", null)
            return false
        }
        taken.reset()
        vocoder = taken
        pitchFactor.reset(p[Param.stretch])
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val processor = vocoder ?: return
        val n = ctx.n
        val right = if (b.channels == 2) b.r else null
        if (pitchFactor.isConstant) {
            processor.process(b.l, right, 0, n, ctx.startFrame, pitchFactor.constantValue)
            return
        }
        // The processor takes the value of the call that completes a quantum, which is what the worklet reads at the quantum's last frame:
        // cut the block at the quantum ends and give each piece the value at its last frame.
        var from = 0
        while (from < n) {
            val frame = ctx.startFrame + from
            val to = Math.min(n, from + (QUANTUM - (frame % QUANTUM)).toInt())
            processor.process(b.l, right, from, to, ctx.startFrame, pitchFactor.sampleAt(ctx, to - 1))
            from = to
        }
    }

    override fun reset() {
        release()
        pitchFactor.reset(1.0)
    }

    override fun release() {
        val held = vocoder ?: return
        vocoder = null
        pool.give(held)
    }

    override fun registerParams(reg: NodeRegistry) {
        reg.register("stretch", "pitchFactor", pitchFactor)
    }
}
