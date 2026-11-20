package cosic.audio.dsp

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The golden vectors of `src/test/resources/dsp`, written by `tools/strudel-ref/dsp-oracle.mjs` and `vowel-oracle.mjs`. */
internal object DspGolden {
    const val QUANTUM = 128

    fun load(name: String): JsonObject {
        val text = checkNotNull(DspGolden::class.java.getResourceAsStream("/dsp/$name")) { "missing golden $name" }.use { it.readBytes().toString(Charsets.UTF_8) }
        return Json.parseToJsonElement(text).jsonObject
    }

    /** A float array stored as base64 of its little-endian float32 bytes. */
    fun floats(base64: String): FloatArray {
        val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    fun cases(array: JsonArray, sharedInput: FloatArray? = null, signals: JsonObject? = null): List<StreamCase> = array.map { StreamCase(it.jsonObject, sharedInput, signals) }
}

/** One recorded run: its channels, the AudioParam values of every render quantum, the input and the output the real processor produced. */
internal class StreamCase(val json: JsonObject, sharedInput: FloatArray?, signals: JsonObject?) {
    val name: String = json["name"]!!.jsonPrimitive.content
    val channels: Int = json["channels"]!!.jsonPrimitive.content.toInt()
    val quanta: Int = json["quanta"]!!.jsonPrimitive.content.toInt()
    val sampleRate: Int = json["sampleRate"]?.jsonPrimitive?.content?.toInt() ?: 48000
    private val params: JsonArray = json["params"]!!.jsonArray
    val input: List<FloatArray> = when {
        json.containsKey("input") -> json["input"]!!.jsonArray.map { DspGolden.floats(it.jsonPrimitive.content) }
        json.containsKey("signals") -> json["signals"]!!.jsonArray.map { DspGolden.floats(signals!![it.jsonPrimitive.content]!!.jsonPrimitive.content) }
        else -> List(channels) { sharedInput!! }
    }
    val output: List<FloatArray> = json["output"]!!.jsonArray.map { DspGolden.floats(it.jsonPrimitive.content) }

    /** The value of AudioParam [key] that is constant over quantum [q]. */
    fun param(q: Int, key: String): Double = params[q].jsonObject[key]!!.jsonPrimitive.double

    /** The per-frame values of a moving AudioParam [key] in quantum [q]. */
    fun moving(q: Int, key: String): FloatArray = params[q].jsonObject[key]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()

    fun frames(): Int = quanta * DspGolden.QUANTUM

    /** Fresh copies of the input, one array per channel, the size of the whole run. */
    fun copyOfInput(): Array<FloatArray> = Array(channels) { input[it].copyOf(frames()) }
}

/** The largest error of [actual] against [expected] over `[from, to)`; fails when any frame is further off than `atol + rtol * |expected|`. */
internal fun assertClose(label: String, expected: FloatArray, actual: FloatArray, atol: Double, rtol: Double = 0.0, from: Int = 0, to: Int = expected.size): Double {
    var worst = 0.0
    var worstAt = -1
    for (i in from until to) {
        val e = expected[i].toDouble()
        val a = actual[i].toDouble()
        val error = abs(a - e)
        if (a.isNaN() || error > atol + rtol * abs(e)) {
            fail("$label: frame $i is $a, expected $e (error $error > ${atol + rtol * abs(e)})")
        }
        if (error > worst) {
            worst = error
            worstAt = i
        }
    }
    if (System.getenv("COSIC_DSP_VERBOSE") == "true") println("$label: worst error $worst at frame $worstAt")
    return worst
}
