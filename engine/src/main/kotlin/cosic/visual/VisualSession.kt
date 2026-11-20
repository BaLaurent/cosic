package cosic.visual

import cosic.ProgramVisuals
import cosic.io.ScopeTaps
import cosic.pattern.Hap
import cosic.pattern.Pattern

/** The kinds of inline widget a call can make: the methods `x._pianoroll(...)` and the like, which the transpiler gives an id (`_widget__pianoroll_0`). */
enum class WidgetType(val method: String) {
    PIANOROLL("_pianoroll"),
    PUNCHCARD("_punchcard"),
    SPIRAL("_spiral"),
    SCOPE("_scope"),
    PITCHWHEEL("_pitchwheel"),
    SPECTRUM("_spectrum"),
    PRINT("_print"),
    ;

    companion object {
        fun ofMethod(method: String): WidgetType? = entries.firstOrNull { it.method == method }
    }
}

/**
 * A widget a program made, as the call of its method registered it: [id] is the one the transpiler gave the call (`_widget__pianoroll_0`; the same as
 * [cosic.WidgetConfig.widgetId]), [index] how many widgets of the same [type] came before it, [callEnd] where the call ends in the program (-1 when that is not known),
 * and [options] what the program passed (`{ width: 300 }`), before the defaults of the widget.
 */
data class WidgetSpec(val type: WidgetType, val id: String, val index: Int, val callEnd: Int, val options: Map<String, Any?>)

/**
 * Somewhere a visual draws: the background of the editor ([key] `bg`, [widget] null, size 0), or an inline widget ([key] its id). A widget has the size its
 * call asked for (defaults: 500x60 for a pianoroll, punchcard and scope; 275x275 for a spiral; 200x200 for a pitch wheel and a spectrum; 600x100 for a print), in
 * logical pixels. The canvas the app gives [VisualSession.paint] has the size of the surface; a background has the size of the editor.
 */
class VisualSurface internal constructor(val key: String, val widget: WidgetSpec?, val width: Double, val height: Double) {
    val isBackground: Boolean get() = widget == null

    override fun toString(): String = "VisualSurface($key ${width}x$height)"

    companion object {
        const val BACKGROUND_KEY = "bg"
    }
}

/**
 * The visuals of one program: the loops and painters its calls of `.pianoroll()`, `._scope()`, `.spiral()` and the like asked for. The program makes them while it is
 * evaluated (the calls register here), the engine tells the session which program it belongs to ([attach]), and the app then drives it, from one thread (the one
 * that draws):
 *
 *  - every frame call [tick] with the cycle that is being heard and whether the program plays; it advances every loop and painter and answers whether anything changed;
 *  - then [paint] every surface that is on the screen ([surfaces]; [versionOf] says whether one changed since it was last painted);
 *  - [printLines] holds the text of a `_print` widget;
 *  - [reset] is what stopping does (`cleanupDraw`): the background is cleared and everything starts over when [tick] is called with a playing program again;
 *  - a session that replaces another one can take over its past with [continueFrom], so that a pianoroll keeps what has already played when the program is evaluated
 *    again.
 *
 * Nothing here needs the main thread but [tick], [paint] and the others should be called from one thread. Registering happens before the session is handed out.
 */
class VisualSession internal constructor(private val log: (String) -> Unit = {}) : ProgramVisuals {
    private val lock = Any()
    private val query = SafeQuery(log)

    // ---- what the program asked for ---------------------------------------------------------------------------------------------------

    private val widgets = LinkedHashMap<String, WidgetSpec>()
    private val sizes = HashMap<String, DoubleArray>()
    private val loops = LinkedHashMap<String, DrawLoop>()
    private val printLinesOf = HashMap<String, PrintLines>()
    private var surfaceCache: List<VisualSurface>? = null
    private val backgroundSurface = VisualSurface(VisualSurface.BACKGROUND_KEY, null, 0.0, 0.0)
    private val surfaceByKey = HashMap<String, VisualSurface>()

    @Volatile
    private var program: Pattern? = null

    @Volatile
    private var scopes: ScopeTaps? = null

