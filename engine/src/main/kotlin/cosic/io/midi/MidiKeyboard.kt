package cosic.io.midi

import cosic.pattern.Hap
import cosic.pattern.Pattern
import cosic.pattern.QueryContext
import cosic.pattern.Rational
import cosic.pattern.TimeSpan
import cosic.pattern.jsToNumber
import cosic.pattern.reify
import java.util.concurrent.ConcurrentLinkedQueue

/** The latency Strudel gives a key when the scheduler can play it at once (`latencySeconds` of `midikeys`): 10 ms; a key the scheduler only finds waits longer. */
internal const val KEY_TRIGGER_LATENCY_SECONDS = 0.01

/**
 * The notes played on a MIDI keyboard (`midikeys` of `midi.mjs`). Pressing a key adds a note `{midikey: "<input>_<note>", note, velocity,
 * midichan}`, and a program turns the notes into events with the pattern [pattern] makes (`kb()`): every note becomes an event as long as the
 * note length, wherever the pattern is queried. The scheduler takes them as it finds them, once, and then they are gone; when the program is
 * playing, the keyboard also asks it to play the note at once, rather than at the next chunk it queries, which is what makes a keyboard playable.
 * Releasing a key does nothing (the length of a note is fixed: a voice cannot be told to stop).
 *
 * Strudel stamps a note with the time its key was pressed (`now + latency`) and the pattern answers at that time. The notes here are stamped
 * by the query that takes them (the event begins where the query begins), because the scheduler that plays them works out its own clock
 * on the audio thread when it gets to them; an event is never more than a block from the key.
 *
 * One keyboard per input, for the life of the engine: the notes wait in it while no program takes them, a new evaluation of `midikeys` for the
 * same input connects the keyboard to that device and the old connection goes (`kListeners` of the module).
 */
internal class MidiKeyboard(private val key: String, private val hub: MidiHub) {
    private class Key(val value: Map<String, Any?>)

    private val notes = ConcurrentLinkedQueue<Key>()

    @Volatile
    private var device: String? = null

    /** The keyboard listens to [name] from now on. */
    fun listenTo(name: String) {
        device = name
    }

    /** A message of the input called [inputName]: the notes pressed on the device this keyboard listens to are added. */
    fun deliver(inputName: String, bytes: ByteArray) {
        if (inputName != device) return
        val status = bytes[0].toInt() and 0xFF
        val command = status shr 4
        val noteOn = command == 9
        val noteOff = command == 8
        // Strudel ignores keys until the scheduler is started, and everything that is not a note
        if (!hub.playing() || (!noteOn && !noteOff) || bytes.size < 3) return
        val note = bytes[1].toInt() and 0xFF
        val velocity = bytes[2].toInt() and 0xFF
        // a note on with velocity zero is a note off, which is ignored: a note has a fixed length
        if (noteOff || velocity == 0) return
        val value = linkedMapOf<String, Any?>(
            "midikey" to "${key}_$note",
            "note" to note.toDouble(),
            "velocity" to velocity / 127.0,
            "midichan" to ((status and 0x0F) + 1).toDouble(),
        )
        notes.add(Key(value))
        if (hub.triggerAvailable) {
            hub.trigger(KEY_TRIGGER_LATENCY_SECONDS, ::isOwnNote) { played -> if (played) notes.clear() }
        }
    }

    private fun isOwnNote(hap: Hap): Boolean {
        val midikey = (hap.value as? Map<*, *>)?.get("midikey")
        return midikey is String && midikey.startsWith("${key}_")
    }

    /** `kb(noteLength = 0.5)`: the pattern of the notes, each as long as the note length (a number or a pattern of numbers) says. */
    fun pattern(noteLength: Any?): Pattern {
        val lengths = reify(noteLength)
        return Pattern { state ->
            val out = ArrayList<Hap>()
            val at = state.span.begin
            for (note in notes.toList()) {
                // the length is asked at the note's own time, which has no duration
                for (lengthHap in lengths.query(state.setSpan(TimeSpan(at, at)))) {
                    val length = lengthHap.value.let { if (it == null || it === cosic.pattern.Undefined) 0.5 else jsToNumber(it) }
                    val span = TimeSpan(at, at + Rational.of(length))
                    out.add(Hap(span, span, note.value, lengthHap.context))
                }
            }
            // the scheduler has them now
            if (QueryContext.fromScheduler) notes.clear()
            out
        }
    }
}
