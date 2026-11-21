package cosic.dough

/*
 * dough.h 729-864: the envelopes. One `AdsrNode` per stage of a voice that has one (gain, the three filters, pitch, FM). The curves are `lerp` with the exponents the node holds
 * (2 for the attack, -2 for the decay and release), so the shape is the approximation `our_powf`, not the exact power.
 */

internal const val ADSR_OFF = 0
internal const val ADSR_ATTACK = 1
internal const val ADSR_DECAY = 2
internal const val ADSR_SUSTAIN = 3
internal const val ADSR_RELEASE = 4

internal class AdsrNode {
    @JvmField
    var state = ADSR_OFF

    @JvmField
    var startTime = 0f

    @JvmField
    var startVal = 0f

    @JvmField
    var attackCurve = 2.0f

    @JvmField
    var decayCurve = 2.0f

    fun reset() {
        state = ADSR_OFF
        startTime = 0.0f
        startVal = 0.0f
        attackCurve = 2.0f
        decayCurve = 2.0f
    }

    /** `ADSRNode_update`: the envelope value at [curTime] for a gate that is open when [gate] is above 0. */
    fun update(curTime: Float, gate: Float, attack: Float, decay: Float, susVal: Float, release: Float): Float {
        when (state) {
            ADSR_OFF -> {
                if (gate > 0) {
                    state = ADSR_ATTACK
                    startTime = curTime
                    startVal = 0.0f
                }
                return 0.0f
            }
            ADSR_ATTACK -> {
                val time = curTime - startTime
                if (time > attack) {
                    state = ADSR_DECAY
                    startTime = curTime
                    return 1.0f
                }
                return lerp(time / attack, startVal, 1.0f, attackCurve)
            }
            ADSR_DECAY -> {
                val time = curTime - startTime
                val curVal = lerp(time / decay, 1.0f, susVal, -decayCurve)
                if (gate <= 0) {
                    state = ADSR_RELEASE
                    startTime = curTime
                    startVal = curVal
                    return curVal
                }
                if (time > decay) {
                    state = ADSR_SUSTAIN
                    startTime = curTime
                    return susVal
                }
                return curVal
            }
            ADSR_SUSTAIN -> {
                if (gate <= 0) {
                    state = ADSR_RELEASE
                    startTime = curTime
                    startVal = susVal
                }
                return susVal
            }
            ADSR_RELEASE -> {
                val time = curTime - startTime
                if (time > release) {
                    state = ADSR_OFF
                    return 0.0f
                }
                val curVal = lerp(time / release, startVal, 0.0f, -decayCurve)
                if (gate > 0) {
                    state = ADSR_ATTACK
                    startTime = curTime
                    startVal = curVal
                }
                return curVal
            }
        }
        return 0.0f
    }
}

/** The five values of an envelope as `init_envelope` reads and completes them, and whether the envelope is used at all. Owned by the engine; the audio thread reuses one. */
internal class EnvelopeFields {
    @JvmField
    var env = 0f

    @JvmField
    var att = 0f

    @JvmField
    var dec = 0f

    @JvmField
    var sus = 0f

    @JvmField
    var rel = 0f

    @JvmField
    var active = false
}

/**
 * `init_envelope`: an envelope none of whose five values is set is off; otherwise the ones that are missing get a value that makes sense for what is set: a lone attack
 * sustains at 1, a lone decay (or an attack and a decay) at 0, a sustain above 1 is 1; `env` is 1, the attack 0.001, the decay 0, the sustain 1, the release 0.005.
 */
internal fun initEnvelope(f: EnvelopeFields) {
    if (f.env.isNaN() && f.att.isNaN() && f.dec.isNaN() && f.sus.isNaN() && f.rel.isNaN()) {
        f.active = false
        return
    }
    f.active = true
    val envmax = 1.0f
    if (f.sus.isNaN() && !f.att.isNaN() && f.dec.isNaN()) {
        f.sus = 1.0f
    } else if (f.sus.isNaN() && f.att.isNaN() && !f.dec.isNaN()) {
        f.sus = 0.0f
    } else if (f.sus.isNaN() && !f.att.isNaN() && !f.dec.isNaN()) {
        f.sus = 0.0f
    } else if (!f.sus.isNaN()) {
        f.sus = fminf(f.sus, envmax)
    }
    f.env = nanFallback(f.env, 1.0f)
    f.att = nanFallback(f.att, 0.001f)
    f.dec = nanFallback(f.dec, 0.0f)
    f.sus = nanFallback(f.sus, 1.0f)
    f.rel = nanFallback(f.rel, 0.005f)
}
