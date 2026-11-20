package cosic.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** [FeedbackDelay] against the real `FeedbackDelayNode` of superdough in Chromium (`orbit-delay.json`), and the behaviours the goldens establish. */
class FeedbackDelayTest {
    private fun signal(spec: JsonObject, frames: Int): FloatArray =
        if ("impulse" in spec) {
            FloatArray(frames).also { for (at in spec["impulse"]!!.jsonArray) it[at.jsonPrimitive.content.toInt()] = 1f }
        } else {
            lcg(spec["noise"]!!.jsonPrimitive.content.toLong(), frames)
        }

    /** Runs a delay over [input] (one array per channel), cutting the blocks as [blocks] says (repeated), with the events of [events]. */
    private fun run(
        sr: Int, input: List<FloatArray>, delayTime: Double, feedback: Double, events: List<JsonObject>, blocks: IntArray,
    ): List<FloatArray> {
        val frames = input[0].size
        val delay = FeedbackDelay(sr)
        delay.start(delayTime, feedback)
        for (e in events) {
            val t = e["frame"]!!.jsonPrimitive.content.toDouble() / sr
            e["delayTime"]?.let { delay.delayTime.setValueAtTime(it.jsonPrimitive.content.toDouble(), t) }
            e["feedback"]?.let { delay.feedback.setValueAtTime(it.jsonPrimitive.content.toDouble(), t) }
        }
        val ctx = BlockContext(sr)
        val out = List(input.size) { FloatArray(frames) }
        val inL = FloatArray(BLOCK_FRAMES)
        val inR = FloatArray(BLOCK_FRAMES)
        val outL = FloatArray(BLOCK_FRAMES)
        val outR = FloatArray(BLOCK_FRAMES)
        var pos = 0
        var b = 0
        while (pos < frames) {
            val n = minOf(blocks[b++ % blocks.size], frames - pos)
            input[0].copyInto(inL, 0, pos, pos + n)
            val right = if (input.size == 2) inR.also { input[1].copyInto(it, 0, pos, pos + n) } else inL
            ctx.startFrame = pos.toLong()
            ctx.n = n
            delay.process(inL, right, outL, outR, n, ctx)
            outL.copyInto(out[0], pos, 0, n)
            if (input.size == 2) outR.copyInto(out[1], pos, 0, n)
            pos += n
        }
        return out
    }

    @Test
    fun matchesTheRealFeedbackDelayNodeInChromium() {
        for (case in OrbitGolden.cases("delay")) {
            val name = OrbitGolden.string(case, "name")
            val sr = OrbitGolden.int(case, "sr")
            val frames = OrbitGolden.int(case, "frames")
            val input = case["input"]!!.jsonArray.map { signal(it.jsonObject, frames) }
            val expected = OrbitGolden.outputs(case)
            val actual = run(
                sr, input, OrbitGolden.number(case, "delayTime"), OrbitGolden.number(case, "feedback"),
                case["events"]!!.jsonArray.map { it.jsonObject }, intArrayOf(128, 77, 256, 1, 200, 31),
            )
            for (ch in expected.indices) assertWithin("$name, channel $ch", expected[ch], actual[ch], 2e-6)
        }
    }

    @Test
    fun theFeedbackLoopHasOneRenderQuantumMoreThanTheDelayTime() {
        // an impulse at 8192 Hz through a delay of 2048 frames and feedback 1/2: the first echo is on time, the next ones come every 2048 + 128 frames
        val out = run(8192, listOf(FloatArray(8192).also { it[0] = 1f }), 0.25, 0.5, emptyList(), intArrayOf(256))[0]
        val echoes = out.indices.filter { out[it] > 1e-4f }
        assertContentEquals(listOf(2048, 4224, 6400), echoes)
        assertEquals(1f, out[2048])
        assertEquals(0.5f, out[4224])
        assertEquals(0.25f, out[6400])
    }

    @Test
    fun aDelayOfNoLengthReadsTheFrameJustWrittenAndStillLoopsEvery128Frames() {
        val out = run(SR, listOf(FloatArray(1024).also { it[0] = 1f }), 0.0, 0.5, emptyList(), intArrayOf(256))[0]
        assertContentEquals(listOf(0, 128, 256, 384, 512, 640, 768, 896), out.indices.filter { out[it] > 1e-4f }.take(8))
    }

    @Test
    fun theDelayTimeIsClampedToTheMaximum() {
        val out = run(4096, listOf(FloatArray(8500).also { it[0] = 1f }), 3.0, 0.5, emptyList(), intArrayOf(256))[0]
        assertContentEquals(listOf(4096, 8320), out.indices.filter { out[it] > 1e-4f })
    }

    @Test
    fun aFractionalDelayInterpolatesWithTheWeightRoundedTo256ths() {
        val out = run(SR, listOf(FloatArray(512).also { it[0] = 1f }), 100.1 / SR, 0.0, emptyList(), intArrayOf(256))[0]
        // 100.1 frames: the weight of the later frame is 0.1, which Chromium rounds to 26/256
        assertEquals(230f / 256f, out[100], 1e-7f)
        assertEquals(26f / 256f, out[101], 1e-7f)
    }

    @Test
    fun theOutputDoesNotDependOnHowTheBlocksAreCut() {
        val noise = listOf(lcg(3, 5000), lcg(4, 5000))
        val events = listOf(
            kotlinx.serialization.json.buildJsonObject { put("frame", kotlinx.serialization.json.JsonPrimitive(1700)); put("delayTime", kotlinx.serialization.json.JsonPrimitive(0.041)) },
        )
        val whole = run(SR, noise, 0.02, 0.6, events, intArrayOf(256))
        val cut = run(SR, noise, 0.02, 0.6, events, intArrayOf(1, 2, 3, 100, 255, 7, 129))
        assertContentEquals(whole[0], cut[0])
        assertContentEquals(whole[1], cut[1])
        assertTrue(peak(whole[0]) > 0.1f)
    }

    @Test
    fun hostileInputsNeverLeaveTheLoopBroken() {
        val delay = FeedbackDelay(SR)
        delay.start(0.01, 0.98)
        val ctx = BlockContext(SR)
        val bad = FloatArray(256) { Float.NaN }
        val quiet = FloatArray(256)
        val outL = FloatArray(256)
        val outR = FloatArray(256)
        ctx.n = 256
        delay.process(bad, bad, outL, outR, 256, ctx)
        for (block in 1..40) {
            ctx.startFrame = block * 256L
            delay.process(quiet, quiet, outL, outR, 256, ctx)
        }
        assertTrue(allFinite(outL) && peak(outL) == 0f, "a NaN or infinite input is not kept in the loop")
    }
}
