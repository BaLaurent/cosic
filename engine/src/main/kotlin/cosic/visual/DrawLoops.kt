package cosic.visual

import cosic.io.ScopeTaps
import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.pattern.reportingExhaustion
import cosic.pattern.withQueryBudget

/*
 * What `Pattern.prototype.draw` of packages/draw/draw.mjs makes of a pattern: a loop that, every frame, remembers the haps that have started (`memory[id]`) and hands
 * them to a function that draws them. Here a loop is data: [DrawLoop.advance] does what a frame does to the memory, [DrawLoop.render] what the drawing function
 * does with the canvas it is given, so the app can run the first on every frame and the second wherever the pixels are.
 */

/** Queries patterns on behalf of the visuals, off the thread that evaluated them: under the budget of a query, with a failure reported once instead of thrown. */
internal class SafeQuery(private val report: (String) -> Unit) {
    private val reported = HashSet<String>()

    /** `pattern.queryArc(begin, end, controls)`; answers nothing, and reports why once, when the query fails. */
    fun arc(pattern: Pattern, begin: Double, end: Double, controls: Map<String, Any?> = emptyMap()): List<Hap> = try {
        reportingExhaustion { withQueryBudget { pattern.queryArc(Rational.of(begin), Rational.of(end), controls) } }
    } catch (e: Exception) {
        // whatever a pattern or the program behind it throws, a visual must not take the thread that draws it down
        val message = e.message ?: e.toString()
        if (reported.add(message)) report("[draw] error: $message")
        emptyList()
    }
}

internal abstract class DrawLoop(
    /** The key of the loop: a new loop with the same key replaces it (`stopAnimationFrame(id)`), and inherits its memory. */
    val key: String,
    /** The key of the surface the loop draws on: `bg` for the background of the editor, else the id of a widget. */
    val target: String,
    /** The pattern the loop asks for haps (`this` of `draw`). */
    val receiver: Pattern,
    lookbehind: Double,
    val lookahead: Double,
) {
    val lookbehind: Double = Math.abs(lookbehind)

    /** The haps that have started and are not too long over (`memory[id]`). */
    var memory: MutableList<Hap> = ArrayList()

    private var last: Double? = null

    /** `_t` of the last frame: the cycle at the playhead. */
    var time: Double = 0.0
        private set

    /** Whether a frame has run, which [render] needs. */
    var framed: Boolean = false
        private set

    /**
     * What `draw` does when it is called: it keeps the haps of the memory that have already started, and adds those that start in the next [lookahead]
     * cycles. [now] is the cycle of the playhead.
     */
    fun begin(now: Double, query: SafeQuery) {
        val t0 = Math.max(now, 0.0)
        memory = memory.filterTo(ArrayList()) { !it.isInFuture(t0) }
        memory.addAll(query.arc(receiver, t0, t0 + lookahead).filter { it.hasOnset() })
        last = null
        framed = false
    }

    /** One frame (the `animate` function of `draw`): [now] is the cycle of the playhead. */
    fun advance(now: Double, query: SafeQuery) {
        val t = now + lookahead
        // haps that are too far in the past go
        memory = memory.filterTo(ArrayList()) { it.isInNearPast(lookbehind, now) }
        // begin where the last frame left off, but at most a tenth of a cycle back
        val previous = last.let { if (it == null || it == 0.0 || it.isNaN()) t else it }
        val begin = Math.max(previous, t - 1.0 / 10)
        memory.addAll(query.arc(receiver, begin, t).filter { it.hasOnset() })
        last = t
        time = now
        framed = true
        onFrame()
    }

    /** Called after every [advance]: what the loop does with the haps besides drawing them (reading an analyser, printing). */
    protected open fun onFrame() {}

    /** Draws the state of the last frame on [ctx]. Does nothing before the first frame. */
    abstract fun render(ctx: PaintCanvas)

    /** Forgets the haps and where the loop was: what `cleanupDraw` and a restart do. */
    fun clear() {
        memory = ArrayList()
        last = null
        framed = false
    }

    /** The colour of the first hap of the memory, the one `scope` and `spectrum` draw in (`haps[0]?.value?.color`). */
    protected fun firstColor(): Any? = memory.firstOrNull()?.let { propOf(it.value, "color") }
}

/** `.pianoroll(options)`: a pianoroll that follows the playhead on its own, which shows [from] cycles back and [to] cycles ahead of it. */
internal class PianorollLoop(key: String, target: String, receiver: Pattern, private val options: VisualOptions) :
    DrawLoop(key, target, receiver, lookbehindOf(options), lookaheadOf(options)) {
    private val cycles = options.num("cycles", 4.0)
    private val playhead = options.num("playhead", 0.5)
    private val hideNegative = options.flag("hideNegative", false)
    private val from = -cycles * playhead
    private val to = cycles * (1 - playhead)

    override fun render(ctx: PaintCanvas) {
        if (!framed) return
        val t = time
        val inFrame = { hap: Hap ->
            (!hideNegative || (hap.whole?.begin?.toDouble() ?: throw CosicError("Cannot read properties of undefined (reading 'begin')")) >= 0) &&
                hap.isWithinTime(t + from, t + to)
        }
        drawPianoroll(ctx, t, memory.filter(inFrame), options)
    }

    private companion object {
        fun lookbehindOf(o: VisualOptions): Double = -o.num("cycles", 4.0) * o.num("playhead", 0.5) - o.num("overscan", 0.0)

        fun lookaheadOf(o: VisualOptions): Double = o.num("cycles", 4.0) * (1 - o.num("playhead", 0.5)) + o.num("overscan", 0.0)
    }
}

