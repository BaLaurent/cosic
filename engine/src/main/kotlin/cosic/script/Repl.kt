package cosic.script

import cosic.mini.getLeafLocations
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Registered
import cosic.pattern.Undefined
import cosic.pattern._fast
import cosic.pattern.jsKeyOrder
import cosic.pattern.jsNumberToString
import cosic.pattern.jsToNumber
import cosic.pattern.reportingExhaustion
import cosic.pattern.silence
import cosic.pattern.stack
import cosic.script.catalogue.Catalogue
import cosic.script.js.PropFlags
import cosic.script.js.describe
import cosic.script.js.stdlib.std
import kotlin.concurrent.withLock

/** The longest and shortest tempo a program may set, in cycles per second (it takes `Tempo out of range` to go beyond). */
private const val MAX_CPS = 10.0

/**
 * What a call into the program may use when the scheduler runs it from the audio thread (the callback of `fmap` or of a
 * registered function): far less than an evaluation, which has time to spare.
 */
private val CALLBACK_LIMITS = Limits(steps = 1_000_000L, millis = 100L, maxDepth = 300)

/**
 * A REPL: the part of Strudel that evaluates the code of an editor over and over in one world (`repl()` of
 * packages/core/repl.mjs). It owns the [realm] its programs run in, with the catalogue installed, and what lives from one
 * evaluation to the next: the tempo (kept by the [host]; it only changes when a program sets it, and a program that
 * fails after `setcps(2)` leaves it at 2), and what the program registered with `$:` and `name:` labels.
 *
 * [evaluate] transpiles and runs a program, then combines what it labelled into the pattern to play: every unmuted
 * label (`name:` is played, `_name:` and `name_:` are muted, `$:` is an anonymous one that gets a number, `S…` solos),
 * stacked; `each(f)` is applied to each, then `all(f)` to the stack; a program without labels plays the value of its last
 * statement. A program that throws leaves the caller with the pattern it had.
 *
 * Calls into the session are serialised by [host]'s lock; the patterns it makes call back into the program from any thread.
 */
internal class ReplSession(val host: SessionHost = SessionHost(), val realm: Realm = Realm(host)) {
    val bridge: Bridge = Catalogue.install(realm)

    private val pPatterns = LinkedHashMap<String, Pattern>()
    private var anonymousIndex = 0
    private var eachTransform: Any? = null
    private val allTransforms = ArrayList<Any?>()
    private val silenceValue: PatternValue = bridge.wrap(silence)

    /** `register('cpm', (cpm, pat) => pat._fast(cpm / 60 / scheduler.cps))` of the REPL: it reads the tempo when it is called. */
    private val cpm = Registered(
        listOf("cpm"), 2, true, false, { it.innerJoin() },
        { a -> (a[1] as Pattern)._fast(jsToNumber(a[0]) / 60.0 / host.cps) },
    )

    init {
        realm.callbackLimits = CALLBACK_LIMITS
        realm.std.loop.executionLock = host.lock
        routeKernelLogsTo(host)
    }

    /**
     * Runs [code] and returns what it plays. When [shouldHush] (the default) everything the previous program labelled is
     * forgotten first. A syntax error, an error of the program, or a program that took too long is a [CosicError] with the
     * range it is at; the tempo the program set before it failed stays set.
     */
    fun evaluate(code: String, shouldHush: Boolean = true): ProgramResult = reportingExhaustion {
        host.lock.withLock { evaluateHere(code, shouldHush) }
    }

    private fun evaluateHere(code: String, shouldHush: Boolean): ProgramResult {
        routeKernelLogsTo(host)
        installReplFunctions()
        allTransforms.clear()
        if (shouldHush) hush()
        val transpiled = transpile(code, TranspileOptions(), DEFAULT_WIDGET_TYPES) { quoted, start -> getLeafLocations(quoted, start) }
        for (warning in transpiled.warnings) host.log(warning, LogLevel.WARNING)
        val value = realm.runProgram(transpiled.program, code)
        return ProgramResult(combine(value), host.cps, transpiled.miniLocations, transpiled.widgets, transpiled.sliders)
    }

    /** What plays once the program has run: see the description of the class. */
    private fun combine(value: Any?): Pattern {
        var result: Any?
        if (pPatterns.isNotEmpty()) {
            var patterns = ArrayList<Pattern>()
            var soloActive = false
            for (key in jsKeyOrder(pPatterns.keys)) {
                val soloed = key.length > 1 && key.startsWith("S")
                if (soloed && !soloActive) {
                    // the first soloed pattern drops what was collected
                    patterns = ArrayList()
                    soloActive = true
                }
                if (!soloActive || soloed) {
                    patterns.add(pPatterns.getValue(key).withState { it.setControls(mapOf("id" to key)) })
                }
            }
            val each = eachTransform
            result = stack(if (each != null) patterns.map { transform(each, it) } else patterns)
        } else {
            val each = eachTransform
            result = if (each != null) transform(each, value) else (value as? PatternValue)?.pattern
        }
        for (f in allTransforms) result = transform(f, result)
        return result as? Pattern ?: silence
    }

