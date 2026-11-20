package cosic.pattern

/**
 * Spreads [k] hits as evenly as possible over [n] steps (Bjorklund's algorithm): `bjorklund(3, 8)` is
 * `x..x..x.`. No hits for `k <= 0`, every step for `k >= n`, nothing for `n <= 0`.
 */
internal fun bjorklund(k: Int, n: Int): BooleanArray {
    if (n <= 0) return BooleanArray(0)
    if (k <= 0) return BooleanArray(n)
    if (k >= n) return BooleanArray(n) { true }

    var front = List(k) { listOf(true) }
    var back = List(n - k) { listOf(false) }
    // Repeatedly glue one `back` group onto each `front` group until at most one group is left over.
    while (minOf(front.size, back.size) > 1) {
        if (front.size > back.size) {
            val glued = front.take(back.size).zip(back) { a, b -> a + b }
            back = front.drop(back.size)
            front = glued
        } else {
            val glued = front.zip(back.take(front.size)) { a, b -> a + b }
            back = back.drop(front.size)
            front = glued
        }
    }
    return (front.flatten() + back.flatten()).toBooleanArray()
}

/** Rotates left by [steps] (right for negative values). */
internal fun BooleanArray.rotatedLeft(steps: Int): BooleanArray {
    if (isEmpty()) return this
    val shift = Math.floorMod(steps, size)
    return BooleanArray(size) { this[(it + shift) % size] }
}

/** Plays this pattern's values on the hits of a Euclidean rhythm of [k] hits over [n] steps. */
internal fun Pattern.euclid(k: Int, n: Int): Pattern = euclidRot(k, n, 0)

/** Like [euclid], with the rhythm rotated left by [rotation] steps. */
internal fun Pattern.euclidRot(k: Int, n: Int, rotation: Int): Pattern =
    struct(fastcat(bjorklund(k, n).rotatedLeft(rotation).map { pure(it) }))
