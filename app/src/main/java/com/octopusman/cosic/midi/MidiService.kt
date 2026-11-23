package com.octopusman.cosic.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiDeviceStatus
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import com.octopusman.cosic.R
import cosic.CosicEngine
import cosic.FrameClock
import cosic.MidiSink
import cosic.io.midi.MidiHub
import cosic.io.midi.MidiInputSource
import cosic.io.midi.MidiStreamParser
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CosicMidi"

/** What a program can see of the MIDI devices: the names of the outputs and of the inputs, in the order it counts them. */
data class MidiDeviceNames(val outputs: List<String>, val inputs: List<String>)

/** Messages that wait for a port that is still being opened, at most. */
private const val MAX_BACKLOG = 256

/**
 * The MIDI devices of the phone (USB, Bluetooth LE and virtual ones, through `android.media.midi`) as the engine uses them: it is the engine's
 * [MidiSink] (what `.midi()` sends goes out through it) and its [MidiInputSource] (what `midin` and `midikeys` listen to comes in through it).
 *
 * - Outputs and inputs are numbered the way a program counts them ([MidiPortLists]); every port is called by the name of its device.
 * - A device is opened when the first message for one of its ports is sent, and then kept open; a port that another app has taken cannot be
 *   used, and messages for it are dropped. The messages are queued with the time of their frame ([FrameClock]) and sent at that time by a
 *   thread of their own ([MidiTimedSender]), so that the timing is the audio's and does not depend on the thread that makes the events.
 * - Inputs are only listened to while a program uses them ([setListening]), so that a device that nobody asked for is left alone.
 * - Devices that come and go are told to the engine ([MidiHub.onDevicesChanged]), which logs what a program using them would want to know.
 *
 * [attachTo] connects the service to an engine; there is one service for the app.
 */
class MidiService(context: Context) : MidiSink, MidiInputSource {
    private val appContext = context.applicationContext
    private val manager: MidiManager? = appContext.getSystemService(Context.MIDI_SERVICE) as? MidiManager
    private val handler = Handler(Looper.getMainLooper())

    /** True when the phone has MIDI at all. */
    val supported: Boolean get() = manager != null

    /** Finds and connects Bluetooth MIDI devices. */
    val scanner = BleMidiScanner(appContext, manager, handler)

    @Volatile
    private var snapshot = MidiPortLists.EMPTY
    private var infoById: Map<Int, MidiDeviceInfo> = emptyMap()

    @Volatile
    private var hub: MidiHub? = null

    @Volatile
    private var frameClock: () -> FrameClock? = { null }

    @Volatile
    private var listening = false
    private var started = false

    /** Sees every message of every input port the service listens to, before the engine does (a diagnostic, and what the tests read). Called on the MIDI thread. */
    @Volatile
    var monitor: ((inputName: String, message: ByteArray) -> Unit)? = null

    private val mutableDevices = MutableStateFlow(MidiDeviceNames(emptyList(), emptyList()))

    /** The devices as they are now, for the screen that lists them. */
    val devices: StateFlow<MidiDeviceNames> = mutableDevices.asStateFlow()

    private class OutputHandle(val ref: MidiPortRef) {
        @Volatile
        var port: MidiInputPort? = null

        @Volatile
        var opening = false

        val backlog = ArrayDeque<ByteArray>()
    }

    private class DeviceSlot(val info: MidiDeviceInfo) {
        @Volatile
        var device: MidiDevice? = null
        var opening = false
        val waiting = ArrayList<(MidiDevice?) -> Unit>()
        val inputs = ConcurrentHashMap<Int, MidiOutputPort>()
    }

    private val handles = ConcurrentHashMap<Pair<Int, Int>, OutputHandle>()
    private val slots = ConcurrentHashMap<Int, DeviceSlot>()

    private val sender = MidiTimedSender<OutputHandle>(
        deliver = ::deliver,
        onThreadStart = {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (e: RuntimeException) {
                // an ordinary priority is only worse
            }
        },
    )

