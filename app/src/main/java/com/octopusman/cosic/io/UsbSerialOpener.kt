package com.octopusman.cosic.io

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import cosic.io.serial.SerialPort
import cosic.io.serial.SerialPortOpener
import cosic.io.serial.SerialRoute
import java.io.IOException
import java.util.concurrent.Executors

/** How long a write to the device may take before it is reported as failed. */
private const val WRITE_TIMEOUT_MS = 1000

/**
 * Opens the ports `.serial()` writes to on the USB serial devices that are attached (`usb-serial-for-android`: CDC-ACM boards such as
 * an Arduino Uno or a Pico, and CH340, CP210x, FTDI and PL2303 adapters), as the engine's [cosic.io.serial.SerialOutput] asks for them.
 * The first port name to ask gets the first device, the next one the next device that no name has; a name that finds none shares the
 * port of `default`. The system's permission dialog is shown the first time a device is used. A port is 8N1 at the baud rate of the
 * route, as WebSerial opens it.
 *
 * [open] does not block: the permission, the USB transfers that open the device and the result all happen later, on a thread of its own.
 * A device that is unplugged closes its port, so the next write fails and the engine forgets the port; the next evaluation opens it again.
 */
class UsbSerialOpener(context: Context, private val log: (String) -> Unit = {}) : SerialPortOpener {
    private val app = context.applicationContext
    private val manager = app.getSystemService(Context.USB_SERVICE) as UsbManager
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "cosic-usb-serial").apply { isDaemon = true } }
    private val permissionAction = "${app.packageName}.USB_SERIAL_PERMISSION"

    private val lock = Any()
    private val owners = HashMap<String, UsbPort>() // port name -> the device port that serves it
    private val claims = HashMap<String, String>() // port name -> the device it uses or waits for the permission of
    private val detachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java) ?: return
            val gone = synchronized(lock) { owners.values.filter { it.device.deviceName == device.deviceName }.distinct() }
            for (port in gone) port.close()
            synchronized(lock) { claims.values.removeAll { it == device.deviceName } }
        }
    }

    init {
        ContextCompat.registerReceiver(app, detachReceiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED), ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun open(route: SerialRoute, result: (SerialPort?) -> Unit) {
        if (!app.packageManager.hasSystemFeature(PackageManager.FEATURE_USB_HOST)) {
            log("serial: this device cannot act as a USB host")
            result(null)
            return
        }
        worker.execute {
            try {
                openNow(route, result)
            } catch (e: Exception) {
                log("serial: ${e.message ?: e.javaClass.simpleName}")
                release(route.name)
                result(null)
            }
        }
    }

    private fun openNow(route: SerialRoute, result: (SerialPort?) -> Unit) {
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(manager)
        val driver = pick(route.name, drivers)
        if (driver == null) {
            val shared = synchronized(lock) { owners["default"] }
            if (shared != null && route.name != "default") {
                synchronized(lock) { owners[route.name] = shared }
                result(SharedPort(shared))
            } else {
                log("serial: no USB serial device is attached")
                result(null)
            }
            return
        }
        if (manager.hasPermission(driver.device)) {
            result(connect(driver, route) ?: run { release(route.name); null })
        } else {
            requestPermission(driver.device) { granted ->
                if (!granted) {
                    log("serial: access to ${driver.device.deviceName} was refused")
                    release(route.name)
                    result(null)
                } else {
                    worker.execute {
                        result(runCatching { connect(driver, route) }.onFailure { log("serial: ${it.message}") }.getOrNull() ?: run { release(route.name); null })
                    }
                }
            }
        }
    }

    /**
     * The first attached driver whose device no other name has or is waiting for, which [name] then claims; null when there is none.
     * A claim stays until the port is closed or opening it failed.
     */
    private fun pick(name: String, drivers: List<UsbSerialDriver>): UsbSerialDriver? = synchronized(lock) {
        val taken = claims.filterKeys { it != name }.values.toSet()
        val driver = drivers.firstOrNull { it.device.deviceName !in taken }
        if (driver != null) claims[name] = driver.device.deviceName else claims.remove(name)
        driver
    }

    private fun release(name: String) {
        synchronized(lock) { if (owners[name] == null) claims.remove(name) }
    }

    private fun requestPermission(device: UsbDevice, done: (Boolean) -> Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val answered = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (answered?.deviceName != device.deviceName) return
                app.unregisterReceiver(this)
                done(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(permissionAction), ContextCompat.RECEIVER_NOT_EXPORTED)
        // the system fills the intent in with the answer, so it has to be mutable from Android 12 on
        val intent = PendingIntent.getBroadcast(
            app,
            0,
            Intent(permissionAction).setPackage(app.packageName),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.requestPermission(device, intent)
    }

    private fun connect(driver: UsbSerialDriver, route: SerialRoute): SerialPort? {
        val connection = manager.openDevice(driver.device)
        if (connection == null) {
            log("serial: could not open ${driver.device.deviceName}")
            return null
        }
        val port = driver.ports.first()
        try {
            port.open(connection)
            port.setParameters(route.baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            runCatching { port.close() }
            connection.close()
            throw e
        }
        val opened = UsbPort(driver.device, port, connection) { self ->
            synchronized(lock) {
                val names = owners.filterValues { it === self }.keys
                owners.keys.removeAll(names)
                claims.keys.removeAll(names)
            }
        }
        synchronized(lock) { owners[route.name] = opened }
        return opened
    }

    /** Closes every port and stops listening for devices that are unplugged. */
    fun close() {
        runCatching { app.unregisterReceiver(detachReceiver) }
        val all = synchronized(lock) { owners.values.distinct() }
        for (port in all) port.close()
        worker.shutdown()
    }

    private class UsbPort(
        val device: UsbDevice,
        private val port: UsbSerialPort,
        private val connection: UsbDeviceConnection,
        private val onClosed: (UsbPort) -> Unit,
    ) : SerialPort {
        @Volatile
        private var closed = false

        override fun write(bytes: ByteArray) {
            if (closed) throw IOException("the port is closed")
            port.write(bytes, WRITE_TIMEOUT_MS)
        }

        override fun close() {
            if (closed) return
            closed = true
            onClosed(this)
            runCatching { port.close() }
            runCatching { connection.close() }
        }
    }

    /** A name that shares the port of `default`: writes go to that port, and closing leaves it open for the other name. */
    private class SharedPort(private val target: UsbPort) : SerialPort {
        override fun write(bytes: ByteArray) = target.write(bytes)

        override fun close() {}
    }
}
