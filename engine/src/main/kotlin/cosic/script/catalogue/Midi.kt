package cosic.script.catalogue

import cosic.InputKind
import cosic.io.midi.MidiConfig
import cosic.io.midi.MidiHook
import cosic.io.midi.MidiMaps
import cosic.io.midi.midiDeviceNames
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Undefined
import cosic.pattern.jsToString

/*
 * MIDI, as `@strudel/midi` makes it: `.midi()` sends the events of a pattern to a MIDI output instead of playing them, `midin` and
 * `midikeys` read a MIDI input, `midimaps` and `defaultmidimap` say which control changes the controls of a pattern are sent as. The controls
 * themselves (`midichan`, `ccn`, `ccv`, `progNum`, `sysex`...) are in the table of controls. What a message looks like, and when it is
 * sent, is in `cosic.io.midi`; the devices are the app's (see `MidiSink`, `MidiInputSource`).
 */

/** `githubPath(base, subpath)` of midi.mjs: `github:user/repo[/branch]` as the address of a file in that repository. */
private fun githubPath(base: String, subpath: String): String {
    if (!base.startsWith("github:")) throw CosicError("expected \"github:\" at the start of pseudoUrl")
    var path = base.split("github:")[1]
    if (path.endsWith("/")) path = path.dropLast(1)
    if (path.split("/").size == 2) path += "/main"
    return "https://raw.githubusercontent.com/$path/$subpath"
}

internal fun registerMidi() = catalogue("Midi") {
    // `.midi(midiport?, options?)`: sends the events to the MIDI output called `midiport` (a name, a number, or nothing for the first IAC
    // port or the first port). The hook is dominant: these events do not sound.
    method(listOf("midi"), 2) { ctx ->
        val outputs = ctx.effects.outputs
        val config = try {
            MidiConfig.from(ctx.kernelArg(0), ctx.kernelArg(1), outputs.midi?.outputNames()?.firstOrNull())
        } catch (e: CosicError) {
            ctx.fail(e.message)
        }
        if (outputs.midi != null && outputs.midiHub.enable()) outputs.midiHub.announceOutputs(config.midiport)
        ctx.bridge.receiverOf(ctx.self, "midi").sortHapsByPart().onSchedule(MidiHook(config, outputs), true)
    }

    // `defaultmidimap(mapping)`: the mapping `.midi()` uses when the pattern sets no `midimap`.
    function(listOf("defaultmidimap"), 1) { ctx ->
        try {
            ctx.effects.outputs.midiHub.maps.define("default", ctx.kernelArg(0))
        } catch (e: CosicError) {
            ctx.fail(e.message)
        }
        Undefined
    }

    // `midimaps(map)`: adds mappings by name; a string is the address of a file that has them (`github:user/repo` is `midimap.json` there).
    function(listOf("midimaps"), 1) { ctx ->
        val effects = ctx.effects
        var map = ctx.kernelArg(0)
        try {
            if (map is String) {
                val url = if (map.startsWith("github:")) githubPath(map, "midimap.json") else map
                val remote = effects.remote
                map = effects.outputs.midiHub.fetchMap(url) { address -> remote?.fetch(address) } ?: throw CosicError("Failed to fetch")
            }
            if (MidiMaps.isObject(map)) {
                for ((name, mapping) in MidiMaps.jsEntries(map)) effects.outputs.midiHub.maps.define(name, mapping)
            }
        } catch (e: CosicError) {
            ctx.fail(e.message)
        }
        Undefined
    }

    // `await midin(input)`: a function `(cc, channel?) => pattern` of the last value (0 to 1) that came in on that control change.
    function(listOf("midin"), 1) { ctx ->
        val input = ctx.kernelArg(0)
        val effects = ctx.effects
        val hub = effects.outputs.midiHub
        if (input is Pattern) {
            ctx.fail(
                "[midi] Midi input cannot be a pattern. Make sure to pass device name with single quotes. " +
                    "Example: midin('${effects.outputs.midi?.outputNames()?.firstOrNull() ?: "IAC Driver Bus 1"}')",
            )
        }
        effects.markInputUsed(InputKind.MIDI_IN)
        val instance = try {
            hub.input(input, announce = true)
        } catch (e: CosicError) {
            ctx.fail(e.message)
        }
        val bridge = ctx.bridge
        ctx.realm.function("", 2) { _, args ->
            try {
                bridge.wrap(instance.state.cc(bridge.toKernel(args.getOrElse(0) { Undefined }), bridge.toKernel(args.getOrElse(1) { Undefined })))
            } catch (e: CosicError) {
                throw ctx.realm.typeError(e.message)
            }
        }
    }

    // `await midikeys(input)`: a function `(noteLength = 0.5) => pattern` of the notes played on a keyboard.
    function(listOf("midikeys"), 1) { ctx ->
        val input = ctx.kernelArg(0)
        val effects = ctx.effects
        val hub = effects.outputs.midiHub
        if (input is Pattern) {
            ctx.fail(
                "[midi] Midi input cannot be a pattern. Make sure to pass device name with single quotes. " +
                    "Example: midin('${effects.outputs.midi?.outputNames()?.firstOrNull() ?: "IAC Driver Bus 1"}')",
            )
        }
        effects.markInputUsed(InputKind.MIDI_IN)
        val instance = try {
            hub.input(input, announce = true)
        } catch (e: CosicError) {
            ctx.fail(e.message)
        }
        val device = instance.initialDevice
            ?: ctx.fail("[midi] Midi device \"${jsToString(input)}\" not found.. connected devices: ${midiDeviceNames(hub.inputNames())}")
        val keyboard = hub.keyboard(jsToString(input))
        keyboard.listenTo(device)
        val bridge = ctx.bridge
        ctx.realm.function("", 1) { _, args ->
            val length = args.getOrElse(0) { Undefined }
            bridge.wrap(keyboard.pattern(if (length === Undefined) 0.5 else bridge.toKernel(length)))
        }
    }
}
