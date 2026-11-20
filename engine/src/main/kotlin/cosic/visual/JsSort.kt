package cosic.visual

import java.text.Collator
import java.util.Locale

/**
 * `Array.prototype.sort(compare)` of V8, for the arrays a visual sorts (the rows of a pianoroll). For a consistent comparator any stable sort gives
 * the same order; the comparator of the pianoroll is not consistent for a mix of numbers and strings, and then the result depends on the algorithm, so
 * the algorithm of V8 is followed: below 64 elements TimSort is one run, made by [countAndMakeRun] and [binaryInsertionSort]; above it a stable
 * merge sort stands in for the merging of runs.
 */
internal fun <T> jsSort(items: MutableList<T>, compare: (T, T) -> Double) {
    val n = items.size
    if (n < 2) return
    val order = { a: T, b: T -> compare(a, b).let { if (it.isNaN()) 0.0 else it } }
    if (n >= 64) {
        val sorted = items.sortedWith { a, b -> order(a, b).let { if (it < 0) -1 else if (it > 0) 1 else 0 } }
        for (i in 0 until n) items[i] = sorted[i]
        return
    }
    val run = countAndMakeRun(items, n, order)
    if (run < n) binaryInsertionSort(items, run, n, order)
}

private fun <T> countAndMakeRun(a: MutableList<T>, high: Int, order: (T, T) -> Double): Int {
    if (high == 1) return 1
    var runLength = 2
    val descending = order(a[1], a[0]) < 0
    var previous = a[1]
    var idx = 2
    while (idx < high) {
        val current = a[idx]
        val o = order(current, previous)
        if (descending) {
            if (o >= 0) break
        } else if (o < 0) {
            break
        }
        previous = current
        runLength++
        idx++
    }
    if (descending) a.subList(0, runLength).reverse()
    return runLength
}

private fun <T> binaryInsertionSort(a: MutableList<T>, startArg: Int, high: Int, order: (T, T) -> Double) {
    var start = if (startArg == 0) 1 else startArg
    while (start < high) {
        var left = 0
        var right = start
        val pivot = a[right]
        while (left < right) {
            val mid = left + ((right - left) shr 1)
            if (order(pivot, a[mid]) < 0) right = mid else left = mid + 1
        }
        var p = start
        while (p > left) {
            a[p] = a[p - 1]
            p--
        }
        a[left] = pivot
        start++
    }
}

private val COLLATOR: Collator by lazy {
    Collator.getInstance(Locale.ROOT).apply { strength = Collator.TERTIARY }
}

/** `a.localeCompare(b)`: the root collation, which orders punctuation before digits before letters and lower case before upper case. */
internal fun localeCompare(a: String, b: String): Int = synchronized(COLLATOR) { COLLATOR.compare(a, b) }
