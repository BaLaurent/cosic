package cosic.script

import cosic.pattern.ArithOp
import cosic.pattern.ControlMap
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.asControlMap
import cosic.pattern.chunk
import cosic.pattern.cosine
import cosic.pattern.degradeBy
import cosic.pattern.early
import cosic.pattern.euclid
import cosic.pattern.euclidRot
import cosic.pattern.every
import cosic.pattern.fast
import cosic.pattern.fastcat
import cosic.pattern.inside
import cosic.pattern.irand
import cosic.pattern.isaw
import cosic.pattern.iter
import cosic.pattern.iterBack
import cosic.pattern.lastOf
import cosic.pattern.late
import cosic.pattern.mask
import cosic.pattern.noteToMidi
import cosic.pattern.off
import cosic.pattern.outside
import cosic.pattern.perlin
import cosic.pattern.ply
import cosic.pattern.pure
import cosic.pattern.rand
import cosic.pattern.rev
import cosic.pattern.saw
import cosic.pattern.scaleNote
import cosic.pattern.segment
import cosic.pattern.silence
import cosic.pattern.sine
import cosic.pattern.slow
import cosic.pattern.slowcat
import cosic.pattern.someCyclesBy
import cosic.pattern.sometimesBy
import cosic.pattern.soundControl
import cosic.pattern.splitWholes
import cosic.pattern.square
import cosic.pattern.stack
import cosic.pattern.struct
import cosic.pattern.superimpose
import cosic.pattern.swingBy
import cosic.pattern.timecat
import cosic.pattern.tri
import cosic.pattern.withControls

private const val CONSTRUCTORS = "Constructors"
private const val SIGNALS = "Signals"
private const val SETTINGS = "Settings"
private const val TIME = "Time"
private const val STRUCTURE = "Structure"
private const val RANDOM = "Random"
private const val VALUES = "Values"
private const val SOUND = "Sound"
private const val MIX = "Mix"
private const val ENVELOPE = "Envelope"
private const val FILTERS = "Filters"
private const val EFFECTS = "Effects"

private val NUM = ParamKind.NUM_PAT
private val STR = ParamKind.STR_PAT
private val PAT = ParamKind.PAT
private val FN = ParamKind.FN
private val ANY = ParamKind.ANY

// ---- entry builders -------------------------------------------------------------------

private fun MutableList<Builtin>.create(
    name: String,
    doc: String,
    example: String,
    vararg params: ParamKind,
    category: String = CONSTRUCTORS,
    variadic: ParamKind? = null,
    aliases: List<String> = emptyList(),
    make: Call.() -> Pattern<Any>,
) {
    add(Builtin(name, aliases, category, params.toList(), variadic, BuiltinKind.CONSTRUCTOR, doc, example) { Value.Pat(make()) })
}

private fun MutableList<Builtin>.signal(name: String, doc: String, example: String, pattern: Pattern<Any>) {
    add(Builtin(name, emptyList(), SIGNALS, emptyList(), null, BuiltinKind.SIGNAL, doc, example) { Value.Pat(pattern) })
}

private fun MutableList<Builtin>.setting(
    name: String,
    doc: String,
    example: String,
    vararg params: ParamKind,
    variadic: ParamKind? = null,
    listed: Boolean = true,
    effect: Call.() -> Unit,
) {
    add(
        Builtin(name, emptyList(), SETTINGS, params.toList(), variadic, BuiltinKind.SETTING, doc, example, listed) {
            effect()
            Value.None
        },
    )
}

private fun MutableList<Builtin>.method(
    name: String,
    category: String,
    doc: String,
    example: String,
    vararg params: ParamKind,
    variadic: ParamKind? = null,
    aliases: List<String> = emptyList(),
    transform: Call.(Pattern<Any>) -> Pattern<Any>,
) {
    add(
        Builtin(name, aliases, category, params.toList(), variadic, BuiltinKind.METHOD, doc, example) {
            Value.Pat(transform(checkNotNull(self)))
        },
    )
}

