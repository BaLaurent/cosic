package com.octopusman.cosic.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import cosic.io.InputState
import cosic.io.MotionChannel
import cosic.io.input.MotionModel
import kotlin.math.sqrt

/**
 * What the sensors report, put into an [InputState] in the units of the browser's `devicemotion` and `deviceorientation` events (the motion
 * signals of a program normalise them): m/s², degrees per second, degrees. Pure functions of the sensor values, so they can be checked
 * without a device.
 */
object SensorMapper {
    /**
     * Puts the reading [values] of a sensor of [type] into [inputs]. [orientationToo] makes a rotation vector set the relative orientation as well as
     * the absolute one (a device that has no game rotation vector). Sensors that are not used here are ignored.
     */
    fun apply(type: Int, values: FloatArray, inputs: InputState, orientationToo: Boolean = false) {
        when (type) {
            // the acceleration of the device without gravity (`event.acceleration`)
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                if (values.size < 3) return
                inputs.setMotion(MotionChannel.ACC_X, values[0].toDouble())
                inputs.setMotion(MotionChannel.ACC_Y, values[1].toDouble())
                inputs.setMotion(MotionChannel.ACC_Z, values[2].toDouble())
            }
            // with gravity (`event.accelerationIncludingGravity`)
            Sensor.TYPE_ACCELEROMETER -> {
                if (values.size < 3) return
                inputs.setMotion(MotionChannel.GRAV_X, values[0].toDouble())
                inputs.setMotion(MotionChannel.GRAV_Y, values[1].toDouble())
                inputs.setMotion(MotionChannel.GRAV_Z, values[2].toDouble())
            }
            // `event.rotationRate`: alpha about z, beta about x, gamma about y, in degrees per second
            Sensor.TYPE_GYROSCOPE -> {
                if (values.size < 3) return
                inputs.setMotion(MotionChannel.ROT_ALPHA, Math.toDegrees(values[2].toDouble()))
                inputs.setMotion(MotionChannel.ROT_BETA, Math.toDegrees(values[0].toDouble()))
                inputs.setMotion(MotionChannel.ROT_GAMMA, Math.toDegrees(values[1].toDouble()))
            }
            Sensor.TYPE_GAME_ROTATION_VECTOR -> orientation(values, inputs, MotionChannel.ORI_ALPHA, MotionChannel.ORI_BETA, MotionChannel.ORI_GAMMA)
            Sensor.TYPE_ROTATION_VECTOR -> {
                orientation(values, inputs, MotionChannel.ABS_ALPHA, MotionChannel.ABS_BETA, MotionChannel.ABS_GAMMA)
                if (orientationToo) orientation(values, inputs, MotionChannel.ORI_ALPHA, MotionChannel.ORI_BETA, MotionChannel.ORI_GAMMA)
            }
        }
    }

    private fun orientation(values: FloatArray, inputs: InputState, alpha: MotionChannel, beta: MotionChannel, gamma: MotionChannel) {
        if (values.size < 3) return
        val (a, b, g) = MotionModel.eulerFromMatrix(rotationMatrix(values))
        inputs.setMotion(alpha, a)
        inputs.setMotion(beta, b)
        inputs.setMotion(gamma, g)
    }

    /** The row-major rotation matrix (device to world) of a rotation vector, as `SensorManager.getRotationMatrixFromVector` makes it. */
    fun rotationMatrix(rotationVector: FloatArray): DoubleArray {
        val x = rotationVector[0].toDouble()
        val y = rotationVector[1].toDouble()
        val z = rotationVector[2].toDouble()
        val w = if (rotationVector.size >= 4) rotationVector[3].toDouble() else sqrt(maxOf(0.0, 1 - x * x - y * y - z * z))
        return doubleArrayOf(
            1 - 2 * (y * y + z * z), 2 * (x * y - z * w), 2 * (x * z + y * w),
            2 * (x * y + z * w), 1 - 2 * (x * x + z * z), 2 * (y * z - x * w),
            2 * (x * z - y * w), 2 * (y * z + x * w), 1 - 2 * (x * x + y * y),
        )
    }
}

/**
 * The motion sensors of the device, feeding an [InputState] for as long as it is started: for a program that called `enableMotion()` (the engine
 * reports `InputKind.MOTION`) and only while the app is in the foreground, so the sensors do not drain the battery. At the game rate
 * (about 50 Hz) none of them needs a permission.
 */
class SensorHub(context: Context, private val inputs: InputState) {
    private val manager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    @Volatile
    var running = false
        private set

    private val gameRotation: Sensor? = manager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            SensorMapper.apply(event.sensor.type, event.values, inputs, orientationToo = gameRotation == null)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /** Starts listening to every sensor the device has; calling it again does nothing. */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        for (type in listOf(
            Sensor.TYPE_LINEAR_ACCELERATION, Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR,
        )) {
            manager.getDefaultSensor(type)?.let { manager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME) }
        }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        manager.unregisterListener(listener)
    }
}
