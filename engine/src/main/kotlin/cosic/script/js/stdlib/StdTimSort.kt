package cosic.script.js.stdlib

/*
 * `Array.prototype.sort` as V8 does it: a TimSort (third_party/v8/builtins/array-sort.tq, itself a port of CPython's
 * listsort), ported with the same order of comparisons. For a consistent comparison any stable sort gives the same array;
 * for one that contradicts itself (`(a, b) => a > b`, `() => 1`, `() => Math.random() - 0.5`) the result, and the calls the
 * comparison function sees, depend on the algorithm, and a program written in a browser gets V8's.
 *
 * [compare] is called as `compare(x, y)` and answers a negative number, zero or a positive number like the function given to
 * `sort`; only its sign is looked at. The elements are in [a], which is sorted in place.
 */
internal class TimSort(private val a: Array<Any?>, private val compare: (Any?, Any?) -> Int) {
    private var minGallop = MIN_GALLOP_WINS
    private val pendingBase = IntArray(MAX_MERGE_PENDING)
    private val pendingLength = IntArray(MAX_MERGE_PENDING)
    private var pendingSize = 0
    private var temp: Array<Any?> = arrayOfNulls(0)

    /** V8 sorts up to [SMALL_ARRAY] elements by binary insertion alone, without looking for runs (checked call by call against Node 25). */
    fun sort() {
        val length = a.size
        if (length < 2) return
        if (length <= SMALL_ARRAY) {
            binaryInsertionSort(0, 0, length)
            return
        }
        var low = 0
        var remaining = length
        val minRunLength = computeMinRunLength(remaining)
        while (remaining != 0) {
            var currentRunLength = countAndMakeRun(low, low + remaining)
            if (currentRunLength < minRunLength) {
                val forcedRunLength = minOf(minRunLength, remaining)
                binaryInsertionSort(low, low + currentRunLength, low + forcedRunLength)
                currentRunLength = forcedRunLength
            }
            pendingBase[pendingSize] = low
            pendingLength[pendingSize] = currentRunLength
            pendingSize++
            mergeCollapse()
            low += currentRunLength
            remaining -= currentRunLength
        }
        mergeForceCollapse()
    }

    private fun computeMinRunLength(nArg: Int): Int {
        var n = nArg
        var r = 0
        while (n >= 64) {
            r = r or (n and 1)
            n = n shr 1
        }
        return n + r
    }

    /** The length of the run (ascending, or strictly descending and then reversed) that starts at [lowArg]. */
    private fun countAndMakeRun(lowArg: Int, high: Int): Int {
        val low = lowArg + 1
        if (low == high) return 1
        var runLength = 2
        val elementLow = a[low]
        val elementHigh = a[low - 1]
        var order = compare(elementLow, elementHigh)
        val isDescending = order < 0
        var previous = elementLow
        var idx = low + 1
        while (idx < high) {
            val current = a[idx]
            order = compare(current, previous)
            if (isDescending) {
                if (order >= 0) break
            } else {
                if (order < 0) break
            }
            previous = current
            runLength++
            idx++
        }
        if (isDescending) reverseRange(lowArg, lowArg + runLength)
        return runLength
    }

    private fun reverseRange(from: Int, to: Int) {
        var lo = from
        var hi = to - 1
        while (lo < hi) {
            val t = a[lo]
            a[lo] = a[hi]
            a[hi] = t
            lo++
            hi--
        }
    }

