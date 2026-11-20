package cosic.script.catalogue

import cosic.io.serial.SerialHook
import cosic.io.serial.SerialRoute
import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy

/*
 * `.serial(br = 115200, sendcrc = false, singlecharids = false, name = 'default')` of `@strudel/serial` (serial.mjs:83): sends the
 * haps of the pattern to a serial port as text (see [cosic.io.serial.serialBytes]) instead of sounding them. `getWriter` is the choice
 * of a port in the browser and has no counterpart: the app opens the first USB serial device it was given access to.
 */
internal fun registerSerial() = catalogue("Serial") {
    method(listOf("serial"), 0) { ctx ->
        val baud = if (ctx.kernelArg(0) === Undefined) SerialRoute.DEFAULT_BAUD_RATE.toDouble() else jsToNumber(ctx.kernelArg(0))
        if (!baud.isFinite() || baud < 1 || baud > Int.MAX_VALUE) ctx.fail("baudRate must be a positive integer")
        val route = SerialRoute(
            baudRate = baud.toInt(),
            sendCrc = ctx.kernelArg(1) !== Undefined && jsTruthy(ctx.kernelArg(1)),
            singleCharIds = ctx.kernelArg(2) !== Undefined && jsTruthy(ctx.kernelArg(2)),
            name = if (ctx.kernelArg(3) === Undefined) SerialRoute.DEFAULT_PORT_NAME else jsToString(ctx.kernelArg(3)),
        )
        val effects = ctx.effects
        effects.outputs.serial?.prepare(route)
        ctx.bridge.receiverOf(ctx.self, "serial").onSchedule(SerialHook(route, effects), true)
    }
}
