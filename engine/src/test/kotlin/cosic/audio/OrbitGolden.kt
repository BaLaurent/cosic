package cosic.audio

import cosic.audio.dsp.DspGolden
import kotlin.math.abs
import kotlin.test.fail
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The goldens of `src/test/resources/dsp/orbit-*.json`, written by `tools/strudel-ref/orbit-oracle.mjs` from headless Chromium and the real superdough sources. The inputs
 * are not stored: they are the 32-bit LCG noise of [lcg], which the oracle uses too.
 */
internal object OrbitGolden {
    fun cases(file: String): List<JsonObject> = DspGolden.load("orbit-$file.json")["cases"]!!.jsonArray.map { it.jsonObject }

    fun floats(base64: JsonPrimitive): FloatArray = DspGolden.floats(base64.content)

    fun outputs(case: JsonObject): List<FloatArray> = case["output"]!!.jsonArray.map { floats(it.jsonPrimitive) }

    fun string(o: JsonObject, key: String): String = o[key]!!.jsonPrimitive.content

    fun number(o: JsonObject, key: String): Double = o[key]!!.jsonPrimitive.double

    fun int(o: JsonObject, key: String): Int = o[key]!!.jsonPrimitive.content.toInt()

    fun array(o: JsonObject, key: String): JsonArray = o[key]!!.jsonArray
}

/** The oracle's noise: 32-bit LCG, `(x / 2^31 - 1) * amp`, rounded to float32 (`lcg(seed, n, amp)` of orbit-oracle.mjs). */
internal fun lcg(seed: Long, n: Int, amp: Double = 1.0): FloatArray {
    var x = seed and 0xFFFFFFFFL
    return FloatArray(n) {
        x = (x * 1664525L + 1013904223L) and 0xFFFFFFFFL
        ((x.toDouble() / 2147483648.0 - 1.0) * amp).toFloat()
    }
}

/** `Math.random` of the oracle's reverb generator: the same LCG, `x / 2^32`. */
internal class LcgRandom(seed: Long) : UniformRandom {
    private var x = seed and 0xFFFFFFFFL

    override fun next(): Double {
        x = (x * 1664525L + 1013904223L) and 0xFFFFFFFFL
        return x.toDouble() / 4294967296.0
    }
}

/** Fails unless every frame of [actual] is within [atol] of [expected]; returns the worst error. */
internal fun assertWithin(label: String, expected: FloatArray, actual: FloatArray, atol: Double, from: Int = 0, to: Int = expected.size): Double {
    var worst = 0.0
    var at = -1
    for (i in from until to) {
        val e = expected[i].toDouble()
        val a = actual[i].toDouble()
        val error = abs(a - e)
        if (a.isNaN() || error > atol) fail("$label: frame $i is $a, expected $e (error $error > $atol)")
        if (error > worst) {
            worst = error
            at = i
        }
    }
    if (System.getenv("COSIC_DSP_VERBOSE") == "true") println("$label: worst error $worst at frame $at")
    return worst
}
