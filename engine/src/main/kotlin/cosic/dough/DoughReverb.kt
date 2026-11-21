package cosic.dough

/*
 * dough.h 1270-1608: the reverb of the orbit, Jon Dattorro's plate (https://ccrma.stanford.edu/~dattorro/EffectDesignPart1.pdf), adapted in dough from the implementation of
 * el-visio (github.com/el-visio, MIT licence). A mono input goes through a pre-delay, a low-pass and four all-passes into a tank of two halves that feed each other; the
 * stereo output is a sum of taps of the tank. Times and offsets are `uint16_t` in the C code, so they wrap around at 65536 like here (`and 0xFFFF`).
 */

internal const val VERB_MAX_PREDELAY = 4800
internal const val VERB_TAP_MAIN = 0
internal const val VERB_TAP_OUT1 = 1
internal const val VERB_TAP_OUT2 = 2
internal const val VERB_TAP_OUT3 = 3
internal const val VERB_MAX_TAPS = 4

private const val UINT16_MASK = 0xFFFF

/** A delay line whose length is a power of two; `readOffset[tap]` is how far behind the write each tap reads (as an offset ahead of it, modulo the size). */
internal class VerbDelayBuffer(delay: Int) {
    private val buffer: FloatArray
    private val mask: Int
    private val readOffset = IntArray(VERB_MAX_TAPS)

    init {
        var numBits = 0
        var x = delay
        while (x != 0) {
            numBits++
            x = x ushr 1
        }
        val bufferSize = (1 shl numBits) and UINT16_MASK
        buffer = FloatArray(bufferSize)
        mask = (bufferSize - 1) and UINT16_MASK
        setDelay(VERB_TAP_MAIN, delay)
    }

    fun clear() {
        java.util.Arrays.fill(buffer, 0.0f)
    }

    fun setDelay(tap: Int, delay: Int) {
        readOffset[tap] = (mask + 1 - delay) and UINT16_MASK
    }

    fun adjustMainOffset(by: Int) {
        readOffset[VERB_TAP_MAIN] = (readOffset[VERB_TAP_MAIN] + by) and UINT16_MASK
    }

    fun write(t: Int, input: Float) {
        buffer[t and mask] = input
    }

    fun read(tap: Int, t: Int): Float = buffer[(t + readOffset[tap]) and mask]

    fun process(t: Int, input: Float): Float {
        buffer[t and mask] = input
        return buffer[(t + readOffset[VERB_TAP_MAIN]) and mask]
    }
}

private fun allPass(db: VerbDelayBuffer, t: Int, gain: Float, input: Float): Float {
    val delayed = db.read(VERB_TAP_MAIN, t)
    val x = input + delayed * -gain
    db.write(t, x)
    return delayed + x * gain
}

internal class DattorroVerb {
    private val preDelay = VerbDelayBuffer(VERB_MAX_PREDELAY)
    private val inDiffusion = arrayOf(VerbDelayBuffer(142), VerbDelayBuffer(107), VerbDelayBuffer(379), VerbDelayBuffer(277))
    private val decayDiffusion1 = arrayOf(VerbDelayBuffer(672), VerbDelayBuffer(908))
    private val preDampingDelay = arrayOf(VerbDelayBuffer(4453), VerbDelayBuffer(4217))
    private val decayDiffusion2 = arrayOf(VerbDelayBuffer(1800), VerbDelayBuffer(2656))
    private val postDampingDelay = arrayOf(VerbDelayBuffer(3720), VerbDelayBuffer(3163))

    private var preFilter = 0f
    private val damping = FloatArray(2)
    private var preFilterAmount = 0f
    private var inputDiffusion1Amount = 0f
    private var inputDiffusion2Amount = 0f
    private var decayDiffusion1Amount = 0f
    private var dampingAmount = 0f
    private var decayAmount = 0f
    private var decayDiffusion2Amount = 0f
    private var t = 0

    init {
        reset()
    }