private fun MutableList<Builtin>.control(
    name: String,
    category: String,
    doc: String,
    example: String,
    kind: ParamKind,
    aliases: List<String> = emptyList(),
    convert: Call.(Any) -> ControlMap,
) {
    add(
        Builtin(name, aliases, category, listOf(kind), null, BuiltinKind.CONTROL, doc, example) {
            Value.Pat(setControl(args[0]) { convert(it) })
        },
    )
}

private fun MutableList<Builtin>.numericControl(
    key: String,
    category: String,
    doc: String,
    example: String,
    aliases: List<String> = emptyList(),
) = control(key, category, doc, example, NUM, aliases) { mapOf(key to number(it)) }

/**
 * Builds the pattern for a control. Called as a constructor ([Call.self] is null) it is a pattern of control
 * maps; called as a method it sets the control on every value of [Call.self], with the structure of that
 * pattern. A plain number or string is converted once, so mistakes in it are reported right away.
 */
private fun Call.setControl(arg: Value, convert: (Any) -> ControlMap): Pattern<Any> {
    val constant: ControlMap? = when (arg) {
        is Value.Num -> convert(arg.value)
        is Value.Str -> convert(arg.value)
        else -> null
    }
    val receiver = self
    return when {
        receiver == null -> if (constant != null) pure(constant) else toPattern(arg).fmap(convert)
        constant != null -> receiver.fmap { asControlMap(it) + constant }
        else -> receiver.appLeft(toPattern(arg).fmap(convert)) { value, set -> asControlMap(value) + set }
    }
}

// ---- constructors, signals and settings -----------------------------------------------

internal fun constructorBuiltins(): List<Builtin> = buildList {
    create("silence", "A pattern with no events, handy as a placeholder or an empty section.", "\$: silence") { silence }
    create(
        "seq", "Plays its arguments one after another, squeezed into a single cycle.",
        "seq(s(\"bd\"), s(\"sd sd\"), s(\"hh\"))", variadic = ANY, aliases = listOf("sequence", "fastcat"),
    ) { fastcat(args.indices.map { pattern(it) }) }
    create(
        "cat", "Plays one argument per cycle, in turn, and starts over after the last.",
        "cat(s(\"bd*2\"), s(\"sd*3\"))", variadic = ANY, aliases = listOf("slowcat"),
    ) { slowcat(args.indices.map { pattern(it) }) }
    create(
        "stack", "Plays all of its arguments at the same time.", "stack(s(\"bd*2\"), s(\"hh*4\"))", variadic = ANY,
    ) { stack(args.indices.map { pattern(it) }) }
    create(
        "arrange", "Plays each [cycles, pattern] section for its number of cycles, then starts over.",
        "arrange([2, s(\"bd*2\")], [1, s(\"sd*4\")])", variadic = ANY,
    ) {
        val sections = args.map { section ->
            val pair = (section as? Value.Arr)?.items?.takeIf { it.size == 2 }
            val cycles = (pair?.get(0) as? Value.Num)?.value
            if (pair == null || cycles == null || !(cycles > 0)) fail("'$name' expects [cycles, pattern] pairs")
            val length = Rational.of(cycles)
            length to toPattern(pair[1]).fast(length)
        }
        timecat(sections).slow(sections.fold(Rational.ZERO) { total, section -> total + section.first })
    }
    create(
        "run", "Counts from 0 up to n - 1, spread evenly over one cycle.", "n(run(4)).s(\"hh\")", NUM,
    ) { joinInt(0) { n -> fastcat((0 until n).map { pure(it.toDouble()) }) } }

    signal("sine", "A smooth wave that rises from 0 to 1 and falls back once per cycle.", "s(\"hh*8\").pan(sine)", sine)
    signal("cosine", "A sine wave shifted by a quarter cycle, so that it starts at its peak.", "s(\"hh*8\").pan(cosine)", cosine)
    signal("saw", "A ramp that climbs from 0 to 1 once per cycle.", "s(\"hh*8\").gain(saw)", saw)
    signal("isaw", "A ramp that falls from 1 to 0 once per cycle.", "s(\"hh*8\").gain(isaw)", isaw)
    signal("tri", "A triangle wave that rises from 0 to 1 and falls back once per cycle.", "s(\"hh*8\").pan(tri)", tri)
    signal("square", "Stays at 0 for half a cycle, then at 1 for the other half.", "s(\"hh*8\").gain(square.range(0.3, 1))", square)
    signal("rand", "Random numbers between 0 and 1 that are the same on every playback.", "s(\"hh*8\").gain(rand)", rand)
    signal("perlin", "Smooth random noise between 0 and 1.", "s(\"hh*8\").pan(perlin)", perlin)
    create(
        "irand", "Random whole numbers from 0 up to n - 1.", "s(\"hh*8\").n(irand(4))", NUM, category = SIGNALS,
    ) { joinNumber(0) { irand(it) } }

    setting(
        "setcps", "Sets the tempo in cycles per second; the last call in a program wins.", "setcps(0.6); \$: s(\"bd sd\")", NUM,
    ) { session.cps = tempo(1.0) }
    setting(
        "setcpm", "Sets the tempo in cycles per minute; 30 is half a cycle per second.", "setcpm(90); \$: s(\"bd sd\")", NUM,
    ) { session.cps = tempo(60.0) }
    setting("hush", "Silences the whole program, whatever else it contains.", "hush()") { session.hushed = true }
    setting("samples", "Not supported yet.", "samples('x')", variadic = ANY, listed = false) {
        fail("samples() isn't supported yet \u2014 import sample folders from the Sounds screen instead")
    }
}