    /** Whether a call made a painter (`.punchcard()` and the like), which makes the session follow the haps of the whole program. */
    private var usesPainters = false
    private var usesBackground = false

    private val window = HapWindow()

    // ---- state of the frames ------------------------------------------------------------------------------------------------------------

    private var armed = false
    private var everFramed = false
    private var lastNow = Double.NEGATIVE_INFINITY
    private var clearBackground = false
    private val versions = HashMap<String, Long>()
    private var inheritedMemory: Map<String, List<Hap>>? = null
    private var inheritedWindow: HapWindow? = null

    /**
     * The surfaces the visuals draw on: the background first, then the widgets in the order their calls end in the program. A widget is matched with the call of the
     * program by [WidgetSpec.id].
     */
    val surfaces: List<VisualSurface>
        get() = synchronized(lock) {
            surfaceCache ?: buildList {
                add(backgroundSurface)
                for (spec in widgets.values.sortedBy { if (it.callEnd < 0) Int.MAX_VALUE else it.callEnd }) {
                    add(surfaceByKey.getOrPut(spec.id) { VisualSurface(spec.id, spec, sizes.getValue(spec.id)[0], sizes.getValue(spec.id)[1]) })
                }
            }.also { surfaceCache = it }
        }

    /** Whether any visual draws on the background of the editor, which needs a surface behind the text. */
    val hasBackground: Boolean get() = synchronized(lock) { usesBackground }

    /** The widgets the program made, in the order their calls end. */
    val widgetSpecs: List<WidgetSpec> get() = synchronized(lock) { surfaces.mapNotNull { it.widget } }

    // ---- registration (the calls of the program) ----------------------------------------------------------------------------------------

    internal fun registerWidget(spec: WidgetSpec, width: Double, height: Double) {
        synchronized(lock) {
            widgets[spec.id] = spec
            sizes[spec.id] = doubleArrayOf(width, height)
            surfaceByKey.remove(spec.id)
            surfaceCache = null
        }
    }

    /** `draw` of Strudel: a later loop with the same key replaces the earlier one, and the new one starts from what the old one had remembered. */
    internal fun addLoop(loop: DrawLoop) {
        synchronized(lock) {
            val old = loops.remove(loop.key)
            if (old != null) loop.memory = ArrayList(old.memory)
            loops[loop.key] = loop
            if (loop.target == VisualSurface.BACKGROUND_KEY) usesBackground = true
            armed = false
        }
    }

    /** A call made a painter on [target]: the program's haps are followed from now on. */
    internal fun addPainter(target: String) {
        synchronized(lock) {
            usesPainters = true
            if (target == VisualSurface.BACKGROUND_KEY) usesBackground = true
            armed = false
        }
    }

    internal fun addPrintLines(id: String, lines: PrintLines) {
        synchronized(lock) { printLinesOf[id] = lines }
    }

    internal fun scopeTaps(): ScopeTaps? = scopes

    // ---- the engine ---------------------------------------------------------------------------------------------------------------------

    /** The engine ([cosic.CosicEngine.evaluate]) says which program the visuals belong to and where the sound of the analysers is. */
    internal fun attach(program: Pattern, scopes: ScopeTaps) {
        synchronized(lock) {
            this.program = program
            this.scopes = scopes
            armed = false
        }
    }

    // ---- the app ------------------------------------------------------------------------------------------------------------------------

    /**
     * One frame: [nowCycle] is the cycle that is being heard (`cycleAt(playheadFrame)` of the engine's snapshot), [playing] whether the program plays. While it plays
     * everything advances; before it has ever played one frame is drawn at cycle 0, so that the widgets show something; after that, a program that does not play
     * stands still. Returns whether any surface needs painting.
     */
    fun tick(nowCycle: Double, playing: Boolean): Boolean = synchronized(lock) {
        if (nowCycle.isNaN() || nowCycle.isInfinite()) return false
        if (armed && nowCycle + REWIND_TOLERANCE < lastNow) armed = false // the playhead went back: start over from here
        if (!playing) {
            if (everFramed) return false
            val t = Math.max(nowCycle, 0.0)
            begin(t)
            stepLoops(t)
            everFramed = true
            lastNow = t
            return true
        }
        lastNow = nowCycle
        if (!armed) begin(nowCycle)
        stepLoops(nowCycle)
        stepPainters(nowCycle)
        everFramed = true
        true
    }

