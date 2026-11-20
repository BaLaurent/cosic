package cosic.io.midi

import cosic.io.ConsoleLog
import cosic.pattern.Hap
import cosic.pattern.jsToString
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The MIDI inputs of the device, which the app implements. The names are those of the ports, in the order a number given to `midin` refers to. */
interface MidiInputSource {
    fun inputNames(): List<String>

    /**
     * Sends [bytes] to the output port called [outputName] at once (`midin` gives the controller back the values it had, which restores the
     * position of motorised faders and encoders). False when the device has no such output.
     */
    fun sendNow(outputName: String, bytes: ByteArray): Boolean
}

/** Where the values `midin` has received are kept between runs of the app (the `localStorage` of the browser: a string under a key). */
interface MidiStateStore {
    fun load(key: String): String?

    fun save(key: String, value: String)
}

/**
 * Everything MIDI the engine remembers: the mappings of `midimaps`, the inputs that `midin` and `midikeys` listen to, and what they have
 * received. There is one per engine ([cosic.io.OutputBus.midiHub]). The app plugs in the devices ([source], [store]) and passes on what it
 * receives ([onMessage]) and when devices come and go ([onDevicesChanged]); the program asks for inputs through the builtins.
 *
 * The messages of an input are only looked at by the input a program has opened: `midin('Arturia')` listens to the first device whose name
 * contains that, two seconds after it was opened (Strudel waits that long, "for the device to be ready to receive its last state"), and
 * `midikeys` listens at once.
 *
 * Threads: [onMessage] and [onDevicesChanged] may be called from any thread (the app's MIDI thread); the program's thread opens inputs; the
 * audio thread reads the values (see [MidiInState]); one timer thread, started when it is needed, writes the state to the [store] and sends it
 * back to a device.
 */
class MidiHub internal constructor(private val console: ConsoleLog, private val outputNames: () -> List<String> = { emptyList() }) {
    internal val maps = MidiMaps()

    @Volatile
    var source: MidiInputSource? = null

    @Volatile
    var store: MidiStateStore? = null

    private val enabled = AtomicBoolean(false)
    private val inputs = ConcurrentHashMap<String, MidiInput>()
    private val keyboards = ConcurrentHashMap<String, MidiKeyboard>()
    private val fetched = ConcurrentHashMap<String, Map<String, Any?>>()

    @Volatile
    private var trigger: ((Double, (Hap) -> Boolean, ((Boolean) -> Unit)?) -> Unit)? = null

    @Volatile
    internal var isPlaying: () -> Boolean = { false }

    /** The outputs `.midi()` has announced, for the line that tells a device went away. */
    @Volatile
    private var announcedOutputs: List<String>? = null

