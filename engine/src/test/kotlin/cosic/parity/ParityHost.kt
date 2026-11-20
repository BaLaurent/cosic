package cosic.parity

import cosic.mini.getLeafLocations
import cosic.pattern.Alignment
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.Rng
import cosic.pattern.TOO_DEEP
import cosic.pattern.TOO_DENSE
import cosic.pattern.Undefined
import cosic.pattern.reportingExhaustion
import cosic.pattern.silence
import cosic.script.MiniLeafLocator
import cosic.script.PatternValue
import cosic.script.Realm
import cosic.script.ReplSession
import cosic.script.SessionHost
import cosic.script.TranspileOptions
import cosic.script.bridge
import cosic.script.catalogue.Catalogue
import cosic.script.catalogue.refPattern
import cosic.script.js.PropFlags
import cosic.script.transpile

/*
 * The world the parity programs run in, the counterpart of Strudel's test/runtime.mjs (LANG 3.0 step 4): the whole
 * catalogue, plus the mocks that file puts over what cannot work in Node (O1-O5): the methods that draw or play are the
 * identity, `samples`, `setcps` and `setcpm` return their argument, there is no audio context, `piano` is the website's.
 * Every case gets a realm of its own, so nothing one program defines is seen by the next.
 */

/** The methods test/runtime.mjs:81-108 replaces with `function () { return this }`. */
private val IDENTITY_METHODS = listOf(
    "osc", "csound", "tone", "webdirt", "pianoroll", "speak", "wave", "filter", "adsr", "webaudio", "soundfont", "tune",
    "midi", "_scope", "_spiral", "_pitchwheel", "_pianoroll", "_spectrum", "_print", "markcss", "p", "dough",
)

/** The globals the oracle makes `(x) => x`. */
private val IDENTITY_FUNCTIONS = listOf("samples", "setcps", "setcpm", "csound", "loadOrc", "initDough", "backgroundImage")

/** The globals the oracle makes `() => {}`. */
private val EMPTY_FUNCTIONS = listOf("loadSoundfont", "loadCSound", "loadCsound", "loadcsound", "getDuration")

/** `website/src/repl/piano.mjs`, which the oracle imports. Everything it needs is looked up when `.piano()` is called. */
private const val PIANO_MJS = """
Pattern.prototype.piano = function () {
  const maxPan = noteToMidi('C8');
  const panwidth = (pan, width) => pan * width + (1 - width) / 2;
  return this.clip(1)
    .s('piano')
    .release(0.1)
    .fmap((value) => {
      const midi = valueToMidi(value);
      const pan = panwidth(Math.min(Math.round(midi) / maxPan, 1), 0.5);
      return { ...value, pan: (value.pan || 1) * pan };
    });
};
"""

/** One realm with the catalogue and the oracle's mocks; [session] is set for a REPL case. */
internal class ParityWorld private constructor(val host: SessionHost, val realm: Realm, val session: ReplSession?) {
    private val locator = MiniLeafLocator { quoted, start -> getLeafLocations(quoted, start) }

    init {
        // vitest.setup.mjs resets `setDefaultJoin` (to `in`) after every test: a case must not see what the one before it chose
        Alignment.reset()
    }

    /** `evaluate` of @strudel/transpiler (test/runtime.mjs): the value of the last statement of [code], with no REPL semantics. */
    fun run(code: String): Any? {
        // widget types are registered by the website's editor only, so the oracle leaves `._scope()` and the like alone
        val transpiled = transpile(code, TranspileOptions(), emptySet(), locator)
        return realm.runProgram(transpiled.program, code)
    }

