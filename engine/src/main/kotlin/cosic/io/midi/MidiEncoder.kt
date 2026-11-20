package cosic.io.midi

import cosic.pattern.Controls
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.ScheduledHap
import cosic.pattern.Undefined
import cosic.pattern.jsMathRound
import cosic.pattern.jsParseInt
import cosic.pattern.jsStrictEq
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.jsTypeof
import cosic.pattern.noteToMidi

/*
 * The messages `.midi()` sends for a hap: the handler of `Pattern.prototype.midi` in `packages/midi/midi.mjs`, together with the parts of
 * WebMidi 3.1.12 that turn its calls into bytes (`Output`, `OutputChannel`, `Note`, `Utilities`): their checks, their messages and their
 * rounding, so that what a program sends here is what it sends from Strudel. The values are those of a program (see K3: `Double`, `String`,
 * `Boolean`, `List`, `Map`, `null` and `Undefined`), and JavaScript's conversions are applied where JavaScript applies them.
 *
 * Nothing here touches a device: [MidiEncoder.encode] returns the packets that belong to the hap, with the time each is due relative to the
 * hap's own, and the error the handler would have thrown (the packets before it stay, as the messages Strudel had sent stay sent).
 */

/**
 * The options of `.midi(midiport, options)` after the defaults and the spread of `options` (`midiConfig` of midi.mjs). The values stay what
 * the program gave, because Strudel uses them as they are: [isController] by its truth, the rest by whatever JavaScript makes of them.
 */
internal class MidiConfig private constructor(private val values: Map<String, Any?>) {
    val isController: Any? get() = values["isController"]
    val noteOffsetMs: Any? get() = values["noteOffsetMs"]
    val midichannel: Any? get() = values["midichannel"]
    val velocity: Any? get() = values["velocity"]
    val gain: Any? get() = values["gain"]
    val midimap: Any? get() = values["midimap"]
    val midiport: Any? get() = values["midiport"]

    companion object {
        /**
         * What `.midi(port, options)` makes of its arguments. [firstOutputName] is only for the message of a pattern given as the port
         * (Strudel names its first device in it). Throws what the call throws.
         */
        fun from(port: Any?, options: Any?, firstOutputName: String?): MidiConfig {
            var midiport = port
            var merged: Map<String, Any?> = if (options is Map<*, *>) stringKeyed(options) else emptyMap()
            if (midiport is Pattern) {
                throw CosicError(
                    ".midi does not accept Pattern input for midiport. Make sure to pass device name with single quotes. " +
                        "Example: .midi('${firstOutputName ?: "IAC Driver Bus 1"}')",
                )
            }
            // for backward compatibility the port may be an object `{port, isController, ...config}`
            if (jsTypeof(midiport) == "object") {
                if (midiport == null) throw CosicError("Cannot destructure property 'port' of 'midiport' as it is null.")
                val given = if (midiport is Map<*, *>) stringKeyed(midiport) else emptyMap()
                val combined = LinkedHashMap<String, Any?>()
                combined["isController"] = given["isController"].let { if (it === Undefined || !given.containsKey("isController")) false else it }
                for ((k, v) in given) if (k != "port" && k != "isController") combined[k] = v
                combined.putAll(merged)
                merged = combined
                midiport = if (given.containsKey("port")) given["port"] else Undefined
            }
            val config = LinkedHashMap<String, Any?>()
            config["isController"] = false
            config["noteOffsetMs"] = 1.0
            config["midichannel"] = 1.0
            config["velocity"] = 0.9
            config["gain"] = 1.0
            config["midimap"] = "default"
            config["midiport"] = midiport
            config.putAll(merged)
            return MidiConfig(config)
        }

        private fun stringKeyed(map: Map<*, *>): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>(map.size * 2)
            for ((k, v) in map) out[k.toString()] = v
            return out
        }
    }
}

/** One message to send: [bytes], due [offsetMs] milliseconds after the time of the hap (NaN: at once, which is how WebMidi reads a time that is not a number). */
internal class MidiPacket(val bytes: ByteArray, val offsetMs: Double) {
    override fun toString(): String = bytes.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } + (if (offsetMs == 0.0) "" else " @$offsetMs")
}

/**
 * What the handler did for a hap: the [device] (an index into the output names) the [packets] go to, null when no device was found; the
 * [logs] it wrote; and the [error] it ended with, which it throws in Strudel after what came before was sent.
 */
internal class MidiEncodeResult(val device: Int?, val packets: List<MidiPacket>, val logs: List<String>, val error: String?)

