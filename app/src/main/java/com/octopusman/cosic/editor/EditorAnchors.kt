package com.octopusman.cosic.editor

import cosic.visual.WidgetType

/** Widgets whose picture keeps its shape when there is less room than the call asked for (the others keep their height and take the width there is). */
internal val WidgetType.keepsAspect: Boolean
    get() = this == WidgetType.SPIRAL || this == WidgetType.PITCHWHEEL || this == WidgetType.SPECTRUM

/**
 * An inline widget as the evaluated program has it, in the coordinates of the text that was evaluated: [callEnd] is where the call of its method (`x._pianoroll()`) ends, and
 * [widthDp] x [heightDp] the size its call asked for.
 */
internal data class BlockSeed(val id: String, val type: WidgetType, val callEnd: Int, val widthDp: Float, val heightDp: Float)

/** A slider of the evaluated program: where the number literal of its first argument starts, its bounds and step, and the number written there. */
internal data class SliderSeed(val id: String, val argStart: Int, val min: Double, val max: Double, val step: Double?, val initial: Double?)

/** A widget's place in the text being edited: [offset] is where its call ends now. */
internal data class BlockAnchor(val seed: BlockSeed, val offset: Int) {
    val id: String get() = seed.id
}

/** A slider's place in the text being edited: [offset] is where its number starts now. */
internal data class SliderAnchor(val seed: SliderSeed, val offset: Int) {
    val id: String get() = seed.id
}

/**
 * Where the widgets and sliders of the program are in the text as it is edited, sorted by place. Every [TextEdit] moves them as CodeMirror's `mapPos` does for the widgets of
 * Strudel's editor:
 *  - a widget's anchor is dropped when the character in front of it is removed or replaced (`start < p <= oldEnd`), and text inserted exactly at it goes after the anchor (below the widget);
 *  - a slider's anchor is dropped only when the edit begins before it and reaches past it (`start < q < oldEnd`), and text inserted at it goes after the anchor too.
 */
internal class EditorAnchors private constructor(val blocks: List<BlockAnchor>, val sliders: List<SliderAnchor>) {
    fun after(e: TextEdit): EditorAnchors {
        val movedBlocks = blocks.mapNotNull { a -> mapBlock(a.offset, e)?.let { if (it == a.offset) a else a.copy(offset = it) } }
        val movedSliders = sliders.mapNotNull { a -> mapSlider(a.offset, e)?.let { if (it == a.offset) a else a.copy(offset = it) } }
        return if (movedBlocks == blocks && movedSliders == sliders) this else EditorAnchors(movedBlocks, movedSliders)
    }

    fun slider(id: String): SliderAnchor? = sliders.firstOrNull { it.id == id }

    fun block(id: String): BlockAnchor? = blocks.firstOrNull { it.id == id }

    val isEmpty: Boolean get() = blocks.isEmpty() && sliders.isEmpty()

    companion object {
        val EMPTY = EditorAnchors(emptyList(), emptyList())

        fun of(blocks: List<BlockSeed>, sliders: List<SliderSeed>) = EditorAnchors(
            blocks.sortedBy { it.callEnd }.map { BlockAnchor(it, it.callEnd) },
            sliders.sortedBy { it.argStart }.map { SliderAnchor(it, it.argStart) },
        )

        private fun mapBlock(p: Int, e: TextEdit): Int? = when {
            p <= e.start -> p
            p > e.oldEnd -> p + e.delta
            else -> null
        }

        private fun mapSlider(q: Int, e: TextEdit): Int? = when {
            q <= e.start -> q
            q >= e.oldEnd -> q + e.delta
            else -> null
        }
    }
}

/**
 * The life of the anchors. Edits between evaluations only move them, so the widgets stay where Strudel's editor leaves them. A program that evaluates replaces them all with
 * fresh ones, taken from the program and moved by the edits made while it was being evaluated; a program that fails, or an evaluation that is cancelled, changes nothing.
 */
internal class AnchorTracker {
    var anchors: EditorAnchors = EditorAnchors.EMPTY
        private set
    private val log = ArrayList<TextEdit>()
    private var logging = false

    /** The text was edited ([edit] is null when it was not). */
    fun onEdit(edit: TextEdit?) {
        if (edit == null) return
        anchors = anchors.after(edit)
        if (logging) log += edit
    }

    /** An evaluation of the current text begins: its program is made for the text as it is now. */
    fun evaluationStarted() {
        log.clear()
        logging = true
    }

    fun evaluationCancelled() {
        log.clear()
        logging = false
    }

    /** The evaluation that [evaluationStarted] began succeeded: [blocks] and [sliders] are what its program says, in the coordinates of the text that was evaluated. */
    fun evaluationFinished(blocks: List<BlockSeed>, sliders: List<SliderSeed>) {
        var fresh = EditorAnchors.of(blocks, sliders)
        for (e in log) fresh = fresh.after(e)
        log.clear()
        logging = false
        anchors = fresh
    }
}