/** Where an analyser loop gets its data: the sound of the voices with its `analyze` id, as an analyser of [fftSize] samples would hand it out. */
internal fun interface AnalyserFeed {
    /** The waveform ([frequency] false: the oldest half of the latest [fftSize] samples) or the decibels of each bin, or null while no sound with the id has played. */
    fun read(fftSize: Int, frequency: Boolean): FloatArray?

    /** The `fft` of a hap that sets none: `getDefaultValue('fft')`, 8 unless a program changed it. NaN (a default that is no number) is the sizes' own 8. */
    val defaultFft: Double get() = 8.0
}

/**
 * The [AnalyserFeed] of the sound in the [ScopeTaps] of the engine under [id]: one [Analyser] per size (its smoothing is state of its own). [fftDefault] is asked
 * for the default `fft` when the haps set none.
 */
internal class TapFeed(private val id: String, private val fftDefault: () -> Double = { 8.0 }, private val taps: () -> ScopeTaps?) : AnalyserFeed {
    private var analyser: Analyser? = null

    override val defaultFft: Double get() = fftDefault()

    override fun read(fftSize: Int, frequency: Boolean): FloatArray? {
        val source = taps() ?: return null
        val node = analyser?.takeIf { it.fftSize == fftSize } ?: Analyser(fftSize).also { analyser = it }
        val out = FloatArray(node.frequencyBinCount)
        val have = if (frequency) node.frequency(source, id, out) else node.timeDomain(source, id, out)
        return if (have) out else null
    }
}

/**
 * The data of an analyser loop for the haps in [memory]: the analyser follows the `fft` of the hap that sounded last (superdough resizes the node for every hap), and the
 * default one of the feed ([AnalyserFeed.defaultFft]) when no hap in memory sets one.
 */
private fun readFeed(feed: AnalyserFeed, memory: List<Hap>, frequency: Boolean): FloatArray? {
    var fft = feed.defaultFft
    for (i in memory.indices.reversed()) {
        val f = propOf(memory[i].value, "fft")
        if (f is Double) {
            fft = f
            break
        }
    }
    return feed.read(Analyser.sizeOf(fft), frequency)
}

/**
 * `.scope()`, `.tscope()` (the waveform) and `.fscope()` (the spectrum bars): they draw the sound of the voices with the analyser id of the `analyze` control that is on
 * every hap of the pattern, which [feed] reads.
 */
internal class ScopeLoop(
    key: String,
    target: String,
    receiver: Pattern,
    private val options: VisualOptions,
    private val frequency: Boolean,
    private val feed: AnalyserFeed,
) : DrawLoop(key, target, receiver, 0.0, 0.0) {
    private var data: FloatArray? = null
    private var color: String = VisualTheme.foreground

    override fun onFrame() {
        if (!frequency) color = jsToString(firstColor().let { if (jsTruthy(it)) it else VisualTheme.foreground })
        data = readFeed(feed, memory, frequency)
    }

    override fun render(ctx: PaintCanvas) {
        if (!framed) return
        clearScreen(ctx, options.raw("smear"))
        if (frequency) {
            data?.let { drawFrequencyScope(ctx, it, options) }
        } else {
            // scope and tscope draw in the colour of the first hap, whatever the program asked for (`config.color` is set before every frame)
            drawTimeScope(ctx, data, options.plus(mapOf("color" to color)))
        }
    }
}

/** `.spectrum()`: the spectrum as a picture that scrolls. */
internal class SpectrumLoop(key: String, target: String, receiver: Pattern, private val options: VisualOptions, private val feed: AnalyserFeed) :
    DrawLoop(key, target, receiver, 0.0, 0.0) {
    private var data: FloatArray? = null
    private var latestColor: String? = null
    private var color: String = VisualTheme.foreground
    private var frameCount = 0L
    private var renderedFrame = -1L

    override fun onFrame() {
        val own = firstColor()
        color = if (jsTruthy(own)) jsToString(own) else latestColor ?: VisualTheme.foreground
        latestColor = color
        data = readFeed(feed, memory, true)
        frameCount++
    }

    /** The picture scrolls once per frame however often it is painted, so a repaint without a new frame leaves it alone. */
    override fun render(ctx: PaintCanvas) {
        if (!framed || renderedFrame == frameCount) return
        renderedFrame = frameCount
        drawSpectrum(ctx, data, options, color)
    }
}

/** `.print()` as a widget: the haps that start are written, one line each, to [lines]. */
internal class PrintLoop(key: String, target: String, receiver: Pattern, private val lines: PrintLines) : DrawLoop(key, target, receiver, 0.0, 0.0) {
    override fun onFrame() {
        lines.append(memory)
    }

    override fun render(ctx: PaintCanvas) {}
}