internal object MidiEncoder {
    /**
     * The handler of `.midi()` for one hap, `cps` being the tempo [hap] plays at. [outputNames] are the names of the output ports, which the
     * `midiport` of the hap or of the options chooses from.
     */
    fun encode(hap: ScheduledHap, cfg: MidiConfig, maps: MidiMaps, outputNames: List<String>): MidiEncodeResult {
        val run = Run(hap, cfg, maps, outputNames)
        var error: String? = null
        try {
            run.handle()
        } catch (e: CosicError) {
            error = e.message
        }
        return MidiEncodeResult(run.device, run.packets, run.logs, error)
    }

    /** `[n, n+1, ..., 16]`, `Enumerations.MIDI_CHANNEL_NUMBERS`. */
    private val ALL_CHANNELS: List<Int> = (1..16).toList()

    private val OBJECT_PROPERTIES = setOf(
        "constructor", "__defineGetter__", "__defineSetter__", "hasOwnProperty", "__lookupGetter__", "__lookupSetter__", "isPrototypeOf",
        "propertyIsEnumerable", "toString", "valueOf", "__proto__", "toLocaleString",
    )

    /** `Utilities.getCcNumberByName`: the number of a controller by name, [Undefined] when there is none, or a value that is not a number when the name is a property every object has. */
    private fun ccNumberByName(name: String): Any? =
        MidiCcNames.modern[name]?.toDouble() ?: MidiCcNames.legacy[name]?.toDouble() ?: if (name in OBJECT_PROPERTIES) name else Undefined

    /** `Utilities.sanitizeChannels` after `Output.send*` has replaced a missing channel by all of them. */
    private fun channelsOf(channels: Any?): List<Int> {
        if (channels == null || channels === Undefined) return ALL_CHANNELS
        if (channels == "all") return ALL_CHANNELS
        if (channels == "none") return emptyList()
        val list: List<Any?> = if (channels is List<*>) channels else listOf(channels)
        if (list.any { it is String && it == "all" }) return ALL_CHANNELS
        val out = ArrayList<Int>(list.size)
        for (item in list) {
            val n = jsParseInt(item)
            if (n >= 1.0 && n <= 16.0) out.add(n.toInt())
        }
        return out
    }

    /** `Number.isInteger(v) && v >= 0 && v <= 255`. */
    private fun isByte(v: Any?): Boolean = v is Double && v.isFinite() && v == Math.floor(v) && v >= 0.0 && v <= 255.0

    private fun isInteger(v: Any?): Boolean = v is Double && v.isFinite() && v == Math.floor(v)

    /** `Math.min(Math.max(Math.round(value * 127), 0), 127)` of `Utilities.fromFloatTo7Bit`, for the float of a note's attack or release. */
    private fun to7Bit(value: Double): Int {
        val v = if (value == Double.POSITIVE_INFINITY) 1.0 else value
        val x = if (v.isNaN() || v == 0.0) 0.0 else v
        return Math.min(Math.max(jsMathRound(x * 127.0), 0.0), 127.0).toInt()
    }

    private class Run(val hap: ScheduledHap, val cfg: MidiConfig, val maps: MidiMaps, val names: List<String>) {
        val packets = ArrayList<MidiPacket>()
        val logs = ArrayList<String>()
        var device: Int? = null

        private fun emit(offsetMs: Double, vararg bytes: Int) {
            packets.add(MidiPacket(ByteArray(bytes.size) { bytes[it].toByte() }, offsetMs))
        }

