package cosic

import cosic.audio.AudioSettings
import cosic.io.ConsoleLog
import cosic.io.InputState
import cosic.io.OutputBus
import java.util.concurrent.atomic.AtomicInteger

/*
 * What the engine shares with the outside world besides the audio itself: devices that hooks of patterns send to (MIDI, OSC,
 * speech), the clock that tells when frames are heard, remote files programs ask for, and the kinds of input a program uses. The
 * app implements these interfaces; the engine ([OutputBus], [InputState], [ConsoleLog]) holds what they plug into.
 */

/**
 * The system time at which the frames of the engine are heard, which devices that take time stamps (MIDI, OSC) need. Nanoseconds are
 * those of `System.nanoTime()`. The answer is null while the audio device has not told yet. Implemented by [cosic.io.ClockBridge], which
 * the app feeds with the time stamps of its audio track.
 */
fun interface FrameClock {
    /** The system time at which engine frame [frame] is heard, or null when that is not known (yet). */
    fun nanoTimeAtFrame(frame: Long): Long?

    /** The engine frame that is heard at the system time [nano], or null when that is not known. */
    fun frameAtNanoTime(nano: Long): Long? = null
}

/** Fetches JSON a program asks for by URL (`midimaps('github:...')`). Blocking, with a timeout of ten seconds; null when it cannot be had. */
fun interface RemoteJson {
    fun fetch(url: String): Map<String, Any?>?
}

/**
 * The MIDI outputs of the device. Called on the dispatcher thread or a thread of the sink's own, never on the audio thread; may block
 * briefly. [send] is told the engine [frame] a message belongs to and [offsetMs] milliseconds to add to the time of that frame; the sink
 * asks the [OutputBus.clock] for the system time of the frame and sends then (it keeps the message until then), or at once when that
 * time has passed.
 */
interface MidiSink {
    /** The names of the outputs in the order they are numbered, which the `midiport` of a pattern refers to. */
    fun outputNames(): List<String>

    fun send(output: Int, bytes: ByteArray, frame: Long, offsetMs: Double)
}

/** Where OSC messages go. [host] and [port] are the `oschost` and `oscport` of the hap, null for the defaults of the settings. */
interface OscSink {
    fun send(host: String?, port: Int?, address: String, args: List<Any?>, frame: Long)
}

/** Speaks text (`.speak()`). [voice] is the number or name a program gave, or null. */
interface SpeechSink {
    fun speak(text: String, lang: String, voice: Any?)
}

/** The inputs a program can ask for, which the app only listens to while a program uses them. */
enum class InputKind { MOTION, GAMEPAD, MOUSE, KEYBOARD, MIDI_IN }

/**
 * The visuals a program made (pianoroll, scope, spiral...), as far as the engine core knows them: the visual feature's session
 * implements this, and the editor takes it from [ProgramInfo.visuals].
 */
interface ProgramVisuals

/**
 * What the builtins of a program reach outside of the language: the inputs and outputs of the engine, and what the evaluation in
 * progress reports about itself. One per engine; [EngineEffects.beginEvaluation] starts the report of the next evaluation. A builtin
 * gets it as `ctx.effects`.
 */
internal interface EvalEffects {
    val inputs: InputState
    val console: ConsoleLog
    val outputs: OutputBus

    /** The settings of the sound engine a program changes (`setGainCurve`, `setMaxPolyphony`, the default values...); they outlive one evaluation. */
    val audio: AudioSettings

    /** Where programs get remote JSON from, if the host can. */
    val remote: RemoteJson?

    /** The visuals the evaluation in progress made, set by the first builtin that makes one; null while there are none. */
    var visuals: ProgramVisuals?

    /** The number of the next `print()` of the evaluation in progress: 1, then 2... (`print(line:N)` of the console line). */
    fun nextPrintIndex(): Int

    /** The program uses [kind]: the host starts listening to it. */
    fun markInputUsed(kind: InputKind)
}

/** The [EvalEffects] of an engine, over the parts of that engine. */
internal class EngineEffects(
    override val inputs: InputState = InputState(),
    override val console: ConsoleLog = ConsoleLog(),
    override val outputs: OutputBus = OutputBus(console),
    override val audio: AudioSettings = AudioSettings(),
    private val remoteSource: () -> RemoteJson? = { null },
) : EvalEffects {
    override val remote: RemoteJson? get() = remoteSource()

    @Volatile
    override var visuals: ProgramVisuals? = null

    private val printIndex = AtomicInteger(0)
    private val used = AtomicInteger(0)

    override fun nextPrintIndex(): Int = printIndex.incrementAndGet()

    override fun markInputUsed(kind: InputKind) {
        used.updateAndGet { it or (1 shl kind.ordinal) }
    }

    /** Forgets what the last evaluation reported: the visuals, the inputs it used and the count of its `print()`s. */
    fun beginEvaluation() {
        printIndex.set(0)
        used.set(0)
        visuals = null
    }

    /** The inputs the last evaluation used. */
    fun inputsUsed(): Set<InputKind> {
        val bits = used.get()
        return InputKind.entries.filterTo(java.util.EnumSet.noneOf(InputKind::class.java)) { bits and (1 shl it.ordinal) != 0 }
    }
}

/** The effects of a host that has none of its own (the stand-ins of tests): inert, and shared. */
internal val detachedEffects: EvalEffects by lazy {
    val console = ConsoleLog()
    EngineEffects(console = console, outputs = OutputBus(console, autoStart = false))
}