/** The tempo in cycles per second given as the only argument, a plain number, after dividing it by [divisor]. */
private fun Call.tempo(divisor: Double): Double {
    val cps = (args[0] as? Value.Num)?.let { it.value / divisor }
    if (cps == null || !(cps > 0.0 && cps <= 10.0)) fail("Tempo out of range")
    return cps
}

// ---- methods --------------------------------------------------------------------------

internal fun methodBuiltins(): List<Builtin> = buildList {
    method("fast", TIME, "Plays the pattern faster by a factor; 2 fits it in half the time.", "s(\"bd sd\").fast(2)", NUM) { p ->
        joinRational(0) { p.fast(it) }
    }
    method("slow", TIME, "Plays the pattern slower by a factor; 2 spreads it over two cycles.", "s(\"bd sd hh cp\").slow(2)", NUM) { p ->
        joinRational(0) { p.slow(it) }
    }
    method("early", TIME, "Shifts the pattern earlier in time by a number of cycles.", "s(\"bd sd hh cp\").early(0.25)", NUM) { p ->
        joinRational(0) { p.early(it) }
    }
    method("late", TIME, "Shifts the pattern later in time by a number of cycles.", "s(\"bd sd hh cp\").late(0.25)", NUM) { p ->
        joinRational(0) { p.late(it) }
    }
    method("hurry", TIME, "Plays faster and raises the speed of samples by the same factor.", "s(\"bd sd\").hurry(2)", NUM) { p ->
        joinRational(0) { factor ->
            p.fast(factor).fmap { value ->
                val controls = asControlMap(value)
                controls + ("speed" to ((controls["speed"] as? Double ?: 1.0) * factor.toDouble()))
            }
        }
    }
    method("rev", TIME, "Plays every cycle backwards.", "s(\"bd sd hh cp\").rev()") { p -> p.rev() }
    method("palindrome", TIME, "Plays forwards on one cycle and backwards on the next.", "s(\"bd sd hh cp\").palindrome()") { p ->
        p.lastOf(2, p.rev())
    }
    method("iter", TIME, "Starts one step later each cycle and wraps around after n cycles.", "s(\"bd sd hh cp\").iter(4)", NUM) { p ->
        joinInt(0) { p.iter(it) }
    }
    method("iterBack", TIME, "Like iter, but moves the start backwards each cycle.", "s(\"bd sd hh cp\").iterBack(4)", NUM) { p ->
        joinInt(0) { p.iterBack(it) }
    }
    method("ply", TIME, "Repeats every event n times within its own time slot.", "s(\"bd sd\").ply(2)", NUM) { p ->
        joinInt(0) { p.ply(it) }
    }
    method(
        "inside", TIME, "Applies a function as if each 1/n of a cycle were a whole cycle.", "s(\"bd sd hh cp\").inside(2, rev)", NUM, FN,
    ) { p ->
        val f = function(1)
        joinRational(0) { p.inside(it, f) }
    }
    method(
        "outside", TIME, "Applies a function across n cycles at once, as if they were one.",
        "cat(s(\"bd sd\"), s(\"hh cp\")).outside(2, rev)", NUM, FN,
    ) { p ->
        val f = function(1)
        joinRational(0) { p.outside(it, f) }
    }
    method(
        "swingBy", TIME, "Delays the second half of each 1/n of a cycle by a fraction of that slice.",
        "s(\"hh*8\").swingBy(1/3, 4)", NUM, NUM,
    ) { p -> joinRational(0) { amount -> joinRational(1) { slices -> p.swingBy(amount, slices) } } }
    method("swing", TIME, "Shorthand for swingBy(1/3, n).", "s(\"hh*8\").swing(4)", NUM) { p ->
        joinRational(0) { p.swingBy(Rational(1, 3), it) }
    }
    method(
        "off", TIME, "Plays the pattern together with a delayed copy that a function transforms.",
        "s(\"bd sd\").off(0.125, x => x.speed(2))", NUM, FN,
    ) { p ->
        val f = function(1)
        joinRational(0) { p.off(it, f) }
    }

    method(
        "struct", STRUCTURE, "Plays the pattern on the true steps of another pattern, taking its rhythm.",
        "note(\"c3 e3 g3\").s(\"sine\").struct(\"x ~ x x\")", PAT,
    ) { p -> p.struct(pattern(0)) }
    method(
        "mask", STRUCTURE, "Silences the parts of the pattern where another pattern is false.",
        "s(\"hh*8\").mask(\"1 1 0 1\")", PAT,
    ) { p -> p.mask(pattern(0)) }
    method("euclid", STRUCTURE, "Spreads k hits as evenly as possible over n steps.", "s(\"bd\").euclid(3, 8)", NUM, NUM) { p ->
        joinInt(0) { hits -> joinInt(1) { steps -> p.euclid(hits, steps) } }
    }
    method(
        "euclidRot", STRUCTURE, "Like euclid, with the rhythm rotated left by r steps.", "s(\"bd\").euclidRot(3, 8, 2)", NUM, NUM, NUM,
    ) { p ->
        joinInt(0) { hits -> joinInt(1) { steps -> joinInt(2) { rotation -> p.euclidRot(hits, steps, rotation) } } }
    }
    method(
        "segment", STRUCTURE, "Samples the pattern n times per cycle, which turns signals into events.",
        "note(saw.range(48, 60)).segment(4).s(\"sine\")", NUM,
    ) { p -> joinRational(0) { p.segment(it) } }
    method(
        "chunk", STRUCTURE, "Applies a function to one n-th of the cycle, moving along one slice each cycle.",
        "s(\"bd sd hh cp\").chunk(4, x => x.speed(2))", NUM, FN,
    ) { p ->
        val applied = function(1)(p)
        joinInt(0) { p.chunk(it, applied) }
    }
    method(
        "every", STRUCTURE, "Applies a function every n-th cycle, starting with the first.",
        "s(\"bd sd\").every(4, fast(2))", NUM, FN, aliases = listOf("firstOf"),
    ) { p ->
        val applied = function(1)(p)
        joinInt(0) { p.every(it, applied) }
    }
    method("lastOf", STRUCTURE, "Applies a function on the last cycle of every n.", "s(\"bd sd\").lastOf(4, rev)", NUM, FN) { p ->
        val applied = function(1)(p)
        joinInt(0) { p.lastOf(it, applied) }
    }
    method(
        "superimpose", STRUCTURE, "Plays the pattern together with a transformed copy of itself.",
        "s(\"bd sd\").superimpose(x => x.speed(2))", FN,
    ) { p -> p.superimpose(function(0)) }
    method(
        "layer", STRUCTURE, "Plays several transformed copies of the pattern at once.",
        "s(\"bd sd\").layer(rev, x => x.fast(2))", variadic = FN,
    ) { p -> stack(args.indices.map { function(it)(p) }) }
    method(
        "jux", STRUCTURE, "Plays the pattern in the left ear and a transformed copy in the right.",
        "s(\"bd sd hh cp\").jux(rev)", FN,
    ) { p ->
        val copy = function(0)(p)
        stack(p.withControls("pan" to 0.0), copy.withControls("pan" to 1.0))
    }
    method(
        "juxBy", STRUCTURE, "Like jux with a narrower stereo spread, from 0 (centre) to 1 (full).",
        "s(\"bd sd hh cp\").juxBy(0.5, rev)", NUM, FN,
    ) { p ->
        val copy = function(1)(p)
        joinNumber(0) { width ->
            stack(p.withControls("pan" to 0.5 - width / 2), copy.withControls("pan" to 0.5 + width / 2))
        }
    }
    method(
        "chop", STRUCTURE, "Cuts each event into n pieces that play consecutive parts of the sample.",
        "s(\"bd sd\").chop(4)", NUM,
    ) { p -> joinInt(0) { pieces -> p.splitWholes(pieces) { value, i -> chopped(value, i, pieces) } } }
    method("striate", STRUCTURE, "Interlaces n slices of each sample across the pattern.", "s(\"bd sd\").striate(4)", NUM) { p ->
        joinInt(0) { slices ->
            fastcat(
                (0 until slices).map { i ->
                    p.withControls("begin" to i.toDouble() / slices, "end" to (i + 1).toDouble() / slices)
                },
            )
        }
    }

    method("degrade", RANDOM, "Drops about half of the events at random.", "s(\"hh*8\").degrade()") { p -> p.degradeBy(0.5) }
    method("degradeBy", RANDOM, "Drops events at random with probability p, from 0 to 1.", "s(\"hh*8\").degradeBy(0.8)", NUM) { p ->
        joinNumber(0) { p.degradeBy(it) }
    }
    method(
        "sometimesBy", RANDOM, "Applies a function to a random share p of the events.",
        "s(\"hh*8\").sometimesBy(0.3, x => x.speed(2))", NUM, FN,
    ) { p ->
        val f = function(1)
        joinNumber(0) { p.sometimesBy(it, f) }
    }
    sometimesEntry("sometimes", 0.5, "about half of the events")
    sometimesEntry("often", 0.75, "about three quarters of the events")
    sometimesEntry("rarely", 0.25, "about a quarter of the events")
    sometimesEntry("almostNever", 0.1, "about one event in ten")
    sometimesEntry("almostAlways", 0.9, "about nine events in ten")
    method(
        "someCyclesBy", RANDOM, "Applies a function on a random share p of the cycles.",
        "s(\"bd sd\").someCyclesBy(0.3, fast(2))", NUM, FN,
    ) { p ->
        val applied = function(1)(p)
        joinNumber(0) { p.someCyclesBy(it, applied) }
    }
    method("someCycles", RANDOM, "Applies a function on about half of the cycles.", "s(\"bd sd\").someCycles(rev)", FN) { p ->
        p.someCyclesBy(0.5, function(0)(p))
    }

    method(
        "add", VALUES, "Adds to the values; note names count as numbers, and controls change note or n.",
        "note(\"c3 e3\").add(12).s(\"sine\")", ANY,
    ) { p -> arithmetic(ArithOp.ADD, Value.Pat(p), args[0]) }
    method("sub", VALUES, "Subtracts from the values.", "n(\"2 3\").sub(1).s(\"hh\")", ANY) { p ->
        arithmetic(ArithOp.SUB, Value.Pat(p), args[0])
    }
    method("mul", VALUES, "Multiplies the values.", "n(\"1 2\").mul(2).s(\"hh\")", ANY) { p ->
        arithmetic(ArithOp.MUL, Value.Pat(p), args[0])
    }
    method("div", VALUES, "Divides the values.", "n(\"2 4\").div(2).s(\"hh\")", ANY) { p ->
        arithmetic(ArithOp.DIV, Value.Pat(p), args[0])
    }
    method(
        "range", VALUES, "Rescales numbers between 0 and 1 to run from lo to hi.",
        "s(\"hh*8\").lpf(sine.range(400, 4000))", NUM, NUM,
    ) { p -> joinNumber(0) { low -> joinNumber(1) { high -> p.fmap { number(it) * (high - low) + low } } } }
    method(
        "scale", VALUES, "Turns numbers into notes of a scale such as C:major or eb2:minor.",
        "n(\"0 2 4 7\").scale(\"C:minor\").s(\"sine\")", STR,
    ) { p ->
        (args[0] as? Value.Str)?.let { scaleNote(0, it.value) }
        joinText(0) { scale -> p.fmap { scaled(it, scale) } }
    }
}