        fun handle() {
            hap.hap.ensureObjectValue()
            val value = hap.hap.value ?: throw CosicError("Cannot destructure property 'note' of 'hap.value' as it is null.")
            @Suppress("UNCHECKED_CAST")
            val v: Map<String, Any?> = value as? Map<String, Any?> ?: emptyMap()

            fun prop(key: String): Any? = if (v.containsKey(key)) v[key] else Undefined
            fun propOr(key: String, default: Any?): Any? = prop(key).let { if (it === Undefined) default else it }

            val note = prop("note")
            val nrpnn = prop("nrpnn")
            val nrpv = prop("nrpv")
            val ccn = prop("ccn")
            val ccv = prop("ccv")
            val midichan = propOr("midichan", cfg.midichannel)
            val midicmd = prop("midicmd")
            val midibend = prop("midibend")
            val miditouch = prop("miditouch")
            val gain = propOr("gain", cfg.gain)
            val velocityValue = propOr("velocity", cfg.velocity)
            val progNum = prop("progNum")
            val sysexid = prop("sysexid")
            val sysexdata = prop("sysexdata")
            val midimap = propOr("midimap", cfg.midimap)
            val midiport = propOr("midiport", cfg.midiport)

            val index = MidiDevices.resolve(midiport, names)
            if (index == null) {
                logs.add("[midi] midiport \"${jsToString(midiport)}\" not found! available: ${names.joinToString(", ") { "'$it'" }}")
                return
            }
            device = index

            val velocity = jsToNumber(gain) * jsToNumber(velocityValue)

            // a midimap turns controls of the hap into control changes
            val map = if (midimap is String) maps.get(midimap) else null
            if (map != null) {
                for ((mappedCcn, mappedCcv) in mapCc(map, v)) sendCc(mappedCcn, mappedCcv, midichan)
            } else if (!jsStrictEq(midimap, "default")) {
                logs.add("[midi] midimap \"${jsToString(midimap)}\" not found! Available maps: ${maps.names().joinToString(", ")}")
            }

            if (note !== Undefined && !jsTruthy(cfg.isController)) {
                // the note off is made shorter by noteOffsetMs, so that it comes before the next note on
                val hapDuration = hap.durationCycles / hap.cps * 1000.0
                val offset = Math.min(jsToNumber(cfg.noteOffsetMs), hapDuration / 2)
                sendNote(note, velocity, hapDuration - offset, midichan)
            }
            if (progNum !== Undefined) sendProgramChange(progNum, midichan)
            if (sysexid !== Undefined && sysexdata !== Undefined) sendSysex(sysexid, sysexdata)
            if (ccv !== Undefined && ccn !== Undefined) sendCc(ccn, ccv, midichan)
            if (nrpnn !== Undefined && nrpv !== Undefined) sendNrpn(nrpnn, nrpv, midichan)
            if (midibend !== Undefined) sendPitchBend(midibend, midichan)
            if (miditouch !== Undefined) sendAftertouch(miditouch, midichan)

            val whole = hap.hap.whole ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")
            if (whole.begin == Rational.ZERO) emit(0.0, 0xFA)
            if (midicmd is String && (midicmd == "clock" || midicmd == "midiClock")) {
                emit(0.0, 0xF8)
            } else if (midicmd is String && midicmd == "start") {
                emit(0.0, 0xFA)
            } else if (midicmd is String && midicmd == "stop") {
                emit(0.0, 0xFC)
            } else if (midicmd is String && midicmd == "continue") {
                emit(0.0, 0xFB)
            } else if (midicmd is List<*>) {
                val first = midicmd.getOrNull(0)
                if (first is String && first == "progNum") {
                    sendProgramChange(midicmd.getOrElse(1) { Undefined }, midichan)
                } else if (first is String && first == "cc") {
                    if (midicmd.size == 2) sendCc("cc", jsToNumber(midicmd[1]) / 127.0, midichan)
                } else if (first is String && first == "sysex") {
                    if (midicmd.size == 3) sendSysex(midicmd[1], midicmd[2])
                }
            }
        }

        /** `mapCC` of midi.mjs: the control changes the mapping makes of the controls of the hap, all of them worked out before any is sent. */
        private fun mapCc(mapping: Map<String, Any?>, value: Map<String, Any?>): List<Pair<Any?, Double>> {
            val out = ArrayList<Pair<Any?, Double>>()
            for (key in value.keys) {
                if (!jsTruthy(mapping[Controls.canonicalName(key)])) continue
                // `mapping[key]`, not the canonical name: a control given under an alias finds nothing here, as in Strudel
                val entry = if (mapping.containsKey(key)) mapping[key] else Undefined
                if (entry === Undefined) throw CosicError("Cannot destructure property 'ccn' of 'mapping[key]' as it is undefined.")
                if (entry == null) throw CosicError("Cannot destructure property 'ccn' of 'mapping[key]' as it is null.")
                val fields = entry as? Map<*, *>
                fun field(name: String, default: Any?): Any? {
                    val got = if (fields != null && fields.containsKey(name)) fields[name] else Undefined
                    return if (got === Undefined) default else got
                }
                val mappedCcn = field("ccn", Undefined)
                val min = field("min", 0.0)
                val max = field("max", 1.0)
                val exp = field("exp", 1.0)
                val raw = if (value.containsKey(key)) value[key] else Undefined
                out.add(mappedCcn to normalize(if (raw === Undefined) 0.0 else raw, min, max, exp))
            }
            return out
        }

