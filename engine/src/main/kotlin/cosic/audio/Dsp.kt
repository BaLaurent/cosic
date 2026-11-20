package cosic.audio

/*
 * Constants and numeric helpers shared by the voice chain (superdough's per-event graph, see docs/parity/spec/superdough-chain.md).
 * Everything here is allocation-free and safe to call on the audio thread.
 */

/** Web Audio's render quantum: worklet processors see time in blocks of this many frames, aligned to the absolute frame counter. */
internal const val QUANTUM = 128

/** Frames the [Mixer] renders per internal block; every per-block buffer is sized for it. */
internal const val BLOCK_FRAMES = 256

/** Effect chains (`FX(...)` elements) a voice has besides the main one; further elements are dropped. */
internal const val MAX_FX = 4

/**
 * Effect chains of `FX(...)` elements the shared pool holds when a [Mixer] is made. Most programs use none; the pool grows ahead of the events
 * that need more (see [GrowablePool]), up to [FX_GROUP_LIMIT].
 */
internal const val FX_GROUP_POOL = 32

/** Modulators (`.lfo`, `.env`, `.bmod`) a voice can connect; further ones are ignored. */
internal const val MAX_MODS = 8

/** Strudel's `maxPolyphony` default: the 129th sounding voice ramps the oldest one down over 0.25 s. */
internal const val DEFAULT_MAX_POLYPHONY = 128

/** Voice slots in the pool. Stolen and cut voices keep their slot until they are done, so there are more slots than [DEFAULT_MAX_POLYPHONY]. */
internal const val VOICE_SLOTS = 256

/** The most chains of `FX(...)` elements that can be in use at once: every voice slot with all [MAX_FX] of them. */
internal const val FX_GROUP_LIMIT = VOICE_SLOTS * MAX_FX

/**
 * Phase vocoders (`stretch`, about 150 KB each) the shared pool can hold. Each costs about 5 % of a core when running (110-140 us per stereo render
 * quantum), so a phone cannot run more than a few dozen anyway; the limit keeps the memory of a runaway pattern finite.
 */
internal const val VOCODER_LIMIT = 64

/** Orbits (effect buses) that can exist at the same time. */
internal const val MAX_ORBITS = 64

/** Filters recompute their coefficients this often (frames, aligned to the absolute frame counter) while their frequency moves. */
internal const val FILTER_UPDATE_INTERVAL = 16

/**
 * `Math.min(Math.max(v, lo), hi)` as the worklets write it: a NaN [v] stays NaN, and [hi] wins when [lo] is above it.
 */
internal fun clamp(v: Double, lo: Double, hi: Double): Double = Math.min(Math.max(v, lo), hi)

/** The fractional part `x - floor(x)`, always in `[0, 1)` for finite [x]. */
internal fun frac(x: Double): Double = x - Math.floor(x)

/** `10^x`. */
internal fun pow10(x: Double): Double = Math.exp(x * Math.log(10.0))

/** Decibels to a linear gain: `10^(db/20)`. */
internal fun dbToLin(db: Double): Double = Math.pow(10.0, db / 20.0)

/** A linear gain in decibels; 0 is -1000 dB, as in Blink's `LinearToDecibels`. */
internal fun linToDb(x: Double): Double = if (x == 0.0) -1000.0 else 20.0 * Math.log10(x)

/**
 * [fallback] when [v] is NaN, otherwise [v]. This is the audio-thread counterpart of Strudel's `nanFallback`
 * (`cosic.pattern.nanFallback` works on JavaScript values, boxes and logs a warning); here NaN is how a [ParamSet] says
 * "not set".
 */
internal fun nanFallback(v: Double, fallback: Double): Double = if (v != v) fallback else v

/**
 * Keeps feedback loops healthy: denormals and anything that is not a sane finite value become exactly zero, so one
 * bad sample can neither slow the loop down nor circulate forever.
 */
internal fun flushToZero(x: Float): Float =
    if ((x > 1e-20f && x < 1e6f) || (x < -1e-20f && x > -1e6f)) x else 0f

/** NaN becomes 0; every other value, infinities included, is left to the caller's clamp. */
internal fun sanitize(x: Float): Float = if (x == x) x else 0f