    companion object {
        /**
         * Strudel's modules keep state of their own that a program can change (which random number generator is in use, ...):
         * a case must not see what the one before it did.
         */
        private fun resetProcessState() {
            Rng.reset()
        }

        /** A world for `queryCode`: `cpm` divides by 1 (test/runtime.mjs has no REPL). */
        fun plain(): ParityWorld {
            resetProcessState()
            val host = SessionHost()
            val realm = Realm(host)
            Catalogue.install(realm)
            host.cps = 1.0
            applyMocks(realm)
            return ParityWorld(host, realm, null)
        }

        /** A world for a REPL case: the session installs the REPL's own `p`, `setcps` ... over the mocks on every run. */
        fun repl(): ParityWorld {
            resetProcessState()
            val session = ReplSession()
            applyMocks(session.realm)
            return ParityWorld(session.host, session.realm, session)
        }

        private fun applyMocks(realm: Realm) {
            val bridge = realm.bridge
            val proto = realm.patternPrototype
            for (name in IDENTITY_METHODS) proto.defineOwn(name, realm.function(name, 0) { self, _ -> self }, PropFlags.HIDDEN)
            for (name in IDENTITY_FUNCTIONS) realm.defineGlobalFunction(name, 1) { _, a -> a.getOrElse(0) { Undefined } }
            for (name in EMPTY_FUNCTIONS) realm.defineGlobalFunction(name, 0) { _, _ -> Undefined }

            realm.defineGlobalFunction("getDrawContext", 0) { _, _ ->
                val canvas = realm.newObject().also {
                    it.defineOwn("width", 100.0)
                    it.defineOwn("height", 100.0)
                }
                realm.newObject().also {
                    for (method in listOf("clearRect", "fillText", "fillRect")) it.defineOwn(method, realm.function(method, 0) { _, _ -> Undefined })
                    it.defineOwn("canvas", canvas)
                }
            }
            realm.defineGlobalFunction("getAudioContext", 0) { _, _ -> realm.newObject().also { it.defineOwn("currentTime", 1.0) } }
            // midin = () => (ccNum) => ref(() => 0); midikeys = async () => () => silence; sysex = ([id, data]) => {}
            realm.defineGlobalFunction("midin", 0) { _, _ ->
                realm.function("", 1) { _, _ -> bridge.wrap(refPattern { 0.0 }) }
            }
            realm.defineGlobalFunction("midikeys", 0) { _, _ -> realm.function("", 0) { _, _ -> bridge.wrap(silence) } }
            realm.defineGlobalFunction("sysex", 1) { _, _ -> Undefined }
            realm.defineGlobal("Clock", realm.newObject())

            realm.runRaw(PIANO_MJS)
        }
    }
}

/**
 * `evaluate(code)` then the pattern, as `queryCode` of test/runtime.mjs does it before it sorts and queries: the value of
 * the last statement must be a pattern.
 */
internal fun evaluateForQuery(code: String): Pattern {
    val value = ParityWorld.plain().run(code)
    return (value as? PatternValue)?.pattern ?: throw CosicError(
        if (value === Undefined || value == null) "Cannot read properties of ${if (value == null) "null" else "undefined"} (reading 'sortHapsByPart')"
        else "pattern.sortHapsByPart is not a function",
    )
}

/**
 * The programs of a REPL case evaluated one after the other in a REPL session (`repl().evaluate(code, false)` of the
 * oracle runner): the pattern, the mini locations and the error are those of the last evaluation that worked or failed,
 * the tempo is the session's. A failing program leaves the pattern the previous one made.
 */
internal fun runReplCase(case: ParityCase): ActualOutcome {
    val world = ParityWorld.repl()
    val session = checkNotNull(world.session)
    var pattern: Pattern? = null
    var locations: List<List<Int>> = emptyList()
    var error: String? = null
    for (code in case.codes) {
        try {
            val result = session.evaluate(code)
            pattern = result.pattern
            locations = result.miniLocations.map { listOf(it.start, it.end) }
            error = null
        } catch (e: CosicError) {
            error = e.message
        }
    }
    val haps = pattern?.let { queriedHaps(it, case) }.orEmpty()
    return ActualOutcome(haps, error, world.host.cps, locations)
}

/**
 * The haps of [pattern] over the cycles of [case], sorted by part and printed like `hap.show(true)`: what `queryCode` of
 * test/runtime.mjs returns. A failing query answers nothing, as `queryArc` does in Strudel (running out of stack or memory
 * is no Strudel behaviour, so that one is thrown).
 */
internal fun queriedHaps(pattern: Pattern, case: ParityCase): List<String> {
    val haps = try {
        reportingExhaustion { pattern.sortHapsByPart().queryArc(Rational.ZERO, Rational(case.cycles.toLong())) }
    } catch (e: CosicError) {
        if (e.message == TOO_DEEP || e.message == TOO_DENSE) throw e
        emptyList()
    }
    val shown = haps.map { it.show(true) }
    return if (case.roundFloats) shown.map { roundFloatStrings(it) } else shown
}