        /** `normalize` of midi.mjs: [value] within [min] and [max] as a fraction between 0 and 1, raised to [exp]. */
        private fun normalize(value: Any?, min: Any?, max: Any?, exp: Any?): Double {
            if (jsStrictEq(min, max)) throw CosicError("min and max cannot be the same value")
            val normalized = (jsToNumber(value) - jsToNumber(min)) / (jsToNumber(max) - jsToNumber(min))
            return StrictMath.pow(Math.min(1.0, Math.max(0.0, normalized)), jsToNumber(exp))
        }

        /** `sendNote` of midi.mjs: a note on now and a note off [duration] milliseconds later, on every channel. */
        private fun sendNote(note: Any?, velocity: Double, duration: Double, midichan: Any?) {
            if (note == null || (note is String && note.isEmpty())) throw CosicError("note cannot be null or empty")
            if (velocity < 0.0 || velocity > 1.0) throw CosicError("velocity must be a number between 0 and 1")
            if (duration < 0.0) throw CosicError("duration must be a positive number")
            val midiNumber = if (note is Double) note else noteToMidi(note)
            // `new Note(midiNumber, {attack: velocity})`
            if (velocity.isNaN()) throw CosicError("Invalid attack value.")
            val number = noteNumber(midiNumber)
            val attack = to7Bit(velocity)
            val channels = channelsOf(midichan)
            for (ch in channels) emit(0.0, 0x90 + ch - 1, number, attack)
            // the release of a note is 0.5, which is 64
            for (ch in channels) emit(duration, 0x80 + ch - 1, number, 64)
        }

        /** The number of a `Note` made of an integer: `toNoteIdentifier` reads it with `parseInt`, so `1e21` is note 1. */
        private fun noteNumber(n: Double): Int {
            if (!isInteger(n)) throw CosicError("value.match is not a function")
            val parsed = jsParseInt(n)
            if (parsed.isNaN() || parsed < 0.0 || parsed > 127.0) throw CosicError("Invalid note number")
            return parsed.toInt()
        }

        /** `sendCC` of midi.mjs and `OutputChannel.sendControlChange` of WebMidi. */
        private fun sendCc(ccn: Any?, ccv: Any?, midichan: Any?) {
            if (ccv !is Double || ccv < 0.0 || ccv > 1.0) throw CosicError("expected ccv to be a number between 0 and 1")
            if (!(ccn is String || ccn is Double)) throw CosicError("expected ccn to be a number or a string")
            val scaled = jsMathRound(ccv * 127.0)
            for (ch in channelsOf(midichan)) controlChange(ch, ccn, scaled)
        }

        /** `OutputChannel.sendControlChange(controller, value)`: the controller by name or number, the value as one or two (coarse and fine) 7-bit numbers. */
        private fun controlChange(ch: Int, controller0: Any?, value0: Any?) {
            val controller = if (controller0 is String) ccNumberByName(controller0) else controller0
            val values: List<Any?> = if (value0 is List<*>) value0 else listOf(value0)
            if (controller === Undefined) {
                throw CosicError("Control change must be identified with a valid name or an integer between 0 and 127.")
            }
            if (controller !is Double || !isInteger(controller) || controller < 0.0 || controller > 127.0) {
                throw CosicError("Control change number must be an integer between 0 and 127.")
            }
            val sevenBit = values.map { item ->
                val n = Math.min(Math.max(jsParseInt(item), 0.0), 127.0)
                if (n.isNaN()) throw CosicError("Values must be integers between 0 and 127")
                n.toInt()
            }
            if (sevenBit.size == 2 && controller >= 32.0) throw CosicError("To use a value array, the controller must be between 0 and 31")
            sevenBit.forEachIndexed { i, n -> emit(0.0, 0xB0 + ch - 1, controller.toInt() + i * 32, n) }
        }

        /** `sendProgramChange` of midi.mjs. */
        private fun sendProgramChange(progNum: Any?, midichan: Any?) {
            if (progNum !is Double || progNum < 0.0 || progNum > 127.0) {
                throw CosicError("expected progNum (program change) to be a number between 0 and 127")
            }
            for (ch in channelsOf(midichan)) {
                val parsed = jsParseInt(progNum)
                val program = if (parsed.isNaN() || parsed == 0.0) 0.0 else parsed
                if (!(program >= 0.0 && program <= 127.0)) throw CosicError("The program number must be between 0 and 127.")
                emit(0.0, 0xC0 + ch - 1, program.toInt())
            }
        }