    private fun binaryInsertionSort(low: Int, startArg: Int, high: Int) {
        var start = if (low == startArg) startArg + 1 else startArg
        while (start < high) {
            var left = low
            var right = start
            val pivot = a[right]
            while (left < right) {
                val mid = left + ((right - left) shr 1)
                if (compare(pivot, a[mid]) < 0) right = mid else left = mid + 1
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

    // ---- the stack of runs ----------------------------------------------------------------------------------------------

    private fun runInvariantEstablished(n: Int): Boolean {
        if (n < 2) return true
        return pendingLength[n - 2] > pendingLength[n - 1] + pendingLength[n]
    }

    private fun mergeCollapse() {
        while (pendingSize > 1) {
            var n = pendingSize - 2
            if (!runInvariantEstablished(n + 1) || !runInvariantEstablished(n)) {
                if (pendingLength[n - 1] < pendingLength[n + 1]) n--
                mergeAt(n)
            } else if (pendingLength[n] <= pendingLength[n + 1]) {
                mergeAt(n)
            } else {
                break
            }
        }
    }

    private fun mergeForceCollapse() {
        while (pendingSize > 1) {
            var n = pendingSize - 2
            if (n > 0 && pendingLength[n - 1] < pendingLength[n + 1]) n--
            mergeAt(n)
        }
    }

    private fun mergeAt(i: Int) {
        val stackSize = pendingSize
        var baseA = pendingBase[i]
        var lengthA = pendingLength[i]
        val baseB = pendingBase[i + 1]
        var lengthB = pendingLength[i + 1]
        pendingLength[i] = lengthA + lengthB
        if (i == stackSize - 3) {
            pendingBase[i + 1] = pendingBase[i + 2]
            pendingLength[i + 1] = pendingLength[i + 2]
        }
        pendingSize = stackSize - 1

        // Where does b start in a? Elements of a before that are already in place.
        val k = gallopRight(a, a[baseB], baseA, lengthA, 0)
        baseA += k
        lengthA -= k
        if (lengthA == 0) return

        // Where does a end in b? Elements of b after that are already in place.
        lengthB = gallopLeft(a, a[baseA + lengthA - 1], baseB, lengthB, lengthB - 1)
        if (lengthB == 0) return

        if (lengthA <= lengthB) mergeLow(baseA, lengthA, baseB, lengthB) else mergeHigh(baseA, lengthA, baseB, lengthB)
    }

    // ---- galloping --------------------------------------------------------------------------------------------------------

    /** The k in 0..length with `array[base + k - 1] < key <= array[base + k]`: the position left of the leftmost element equal to [key]. */
    private fun gallopLeft(array: Array<Any?>, key: Any?, base: Int, length: Int, hint: Int): Int {
        var lastOfs = 0
        var offset = 1
        var order = compare(array[base + hint], key)
        if (order < 0) {
            val maxOfs = length - hint
            while (offset < maxOfs) {
                order = compare(array[base + hint + offset], key)
                if (order >= 0) break
                lastOfs = offset
                offset = (offset shl 1) + 1
                if (offset <= 0) offset = maxOfs
            }
            if (offset > maxOfs) offset = maxOfs
            lastOfs += hint
            offset += hint
        } else {
            val maxOfs = hint + 1
            while (offset < maxOfs) {
                order = compare(array[base + hint - offset], key)
                if (order < 0) break
                lastOfs = offset
                offset = (offset shl 1) + 1
                if (offset <= 0) offset = maxOfs
            }
            if (offset > maxOfs) offset = maxOfs
            val t = lastOfs
            lastOfs = hint - offset
            offset = hint - t
        }
        lastOfs++
        while (lastOfs < offset) {
            val m = lastOfs + ((offset - lastOfs) shr 1)
            order = compare(array[base + m], key)
            if (order < 0) lastOfs = m + 1 else offset = m
        }
        return offset
    }

    /** The k in 0..length with `array[base + k - 1] <= key < array[base + k]`: the position right of the rightmost element equal to [key]. */
    private fun gallopRight(array: Array<Any?>, key: Any?, base: Int, length: Int, hint: Int): Int {
        var lastOfs = 0
        var offset = 1
        var order = compare(key, array[base + hint])
        if (order < 0) {
            val maxOfs = hint + 1
            while (offset < maxOfs) {
                order = compare(key, array[base + hint - offset])
                if (order >= 0) break
                lastOfs = offset
                offset = (offset shl 1) + 1
                if (offset <= 0) offset = maxOfs
            }
            if (offset > maxOfs) offset = maxOfs
            val t = lastOfs
            lastOfs = hint - offset
            offset = hint - t
        } else {
            val maxOfs = length - hint
            while (offset < maxOfs) {
                order = compare(key, array[base + hint + offset])
                if (order < 0) break
                lastOfs = offset
                offset = (offset shl 1) + 1
                if (offset <= 0) offset = maxOfs
            }
            if (offset > maxOfs) offset = maxOfs
            lastOfs += hint
            offset += hint
        }
        lastOfs++
        while (lastOfs < offset) {
            val m = lastOfs + ((offset - lastOfs) shr 1)
            order = compare(key, array[base + m])
            if (order < 0) offset = m else lastOfs = m + 1
        }
        return offset
    }

    private fun tempArray(size: Int): Array<Any?> {
        if (temp.size < size) temp = arrayOfNulls(size)
        return temp
    }

    // ---- merging ----------------------------------------------------------------------------------------------------------

    /** Merges run A (at [baseA]) with run B (at [baseB]) in place, using a copy of A; A is the shorter or as long. */
    private fun mergeLow(baseA: Int, lengthAArg: Int, baseB: Int, lengthBArg: Int) {
        var lengthA = lengthAArg
        var lengthB = lengthBArg
        val tmp = tempArray(lengthA)
        System.arraycopy(a, baseA, tmp, 0, lengthA)
        var dest = baseA
        var cursorTemp = 0
        var cursorB = baseB
        a[dest++] = a[cursorB++]

        // 0: finished, 1: copy what is left of B ("CopyB")
        var finish = 0
        run done@{
            if (--lengthB == 0) return@done
            if (lengthA == 1) {
                finish = 1
                return@done
            }
            var gallop = minGallop
            while (true) {
                var winsA = 0
                var winsB = 0
                while (true) {
                    val order = compare(a[cursorB], tmp[cursorTemp])
                    if (order < 0) {
                        a[dest++] = a[cursorB++]
                        winsB++
                        lengthB--
                        winsA = 0
                        if (lengthB == 0) return@done
                        if (winsB >= gallop) break
                    } else {
                        a[dest++] = tmp[cursorTemp++]
                        winsA++
                        lengthA--
                        winsB = 0
                        if (lengthA == 1) {
                            finish = 1
                            return@done
                        }
                        if (winsA >= gallop) break
                    }
                }
                gallop++
                var firstIteration = true
                while (winsA >= MIN_GALLOP_WINS || winsB >= MIN_GALLOP_WINS || firstIteration) {
                    firstIteration = false
                    gallop = maxOf(1, gallop - 1)
                    minGallop = gallop
                    winsA = gallopRight(tmp, a[cursorB], cursorTemp, lengthA, 0)
                    if (winsA > 0) {
                        System.arraycopy(tmp, cursorTemp, a, dest, winsA)
                        dest += winsA
                        cursorTemp += winsA
                        lengthA -= winsA
                        if (lengthA == 1) {
                            finish = 1
                            return@done
                        }
                        if (lengthA == 0) return@done
                    }
                    a[dest++] = a[cursorB++]
                    if (--lengthB == 0) return@done
                    winsB = gallopLeft(a, tmp[cursorTemp], cursorB, lengthB, 0)
                    if (winsB > 0) {
                        System.arraycopy(a, cursorB, a, dest, winsB)
                        dest += winsB
                        cursorB += winsB
                        lengthB -= winsB
                        if (lengthB == 0) return@done
                    }
                    a[dest++] = tmp[cursorTemp++]
                    if (--lengthA == 1) {
                        finish = 1
                        return@done
                    }
                }
                gallop++
                minGallop = gallop
            }
        }
        if (finish == 1) {
            System.arraycopy(a, cursorB, a, dest, lengthB)
            a[dest + lengthB] = tmp[cursorTemp]
        } else if (lengthA > 0) {
            System.arraycopy(tmp, cursorTemp, a, dest, lengthA)
        }
    }

    /** Merges run A with run B in place from the right end, using a copy of B; B is the shorter or as long. */
    private fun mergeHigh(baseA: Int, lengthAArg: Int, baseB: Int, lengthBArg: Int) {
        var lengthA = lengthAArg
        var lengthB = lengthBArg
        val tmp = tempArray(lengthB)
        System.arraycopy(a, baseB, tmp, 0, lengthB)
        var dest = baseB + lengthB - 1
        var cursorTemp = lengthB - 1
        var cursorA = baseA + lengthA - 1
        a[dest--] = a[cursorA--]

        // 0: finished, 1: copy what is left of A ("CopyA")
        var finish = 0
        run done@{
            if (--lengthA == 0) return@done
            if (lengthB == 1) {
                finish = 1
                return@done
            }
            var gallop = minGallop
            while (true) {
                var winsA = 0
                var winsB = 0
                while (true) {
                    val order = compare(tmp[cursorTemp], a[cursorA])
                    if (order < 0) {
                        a[dest--] = a[cursorA--]
                        winsA++
                        lengthA--
                        winsB = 0
                        if (lengthA == 0) return@done
                        if (winsA >= gallop) break
                    } else {
                        a[dest--] = tmp[cursorTemp--]
                        winsB++
                        lengthB--
                        winsA = 0
                        if (lengthB == 1) {
                            finish = 1
                            return@done
                        }
                        if (winsB >= gallop) break
                    }
                }
                gallop++
                var firstIteration = true
                while (winsA >= MIN_GALLOP_WINS || winsB >= MIN_GALLOP_WINS || firstIteration) {
                    firstIteration = false
                    gallop = maxOf(1, gallop - 1)
                    minGallop = gallop
                    var k = gallopRight(a, tmp[cursorTemp], baseA, lengthA, lengthA - 1)
                    winsA = lengthA - k
                    if (winsA > 0) {
                        dest -= winsA
                        cursorA -= winsA
                        System.arraycopy(a, cursorA + 1, a, dest + 1, winsA)
                        lengthA -= winsA
                        if (lengthA == 0) return@done
                    }
                    a[dest--] = tmp[cursorTemp--]
                    if (--lengthB == 1) {
                        finish = 1
                        return@done
                    }
                    k = gallopLeft(tmp, a[cursorA], 0, lengthB, lengthB - 1)
                    winsB = lengthB - k
                    if (winsB > 0) {
                        dest -= winsB
                        cursorTemp -= winsB
                        System.arraycopy(tmp, cursorTemp + 1, a, dest + 1, winsB)
                        lengthB -= winsB
                        if (lengthB == 1) {
                            finish = 1
                            return@done
                        }
                        if (lengthB == 0) return@done
                    }
                    a[dest--] = a[cursorA--]
                    if (--lengthA == 0) return@done
                }
                gallop++
                minGallop = gallop
            }
        }
        if (finish == 1) {
            dest -= lengthA
            cursorA -= lengthA
            System.arraycopy(a, cursorA + 1, a, dest + 1, lengthA)
            a[dest] = tmp[cursorTemp]
        } else if (lengthB > 0) {
            System.arraycopy(tmp, 0, a, dest - (lengthB - 1), lengthB)
        }
    }

    private companion object {
        const val MIN_GALLOP_WINS = 7
        const val MAX_MERGE_PENDING = 85
        const val SMALL_ARRAY = 7
    }
}