    private val timer: ScheduledThreadPoolExecutor by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        ScheduledThreadPoolExecutor(1) { r -> Thread(r, "cosic-midi").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
            setKeepAliveTime(IDLE_SECONDS, TimeUnit.SECONDS)
            allowCoreThreadTimeOut(true)
        }
    }

    // ---- what the engine tells the hub ---------------------------------------------------------------------------------------

    /**
     * Connects the hub to the scheduler: [trigger] makes the program play what a key just made (`CosicEngine.trigger`) and [isPlaying] tells
     * whether it plays at all (Strudel ignores the keyboard until the scheduler has started).
     */
    internal fun attach(trigger: (Double, (Hap) -> Boolean, ((Boolean) -> Unit)?) -> Unit, isPlaying: () -> Boolean) {
        this.trigger = trigger
        this.isPlaying = isPlaying
    }

    internal val triggerAvailable: Boolean get() = trigger != null

    internal fun playing(): Boolean = isPlaying()

    internal fun trigger(latencySeconds: Double, match: (Hap) -> Boolean, done: ((Boolean) -> Unit)?) {
        trigger?.invoke(latencySeconds, match, done)
    }

    // ---- what the app tells the hub --------------------------------------------------------------------------------------------

    /** A message the input port called [inputName] received: one whole MIDI message (see [MidiStreamParser]). */
    fun onMessage(inputName: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        for (input in inputs.values) input.deliver(inputName, bytes)
        for (keyboard in keyboards.values) keyboard.deliver(inputName, bytes)
    }

    /** Devices were plugged in or removed: inputs that wait for their device pick it up, and a program that sends MIDI is told if its device went away. */
    fun onDevicesChanged() {
        val names = inputNames()
        for (input in inputs.values) input.onDevicesChanged(names)
        val before = announcedOutputs
        if (before != null) {
            val now = outputNames()
            announcedOutputs = now
            if (before.any { it !in now }) log("Midi device disconnected! Available: ${midiDeviceNames(now)}")
        }
    }

    // ---- what the builtins ask for -----------------------------------------------------------------------------------------------

    internal fun inputNames(): List<String> = source?.inputNames() ?: emptyList()

    /** True for the first program that uses MIDI in this engine: that is the one that announces the devices. */
    internal fun enable(): Boolean = enabled.compareAndSet(false, true)

    /** The announcement `.midi()` makes when it is the first to use MIDI: the output it uses and the others. */
    internal fun announceOutputs(port: Any?) {
        val names = outputNames()
        announcedOutputs = names
        val index = try {
            MidiDevices.resolve(port, names)
        } catch (e: cosic.pattern.CosicError) {
            null
        } ?: return
        val others = names.filter { it != names[index] }
        log("Midi enabled! Using \"${names[index]}\". " + if (others.isNotEmpty()) "Also available: ${midiDeviceNames(others)}" else "")
    }

    /** The input a program opened with `midin(spec)` or `midikeys(spec)`: one for each spec, which stays for the life of the engine. Throws what `getDevice` throws. */
    internal fun input(spec: Any?, announce: Boolean): MidiInput {
        val key = jsToString(spec)
        inputs[key]?.let { return it }
        val created = MidiInput(spec, this)
        created.start()
        val existing = inputs.putIfAbsent(key, created)
        if (existing != null) return existing
        if (announce) announceInputs(created, spec)
        return created
    }

    private fun announceInputs(input: MidiInput, spec: Any?) {
        if (!enable()) return
        val names = inputNames()
        val device = input.initialDevice
        log(
            if (device != null) {
                val others = names.filter { it != device }
                "[midi] Midi enabled! Using \"$device\". " + if (others.isNotEmpty()) "Also available: ${midiDeviceNames(others)}" else ""
            } else {
                "[midi] Midi enabled! Waiting for device \"${jsToString(spec)}\"... Currently connected devices: ${midiDeviceNames(names)}"
            },
        )
    }

    /** The keyboard of the input [key] (the string of the `midikeys` argument), which keeps the notes played until the program has taken them. */
    internal fun keyboard(key: String): MidiKeyboard = keyboards.getOrPut(key) { MidiKeyboard(key, this) }

    internal fun log(message: String) = console.log(message)

    /** Replaces the timer: a test that wants to run the delayed tasks itself sets it. */
    internal var scheduler: ((delayMillis: Long, task: () -> Unit) -> (() -> Unit))? = null

    /** Runs [task] after [delayMillis] on the timer of the hub (or on [scheduler]). Returns what cancels it. */
    internal fun schedule(delayMillis: Long, task: () -> Unit): () -> Unit {
        scheduler?.let { return it(delayMillis, task) }
        val future = timer.schedule({
            try {
                task()
            } catch (e: Exception) {
                // a device that fails must not take the timer with it
            }
        }, delayMillis, TimeUnit.MILLISECONDS)
        return { future.cancel(false) }
    }

    /** The mapping file a program asked `midimaps` for, fetched once per address. */
    internal fun fetchMap(url: String, fetch: (String) -> Map<String, Any?>?): Map<String, Any?>? {
        fetched[url]?.let { return it }
        val got = fetch(url) ?: return null
        fetched[url] = got
        return got
    }

    private companion object {
        const val IDLE_SECONDS = 10L
    }
}
