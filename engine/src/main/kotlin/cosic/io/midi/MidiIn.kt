package cosic.io.midi

import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Undefined
import cosic.pattern.jsKeyOrder
import cosic.pattern.jsMathRound
import cosic.pattern.jsNumberToString
import cosic.pattern.jsToString
import cosic.script.catalogue.refPattern
import cosic.sound.LooseJson
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/** What `midin` sends back to a device when it has come back, and when its state is written: Strudel's delays. */
internal const val MIDI_IN_LISTEN_DELAY_MILLIS = 2000L
internal const val MIDI_IN_SAVE_DELAY_MILLIS = 500L

/**
 * The values a MIDI input has sent, as `midin` gives them to a program (`_refs` and `_refsByChan` of `MidiInput` in `input.mjs`). Every
 * message of a channel (a control change, but also a note or a pitch bend: Strudel does not look at the kind) sets `value / 127` in the slot
 * of its first data byte, for all channels and for its own; a message with one data byte sets NaN. A program reads a slot with [cc].
 *
 * The values are kept in the [store] under `strudel-midin-<name>-chan<all|N>`, as `{"<slot>": value}`, so that a knob keeps its value from one
 * run of the app to the next. They are loaded when the state is made and written [MIDI_IN_SAVE_DELAY_MILLIS] after the first change of a
 * burst. Only an input given by name keeps state (`stateKey`).
 *
 * The audio thread reads slots (a program's patterns query them) while the MIDI thread writes them.
 */
internal class MidiInState(
    private val stateKey: String?,
    private val store: () -> MidiStateStore?,
    private val scheduleSave: (() -> Unit) -> Unit,
) {
    private val refs = ConcurrentHashMap<String, Double>()
    private val refsByChannel = Array(17) { ConcurrentHashMap<String, Double>() }

    /** What is in the store: index 0 is `chanall`, 1..16 the channels. Touched only by [onMessage] (one thread) and [flush], under [lock]. */
    private val stored = Array(17) { LinkedHashMap<String, Double>() }
    private val dirty = BooleanArray(17)
    private val lock = Any()
    private val flushQueued = AtomicBoolean(false)

    init {
        // `_loadAllStates`
        loadState(0).let { refs.putAll(it); stored[0].putAll(it) }
        for (chan in 1..16) loadState(chan).let { refsByChannel[chan].putAll(it); stored[chan].putAll(it) }
    }

    private fun storageKey(chan: Int) = "strudel-midin-$stateKey-chan${if (chan == 0) "all" else chan.toString()}"

    /** `_loadState(chan)`: what the store has for the channel (0 for all of them); nothing for an input without a name or a state that is no object. */
    private fun loadState(chan: Int): Map<String, Double> {
        if (stateKey == null) return emptyMap()
        val text = store()?.load(storageKey(chan)) ?: return emptyMap()
        val parsed = try {
            LooseJson.parse(text)
        } catch (e: IllegalArgumentException) {
            return emptyMap()
        }
        if (parsed !is Map<*, *>) return emptyMap()
        val out = LinkedHashMap<String, Double>(parsed.size * 2)
        for ((k, v) in parsed) out[k.toString()] = (v as? Double) ?: Double.NaN
        return out
    }

    /**
     * `createCC(cc, chan)`: the pattern of one slot of all channels, or of channel [chan] (1 to 16). It answers the value the slot has when it
     * is queried; a slot that nothing has set yet starts as what the store had for it, or 0.
     */
    fun cc(cc: Any?, chan: Any?): Pattern {
        val key = jsToString(cc)
        val channel: Int = if (chan === Undefined) 0 else jsToString(chan).let { s -> CHANNEL_KEYS[s] } ?: throw CosicError("Cannot use 'in' operator to search for '$key' in undefined")
        val slots = if (channel == 0) refs else refsByChannel[channel]
        if (!slots.containsKey(key)) {
            val initial = loadState(channel)[key]
            slots.putIfAbsent(key, if (initial == null || initial.isNaN() || initial == 0.0) 0.0 else initial)
        }
        return refPattern { slots[key] }
    }

    /** `_onMidiMessage`: a channel message sets its slot. Messages that belong to no channel (clock, system exclusive) are not looked at. */
    fun onMessage(bytes: ByteArray) {
        val status = bytes[0].toInt() and 0xFF
        if (status < 0x80 || status > 0xEF || bytes.size < 2) return
        val channel = (status and 0x0F) + 1
        val slot = (bytes[1].toInt() and 0xFF).toString()
        val scaled = if (bytes.size > 2) (bytes[2].toInt() and 0xFF) / 127.0 else Double.NaN
        refs[slot] = scaled
        refsByChannel[channel][slot] = scaled
        if (stateKey != null) {
            synchronized(lock) {
                stored[0][slot] = scaled
                stored[channel][slot] = scaled
                dirty[0] = true
                dirty[channel] = true
            }
            if (flushQueued.compareAndSet(false, true)) scheduleSave(::flush)
        }
    }

    /** Writes what changed to the store (`_saveState`: `JSON.stringify` of the slots, the whole numbers first). */
    fun flush() {
        flushQueued.set(false)
        val target = store() ?: return
        val writes = ArrayList<Pair<String, String>>()
        synchronized(lock) {
            for (chan in 0..16) {
                if (!dirty[chan]) continue
                dirty[chan] = false
                writes.add(storageKey(chan) to stateJson(stored[chan]))
            }
        }
        for ((key, json) in writes) target.save(key, json)
    }

    /** The slots of every channel as the control changes that put a controller back where it was: channel 1 to 16, slots in order, `[B0 | channel - 1, slot, round(value * 127)]`. */
    fun messagesToRestore(): List<ByteArray>? {
        val out = ArrayList<ByteArray>()
        for (channel in 1..16) {
            val slots = refsByChannel[channel]
            for (slot in jsKeyOrder(slots.keys.toList())) {
                val n = slot.toIntOrNull()
                val value = slots[slot] ?: continue
                val scaled = jsMathRound(value * 127.0)
                // WebMidi refuses a controller that is no integer from 0 to 127 or a value that is no number: the rest is not sent
                if (n == null || n < 0 || n > 127 || scaled.isNaN()) return out
                out.add(byteArrayOf((0xB0 or (channel - 1)).toByte(), n.toByte(), scaled.coerceIn(0.0, 127.0).toInt().toByte()))
            }
        }
        return out
    }

    private fun stateJson(slots: Map<String, Double>): String =
        jsKeyOrder(slots.keys.toList()).joinToString(",", "{", "}") { k -> "\"$k\":" + slots.getValue(k).let { if (it.isNaN()) "null" else jsNumberToString(it) } }

    private companion object {
        val CHANNEL_KEYS: Map<String, Int> = (1..16).associateBy { it.toString() }
    }
}

