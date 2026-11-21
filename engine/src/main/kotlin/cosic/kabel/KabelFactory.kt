package cosic.kabel

import cosic.SoundLibrary
import cosic.audio.sources.SourceControls
import cosic.audio.sources.SourceRng
import cosic.audio.sources.frequencyFromValue
import cosic.audio.sources.asSourceControls
import cosic.pattern.CosicError
import cosic.pattern.logger
import cosic.render.emptyLibrary
import cosic.script.LogLevel
import cosic.script.ScriptHost
import java.util.concurrent.atomic.AtomicLong

/** Programs kept after they are compiled, by their text: a pattern that repeats makes the same programs again. */
private const val CACHE_SIZE = 256

/** The longest program text that is compiled. */
private const val MAX_SOURCE_CHARS = 64 * 1024

/** The host of the kabel realm: what a program writes to the console goes to the log of the pattern kernel. */
private class KabelHost : ScriptHost {
    override var cps: Double = 0.5
    override val library: SoundLibrary by lazy { emptyLibrary() }

    override fun log(message: String, level: LogLevel) {
        logger(
            "[kabelsalat] $message",
            when (level) {
                LogLevel.ERROR -> "error"
                LogLevel.WARNING -> "warning"
                LogLevel.DEBUG -> "debug"
                LogLevel.INFO -> "info"
            },
            null,
        )
    }

    override fun nowSeconds(): Double = System.nanoTime() / 1e9

    override fun runLater(delayMillis: Long, task: () -> Unit): () -> Unit = {}

    override fun sliderGet(id: String): Double? = null

    override fun sliderSet(id: String, value: Double) {}
}

/** A generator for the noise ugens: the same seeds give the same noise on every run, a different noise to every voice. */
private fun seededRandoms(): () -> KabelRandom {
    val counter = AtomicLong()
    return {
        val rng = SourceRng(counter.incrementAndGet() * -0x61c8864680b583ebL)
        KabelRandom { rng.nextDouble() }
    }
}

/**
 * Makes the kabelsalat part of an event: the [KabelInstance] that runs the program in the event's `workletSrc` (a `K(...)` or `worklet(...)` pattern).
 * Compiling is what superdough does on the main thread for every event: the text of the program has its patterns and `sFreq`/`sGate` filled in
 * ([substituteWorklet]), is evaluated in the [KabelRealm] and compiled. The result is cached by that text; an instance is made per event.
 *
 * Compile errors are [CosicError]s (the message of the JavaScript error, as Strudel logs it: the event is dropped), and are remembered, so that a program that
 * fails is not run again for every event. A program that loads but whose worklet would not (see [KabelProgram.loadFault]) makes a silent instance and
 * logs the failure once.
 *
 * Any thread may call [create]: lookups are cheap, a compile holds a lock (the realm is single-threaded), and instances draw from the [runtime] under its own.
 */
internal class KabelFactory(
    val sampleRate: Int,
    private val host: ScriptHost = KabelHost(),
    private val runtime: KabelRuntime = KabelRuntime(),
    private val newRandom: () -> KabelRandom = seededRandoms(),
) {
    private val cacheLock = Any()
    private val cache = object : LinkedHashMap<String, KabelProgram>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, KabelProgram>?): Boolean = size > CACHE_SIZE
    }
    private val failures = HashMap<String, String>()
    private val loggedFaults = HashSet<String>()
    private val compileLock = Any()
    private var compiler: KabelCompiler? = null

    /** How many programs have been compiled (a cache hit is not one); tests read it. */
    @Volatile
    var compiled = 0
        private set

    /**
     * The program that runs the worklet of one effect chain, compiled (or found compiled): [workletSrc] and [workletInputs] are the controls of the chain; the
     * frequency `sFreq` stands for is always that of [main], the event itself. Null when the chain has no worklet. Throws [CosicError] when it does not compile.
     */
    private fun programFor(workletSrc: Any?, workletInputs: Any?, main: SourceControls): KabelProgram? {
        if (workletSrc !is String) return null
        val inputs = workletInputs as? List<*> ?: emptyList<Any?>()
        val program = program(substituteWorklet(workletSrc, inputs, frequencyFromValue(main)))
        program.loadFault?.let { fault ->
            val message = "error: $fault"
            val first = synchronized(loggedFaults) { loggedFaults.add(message) }
            if (first) logger("[kabelsalat] $message", "error", null)
        }
        return program
    }

    /**
     * The instance that runs the worklet of one effect chain (see [programFor] for the arguments). With [dry] the instance draws from a runtime of its own, so that
     * looking ahead does not use up the noise channels the real events get.
     */
    fun create(workletSrc: Any?, workletInputs: Any?, main: SourceControls, dry: Boolean = false): KabelInstance? {
        val program = programFor(workletSrc, workletInputs, main) ?: return null
        val random = newRandom()
        return if (dry) KabelInstance(program, sampleRate, KabelRuntime(), random) else synchronized(runtime) { KabelInstance(program, sampleRate, runtime, random) }
    }

    /**
     * Compiles whatever worklets the control map [controls] holds (the event's own and those of its `FX(...)` elements), so that playing it finds them compiled and a
     * program that does not compile is found now (a [CosicError]). Makes no instances.
     */
    fun warm(controls: Map<String, Any?>) {
        val main = controls.asSourceControls()
        for (element in (controls["FX"] as? List<*>).orEmpty()) {
            if (element is Map<*, *>) programFor(element["workletSrc"], element["workletInputs"], main)
        }
        programFor(controls["workletSrc"], controls["workletInputs"], main)
    }

    /** Forgets the realm (what programs registered in it) and every compiled program, as when another project is opened. */
    fun reset() {
        synchronized(compileLock) {
            compiler = null
            synchronized(cacheLock) {
                cache.clear()
                failures.clear()
            }
        }
    }

    /** The compiled program of [text], compiling it when it has not been. Throws [CosicError]. */
    fun program(text: String): KabelProgram {
        synchronized(cacheLock) {
            cache[text]?.let { return it }
            failures[text]?.let { throw CosicError(it) }
        }
        synchronized(compileLock) {
            synchronized(cacheLock) {
                cache[text]?.let { return it }
                failures[text]?.let { throw CosicError(it) }
            }
            try {
                if (text.length > MAX_SOURCE_CHARS) throw KabelError("the program is longer than $MAX_SOURCE_CHARS characters")
                val c = compiler ?: KabelCompiler(KabelRealm(host), sampleRate).also { compiler = it }
                val program = c.compile(text)
                compiled++
                synchronized(cacheLock) { cache[text] = program }
                return program
            } catch (e: CosicError) {
                throw remember(text, e.message)
            } catch (e: KabelError) {
                throw remember(text, e.message ?: "error")
            } catch (e: StackOverflowError) {
                throw remember(text, "Maximum call stack size exceeded")
            }
        }
    }

    private fun remember(text: String, message: String): CosicError {
        synchronized(cacheLock) { failures[text] = message }
        if (failures.size > CACHE_SIZE) synchronized(cacheLock) { failures.clear() }
        return CosicError(message)
    }
}
