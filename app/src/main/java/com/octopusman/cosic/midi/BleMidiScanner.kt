package com.octopusman.cosic.midi

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.media.midi.MidiDevice
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.ParcelUuid
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The GATT service every Bluetooth MIDI device advertises. */
internal val BLE_MIDI_SERVICE: UUID = UUID.fromString("03B80E5A-EDE8-4B33-A751-6CE34EC4C700")

/** How long a scan runs before it stops by itself. */
private const val SCAN_MILLIS = 15_000L

/** The permissions looking for and connecting to a Bluetooth device needs on Android version [sdk] (which the user is asked for at run time). */
internal fun bluetoothPermissions(sdk: Int): List<String> =
    if (sdk >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN, Manifest.permission.ACCESS_FINE_LOCATION)
    }

/** What a scan for Bluetooth MIDI devices is doing. */
enum class BleScanState { IDLE, SCANNING, NO_BLUETOOTH, BLUETOOTH_OFF, NO_PERMISSION }

/** A Bluetooth MIDI device in range. */
class BleMidiDevice internal constructor(internal val device: BluetoothDevice, val name: String, val address: String)

/**
 * Finds Bluetooth LE MIDI devices (the ones that advertise the MIDI service) and connects them to the system's MIDI manager, where they appear
 * like any other device. [found] is the list of devices seen by the current scan; a scan stops after fifteen seconds or when [stop] says so.
 * Looking for devices needs run-time permissions ([bluetoothPermissions]); without them nothing is scanned and [state] says so.
 */
class BleMidiScanner internal constructor(
    private val context: Context,
    private val manager: MidiManager?,
    private val handler: Handler,
) {
    private val mutableFound = MutableStateFlow<List<BleMidiDevice>>(emptyList())
    private val mutableState = MutableStateFlow(BleScanState.IDLE)

    /** The devices the scan has seen, nearest first as the system reports them. */
    val found: StateFlow<List<BleMidiDevice>> = mutableFound.asStateFlow()

    val state: StateFlow<BleScanState> = mutableState.asStateFlow()

    private val opened = HashMap<String, MidiDevice>()
    private var callback: ScanCallback? = null
    private val stopLater = Runnable { stop() }

    fun hasPermissions(): Boolean =
        bluetoothPermissions(Build.VERSION.SDK_INT).all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun adapter() = context.getSystemService(BluetoothManager::class.java)?.adapter

    /** Starts a scan; the devices it sees are added to [found]. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (mutableState.value == BleScanState.SCANNING) return
        if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            mutableState.value = BleScanState.NO_BLUETOOTH
            return
        }
        if (!hasPermissions()) {
            mutableState.value = BleScanState.NO_PERMISSION
            return
        }
        val adapter = adapter()
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            mutableState.value = if (adapter == null) BleScanState.NO_BLUETOOTH else BleScanState.BLUETOOTH_OFF
            return
        }
        mutableFound.value = emptyList()
        val scan = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                add(result)
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach(::add)
            }

            override fun onScanFailed(errorCode: Int) {
                mutableState.value = BleScanState.IDLE
                callback = null
            }
        }
        callback = scan
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(BLE_MIDI_SERVICE)).build()
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(listOf(filter), settings, scan)
            mutableState.value = BleScanState.SCANNING
            handler.postDelayed(stopLater, SCAN_MILLIS)
        } catch (e: SecurityException) {
            callback = null
            mutableState.value = BleScanState.NO_PERMISSION
        }
    }

    @SuppressLint("MissingPermission")
    private fun add(result: ScanResult) {
        val device = result.device
        if (mutableFound.value.any { it.address == device.address }) return
        val name = try {
            device.name
        } catch (e: SecurityException) {
            null
        } ?: result.scanRecord?.deviceName ?: device.address
        mutableFound.value = mutableFound.value + BleMidiDevice(device, name, device.address)
    }

    /** Stops the scan. */
    @SuppressLint("MissingPermission")
    fun stop() {
        handler.removeCallbacks(stopLater)
        val running = callback
        callback = null
        if (running != null) {
            try {
                adapter()?.bluetoothLeScanner?.stopScan(running)
            } catch (e: SecurityException) {
                // the permission went away: the scan stops with the app
            }
        }
        if (mutableState.value == BleScanState.SCANNING) mutableState.value = BleScanState.IDLE
    }

    /** Connects [device] to the MIDI manager; [done] is told whether it worked. The device then shows up in the list of ports. */
    @SuppressLint("MissingPermission")
    fun connect(device: BleMidiDevice, done: (Boolean) -> Unit) {
        val midi = manager
        if (midi == null) {
            done(false)
            return
        }
        try {
            midi.openBluetoothDevice(
                device.device,
                { opened ->
                    if (opened != null) this.opened[device.address] = opened
                    done(opened != null)
                },
                handler,
            )
        } catch (e: SecurityException) {
            done(false)
        }
    }

    /** Lets go of the Bluetooth devices this scanner connected (the connection ends with the last client). */
    fun disconnectAll() {
        for (device in opened.values) {
            try {
                device.close()
            } catch (e: java.io.IOException) {
                // already gone
            }
        }
        opened.clear()
    }
}