    /** `f(x)`, `x` and the result being values of the kernel. */
    private fun transform(f: Any?, x: Any?): Any? = bridge.toKernel(bridge.callJs(f, bridge.toJs(x)))

    private fun hush() {
        pPatterns.clear()
        anonymousIndex = 0
        eachTransform = null
    }

    // ---- what the REPL adds to the world of a program ------------------------------------------------------------------------

    /**
     * Puts the REPL's own functions in the realm, as Strudel's `evaluate` does before every run: `all`, `each`, `hush`,
     * `setcps` and `setcpm` (also spelt `setCps` and `setCpm`), `cpm`, and the methods `p`, `q` and `d1` to `d9`, `p1` to
     * `p9`, `q1` to `q9` of patterns (repl.mjs:184-232).
     */
    private fun installReplFunctions() {
        realm.defineGlobalFunction("all", 1) { _, a -> allTransforms.add(a.getOrElse(0) { Undefined }); silenceValue }
        realm.defineGlobalFunction("each", 1) { _, a -> eachTransform = a.getOrElse(0) { Undefined }; silenceValue }
        realm.defineGlobalFunction("hush", 0) { _, _ -> hush(); silenceValue }
        for (name in listOf("setCps", "setcps")) {
            realm.defineGlobalFunction(name, 1) { _, a -> setTempo(a.getOrElse(0) { Undefined }, 1.0); silenceValue }
        }
        for (name in listOf("setCpm", "setcpm")) {
            realm.defineGlobalFunction(name, 1) { _, a -> setTempo(a.getOrElse(0) { Undefined }, 60.0); silenceValue }
        }
        bridge.installRegistered(cpm)

        val proto = realm.patternPrototype
        proto.defineOwn("p", realm.function("p", 1) { self, a -> label(self, a.getOrElse(0) { Undefined }) }, PropFlags.HIDDEN)
        proto.defineOwn("q", realm.function("q", 1) { _, _ -> silenceValue }, PropFlags.HIDDEN)
        for (i in 1..9) {
            val id = i.toDouble()
            // Strudel calls p(i) with a number, which throws there; here the number is the name of the label
            proto.defineAccessor("d$i", { self -> label(self, id) }, null, PropFlags.CONFIGURABLE)
            proto.defineAccessor("p$i", { self -> label(self, id) }, null, PropFlags.CONFIGURABLE)
            proto.defineOwn("q$i", silenceValue, PropFlags.HIDDEN)
        }
    }

    /** `setcps(x)`, and `setcpm(x)` with [divisor] 60: [x] is a number or a constant pattern (`"1"`), and the tempo changes at once. */
    private fun setTempo(x: Any?, divisor: Double) {
        val raw = if (x is PatternValue) x.pattern.pureValue ?: Undefined else x
        val cps = jsToNumber(raw) / divisor
        if (!(cps > 0.0 && cps <= MAX_CPS)) throw CosicError("Tempo out of range")
        host.cps = cps
    }

    /** `pattern.p(id)`: registers the pattern under a label; `_name` and `name_` mute it, a name with `$` in it gets the next number. */
    private fun label(self: Any?, idArg: Any?): Any? {
        val pattern = (self as? PatternValue) ?: throw realm.typeError("Pattern.prototype.p called on incompatible receiver ${realm.describe(self)}")
        var id = when (idArg) {
            is String -> {
                if (idArg.startsWith("_") || idArg.endsWith("_")) return silenceValue
                idArg
            }
            is Double -> jsNumberToString(idArg)
            null, Undefined -> throw realm.typeError("Cannot read properties of ${if (idArg == null) "null" else "undefined"} (reading 'includes')")
            else -> throw realm.typeError("id.includes is not a function")
        }
        if (id.contains('$')) {
            id = "$id$anonymousIndex"
            anonymousIndex++
        }
        pPatterns[id] = pattern.pattern
        return pattern
    }

    companion object {
        fun create(): ReplSession = ReplSession()
    }
}

/** The result of the program [code], run in a session of its own: for tests and the reference's examples. */
internal fun evaluateProgram(code: String): ProgramResult = ReplSession.create().evaluate(code)
