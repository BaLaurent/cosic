package cosic.kabel

import cosic.script.TestHost

/** `mulberry32` of the golden harness (tools/strudel-ref/kabel/render-golden.mjs), which seeds `Math.random` of the worklet in Node. */
internal class Mulberry32(seed: Int) : KabelRandom {
    private var s = seed

    override fun nextDouble(): Double {
        s += 0x6D2B79F5
        var t = (s xor (s ushr 15)) * (1 or s)
        t = (t + (t xor (t ushr 7)) * (61 or t)) xor t
        return ((t xor (t ushr 14)).toLong() and 0xFFFFFFFFL).toDouble() / 4294967296.0
    }
}

/** A random source that always returns [value]: noise that is not noise, for programs that must be exact. */
internal class FixedRandom(private val value: Double) : KabelRandom {
    override fun nextDouble(): Double = value
}

internal val kabelTestHost = TestHost()

/** The program of [code] compiled for [sampleRate] in a realm of its own. */
internal fun compileKabel(code: String, sampleRate: Int = 48000): KabelProgram = KabelCompiler(KabelRealm(TestHost()), sampleRate).compile(code)

/**
 * Runs [code] for [frames] samples at [sampleRate]: `[left, right]`. [input] is what `audioin()` reads at each frame; the gate is switched off before
 * sample [gateOffAt]; [rng] is the `Math.random` of the worklet.
 */
internal fun renderKabel(
    code: String,
    sampleRate: Int,
    frames: Int,
    input: (Int) -> Double = { 0.0 },
    rng: KabelRandom = FixedRandom(0.0),
    gateOffAt: Int = Int.MAX_VALUE,
    runtime: KabelRuntime = KabelRuntime(),
): Array<DoubleArray> {
    val instance = KabelInstance(compileKabel(code, sampleRate), sampleRate, runtime, rng)
    val left = DoubleArray(frames)
    val right = DoubleArray(frames)
    for (i in 0 until frames) {
        if (i == gateOffAt) instance.setGate(false)
        instance.sample(input(i))
        left[i] = instance.left
        right[i] = instance.right
    }
    return arrayOf(left, right)
}

/** The left channel of [renderKabel] at 48 kHz. */
internal fun kabelOut(code: String, frames: Int = 3, sampleRate: Int = 48000, rng: KabelRandom = FixedRandom(0.0)): DoubleArray =
    renderKabel(code, sampleRate, frames, rng = rng)[0]
