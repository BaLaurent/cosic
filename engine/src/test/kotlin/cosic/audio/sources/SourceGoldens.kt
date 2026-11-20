package cosic.audio.sources

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.test.fail
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The golden vectors of `src/test/resources/sources`, written by `tools/strudel-ref/sources-oracle.mjs`: the real `onTrigger` of each synth sound of the pinned
 * superdough, run in headless Chromium, and the pitch traces of `getPitchEnvelope` / `getVibratoOscillator` on a real AudioParam.
 */
internal class SourceGolden(json: JsonObject) {
    val name: String = json["name"]!!.jsonPrimitive.content
    val sound: String? = json["sound"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    val controls: Map<String, Any?> = json["controls"]!!.jsonObject.mapValues { plain(it.value) }
    val durationSec: Double = json["durationSec"]!!.jsonPrimitive.double()
    val startFrame: Long = json["startFrame"]!!.jsonPrimitive.content.toLong()
    val frames: Int = json["frames"]!!.jsonPrimitive.content.toInt()
    val sampleRate: Int = json["sampleRate"]!!.jsonPrimitive.content.toInt()
    val stereo: Boolean = json["stereo"]?.jsonPrimitive?.booleanOrNull ?: false
    val noiseType: String? = json["noiseType"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
    val left: FloatArray = floats(json["left"]!!.jsonPrimitive.content)
    val right: FloatArray? = json["right"]?.takeIf { it !is JsonNull }?.let { floats(it.jsonPrimitive.content) }
    val noise: FloatArray? = json["noise"]?.takeIf { it !is JsonNull }?.let { floats(it.jsonPrimitive.content) }

    fun spec(seed: Long = 1L): SourceSpec = SourceSpec(sampleRate, controls.asSourceControls(), durationSec, 1.0, seed)

    override fun toString(): String = name

    companion object {
        fun load(file: String): List<SourceGolden> {
            val text = checkNotNull(SourceGolden::class.java.getResourceAsStream("/sources/$file")) { "missing golden $file" }.use { it.readBytes().toString(Charsets.UTF_8) }
            return Json.parseToJsonElement(text).jsonObject["cases"]!!.jsonArray.map { SourceGolden(it.jsonObject) }
        }

        private fun JsonPrimitive.double(): Double = doubleOrNull ?: fail("not a number: $this")

        /** A control value as a pattern gives it: numbers are doubles, lists are lists. */
        private fun plain(e: JsonElement): Any? = when (e) {
            is JsonNull -> null
            is JsonArray -> e.map { plain(it) }
            is JsonObject -> fail("an object control")
            is JsonPrimitive -> if (e.isString) e.content else e.booleanOrNull ?: e.doubleOrNull
        }

        fun floats(base64: String): FloatArray {
            val buffer = ByteBuffer.wrap(Base64.getDecoder().decode(base64)).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
            return FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
    }
}

/** Plays [source] from engine frame [startFrame] for [frames] frames, asking for [blockSizes] frames at a time (cycled), and returns both channels. */
internal fun renderSource(source: SoundSource, startFrame: Long, frames: Int, vararg blockSizes: Int): Pair<FloatArray, FloatArray> {
    val sizes = if (blockSizes.isEmpty()) intArrayOf(256) else blockSizes
    source.start(startFrame)
    val left = FloatArray(frames)
    val right = FloatArray(frames)
    val blockL = FloatArray(sizes.max())
    val blockR = FloatArray(sizes.max())
    var done = 0
    var turn = 0
    while (done < frames) {
        val n = minOf(sizes[turn++ % sizes.size], frames - done)
        java.util.Arrays.fill(blockL, Float.NaN)
        java.util.Arrays.fill(blockR, Float.NaN)
        val produced = source.render(blockL, blockR, n)
        check(produced in 0..n) { "render returned $produced for $n frames" }
        System.arraycopy(blockL, 0, left, done, n)
        if (source.channels == 2) System.arraycopy(blockR, 0, right, done, n) else System.arraycopy(blockL, 0, right, done, n)
        done += n
    }
    return left to right
}

/** The largest error of [actual] against [expected]; fails when any frame is further off than `atol + rtol * |expected|`. */
internal fun assertClose(label: String, expected: FloatArray, actual: FloatArray, atol: Double, rtol: Double = 0.0, from: Int = 0, to: Int = minOf(expected.size, actual.size)): Double {
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
    if (System.getenv("COSIC_SOURCES_VERBOSE") == "true") println("$label: worst error $worst at frame $worstAt")
    return worst
}
