package cosic.audio.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The nine distortion curves and the `distort-processor` worklet against the real source (helpers.mjs:509-599, worklets.mjs:429-463). */
class DistortionTest {
    private val golden = DspGolden.load("distort.json")
    private val shared = DspGolden.floats(golden["input"]!!.jsonPrimitive.content)

    @Test
    fun theCurvesMatchTheWorkletAtEveryGridPoint() {
        assertEquals(golden["names"]!!.jsonArray.map { it.jsonPrimitive.content }, Distortion.names)
        val xs = golden["xs"]!!.jsonArray.map { it.jsonPrimitive.double }
        var checked = 0
        for (entry in golden["grid"]!!.jsonArray) {
            val e = entry.jsonObject
            val index = Distortion.names.indexOf(e["name"]!!.jsonPrimitive.content)
            val k = e["k"]!!.jsonPrimitive.double
            val ys = e["y"]!!.jsonArray
            for (i in xs.indices) {
                val expected = ys[i].jsonPrimitive.double
                val actual = Distortion.apply(index, xs[i], k)
                assertTrue(abs(actual - expected) <= 1e-12 * max(1.0, abs(expected)), "${Distortion.names[index]} x=${xs[i]} k=$k: $actual, expected $expected")
                checked++
            }
        }
        assertEquals(Distortion.names.size * 5 * xs.size, checked)
    }

    private fun primitiveToType(p: JsonPrimitive): Any = if (p.isString) p.content else p.double

    @Test
    fun theProcessorMatchesTheWorkletForEveryAlgorithmAndParameter() {
        for (case in DspGolden.cases(golden["processor"] as JsonArray, sharedInput = shared)) {
            val index = Distortion.algorithmIndex(primitiveToType(case.json["algorithm"]!!.jsonPrimitive))
            val data = case.copyOfInput()
            val processor = DistortProcessor(index)
            for (q in 0 until case.quanta) {
                processor.process(data[0], null, q * 128, (q + 1) * 128, case.param(q, "distort"), case.param(q, "postgain"))
            }
            assertClose(case.name, case.output[0], data[0], atol = 1e-7, rtol = 1e-6)
        }
    }

    @Test
    fun movingParametersAreAppliedPerFrame() {
        for (case in DspGolden.cases(golden["aRate"] as JsonArray, sharedInput = shared)) {
            val index = Distortion.algorithmIndex(case.json["algorithm"]!!.jsonPrimitive.content)
            val data = case.copyOfInput()
            val distort = FloatArray(case.frames())
            val postgain = FloatArray(case.frames())
            for (q in 0 until case.quanta) {
                case.moving(q, "distort").copyInto(distort, q * 128)
                case.moving(q, "postgain").copyInto(postgain, q * 128)
            }
            DistortProcessor(index).processModulated(data[0], null, 0, case.frames(), distort, postgain)
            assertClose(case.name, case.output[0], data[0], atol = 1e-7, rtol = 1e-6)
        }
    }

    @Test
    fun stereoProcessesBothChannelsAlike() {
        val left = shared.copyOf(256)
        val right = shared.copyOf(256)
        DistortProcessor(Distortion.algorithmIndex("fold")).process(left, right, 0, 256, 1.0, 0.7)
        assertContentEquals(left, right)
    }

    @Test
    fun theCurvesHaveTheDocumentedValues() {
        val k = Math.E - 1
        assertEquals(0.7310586, Distortion.apply(0, 0.5, k), 1e-6, "scurve")
        assertEquals(0.87619, Distortion.apply(1, 0.5, k), 1e-5, "soft")
        assertEquals(1.0, Distortion.apply(2, 0.5, k), 1e-12, "hard")
        assertEquals(0.9159, Distortion.apply(3, 0.5, k), 1e-3, "cubic")
        assertEquals(0.9295705, Distortion.apply(6, 0.5, k), 1e-6, "fold")
        assertEquals(0.5126873, Distortion.apply(6, 0.8, k), 1e-6, "fold at 0.8")
        assertEquals(0.99389, Distortion.apply(7, 0.5, k), 2e-4, "sinefold")
        // distort = 0 is k = 0: the identity for the S-curve with postgain 1
        val io = floatArrayOf(-0.9f, -0.2f, 0f, 0.4f, 0.8f)
        val expected = io.copyOf()
        DistortProcessor(0).process(io, null, 0, io.size, 0.0, 1.0)
        assertContentEquals(expected, io)
    }

    @Test
    fun theAlgorithmIndexFollowsGetDistortionAlgorithm() {
        assertEquals(listOf("scurve", "soft", "hard", "cubic", "diode", "asym", "fold", "sinefold", "chebyshev"), Distortion.names)
        assertEquals(6, Distortion.algorithmIndex("fold"))
        assertEquals(8, Distortion.algorithmIndex("chebyshev"))
        assertEquals(5, Distortion.algorithmIndex(5))
        assertEquals(5.0.toInt(), Distortion.algorithmIndex(5.0))
        assertEquals(0, Distortion.algorithmIndex(9), "numbers wrap around the nine curves")
        assertEquals(1, Distortion.algorithmIndex(10))
        assertEquals(0, Distortion.algorithmIndex("nonsense"), "an unknown name is the first curve")
        assertEquals(0, Distortion.algorithmIndex(null))
        assertEquals(8, Distortion.algorithmIndex(-1), "where the worklet would throw, a negative number wraps with a positive modulo")
    }

    @Test
    fun postgainIsClampedAndTheCurveIsBounded() {
        val loud = FloatArray(64) { (it - 32) / 8f }
        val quiet = loud.copyOf()
        DistortProcessor(Distortion.algorithmIndex("hard")).process(loud, null, 0, 64, 2.0, 5.0) // postgain 5 -> 1
        DistortProcessor(Distortion.algorithmIndex("hard")).process(quiet, null, 0, 64, 2.0, 0.0) // postgain 0 -> 0.001
        assertTrue(loud.all { abs(it) <= 1f })
        assertTrue(quiet.all { abs(it) <= 0.001f })
    }
}
