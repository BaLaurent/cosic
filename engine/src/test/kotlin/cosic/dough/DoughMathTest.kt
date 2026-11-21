package cosic.dough

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The approximations of dough.h stand in for the C library. Their exact values are held by the golden cases (every voice goes through them); these check that they approximate what
 * they stand for, so that a slip in a constant shows up as what it is.
 */
class DoughMathTest {
    @Test
    fun exp2fIsTwoToThePower() {
        var x = -12f
        while (x <= 12f) {
            val exact = 2.0.pow(x.toDouble())
            assertTrue(abs(ourExp2f(x) - exact) / exact < 2e-4, "2^$x: ${ourExp2f(x)} against $exact")
            x += 0.173f
        }
    }

    @Test
    fun log2fIsTheLogarithmToTheBaseTwo() {
        var x = 0.001f
        while (x < 4000f) {
            assertTrue(abs(ourLog2f(x) - ln(x.toDouble()) / ln(2.0)) < 1e-4, "log2 $x")
            x *= 1.37f
        }
    }

    @Test
    fun powfAndTheDerivedFunctionsAgreeWithThePowerAndTheCornerCasesAreC() {
        assertTrue(abs(ourPowf(3f, 2f) - 9f) < 2e-3f)
        assertTrue(ourPowf(-1f, 2f).isNaN())
        assertEquals(0f, ourPowf(0f, 3f))
        assertEquals(1f, ourPowf(5f, 0f))
        assertTrue(abs(pow10f(1f) - 10f) < 2e-3f)
        assertTrue(abs(pow10f(-1f) - 0.1f) < 2e-4f)
        assertTrue(abs(ourExpm1f(1f) - (Math.E - 1).toFloat()) < 2e-3f)
    }

    @Test
    fun sineApproximationsFollowTheSine() {
        var x = -20f
        while (x < 20f) {
            assertTrue(abs(ourSinf(x) - sin(x.toDouble())) < 1e-3, "our_sinf($x)")
            assertTrue(abs(parSinf(x) - sin(x.toDouble())) < 0.06, "par_sinf($x)") // the parabola is that far from a sine at most
            x += 0.0731f
        }
        assertTrue(abs(parCosf(0f) - 1f) < 0.06f)
    }

    @Test
    fun aMidiNoteIsItsFrequencyThroughTheApproximation() {
        assertTrue(abs(midi2freq(69f) - 440f) < 0.2f)
        assertTrue(abs(midi2freq(57f) - 220f) < 0.2f)
        assertTrue(abs(midi2freq(60f) - 261.6256f) < 0.2f)
    }

    @Test
    fun theCornersOfTheFloatHelpersAreTheCLibrarys() {
        // the CLANGWASM fminf and fmaxf are ternaries: a NaN in either place gives the second argument
        assertEquals(2f, fminf(Float.NaN, 2f))
        assertTrue(fminf(2f, Float.NaN).isNaN())
        assertEquals(2f, fmaxf(Float.NaN, 2f))
        assertEquals(3f, roundf(2.5f))
        assertEquals(-2f, roundf(-2.5f)) // floorf(x + 0.5): halves go up
        assertEquals(0f, ftz(0.00005f, 0.0001f))
        assertEquals(0.5f, ftz(0.5f, 0.0001f))
        assertEquals(1f, lerp(1.5f, 0f, 1f, 2f))
        assertEquals(0f, lerp(-1f, 0f, 1f, 2f))
        assertEquals(0.5f, lerp(0.5f, 0f, 1f, 0f))
        assertTrue(lerp(0.5f, 0f, 1f, 2f) < 0.5f && lerp(0.5f, 0f, 1f, -2f) > 0.5f, "ease in, ease out")
    }
}
