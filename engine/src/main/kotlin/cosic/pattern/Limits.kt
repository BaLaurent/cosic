package cosic.pattern

/**
 * The most steps, copies, slices or events per cycle a number in a program may ask for (`euclid(3, 8)`, `a!4`,
 * `run(16)`, `fast(4)`, ...). Larger values would only exhaust memory building more events than anybody could hear.
 */
internal const val MAX_COUNT = 10_000
