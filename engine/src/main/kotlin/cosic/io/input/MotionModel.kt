package cosic.io.input

import cosic.io.MotionChannel

/**
 * What the motion signals of Strudel (`accX`, `gravX`, `rotA`, `oriB`, `absOriG`... of `motion.mjs`) make of what a sensor reports: each value
 * is brought into 0..1 by a fixed offset and scale, without clamping, so a hard shake leaves the range. The sensors give the units of the
 * browser's `devicemotion` and `deviceorientation` events (m/s², degrees per second, degrees); the app writes those into the
 * [cosic.io.InputState] and the signals normalise at the time they are read.
 */
object MotionModel {
    /** Earth gravity as `motion.mjs` takes it. */
    const val GRAVITY = 9.81

    /** `event.acceleration` (m/s², without gravity): -1..1 maps to 0..1. */
    fun acceleration(v: Double): Double = (v + 1) / 2

    /** `event.accelerationIncludingGravity` (m/s²): -g..g maps to 0..1. */
    fun gravity(v: Double): Double = (v + GRAVITY) / (2 * GRAVITY)

    /** `event.rotationRate` (degrees per second): -180..180 maps to 0..1. */
    fun rotation(degPerSec: Double): Double = (degPerSec + 180) / 360

    /** The orientation's alpha, the heading, 0..360 degrees. */
    fun alpha(deg: Double): Double = deg / 360

    /** The orientation's beta, the front-to-back tilt, -180..180 degrees. */
    fun beta(deg: Double): Double = (deg + 180) / 360

    /** The orientation's gamma, the left-to-right tilt, -90..90 degrees. */
    fun gamma(deg: Double): Double = (deg + 90) / 180

    /** The normalised value of [channel] for the sensor reading [raw]: what `signal(() => deviceMotion.get...().x)` hands back. */
    fun normalise(channel: MotionChannel, raw: Double): Double = when (channel) {
        MotionChannel.ACC_X, MotionChannel.ACC_Y, MotionChannel.ACC_Z -> acceleration(raw)
        MotionChannel.GRAV_X, MotionChannel.GRAV_Y, MotionChannel.GRAV_Z -> gravity(raw)
        MotionChannel.ROT_ALPHA, MotionChannel.ROT_BETA, MotionChannel.ROT_GAMMA -> rotation(raw)
        MotionChannel.ORI_ALPHA, MotionChannel.ABS_ALPHA -> alpha(raw)
        MotionChannel.ORI_BETA, MotionChannel.ABS_BETA -> beta(raw)
        MotionChannel.ORI_GAMMA, MotionChannel.ABS_GAMMA -> gamma(raw)
    }

    /**
     * The three angles of the W3C device orientation (`alpha`, `beta`, `gamma`, in degrees) of a rotation matrix [m] (row-major 3x3, from
     * device to world coordinates, as `SensorManager.getRotationMatrixFromVector` gives it). The matrix is `Rz(alpha) * Rx(beta) * Ry(gamma)`
     * (the angles are applied in the order Z-X'-Y'', Device Orientation spec, "Worked example"), so `m[7] = sin(beta)` and the angles come
     * back out of the matrix the way the spec's implementation does it: alpha in `[0, 360)`, beta in `[-180, 180)`, gamma in `[-90, 90)`.
     */
    fun eulerFromMatrix(m: DoubleArray): Triple<Double, Double, Double> {
        require(m.size >= 9) { "a rotation matrix has 9 entries" }
        val halfPi = Math.PI / 2
        var alpha: Double
        var beta: Double
        val gamma: Double
        if (m[8] > 0) {
            alpha = Math.atan2(-m[1], m[4])
            beta = Math.asin(m[7].coerceIn(-1.0, 1.0))
            gamma = Math.atan2(-m[6], m[8])
        } else if (m[8] < 0) {
            alpha = Math.atan2(m[1], -m[4])
            beta = -Math.asin(m[7].coerceIn(-1.0, 1.0))
            beta += if (beta >= 0) -Math.PI else Math.PI
            gamma = Math.atan2(m[6], -m[8])
        } else if (m[6] > 0) {
            alpha = Math.atan2(-m[1], m[4])
            beta = Math.asin(m[7].coerceIn(-1.0, 1.0))
            gamma = -halfPi
        } else if (m[6] < 0) {
            alpha = Math.atan2(m[1], -m[4])
            beta = -Math.asin(m[7].coerceIn(-1.0, 1.0))
            beta += if (beta >= 0) -Math.PI else Math.PI
            gamma = -halfPi
        } else {
            alpha = Math.atan2(m[3], m[0])
            beta = if (m[7] > 0) halfPi else -halfPi
            gamma = 0.0
        }
        if (alpha < 0) alpha += 2 * Math.PI
        // `+ 0.0` turns a -0.0 into 0.0
        return Triple(Math.toDegrees(alpha) + 0.0, Math.toDegrees(beta) + 0.0, Math.toDegrees(gamma) + 0.0)
    }
}