        /** `sendSysex` of midi.mjs and `Output.sendSysex` of WebMidi: `F0`, the manufacturer id, the data and `F7`. */
        private fun sendSysex(sysexid: Any?, sysexdata: Any?) {
            if (sysexid is List<*>) {
                if (!sysexid.all(::isByte)) throw CosicError("all sysexid bytes must be integers between 0 and 255")
            } else if (!isByte(sysexid)) {
                throw CosicError("A:sysexid must be an number between 0 and 255 or an array of such integers")
            }
            if (sysexdata !is List<*>) throw CosicError("expected sysex to be an array of numbers (0-255)")
            if (!sysexdata.all(::isByte)) throw CosicError("all sysex bytes must be integers between 0 and 255")
            val id: List<Any?> = if (sysexid is List<*>) sysexid else listOf(sysexid)
            val bytes = ArrayList<Int>(id.size + sysexdata.size + 2)
            bytes.add(0xF0)
            for (b in id) bytes.add((b as Double).toInt())
            for (b in sysexdata) bytes.add((b as Double).toInt())
            bytes.add(0xF7)
            emit(0.0, *bytes.toIntArray())
        }

        /** `sendNRPN` of midi.mjs and `OutputChannel.sendNrpnValue` of WebMidi: parameter number, value, and the selection reset, as control changes. */
        private fun sendNrpn(nrpnn: Any?, nrpv: Any?, midichan: Any?) {
            if (nrpnn is List<*>) {
                if (!nrpnn.all(::isByte)) throw CosicError("all nrpnn bytes must be integers between 0 and 255")
            } else if (!isByte(nrpv)) {
                throw CosicError("A:sysexid must be an number between 0 and 255 or an array of such integers")
            }
            for (ch in channelsOf(midichan)) {
                val data: List<Any?> = if (nrpv is List<*>) nrpv else listOf(nrpv)
                if (nrpnn !is List<*> || !isInteger(nrpnn.getOrNull(0)) || !isInteger(nrpnn.getOrNull(1))) {
                    throw CosicError("The specified NRPN is invalid.")
                }
                val first = nrpnn[0] as Double
                val second = nrpnn[1] as Double
                if (!(first >= 0.0 && first <= 127.0)) throw CosicError("The first byte of the NRPN must be between 0 and 127.")
                if (!(second >= 0.0 && second <= 127.0)) throw CosicError("The second byte of the NRPN must be between 0 and 127.")
                for (item in data) {
                    if (!inRange127(item)) throw CosicError("The data bytes of the NRPN must be between 0 and 127.")
                }
                controlChange(ch, 0x63.toDouble(), first)
                controlChange(ch, 0x62.toDouble(), second)
                controlChange(ch, 0x06.toDouble(), data.getOrElse(0) { Undefined })
                if (data.size >= 2) controlChange(ch, 0x26.toDouble(), data[1])
                controlChange(ch, 0x65.toDouble(), 0x7F.toDouble())
                controlChange(ch, 0x64.toDouble(), 0x7F.toDouble())
            }
        }

        /** `value >= 0 && value <= 127` with JavaScript's conversions. */
        private fun inRange127(v: Any?): Boolean {
            val n = jsToNumber(if (v is List<*>) jsToString(v) else v)
            return n >= 0.0 && n <= 127.0
        }

        /** `sendPitchBend` of midi.mjs and `OutputChannel.sendPitchBend`: -1 to 1 as 14 bits. */
        private fun sendPitchBend(midibend: Any?, midichan: Any?) {
            if (midibend !is Double || midibend < -1.0 || midibend > 1.0) throw CosicError("expected midibend to be a number between -1 and 1")
            for (ch in channelsOf(midichan)) {
                if (midibend.isNaN()) throw CosicError("Invalid pitch bend value.")
                val unit = Math.min(Math.max(((midibend + 1.0) / 2.0).let { if (it.isNaN()) 0.0 else it }, 0.0), 1.0)
                val scaled = jsMathRound(unit * 16383.0).toInt()
                emit(0.0, 0xE0 + ch - 1, scaled and 0x7F, scaled shr 7)
            }
        }

        /** `sendAftertouch` of midi.mjs and `OutputChannel.sendChannelAftertouch`: channel pressure, 0 to 1 as 7 bits. */
        private fun sendAftertouch(miditouch: Any?, midichan: Any?) {
            if (miditouch !is Double || miditouch < 0.0 || miditouch > 1.0) throw CosicError("expected miditouch to be a number between 0 and 1")
            for (ch in channelsOf(midichan)) {
                if (miditouch.isNaN()) throw CosicError("Invalid channel aftertouch value.")
                emit(0.0, 0xD0 + ch - 1, to7Bit(miditouch))
            }
        }
    }
}