/**
 * An input a program opened with `midin` (`MidiInput` of `input.mjs`): the values it received ([state]) and the device it is connected to.
 *
 * The device is the one `spec` names (a number is the position in the list of inputs, a string the first whose name contains it, nothing the
 * first IAC port or the first port): [initialDevice] when the input was opened, and again whenever that device was gone and a new one fits. Two
 * seconds after it has one, it gives the device the values it has stored (a controller moves its faders to them) and starts listening to it.
 * While no device fits, it keeps what it has, and waits.
 */
internal class MidiInput(private val spec: Any?, private val hub: MidiHub) {
    val state = MidiInState(spec as? String, { hub.store }, { flush -> hub.schedule(MIDI_IN_SAVE_DELAY_MILLIS, flush) })

    /** The device that fitted when the input was opened, or null. A keyboard listens to this one only. */
    @Volatile
    var initialDevice: String? = null
        private set

    @Volatile
    private var bound: String? = null

    @Volatile
    private var listening = false

    @Volatile
    private var generation = 0

    /** Finds the device (throwing what `getDevice` throws) and starts the listening. */
    fun start() {
        val names = hub.inputNames()
        val index = MidiDevices.resolve(spec, names)
        initialDevice = index?.let { names[it] }
        initialDevice?.let(::bind)
    }

    private fun bind(device: String) {
        bound = device
        listening = false
        val mine = ++generation
        hub.schedule(MIDI_IN_LISTEN_DELAY_MILLIS) {
            if (generation != mine || bound != device) return@schedule
            // Strudel sends the stored values to the output of that name, and goes on listening whatever comes of it
            hub.source?.let { source ->
                for (message in state.messagesToRestore().orEmpty()) if (!source.sendNow(device, message)) break
            }
            listening = true
        }
    }

    /** `_onMidiMessage`: the messages of the device this input listens to. */
    fun deliver(inputName: String, bytes: ByteArray) {
        if (listening && inputName == bound) state.onMessage(bytes)
    }

    /** The devices that are connected changed: the device is gone (`[midi] device disconnected`), or a device that fits is there (`[midi] device reconnected`). */
    fun onDevicesChanged(names: List<String>) {
        val current = bound
        if (current != null && current !in names) {
            hub.log("[midi] device disconnected: $current")
            bound = null
            listening = false
            generation++
        }
        if (bound == null) {
            val index = try {
                MidiDevices.resolve(spec, names)
            } catch (e: CosicError) {
                null
            } ?: return
            hub.log("[midi] device reconnected: ${names[index]}")
            bind(names[index])
        }
    }
}
