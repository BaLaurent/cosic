package cosic.audio

/**
 * The end of the engine (superdoughoutput.mjs:132-175): the orbits are summed into the stereo device output, each through its channel map (output
 * channel `ch % 2` receives the orbit's left or right channel `i % 2` for the `i`-th entry `ch` of its channels), with no master gain and no
 * limiter, as in Strudel. The only protection is a hard clamp to `[-1, 1]` that turns NaN into silence.
 */
internal object MasterOutput {
    /**
     * Overwrites `out[2 * outFrame until 2 * (outFrame + n)]` with the sum of the first [count] [orbits], each frame scaled by [stopGain] (the
     * fade of a stop; 1 otherwise).
     */
    fun mix(orbits: Array<Orbit?>, count: Int, out: FloatArray, outFrame: Int, n: Int, stopGain: FloatArray) {
        val base = outFrame * 2
        java.util.Arrays.fill(out, base, base + n * 2, 0f)
        for (o in 0 until count) {
            val orbit = orbits[o]!!
            val channels = orbit.channels
            for (i in channels.indices) {
                val source = if (i % 2 == 0) orbit.summing.left else orbit.summing.right
                val destination = Math.floorMod(channels[i], 2)
                var at = base + destination
                for (k in 0 until n) {
                    out[at] += source[k] * stopGain[k]
                    at += 2
                }
            }
        }
        for (k in base until base + n * 2) {
            val v = out[k]
            out[k] = if (v > 1f) 1f else if (v < -1f) -1f else if (v == v) v else 0f
        }
    }
}
