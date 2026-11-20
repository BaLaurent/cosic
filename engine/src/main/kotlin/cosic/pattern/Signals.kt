package cosic.pattern

/*
 * Continuous patterns (`signal.mjs`): a pattern that has a value at every moment instead of events. A signal answers a
 * query with one hap that has no whole, whose part is the span asked for and whose value is a function of the time
 * the span begins at; so `sine` as the argument of another pattern's function (`note("c e").pan(sine)`) is read at
 * the start of each event, and `sine.segment(4)` at the start of each quarter.
 *
 * The signals that are values (`saw`, `sine`, `rand`, ...) live here and in Random.kt; the catalogue entries that make
 * them callable from programs are in script/catalogue/Signals.kt and Random.kt. Fractions in Strudel's signals
 * (`time`, `cyclesPer`, `per`, `open`, `close`) are numbers here: the arithmetic is done on fractions and the result
 * made a number last, so only printing a bare value would show the difference.
 */

/**
 * A pattern with one hap per query, without a whole, whose value is [func] of the beginning of the span and of the
 * controls of the query (`signal`). Signals are sampled at the beginning of a span, not at its middle.
 */
internal fun signal(func: (t: Rational, controls: Map<String, Any?>) -> Any?): Pattern =
    Pattern { state -> listOf(Hap(null, state.span, func(state.span.begin, state.controls))) }

/** A continuous pattern of one constant value (`steady`). */
internal fun steady(value: Any?): Pattern = Pattern { state -> listOf(Hap(null, state.span, value)) }

/** `pat.toBipolar()`: numbers in 0..1 become numbers in -1..1. */
internal fun Pattern.toBipolarValues(): Pattern = fmap { jsToNumber(it) * 2.0 - 1.0 }

/** `pat.fromBipolar()`: numbers in -1..1 become numbers in 0..1. */
internal fun Pattern.fromBipolarValues(): Pattern = fmap { (jsToNumber(it) + 1.0) / 2.0 }

// ---- waves -------------------------------------------------------------------------------------------------------------

/** A sawtooth between 0 and 1. It is `_mod(t, 1)`, which is not the position in the cycle: `1/3` gives `0.33333333333333326`. */
internal val saw: Pattern = signal { t, _ -> jsMod(t.toDouble(), 1.0) }

/** A sawtooth between -1 and 1. */
internal val saw2: Pattern = saw.toBipolarValues()

/** A sawtooth between 1 and 0. */
internal val isaw: Pattern = signal { t, _ -> 1.0 - jsMod(t.toDouble(), 1.0) }

/** A sawtooth between 1 and -1. */
internal val isaw2: Pattern = isaw.toBipolarValues()

/** A sine between -1 and 1. */
internal val sine2: Pattern = signal { t, _ -> StrictMath.sin(Math.PI * 2 * t.toDouble()) }

/** A sine between 0 and 1: `(sin + 1) / 2`, not `0.5 + 0.5 * sin`, which differs in the last digits. */
internal val sine: Pattern = sine2.fromBipolarValues()

/** A cosine between 0 and 1: the sine a quarter of a cycle earlier. */
internal val cosine: Pattern = sine._early(Rational(1, 4))

/** A cosine between -1 and 1. */
internal val cosine2: Pattern = sine2._early(Rational(1, 4))

/** A square wave between 0 and 1, 0 during the first half of each cycle. */
internal val square: Pattern = signal { t, _ -> Math.floor(jsMod(t.toDouble() * 2.0, 2.0)) }

/** A square wave between -1 and 1. */
internal val square2: Pattern = square.toBipolarValues()

/** A square wave between 1 and 0. */
internal val isquare: Pattern = signal { t, _ -> 1.0 - Math.floor(jsMod(t.toDouble() * 2.0, 2.0)) }

/** A square wave between 1 and -1. */
internal val isquare2: Pattern = isquare.toBipolarValues()