private fun MutableList<Builtin>.sometimesEntry(name: String, probability: Double, share: String) {
    method(
        name, RANDOM, "Applies a function to $share.", "s(\"hh*8\").$name(x => x.speed(2))", FN,
    ) { p -> p.sometimesBy(probability, function(0)) }
}

/** Piece [index] of [count] of a sample: narrows `begin`..`end` (0..1 unless set) to that piece. */
private fun chopped(value: Any, index: Int, count: Int): ControlMap {
    val controls = asControlMap(value)
    val begin = controls["begin"] as? Double ?: 0.0
    val end = controls["end"] as? Double ?: 1.0
    return controls + mapOf(
        "begin" to begin + (end - begin) * index / count,
        "end" to begin + (end - begin) * (index + 1) / count,
    )
}

/** [value] (a degree, or a control map holding one in `n`) as a note of [scale]. */
private fun Call.scaled(value: Any, scale: String): ControlMap = when (value) {
    is Double -> mapOf("note" to scaleNote(Math.round(value).toInt(), scale))
    is Map<*, *> -> {
        val degree = value["n"] as? Double ?: fail("scale: needs n values")
        (asControlMap(value) - "n") + ("note" to scaleNote(Math.round(degree).toInt(), scale))
    }
    else -> fail("'$name' expects a number, got ${describeValue(value)}")
}

