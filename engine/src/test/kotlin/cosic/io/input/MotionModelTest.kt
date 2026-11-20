package cosic.io.input

import cosic.io.MotionChannel
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MotionModelTest {
    private fun near(expected: Double, actual: Double, eps: Double = 1e-9) =
        assertTrue(abs(expected - actual) <= eps, "expected $expected but was $actual")

    @Test
    fun accelerationMapsMinusOneToOneOntoZeroToOne() {
        assertEquals(0.5, MotionModel.acceleration(0.0))
        assertEquals(0.0, MotionModel.acceleration(-1.0))
        assertEquals(2.0, MotionModel.acceleration(3.0), "values are not clamped")
    }

    @Test
    fun gravityMapsMinusGToGOntoZeroToOne() {
        assertEquals(1.0, MotionModel.gravity(9.81))
        assertEquals(0.5, MotionModel.gravity(0.0))
        assertEquals(0.0, MotionModel.gravity(-9.81))
    }

    @Test
    fun rotationAndOrientationScales() {
        assertEquals(0.5, MotionModel.rotation(0.0))
        assertEquals(0.25, MotionModel.alpha(90.0))
        assertEquals(0.25, MotionModel.beta(-90.0))
        assertEquals(0.75, MotionModel.gamma(45.0))
    }

    @Test
    fun everyChannelHasItsScale() {
        for (c in MotionChannel.entries) near(MotionModel.normalise(c, 0.0), when (c) {
            MotionChannel.ORI_ALPHA, MotionChannel.ABS_ALPHA -> 0.0
            MotionChannel.ORI_BETA, MotionChannel.ABS_BETA -> 0.5
            MotionChannel.ORI_GAMMA, MotionChannel.ABS_GAMMA -> 0.5
            else -> 0.5
        })
        assertEquals(0.75, MotionModel.normalise(MotionChannel.ABS_GAMMA, 45.0))
        assertEquals(1.0, MotionModel.normalise(MotionChannel.GRAV_Z, 9.81))
        assertEquals(0.25, MotionModel.normalise(MotionChannel.ROT_BETA, -90.0))
    }

    /** `Rz(alpha) * Rx(beta) * Ry(gamma)`, row-major. */
    private fun matrix(alphaDeg: Double, betaDeg: Double, gammaDeg: Double): DoubleArray {
        val a = Math.toRadians(alphaDeg); val b = Math.toRadians(betaDeg); val g = Math.toRadians(gammaDeg)
        val ca = cos(a); val sa = sin(a); val cb = cos(b); val sb = sin(b); val cg = cos(g); val sg = sin(g)
        return doubleArrayOf(
            ca * cg - sa * sb * sg, -sa * cb, ca * sg + sa * sb * cg,
            sa * cg + ca * sb * sg, ca * cb, sa * sg - ca * sb * cg,
            -cb * sg, sb, cb * cg,
        )
    }

    @Test
    fun eulerAnglesRoundTrip() {
        for ((a, b, g) in listOf(
            Triple(30.0, 20.0, 10.0), Triple(200.0, -45.0, 60.0), Triple(359.0, 170.0, -80.0), Triple(10.0, -170.0, 80.0), Triple(90.0, 0.0, 0.0),
        )) {
            val (ra, rb, rg) = MotionModel.eulerFromMatrix(matrix(a, b, g))
            near(a, ra, 1e-7); near(b, rb, 1e-7); near(g, rg, 1e-7)
        }
    }

    @Test
    fun theIdentityHasNoAngles() {
        val (a, b, g) = MotionModel.eulerFromMatrix(doubleArrayOf(1.0, 0.0, 0.0, 0.0, 1.0, 0.0, 0.0, 0.0, 1.0))
        assertEquals(0.0, a); assertEquals(0.0, b); assertEquals(0.0, g)
    }

    @Test
    fun aQuarterTurnAboutZIsAlpha() {
        val (a, b, g) = MotionModel.eulerFromMatrix(matrix(90.0, 0.0, 0.0))
        near(90.0, a); near(0.0, b); near(0.0, g)
    }

    @Test
    fun theDegenerateCasesStayInRange() {
        // gamma = -90: R22 = cos(beta) * cos(gamma) = 0
        val (a, b, g) = MotionModel.eulerFromMatrix(matrix(40.0, 30.0, -90.0))
        near(-90.0, g, 1e-6); near(30.0, b, 1e-6); near(40.0, a, 1e-6)
        // beta = 90: R20 = R22 = 0
        val (_, b2, g2) = MotionModel.eulerFromMatrix(matrix(0.0, 90.0, 0.0))
        near(90.0, b2, 1e-6); near(0.0, g2, 1e-6)
    }
}
