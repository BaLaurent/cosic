package com.octopusman.cosic.editor

import androidx.compose.ui.text.input.OffsetMapping

/**
 * Text that the editor shows but the code does not have: the empty rows a widget takes under its call, the gap a slider sits in. It is put in front of the character at [at]
 * (an offset of the code). [caretAfter] says on which side of the text a caret at [at] is drawn: after it (the slider's gap, so the caret at the number is drawn behind the
 * slider) or before it (the rows, so the caret at the end of a call is drawn at the end of that call's line). [key] is the id of what it is made for.
 */
internal class Insertion(val at: Int, val text: String, val caretAfter: Boolean, val key: String)

/**
 * The offset mapping between the code and the text the field shows, which has the [Insertion]s in it. Both directions are monotone, `transformedToOriginal(originalToTransformed(o))`
 * is `o`, and every offset given is clamped, so the text field (which throws on a value out of range) never gets a bad one. A lookup costs O(log k) for k insertions.
 *
 * Insertions are kept sorted by place, those a caret goes in front of before those it goes behind, then by key; empty ones are left out and a place past the end of the code
 * is clamped to the end.
 */
internal class InsertionMap private constructor(val originalLength: Int, private val items: List<Insertion>) {
    private val ats = IntArray(items.size) { items[it].at }
    private val after = BooleanArray(items.size) { items[it].caretAfter }
    private val prefix = IntArray(items.size + 1).also { for (i in items.indices) it[i + 1] = it[i] + items[i].text.length }
    private val byKey = HashMap<String, Int>().also { for (i in items.indices) it[items[i].key] = i }

    val size: Int get() = items.size

    /** The length of the shown text: the code and everything inserted. */
    val transformedLength: Int get() = originalLength + prefix[items.size]

    fun insertion(i: Int): Insertion = items[i]

    /** The index of the insertion made for [key], or -1. */
    fun indexOf(key: String): Int = byKey[key] ?: -1

    /** Where insertion [i] begins in the shown text. */
    fun transformedStart(i: Int): Int = ats[i] + prefix[i]

    /** Where insertion [i] ends in the shown text. */
    fun transformedEnd(i: Int): Int = transformedStart(i) + items[i].text.length

    /** The place in the shown text of the caret at [offset] of the code. */
    fun originalToTransformed(offset: Int): Int {
        val o = offset.coerceIn(0, originalLength)
        return o + prefix[firstWhere { ats[it] > o || (ats[it] == o && !after[it]) }]
    }

    /** The offset of the code a place in the shown text stands for: a place inside an inserted text is where that text was put in. */
    fun transformedToOriginal(offset: Int): Int {
        val t = offset.coerceIn(0, transformedLength)
        val k = firstWhere { transformedEnd(it) > t }
        return (if (k < size && transformedStart(k) < t) ats[k] else t - prefix[k]).coerceIn(0, originalLength)
    }

    /** Where a span of the code that begins at [offset] begins in the shown text: after every insertion made at or before it. */
    fun spanStart(offset: Int): Int {
        val o = offset.coerceIn(0, originalLength)
        return o + prefix[firstWhere { ats[it] > o }]
    }

    /** Where a span of the code that ends at [offset] ends in the shown text: before every insertion made at it. */
    fun spanEnd(offset: Int): Int {
        val o = offset.coerceIn(0, originalLength)
        return o + prefix[firstWhere { ats[it] >= o }]
    }

    /** The text to show for [original], which must be the code this map was made for. */
    fun apply(original: CharSequence): String {
        require(original.length == originalLength) { "the map is for $originalLength characters, not ${original.length}" }
        if (items.isEmpty()) return original.toString()
        val out = StringBuilder(transformedLength)
        var from = 0
        for (item in items) {
            out.append(original, from, item.at)
            out.append(item.text)
            from = item.at
        }
        return out.append(original, from, originalLength).toString()
    }

    /** This map as the field's offset mapping. */
    val offsetMapping: OffsetMapping = object : OffsetMapping {
        override fun originalToTransformed(offset: Int): Int = this@InsertionMap.originalToTransformed(offset)

        override fun transformedToOriginal(offset: Int): Int = this@InsertionMap.transformedToOriginal(offset)
    }

    private inline fun firstWhere(predicate: (Int) -> Boolean): Int {
        var lo = 0
        var hi = items.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (predicate(mid)) hi = mid else lo = mid + 1
        }
        return lo
    }

    companion object {
        fun of(originalLength: Int, insertions: List<Insertion>): InsertionMap = InsertionMap(
            originalLength,
            insertions.filter { it.text.isNotEmpty() }
                .map { Insertion(it.at.coerceIn(0, originalLength), it.text, it.caretAfter, it.key) }
                .sortedWith(compareBy<Insertion>({ it.at }, { !it.caretAfter }, { it.key })),
        )
    }
}