// ---- controls -------------------------------------------------------------------------

internal fun controlBuiltins(): List<Builtin> = buildList {
    control(
        "s", SOUND, "Chooses the sound to play: a synth, a drum or a sample bank, optionally with a sample number as in bd:2.",
        "s(\"bd sd:2 hh\")", STR, aliases = listOf("sound"),
    ) { soundControl(text(it)) }
    control(
        "n", SOUND, "Picks a sample number in a bank, or a scale degree when followed by scale().",
        "n(\"0 1 2 3\").s(\"hh\")", NUM,
    ) { mapOf("n" to number(it)) }
    control(
        "note", SOUND, "Sets the pitch as a MIDI number or a note name like c3 or eb4.",
        "note(\"c3 eb3 g3\").s(\"sawtooth\")", ANY,
    ) { mapOf("note" to noteNumber(it)) }
    control(
        "bank", SOUND, "Looks sounds up in a named bank: kick with the bank my plays the sound called my_kick.",
        "s(\"kick snare\").bank(\"my\")", STR,
    ) { mapOf("bank" to text(it)) }
    numericControl(
        "speed", SOUND, "Playback speed of samples: 2 is an octave up, negative plays backwards.",
        "s(\"bd*3\").speed(\"1 2 -1\")",
    )
    numericControl("begin", SOUND, "Where in the sample playback starts, from 0 to 1.", "s(\"bd*2\").begin(0.25)")
    numericControl("end", SOUND, "Where in the sample playback stops, from 0 to 1.", "s(\"bd*2\").end(0.5)")
    numericControl(
        "cut", SOUND, "Puts sounds in a cut group; a new sound silences earlier ones of the same group.",
        "s(\"oh*4\").cut(1)",
    )

    numericControl("gain", MIX, "Multiplies the volume of each event.", "s(\"bd*4\").gain(\"1 0.6\")")
    numericControl(
        "velocity", MIX, "Scales the volume like the strength of a key press, from 0 to 1.",
        "s(\"bd*4\").velocity(\"1 0.5\")",
    )
    numericControl(
        "postgain", MIX, "Multiplies the volume after the effects have been applied.",
        "s(\"bd*2\").room(0.5).postgain(0.8)",
    )
    numericControl("pan", MIX, "Places the sound between the left (0) and right (1) speaker.", "s(\"hh*4\").pan(\"0 1\")")

    numericControl(
        "clip", ENVELOPE, "Scales how long a note is held, as a multiple of its length.",
        "note(\"c3 e3\").s(\"sine\").clip(0.5)", listOf("legato"),
    )
    numericControl(
        "attack", ENVELOPE, "Seconds the sound takes to fade in to full volume.",
        "note(\"c3\").s(\"sine\").attack(0.2)", listOf("att"),
    )
    numericControl(
        "decay", ENVELOPE, "Seconds the sound takes to fall from full volume to the sustain level.",
        "note(\"c3\").s(\"sine\").decay(0.3)", listOf("dec"),
    )
    numericControl(
        "sustain", ENVELOPE, "The volume held after the decay, from 0 to 1.",
        "note(\"c3\").s(\"sine\").sustain(0.5)", listOf("sus"),
    )
    numericControl(
        "release", ENVELOPE, "Seconds the sound takes to fade out after the note ends.",
        "note(\"c3\").s(\"sine\").release(0.5)", listOf("rel"),
    )

    numericControl(
        "lpf", FILTERS, "Low-pass filter: cuts frequencies above the cutoff in Hz.",
        "note(\"c2*4\").s(\"sawtooth\").lpf(600)", listOf("cutoff", "ctf"),
    )
    numericControl(
        "lpq", FILTERS, "Resonance of the low-pass filter; higher values ring around the cutoff.",
        "note(\"c2*4\").s(\"sawtooth\").lpf(600).lpq(8)", listOf("resonance"),
    )
    numericControl(
        "hpf", FILTERS, "High-pass filter: cuts frequencies below the cutoff in Hz.",
        "s(\"hh*4\").hpf(5000)", listOf("hcutoff"),
    )
    numericControl(
        "hpq", FILTERS, "Resonance of the high-pass filter.", "s(\"hh*4\").hpf(5000).hpq(6)", listOf("hresonance"),
    )
    numericControl(
        "bpf", FILTERS, "Band-pass filter: keeps only the frequencies around the given centre in Hz.",
        "s(\"hh*4\").bpf(2000)", listOf("bandf"),
    )
    numericControl(
        "bpq", FILTERS, "Width of the band-pass filter; higher values are narrower.",
        "s(\"hh*4\").bpf(2000).bpq(5)", listOf("bandq"),
    )

    numericControl("shape", EFFECTS, "Distorts the sound; 0 is clean and values close to 1 are harsh.", "s(\"bd*2\").shape(0.6)")
    numericControl(
        "crush", EFFECTS, "Reduces the bit depth for a crunchy sound; lower numbers are rougher, from 1 to 16.",
        "s(\"hh*4\").crush(4)",
    )
    numericControl("coarse", EFFECTS, "Reduces the sample rate by repeating every n-th sample.", "s(\"hh*4\").coarse(8)")
    numericControl("delay", EFFECTS, "How much of the sound is sent to the echo, from 0 to 1.", "s(\"sd*2\").delay(0.5)")
    numericControl(
        "delaytime", EFFECTS, "Time between echoes in seconds.", "s(\"sd*2\").delay(0.5).delaytime(0.2)", listOf("delayt"),
    )
    numericControl(
        "delayfeedback", EFFECTS, "How much each echo feeds the next, from 0 to 0.95.",
        "s(\"sd*2\").delay(0.5).delayfeedback(0.7)", listOf("delayfb"),
    )
    numericControl("room", EFFECTS, "How much of the sound is sent to the reverb, from 0 to 1.", "s(\"sd*2\").room(0.4)")
    numericControl(
        "size", EFFECTS, "Size of the reverb room, from 0 to 1.", "s(\"sd*2\").room(0.4).size(0.8)", listOf("roomsize"),
    )
}

/** A MIDI number as it is, or a note name converted to one. */
private fun Call.noteNumber(value: Any): Double = when (value) {
    is Double -> value
    is String -> noteToMidi(value)?.toDouble() ?: fail("Not a note: '$value'")
    else -> fail("'$name' expects a number or a note name, got ${describeValue(value)}")
}
