package cosic.dough

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * [DoughEngine] against the real engine: `dough.wasm` of `dough-synth@0.4.1`, run in Node by `tools/strudel-ref/dough-oracle.mjs`, which wrote
 * `src/test/resources/dough/golden.json`. Every case is a list of events (the text Strudel sends, `key/value/...`) with the frame each starts at, the files that were registered, and
 * the stereo output of the wasm, block by block, with the number of voices it had after every block.
 *
 * The port is meant to be bit for bit the wasm, so the tolerance is tiny. The only places where the output may differ are the ones where the wasm produces NaN (an `rmdepth` with
 * no `rm`): the engine writes 0 there, and a NaN in the golden stands for 0.
 */
class DoughGoldenTest {
    private class Event(val encoded: String, val frame: Int)

    private class Case(
        val name: String,
        val sampleRate: Int,
        val frames: Int,
        val events: List<Event>,
        val files: List<Triple<Int, DoughFile, Int>>,
        val activeVoices: List<Int>,
        val expected: FloatArray, // interleaved stereo
    )

    private fun floats(base64: String): FloatArray {
        val bytes = Base64.getDecoder().decode(base64)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun load(): List<Case> {
        val text = checkNotNull(DoughGoldenTest::class.java.getResourceAsStream("/dough/golden.json")) { "missing golden dough/golden.json" }.use { it.readBytes().toString(Charsets.UTF_8) }
        val root = Json.parseToJsonElement(text).jsonObject
        return root["cases"]!!.jsonArray.map { element ->
            val c = element.jsonObject
            val frames = c["frames"]!!.jsonPrimitive.int
            val events = c["events"]!!.jsonArray.map { e ->
                val o = e.jsonObject as JsonObject
                val frame = o["startFrame"]?.jsonPrimitive?.int ?: (o["atBlock"]!!.jsonPrimitive.int * BLOCK)
                Event(o["encoded"]!!.jsonPrimitive.content, frame)
            }
            val files = c["files"]!!.jsonArray.map { f ->
                val o = f.jsonObject
                val channels = o["channels"]!!.jsonPrimitive.int
                val n = o["frames"]!!.jsonPrimitive.int
                val freq = o["freq"]!!.jsonPrimitive.content.toFloat()
                Triple(o["index"]!!.jsonPrimitive.int, DoughFile(floats(o["pcm"]!!.jsonPrimitive.content), n, channels, freq), channels)
            }
            val data = floats(c["output"]!!.jsonPrimitive.content)
            val expected = if (c["stereo"]!!.jsonPrimitive.content == "true") data else FloatArray(data.size * 2) { data[it / 2] }
            Case(
                c["name"]!!.jsonPrimitive.content,
                c["sampleRate"]!!.jsonPrimitive.int,
                frames,
                events,
                files,
                c["activeVoices"]!!.jsonArray.map { it.jsonPrimitive.int },
                expected,
            )
        }
    }

    /** What [case] renders: the output and the number of voices after every block of 128 frames. */
    private fun render(case: Case): Pair<FloatArray, List<Int>> {
        val engine = DoughEngine(case.sampleRate)
        for ((index, file, _) in case.files) assertTrue(engine.registerFile(index, file), "file $index of ${case.name}")
        val out = FloatArray(case.frames * 2)
        val voices = ArrayList<Int>()
        val pending = case.events.withIndex().sortedWith(compareBy({ it.value.frame }, { it.index })).map { it.value }
        var next = 0
        var position = 0
        while (position < case.frames) {
            while (next < pending.size && pending[next].frame <= position) {
                val e = DoughEvent()
                e.reset()
                parseEvent(e, pending[next].encoded)
                engine.run(e)
                next++
            }
            var stop = minOf(case.frames, (position / BLOCK + 1) * BLOCK)
            if (next < pending.size) stop = minOf(stop, pending[next].frame)
            engine.render(out, stop - position, position)
            position = stop
            if (position % BLOCK == 0) voices.add(engine.activeVoices)
        }
        return out to voices
    }

    @Test
    fun everyCaseIsBitForBitTheWasm() {
        val cases = load()
        assertTrue(cases.size > 100, "the golden has ${cases.size} cases")
        val failures = ArrayList<String>()
        val inexact = ArrayList<Pair<String, Double>>()
        var worst = 0.0
        for (case in cases) {
            val (actual, voices) = render(case)
            var maxError = 0.0
            var first = -1
            for (i in actual.indices) {
                val e = case.expected[i].let { if (it.isNaN()) 0f else it }
                val error = abs(actual[i].toDouble() - e.toDouble())
                if (error > maxError) maxError = error
                if (error > TOLERANCE && first < 0) first = i
            }
            if (maxError > worst) worst = maxError
            if (maxError > 0.0) inexact.add(case.name to maxError)
            if (first >= 0) {
                failures.add("${case.name}: max error $maxError, first at frame ${first / 2} channel ${first % 2}: got ${actual[first]}, wasm ${case.expected[first]}")
            } else if (voices != case.activeVoices) {
                val at = voices.indices.firstOrNull { voices[it] != case.activeVoices[it] } ?: -1
                failures.add("${case.name}: voices after block $at: got ${voices.getOrNull(at)}, wasm ${case.activeVoices.getOrNull(at)}")
            }
        }
        println("dough golden: ${cases.size} cases, worst error $worst, not bit for bit: ${inexact.joinToString { "${it.first} (${it.second})" }}")
        assertEquals(emptyList<String>(), failures)
    }

    private companion object {
        const val BLOCK = 128
        const val TOLERANCE = 1e-6
    }
}
