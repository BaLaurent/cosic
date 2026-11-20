package cosic.audio

import cosic.audio.dsp.VowelFilter
import cosic.pattern.logger

/**
 * Stage 8, the vowel filter (superdough.mjs:790-794, vowel.mjs): five parallel band-passes at the formants of a vowel, summed
 * and multiplied by 8, in the chain when `vowel` is set. The filter itself is [VowelFilter] (checked against Chromium's biquad).
 *
 * A name that is not a vowel (`vowel.mjs` throws `vowel: unknown vowel x` while the chain is built, so the event never reaches its orbit)
 * makes the voice silent: the stage logs the same message as a warning and writes zeros for as long as the voice lives.
 *
 * Superdough exposes the five biquads' frequencies as `vowel.frequency` to modulators (`.lfo({control: 'vowel'})`); this stage registers
 * nothing, so such a modulator finds no target. The filter keeps its formants fixed.
 */
internal class VowelStage(sampleRate: Int) : ChainStage {
    private val filter = VowelFilter(sampleRate)
    private var broken = false

    override fun configure(p: ParamSet, timing: VoiceTiming, isMain: Boolean): Boolean {
        val letter = p.str("vowel") ?: return false
        broken = !filter.configure(letter)
        if (broken) logger("vowel: unknown vowel $letter", "warning", null)
        return true
    }

    override fun process(b: AudioBlock, ctx: BlockContext) {
        val n = ctx.n
        if (broken) {
            b.l.fill(0f, 0, n)
            if (b.channels == 2) b.r.fill(0f, 0, n)
            return
        }
        filter.process(b.l, if (b.channels == 2) b.r else null, 0, n)
    }

    override fun reset() {
        filter.reset()
        broken = false
    }
}