    private val callback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) = rebuild()

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            release(device.id)
            rebuild()
        }

        override fun onDeviceStatusChanged(status: MidiDeviceStatus) = Unit
    }

    /** Makes this the MIDI of [engine]: its programs send to and listen to the devices of the phone from now on. Call it once. */
    fun attachTo(engine: CosicEngine) {
        hub = engine.outputs.midiHub
        frameClock = { engine.outputs.clock }
        engine.outputs.midi = this
        engine.outputs.midiHub.source = this
        engine.outputs.midiHub.store = SharedPreferencesMidiStore(appContext)
        start()
    }

    /** Starts watching the devices. */
    @Synchronized
    @Suppress("DEPRECATION")
    fun start() {
        val midi = manager ?: return
        if (started) return
        started = true
        midi.registerDeviceCallback(callback, handler)
        rebuild()
    }

    @Suppress("DEPRECATION")
    private fun rebuild() {
        val midi = manager ?: return
        val infos = midi.devices
        val descriptions = infos.map { info ->
            val ports = info.ports
            MidiDeviceDescription(
                info.id,
                midiDeviceName(info.properties.getString(MidiDeviceInfo.PROPERTY_NAME), info.properties.getString(MidiDeviceInfo.PROPERTY_PRODUCT))
                    ?: appContext.getString(R.string.midi_device_fallback, info.id),
                ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_INPUT }.map { it.portNumber },
                ports.filter { it.type == MidiDeviceInfo.PortInfo.TYPE_OUTPUT }.map { it.portNumber },
            )
        }
        synchronized(this) { infoById = infos.associateBy { it.id } }
        val lists = MidiPortLists.of(descriptions)
        snapshot = lists
        mutableDevices.value = MidiDeviceNames(lists.outputs.map { it.name }, lists.inputs.map { it.name })
        hub?.onDevicesChanged()
        if (listening) syncListening()
    }

    // ---- outputs -------------------------------------------------------------------------------------------------------------

    override fun outputNames(): List<String> = mutableDevices.value.outputs

    override fun send(output: Int, bytes: ByteArray, frame: Long, offsetMs: Double) {
        val ref = snapshot.outputs.getOrNull(output) ?: return
        val handle = handles.getOrPut(ref.deviceId to ref.portNumber) { OutputHandle(ref) }
        ensureOpen(handle)
        val base = frameClock()?.nanoTimeAtFrame(frame) ?: System.nanoTime()
        sender.send(handle, bytes, base + Math.round(offsetMs * 1e6))
    }

    private fun ensureOpen(handle: OutputHandle) {
        if (handle.port != null) return
        synchronized(handle) {
            if (handle.port != null || handle.opening) return
            handle.opening = true
        }
        withDevice(handle.ref.deviceId) { device ->
            val port = try {
                device?.openInputPort(handle.ref.portNumber)
            } catch (e: IOException) {
                null
            }
            val backlog: List<ByteArray>
            synchronized(handle) {
                handle.port = port
                handle.opening = false
                backlog = handle.backlog.toList()
                handle.backlog.clear()
            }
            if (port == null) Log.w(TAG, "cannot open port ${handle.ref.portNumber} of ${handle.ref.name}")
            // what came due while the port was being opened goes out now, in order
            if (port != null) for (message in backlog) write(handle, port, message)
        }
    }

    /** Called by the sender when a message is due. */
    private fun deliver(handle: OutputHandle, bytes: ByteArray) {
        val port = handle.port
        if (port != null) {
            write(handle, port, bytes)
            return
        }
        synchronized(handle) {
            if (handle.backlog.size >= MAX_BACKLOG) handle.backlog.removeFirst()
            handle.backlog.addLast(bytes)
        }
        ensureOpen(handle)
    }

    private fun write(handle: OutputHandle, port: MidiInputPort, bytes: ByteArray) {
        try {
            // a long system exclusive message goes over in pieces the port takes
            for (piece in chunkMessage(bytes, port.maxMessageSize)) port.send(piece, 0, piece.size, System.nanoTime())
        } catch (e: IOException) {
            Log.w(TAG, "port of ${handle.ref.name} failed", e)
            handle.port = null
            try {
                port.close()
            } catch (_: IOException) {
            }
        } catch (e: RuntimeException) {
            Log.w(TAG, "port of ${handle.ref.name} failed", e)
        }
    }

    // ---- inputs --------------------------------------------------------------------------------------------------------------

    override fun inputNames(): List<String> = mutableDevices.value.inputs

    override fun sendNow(outputName: String, bytes: ByteArray): Boolean {
        val index = snapshot.outputs.indexOfFirst { it.name == outputName }
        if (index < 0) return false
        send(index, bytes, 0L, -1.0e9)
        return true
    }

    /** Starts or stops listening to the inputs (a program that uses `midin` or `midikeys` needs them; otherwise the devices are left alone). */
    fun setListening(on: Boolean) {
        listening = on
        if (on) syncListening() else stopListening()
    }

    private fun syncListening() {
        for (ref in snapshot.inputs) {
            val slot = slotFor(ref.deviceId) ?: continue
            if (slot.inputs.containsKey(ref.portNumber)) continue
            withDevice(ref.deviceId) { device ->
                if (device == null || !listening || slot.inputs.containsKey(ref.portNumber)) return@withDevice
                val port = device.openOutputPort(ref.portNumber) ?: return@withDevice
                val parser = MidiStreamParser()
                port.connect(object : MidiReceiver() {
                    override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
                        parser.feed(msg, offset, count) { message ->
                            monitor?.invoke(ref.name, message)
                            hub?.onMessage(ref.name, message)
                        }
                    }
                })
                slot.inputs[ref.portNumber] = port
            }
        }
    }

    private fun stopListening() {
        for (slot in slots.values) {
            for (port in slot.inputs.values) {
                try {
                    port.close()
                } catch (_: IOException) {
                }
            }
            slot.inputs.clear()
        }
    }

    // ---- devices ---------------------------------------------------------------------------------------------------------------

    @Synchronized
    private fun infoOf(id: Int): MidiDeviceInfo? = infoById[id]

    /** The state of device [id], made when it is first needed; null when the system does not list the device (any more). */
    private fun slotFor(id: Int): DeviceSlot? = slots[id] ?: infoOf(id)?.let { info -> slots.getOrPut(id) { DeviceSlot(info) } }

    /** Runs [then] with the open device [id] (null when it could not be opened), opening it first if need be. */
    private fun withDevice(id: Int, then: (MidiDevice?) -> Unit) {
        val slot = slotFor(id) ?: run {
            then(null)
            return
        }
        val open = synchronized(slot) {
            val device = slot.device
            if (device != null) return@synchronized device
            slot.waiting.add(then)
            if (slot.opening) return
            slot.opening = true
            null
        }
        if (open != null) {
            then(open)
            return
        }
        val midi = manager
        if (midi == null) {
            opened(slot, null)
            return
        }
        midi.openDevice(slot.info, { device -> opened(slot, device) }, handler)
    }

    private fun opened(slot: DeviceSlot, device: MidiDevice?) {
        val waiting: List<(MidiDevice?) -> Unit>
        synchronized(slot) {
            slot.opening = false
            slot.device = device
            waiting = slot.waiting.toList()
            slot.waiting.clear()
        }
        for (task in waiting) task(device)
    }

    /** A device went away: its ports and its messages go with it. */
    private fun release(id: Int) {
        val slot = slots.remove(id)
        if (slot != null) {
            for (port in slot.inputs.values) {
                try {
                    port.close()
                } catch (_: IOException) {
                }
            }
            slot.inputs.clear()
            try {
                slot.device?.close()
            } catch (_: IOException) {
            }
        }
        for ((key, handle) in handles) {
            if (key.first != id) continue
            handles.remove(key)
            sender.clear(handle)
            try {
                handle.port?.close()
            } catch (_: IOException) {
            }
            handle.port = null
        }
    }

    /** Lets go of everything: the ports, the devices and the messages that wait. */
    fun close() {
        listening = false
        sender.clear()
        stopListening()
        scanner.stop()
        scanner.disconnectAll()
        for (id in slots.keys.toList()) release(id)
        if (started) manager?.unregisterDeviceCallback(callback)
        started = false
    }
}