    private fun begin(now: Double) {
        val inherited = inheritedMemory
        if (inherited != null) {
            for (loop in loops.values) inherited[loop.key]?.let { loop.memory = ArrayList(it) }
            inheritedMemory = null
        }
        for (loop in loops.values) loop.begin(now, query)
        val p = program
        if (usesPainters && p != null) {
            inheritedWindow?.let { window.inherit(it) }
            inheritedWindow = null
            window.invalidate(p, now, query)
        }
        armed = true
    }

    private fun stepLoops(now: Double) {
        for (loop in loops.values) {
            guarded { loop.advance(now, query) }
            bump(loop.target)
        }
    }

    private fun stepPainters(now: Double) {
        val p = program
        if (!usesPainters || p == null) return
        if (window.frame(p, now, query) == null) return
        for (painter in window.painters) bump(painter.target)
    }

    private val reportedFailures = HashSet<String>()

    /** Runs [block], which draws or follows a visual: a visual that fails is reported once and skipped, it must not take the thread that draws down. */
    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            val message = e.message ?: e.toString()
            if (reportedFailures.add(message)) log("[draw] error: $message")
        }
    }

    private fun bump(key: String) {
        versions[key] = (versions[key] ?: 0L) + 1
    }

    /** How often [surface] has changed: a surface needs painting when this is not what it was when it was last painted. */
    fun versionOf(surface: VisualSurface): Long = synchronized(lock) { versions[surface.key] ?: 0L }

    /** Draws what [surface] shows now on [canvas]. Safe to call at any time and as often as needed; the state of the visuals only moves with [tick]. */
    fun paint(surface: VisualSurface, canvas: PaintCanvas) {
        synchronized(lock) {
            if (surface.isBackground && clearBackground) {
                canvas.clearRect(0.0, 0.0, canvas.width, canvas.height)
                clearBackground = false
                return
            }
            for (loop in loops.values) if (loop.target == surface.key) guarded { loop.render(canvas) }
            val frame = window.current ?: return
            for (painter in window.painters) {
                if (painter.target == surface.key) guarded { painter.paint(canvas, frame.time, frame.haps, window.lookbehind, window.lookahead) }
            }
        }
    }

    /** The haps the loop with [key] remembers now (`memory[id]` of Strudel), for tests and tools; empty when there is no such loop. */
    internal fun loopMemory(key: String): List<Hap> = synchronized(lock) { loops[key]?.memory?.toList() ?: emptyList() }

    /** The lines of the `_print` widget [widgetId]: empty when there is no such widget. */
    fun printLines(widgetId: String): List<String> = synchronized(lock) { printLinesOf[widgetId] }?.snapshot ?: emptyList()

    /** What stopping the program does (`cleanupDraw`): the background is cleared at the next [paint], and everything begins again when [tick] is called with a playing program. */
    fun reset() {
        synchronized(lock) {
            for (loop in loops.values) loop.clear()
            window.clear()
            armed = false
            clearBackground = true
            bump(VisualSurface.BACKGROUND_KEY)
        }
    }

    /**
     * Takes over what [previous] (the session of the program that was evaluated before) had in view: the haps its loops remembered and those its drawer held, so the past
     * of a pianoroll does not vanish when the program is evaluated again, as in Strudel. Call it before the first [tick].
     */
    fun continueFrom(previous: VisualSession?) {
        if (previous == null || previous === this) return
        val memory: Map<String, List<Hap>>
        val held: HapWindow
        synchronized(previous.lock) {
            memory = previous.loops.mapValues { it.value.memory.toList() }
            held = HapWindow().also { it.inherit(previous.window) }
        }
        synchronized(lock) {
            inheritedMemory = memory
            inheritedWindow = held
        }
    }

    private companion object {
        /** How far back [tick] may go before the session takes it for a restart. */
        const val REWIND_TOLERANCE = 0.25
    }
}
