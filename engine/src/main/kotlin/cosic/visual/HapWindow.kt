package cosic.visual

import cosic.pattern.Hap
import cosic.pattern.Pattern
import cosic.pattern.State

/*
 * The painters (`.punchcard()`, `.spiral()`, `.pitchwheel()`, `.wordfall()`): visuals that are not fed by a pattern of their own but see *every* hap of the program, as
 * `Pattern.prototype.onPaint` of packages/draw/draw.mjs makes them. The `Drawer` of that file keeps the haps around the playhead and calls every painter with them once per
 * frame; [HapWindow] is that drawer.
 */

/** What a painter draws: the kinds of `onPaint(painter)` the visuals of Strudel make. */
internal enum class PainterKind { PUNCHCARD, SPIRAL, PITCHWHEEL }

/**
 * One painter: what to draw ([kind] with its [options]) and [target], the key of the surface it draws on. `getPunchcardPainter(options)`, the painter of `spiral`
 * and that of `pitchwheel`, called as `painter(ctx, time, haps, drawTime)`.
 */
internal class Painter(val kind: PainterKind, val options: VisualOptions, val target: String) {
    /** [time] is the cycle at the playhead, [haps] the haps the drawer has, [lookbehind] and [lookahead] the time it looks back and ahead. */
    fun paint(ctx: PaintCanvas, time: Double, haps: List<Hap>, lookbehind: Double, lookahead: Double) {
        when (kind) {
            PainterKind.PUNCHCARD -> drawPianoroll(ctx, time, haps, drawOptionsOf(lookbehind, lookahead, options))
            PainterKind.SPIRAL -> drawSpiral(ctx, time, haps, lookbehind, options)
            PainterKind.PITCHWHEEL -> drawPitchwheel(ctx, haps.filter { it.isActive(time) }, options)
        }
    }
}

/**
 * `onPaint(painter)`: the pattern asks to be painted: when it is queried with a `painters` control (the drawer does that once, when it is set up), the painter is added
 * to the list. Queries without that control (the scheduler's) do not notice.
 */
internal fun Pattern.onPaint(painter: Painter): Pattern = withState { state: State ->
    @Suppress("UNCHECKED_CAST")
    (state.controls["painters"] as? MutableList<Any?>)?.add(painter)
    state
}

/** What a frame of the drawer shows: the [haps] it has and the cycle at the playhead, [time]. */
internal class DrawerFrame(val haps: List<Hap>, val time: Double)

/**
 * The `Drawer` of draw.mjs:136-204 without the animation frames: the haps of the program that are in view, kept up to date by [invalidate] (when the program
 * changes) and [frame] (every frame). [lookbehind] and [lookahead] are the cycles it looks back and ahead (`drawTime` of the REPL: `[-2, 2]`).
 */
internal class HapWindow(val lookbehind: Double = 2.0, val lookahead: Double = 2.0) {
    /** The haps in view: those that started and have not ended long ago, and those about to start. */
    var visibleHaps: List<Hap> = emptyList()
        private set

    /** The painters the program asked for: filled by [invalidate]. */
    var painters: List<Painter> = emptyList()
        private set

    var lastFrame: Double? = null
        private set

    /** The cycle at the playhead of the last [frame], and its haps, null before the first frame. */
    var current: DrawerFrame? = null
        private set

    /**
     * The program has changed (or begun): throws away the haps that have not started at [t], asks the pattern for those of the next [lookahead] cycles and collects the
     * painters it makes. The haps of the old pattern that have started stay (a program that is edited while it plays keeps its past in view); every hap of the new
     * one in the window is added, so a hap that straddles [t] can be there twice, as in Strudel.
     */
    fun invalidate(program: Pattern, t: Double, query: SafeQuery) {
        // +0.1: a workaround of Strudel's for weird holes in queries
        val begin = Math.max(t, 0.0)
        val end = t + lookahead + 0.1
        visibleHaps = visibleHaps.filter { h -> h.whole?.begin?.toDouble()?.let { it < t } == true }
        val found = ArrayList<Painter>()
        val future = query.arc(program, begin, end, mapOf("painters" to found))
        painters = found.distinct()
        visibleHaps = visibleHaps + future
    }

    /**
     * One frame with the playhead at [now]. The first frame only notes where the playhead is and answers null; every later one asks for the haps since the last
     * frame (at most a tenth of a cycle) and returns the haps in view with the time of the playhead.
     */
    fun frame(program: Pattern, now: Double, query: SafeQuery): DrawerFrame? {
        val phase = now + lookahead
        val before = lastFrame
        if (before == null) {
            lastFrame = phase
            return null
        }
        val haps = query.arc(program, Math.max(before, phase - 1.0 / 10), phase)
        lastFrame = phase
        val bound = phase - lookbehind - lookahead
        visibleHaps = visibleHaps.filter { h -> h.whole != null && h.endClipped.toDouble() >= bound } + haps.filter { it.hasOnset() }
        return DrawerFrame(visibleHaps, phase - lookahead).also { current = it }
    }

    /** Forgets everything but the painters: the playhead was moved back or the visuals were stopped. */
    fun clear() {
        visibleHaps = emptyList()
        lastFrame = null
        current = null
    }

    /** Takes over what [other] had in view and where it was (the program was evaluated again). */
    fun inherit(other: HapWindow) {
        visibleHaps = other.visibleHaps
        lastFrame = other.lastFrame
    }
}