    /** `DattorroVerb_init` on the buffers it already has: empties them and sets the default parameters (and the taps, which the process modulates). */
    fun reset() {
        preFilter = 0.0f
        damping[0] = 0.0f
        damping[1] = 0.0f
        t = 0
        preDelay.clear()
        for (b in inDiffusion) b.clear()
        for (b in decayDiffusion1) b.clear()
        for (b in preDampingDelay) b.clear()
        for (b in decayDiffusion2) b.clear()
        for (b in postDampingDelay) b.clear()
        preDelay.setDelay(VERB_TAP_MAIN, VERB_MAX_PREDELAY)
        inDiffusion[0].setDelay(VERB_TAP_MAIN, 142)
        inDiffusion[1].setDelay(VERB_TAP_MAIN, 107)
        inDiffusion[2].setDelay(VERB_TAP_MAIN, 379)
        inDiffusion[3].setDelay(VERB_TAP_MAIN, 277)
        decayDiffusion1[0].setDelay(VERB_TAP_MAIN, 672)
        preDampingDelay[0].setDelay(VERB_TAP_MAIN, 4453)
        preDampingDelay[0].setDelay(VERB_TAP_OUT1, 353)
        preDampingDelay[0].setDelay(VERB_TAP_OUT2, 3627)
        preDampingDelay[0].setDelay(VERB_TAP_OUT3, 1990)
        decayDiffusion2[0].setDelay(VERB_TAP_MAIN, 1800)
        decayDiffusion2[0].setDelay(VERB_TAP_OUT1, 187)
        decayDiffusion2[0].setDelay(VERB_TAP_OUT2, 1228)
        postDampingDelay[0].setDelay(VERB_TAP_MAIN, 3720)
        postDampingDelay[0].setDelay(VERB_TAP_OUT1, 1066)
        postDampingDelay[0].setDelay(VERB_TAP_OUT2, 2673)
        decayDiffusion1[1].setDelay(VERB_TAP_MAIN, 908)
        preDampingDelay[1].setDelay(VERB_TAP_MAIN, 4217)
        preDampingDelay[1].setDelay(VERB_TAP_OUT1, 266)
        preDampingDelay[1].setDelay(VERB_TAP_OUT2, 2974)
        preDampingDelay[1].setDelay(VERB_TAP_OUT3, 2111)
        decayDiffusion2[1].setDelay(VERB_TAP_MAIN, 2656)
        decayDiffusion2[1].setDelay(VERB_TAP_OUT1, 335)
        decayDiffusion2[1].setDelay(VERB_TAP_OUT2, 1913)
        postDampingDelay[1].setDelay(VERB_TAP_MAIN, 3163)
        postDampingDelay[1].setDelay(VERB_TAP_OUT1, 121)
        postDampingDelay[1].setDelay(VERB_TAP_OUT2, 1996)
        setPreDelay(0.1f)
        setPreFilter(0.85f)
        setInputDiffusion1(0.75f)
        setInputDiffusion2(0.625f)
        setDecay(0.75f)
        setDecayDiffusion(0.70f)
        setDamping(0.95f)
    }

    fun setPreDelay(value: Float) {
        // float -> uint16_t: the conversion of a value outside 0..65535 is undefined in C; here it saturates
        preDelay.setDelay(VERB_TAP_MAIN, (value * VERB_MAX_PREDELAY).toInt().coerceIn(0, UINT16_MASK))
    }

    fun setPreFilter(value: Float) {
        preFilterAmount = value
    }

    fun setInputDiffusion1(value: Float) {
        inputDiffusion1Amount = value
    }

    fun setInputDiffusion2(value: Float) {
        inputDiffusion2Amount = value
    }

    fun setDecayDiffusion(value: Float) {
        decayDiffusion1Amount = value
    }

    fun setDecay(value: Float) {
        decayAmount = clamp(value, 0.0f, 0.99f)
        decayDiffusion2Amount = clamp(decayAmount + 0.15f, 0.25f, 0.50f)
    }

    fun setDamping(value: Float) {
        dampingAmount = value
    }

    private fun lowPass(state: Float, freq: Float, input: Float): Float = state + (input - state) * freq

    fun process(input: Float) {
        // modulate the decay diffusion delay lines: one step every 2048 samples, down for the first half of the 16-bit counter, up for the second
        if ((t and 0x07ff) == 0) {
            val by = if (t < (1 shl 15)) -1 else 1
            decayDiffusion1[0].adjustMainOffset(by)
            decayDiffusion1[1].adjustMainOffset(by)
        }
        var x = preDelay.process(t, input)
        preFilter = lowPass(preFilter, preFilterAmount, x)
        x = preFilter
        x = allPass(inDiffusion[0], t, inputDiffusion1Amount, x)
        x = allPass(inDiffusion[1], t, inputDiffusion1Amount, x)
        x = allPass(inDiffusion[2], t, inputDiffusion2Amount, x)
        x = allPass(inDiffusion[3], t, inputDiffusion2Amount, x)
        for (i in 0 until 2) {
            var x1 = x + postDampingDelay[1 - i].read(VERB_TAP_MAIN, t) * decayAmount
            x1 = allPass(decayDiffusion1[i], t, -decayDiffusion1Amount, x1)
            x1 = preDampingDelay[i].process(t, x1)
            damping[i] = lowPass(damping[i], dampingAmount, x1)
            x1 = damping[i]
            x1 *= decayAmount
            x1 = allPass(decayDiffusion2[i], t, decayDiffusion2Amount, x1)
            postDampingDelay[i].write(t, x1)
        }
        t = (t + 1) and UINT16_MASK
    }

    fun left(): Float {
        var a = 0.0f
        a += preDampingDelay[1].read(VERB_TAP_OUT1, t)
        a += preDampingDelay[1].read(VERB_TAP_OUT2, t)
        a -= decayDiffusion2[1].read(VERB_TAP_OUT2, t)
        a += postDampingDelay[1].read(VERB_TAP_OUT2, t)
        a -= preDampingDelay[0].read(VERB_TAP_OUT3, t)
        a -= decayDiffusion2[0].read(VERB_TAP_OUT1, t)
        a += postDampingDelay[0].read(VERB_TAP_OUT1, t)
        return a
    }

    fun right(): Float {
        var a = 0.0f
        a += preDampingDelay[0].read(VERB_TAP_OUT1, t)
        a += preDampingDelay[0].read(VERB_TAP_OUT2, t)
        a -= decayDiffusion2[0].read(VERB_TAP_OUT2, t)
        a += postDampingDelay[0].read(VERB_TAP_OUT2, t)
        a -= preDampingDelay[1].read(VERB_TAP_OUT3, t)
        a -= decayDiffusion2[1].read(VERB_TAP_OUT1, t)
        a += postDampingDelay[1].read(VERB_TAP_OUT1, t)
        return a
    }
}
