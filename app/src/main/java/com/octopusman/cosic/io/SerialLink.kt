package com.octopusman.cosic.io

import android.content.Context
import cosic.io.serial.SerialOutput
import cosic.io.serial.SerialPort
import cosic.io.serial.SerialRoute

/**
 * The serial output of the app, `.serial()`'s device: a [SerialOutput] over the USB serial devices that are attached. [close] ends it for good (the ports and the
 * threads go, and a closed output cannot be opened again), so the app makes a new link when it is opened once more in the same process.
 *
 * The USB side ([UsbSerialOpener]: a broadcast receiver for devices that are unplugged, and a thread) is only made when a program first asks for a port, so an app
 * that never uses serial output holds nothing, and a device without a USB service does not fail when the engine is made.
 */
class SerialLink(private val context: Context, private val log: (String) -> Unit) {
    private val lock = Any()
    private var opener: UsbSerialOpener? = null
    private var closed = false

    /** What the engine writes to; set it as `outputs.serial`. */
    val output = SerialOutput({ route, result -> open(route, result) }, log)

    private fun open(route: SerialRoute, result: (SerialPort?) -> Unit) {
        val usb = synchronized(lock) {
            if (closed) null else opener ?: try {
                UsbSerialOpener(context, log).also { opener = it }
            } catch (e: RuntimeException) {
                log("serial: USB is not available (${e.message ?: e.javaClass.simpleName})")
                null
            }
        }
        if (usb == null) result(null) else usb.open(route, result)
    }

    /** Closes the ports and ends the writing; what is queued is dropped. */
    fun close() {
        val usb = synchronized(lock) {
            closed = true
            opener.also { opener = null }
        }
        output.close()
        usb?.close()
    }
}