/** A triangle between 0 and 1: a rising saw for the first half of each cycle and a falling one for the second (a two-step sequence of signals). */
internal val tri: Pattern = fastcat(saw, isaw)

/** A triangle between -1 and 1. */
internal val tri2: Pattern = fastcat(saw2, isaw2)

/** An upside-down triangle between 1 and 0. */
internal val itri: Pattern = fastcat(isaw, saw)

/** An upside-down triangle between 1 and -1. */
internal val itri2: Pattern = fastcat(isaw2, saw2)

/** The time in cycles. */
internal val time: Pattern = signal { t, _ -> t.toDouble() }

// ---- the length of events ---------------------------------------------------------------------------------------------

/** The duration of the events of the pattern it is combined with, in cycles per event: it takes the structure of the other pattern. */
internal val cyclesPer: Pattern = Pattern { state -> listOf(Hap(null, state.span, state.span.duration.toDouble())) }

/** The reciprocal of [cyclesPer]: how many events like this one fit in a cycle (`perCycle`). */
internal val per: Pattern = Pattern { state -> listOf(Hap(null, state.span, (Rational.ONE / state.span.duration).toDouble())) }

/** Like [per] on an exponential scale: it goes up by one when the duration of an event halves. */
internal val perx: Pattern = Pattern { state ->
    val n = (Rational.ONE / state.span.duration).toDouble()
    listOf(Hap(null, state.span, StrictMath.log(n) / StrictMath.log(2.0) + 1.0))
}

/** A signal that goes from 0 to 1 over the first cycle and stays at 1 (`open`). */
internal val openSignal: Pattern = signal { t, _ -> t.min(Rational.ONE).toDouble() }

/** A signal that goes from 1 to 0 over the first cycle and stays at 0 (`close`). */
internal val closeSignal: Pattern = signal { t, _ -> (Rational.ONE - t).max(Rational.ZERO).toDouble() }

// ---- pointer and keyboard ---------------------------------------------------------------------------------------------

/**
 * Where the pointer is, each coordinate between 0 and 1 of the screen (`mousex`, `mousey`). The app sets it from touches
 * (K8); it is 0 while nothing does, as it is in Strudel without a window.
 */
internal object PointerState {
    @Volatile
    var x: Double = 0.0

    @Volatile
    var y: Double = 0.0
}

/** The pointer's horizontal position, between 0 and 1 (`mousex`, `mouseX`). */
internal val mousex: Pattern = signal { _, _ -> PointerState.x }

/** The pointer's vertical position, between 0 and 1 (`mousey`, `mouseY`). */
internal val mousey: Pattern = signal { _, _ -> PointerState.y }

/**
 * The keys that are held, by their `KeyboardEvent.key` name (`getCurrentKeyboardState` of util.mjs). Whoever has a
 * keyboard sets the [provider]; without one there is no keyboard state, like Strudel in Node, where asking for a key is an error.
 */
internal object KeyboardState {
    @Volatile
    var provider: (() -> Map<String, Boolean>)? = null
}

/** The names Strudel lets programs call the modifier and arrow keys (`keyAlias`). */
private val KEY_ALIAS = mapOf(
    "control" to "Control", "ctrl" to "Control", "alt" to "Alt", "shift" to "Shift",
    "down" to "ArrowDown", "up" to "ArrowUp", "left" to "ArrowLeft", "right" to "ArrowRight",
)

/** Whether every key of [keyname] (a key or a list of keys) is held (`_keyDown`). An empty list is true without asking the keyboard. */
internal fun _keyDown(keyname: Any?): Boolean {
    val keys = keyname as? List<*> ?: listOf(keyname)
    if (keys.isEmpty()) return true
    val state = KeyboardState.provider?.invoke()
    for (key in keys) {
        val name = (key as? String)?.let { KEY_ALIAS[it] } ?: key
        if (state == null) throw CosicError("Cannot read properties of undefined (reading '${jsToString(name)}')")
        if (state[jsToString(name)] != true) return false
    }
    return true
}
