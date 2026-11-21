package cosic.audio

import cosic.kabel.KabelInstance

/** A float for the output of a worklet: NaN and infinities become silence (a browser's mixer would carry them along, but nothing here should). */
private fun finiteFloat(x: Double): Float {
    val f = x.toFloat()
    return if (f - f == 0f) f else 0f
}

/**
 * Stage 0 of the chain, kabelsalat's `generic-processor` (superdough.mjs:636-647, worklets.mjs:1496-1572): runs the program of the event's `K(...)` or
 * `worklet(...)` (an instance made by [cosic.kabel.KabelFactory]) in place of the signal that comes in, which the program reads as `audioin()`. The
 * source of the event (a triangle at the note's frequency unless `s` says otherwise) still plays, ahead of the program: it is what `audioin()` returns,
 * and it is why a stolen or cut voice does not silence the program (the voice's gain ramps act on the source).
 *
 * The worklet works in render quanta of 128 frames and so does this stage, aligned to the absolute frame counter whatever the length of the blocks it is
 * given: it is silent until the first quantum that starts at or after the onset, switches the gate off at the first quantum that starts after the end of the
 * hap's gate, and is gone from the first quantum that starts at or after the end of the release. The sound is stereo (the two outputs of the program).
 * A voice whose program is dead (see [KabelInstance.dead]) is silent.
 */
internal class KabelStage : ChainStage {
    private var instance: KabelInstance? = null
    private var start = 0.0
    private var gateEnd = 0.0
    private var end = 0.0
    private var quantum = Long.MIN_VALUE
    private var phase = PENDING
    private var gateEnded = false

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        val k = p.kabel
        instance = k
        if (k == null) return false
        start = timing.t
        gateEnd = timing.end
        end = timing.endWithRelease
        quantum = Long.MIN_VALUE
        phase = PENDING
        gateEnded = false
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val k = instance ?: return
        val n = ctx.n
        val left = b.l
        val right = b.r
        var i = 0
        while (i < n) {
            val absolute = ctx.startFrame + i
            val q = absolute / QUANTUM
            val within = (absolute - q * QUANTUM).toInt()
            val to = Math.min(n, i + QUANTUM - within)
            if (q != quantum) {
                quantum = q
                if (phase != DEAD) {
                    val currentTime = ctx.quantumTime(q)
                    if (currentTime >= end) {
                        phase = DEAD
                    } else if (currentTime < start) {
                        phase = PENDING
                    } else {
                        phase = RUNNING
                        if (!gateEnded && currentTime > gateEnd) {
                            k.setGate(false)
                            gateEnded = true
                        }
                    }
                }
            }
            var j = i
            if (phase == RUNNING) {
                while (j < to && !k.dead) {
                    k.sample(left[j].toDouble()) // audioin() is the first channel of what comes in
                    left[j] = finiteFloat(k.left)
                    right[j] = finiteFloat(k.right)
                    j++
                }
            }
            if (j < to) {
                left.fill(0f, j, to)
                right.fill(0f, j, to)
            }
            i = to
        }
        b.channels = 2
    }

    override fun reset() {
        instance = null
    }

    override fun release() {
        instance = null
    }

    private companion object {
        const val PENDING = 0
        const val RUNNING = 1
        const val DEAD = 2
    }
}
