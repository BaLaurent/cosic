package cosic.io.midi

import java.io.ByteArrayOutputStream

/**
 * Cuts the bytes a MIDI input delivers into messages. A device may hand over several messages in one buffer, a message in pieces, messages
 * that leave out the status byte (running status), real-time bytes in the middle of other messages, and system exclusive messages of any
 * length; every call of [feed] reports the messages that were completed, each as an array of its own.
 *
 * - a channel message (`80`..`EF`) has one or two data bytes, and the status byte may be left out of the ones that follow it;
 * - a system common message (`F1`..`F6`) cancels running status;
 * - a system exclusive message is reported whole, from `F0` to `F7`; one that is cut short by another status byte is dropped, and so is
 *   one longer than [maxSysexBytes];
 * - a real-time byte (`F8`..`FF`) is reported at once and changes nothing else, so it may fall inside any message.
 *
 * A parser is for one input: it keeps what a message that is not complete yet needs. Not thread safe.
 */
class MidiStreamParser(private val maxSysexBytes: Int = 1 shl 20) {
    private var running = 0
    private val message = ByteArray(3)
    private var length = 0
    private var expected = 0
    private var sysex: ByteArrayOutputStream? = null

    /** Feeds [count] bytes of [data] from [offset] on; [emit] gets each message that is complete. */
    fun feed(data: ByteArray, offset: Int, count: Int, emit: (ByteArray) -> Unit) {
        for (i in offset until offset + count) {
            val b = data[i].toInt() and 0xFF
            when {
                b >= 0xF8 -> emit(byteArrayOf(b.toByte()))
                b == 0xF0 -> {
                    cancelMessage()
                    sysex = ByteArrayOutputStream().also { it.write(b) }
                }
                b == 0xF7 -> {
                    val open = sysex
                    if (open != null) {
                        open.write(b)
                        sysex = null
                        emit(open.toByteArray())
                    }
                }
                b >= 0x80 -> status(b, emit)
                else -> dataByte(b, emit)
            }
        }
    }

    /** Forgets what was half received (the input was closed, or the device was unplugged). */
    fun reset() {
        running = 0
        cancelMessage()
        sysex = null
    }

    private fun cancelMessage() {
        length = 0
        expected = 0
    }

    private fun status(b: Int, emit: (ByteArray) -> Unit) {
        sysex = null
        message[0] = b.toByte()
        length = 1
        if (b < 0xF0) {
            running = b
            expected = if (b < 0xC0 || b >= 0xE0) 3 else 2
            return
        }
        running = 0
        expected = when (b) {
            0xF1, 0xF3 -> 2
            0xF2 -> 3
            else -> 1
        }
        if (expected == 1) {
            emit(byteArrayOf(b.toByte()))
            cancelMessage()
        }
    }

    private fun dataByte(b: Int, emit: (ByteArray) -> Unit) {
        val open = sysex
        if (open != null) {
            if (open.size() >= maxSysexBytes) sysex = null else open.write(b)
            return
        }
        if (expected == 0) return // a data byte that belongs to no message
        if (length == 0) {
            // running status: the status byte of the last channel message is understood
            message[0] = running.toByte()
            length = 1
        }
        message[length++] = b.toByte()
        if (length == expected) {
            emit(message.copyOf(length))
            length = 0
            if (running == 0) expected = 0
        }
    }
}
