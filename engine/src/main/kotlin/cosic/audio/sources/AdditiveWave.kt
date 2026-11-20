package cosic.audio.sources

import cosic.pattern.CosicError
import java.util.Arrays

/**
 * `waveformN(partials, phases, type)` of superdough (synth.mjs:457-500): the wave of an oscillator that was given `partials` (a list of magnitudes, or a count
 * for that many equal ones), as the `PeriodicWave` Web Audio builds from its coefficients (`createPeriodicWave(real, imag)`, normalised). Partial `m` (from 1) is
 * `partials[m - 1]` times
 *
 * - `sawtooth`: `-sin(m x) / m`. This is not the sign of the native sawtooth (which is `+(-1)^(m+1)`): the two start at different phases;
 * - `square`: `sin(m x) / m` for odd `m`, nothing for even ones;
 * - `triangle`: `cos(m x) / m^2` for odd `m`, nothing for even ones. A cosine, so the wave starts at its peak;
 * - `user`: `sin(m x)`, the partials as they are;
 *
 * rotated by `2 pi phases[m - 1]` (the pair `(real, imag)` is rotated as a complex number) when the phase is not 0. Coefficients are float32, as in the
 * `Float32Array`s of the original, and a wave has at most `size / 2` of them (more are dropped, which is all a PeriodicWave can use).
 *
 * Building a wave costs about 36 inverse FFTs, a few milliseconds, so the waves are kept: the last 64 distinct ones, as long as they take less than 24 MB together.
 */
internal fun additiveWave(type: BasicWave?, partials: DoubleArray, phases: DoubleArray?, sampleRate: Int, user: Boolean): PeriodicWave {
    val kind = if (user) AdditiveKind.USER else when (type) {
        BasicWave.SAWTOOTH -> AdditiveKind.SAWTOOTH
        BasicWave.SQUARE -> AdditiveKind.SQUARE
        BasicWave.TRIANGLE -> AdditiveKind.TRIANGLE
        else -> throw CosicError("unknown wave type ${type?.name?.lowercase()}")
    }
    val len = Math.min(partials.size, MAX_PARTIALS)
    // createPeriodicWave needs at least 2 coefficients, that is at least one partial
    if (len < 1) {
        throw CosicError("Failed to execute 'createPeriodicWave' on 'BaseAudioContext': The length of the real array provided (1) is less than the minimum bound (2).")
    }
    val key = AdditiveKey(kind, sampleRate, partials.copyOf(len), phases?.let { it.copyOf(Math.min(it.size, len)) })
    synchronized(cache) {
        cache[key]?.let { return it }
    }
    val wave = build(kind, key.partials, key.phases, len, sampleRate)
    synchronized(cache) {
        cache[key] = wave
        trim()
    }
    return wave
}

private enum class AdditiveKind { SAWTOOTH, SQUARE, TRIANGLE, USER }

/** The most partials a wave keeps: the largest wave table (16384 entries, above 88.2 kHz) holds 8192 partials. */
private const val MAX_PARTIALS = 8192
private const val MAX_WAVES = 64
private const val MAX_BYTES = 24L * 1024 * 1024
private const val TWO_PI = 2.0 * Math.PI

private class AdditiveKey(val kind: AdditiveKind, val sampleRate: Int, val partials: DoubleArray, val phases: DoubleArray?) {
    private val hash: Int = ((kind.ordinal * 31 + sampleRate) * 31 + Arrays.hashCode(partials)) * 31 + (phases?.let { Arrays.hashCode(it) } ?: 0)

    override fun hashCode(): Int = hash

    override fun equals(other: Any?): Boolean =
        other is AdditiveKey && other.hash == hash && other.kind == kind && other.sampleRate == sampleRate &&
            Arrays.equals(other.partials, partials) && Arrays.equals(other.phases, phases)
}

/** Access order, so the least recently used wave is the first. */
private val cache = LinkedHashMap<AdditiveKey, PeriodicWave>(16, 0.75f, true)

private fun trim() {
    var bytes = 0L
    for (wave in cache.values) bytes += tableBytes(wave)
    val it = cache.entries.iterator()
    while (it.hasNext() && (cache.size > MAX_WAVES || bytes > MAX_BYTES) && cache.size > 1) {
        val entry = it.next()
        bytes -= tableBytes(entry.value)
        it.remove()
    }
}

/** The memory of the tables of a wave: tables for neighbouring ranges that have the same partials are one array. */
private fun tableBytes(wave: PeriodicWave): Long {
    var distinct = 0
    for (r in 0 until wave.rangeCount) if (r == 0 || wave.table(r) !== wave.table(r - 1)) distinct++
    return distinct.toLong() * wave.size * 4
}

private fun build(kind: AdditiveKind, partials: DoubleArray, phases: DoubleArray?, len: Int, sampleRate: Int): PeriodicWave {
    val real = DoubleArray(len + 1)
    val imag = DoubleArray(len + 1)
    for (n in 0 until len) {
        val m = (n + 1).toDouble() // the partial number; index 0 is the DC offset and is skipped
        var r = 0.0
        var i = 0.0
        when (kind) {
            AdditiveKind.SAWTOOTH -> i = -1.0 / m
            AdditiveKind.SQUARE -> i = if ((n + 1) % 2 == 0) 0.0 else 1.0 / m
            AdditiveKind.TRIANGLE -> r = if ((n + 1) % 2 == 0) 0.0 else 1.0 / (m * m)
            AdditiveKind.USER -> i = 1.0
        }
        val magnitude = partials[n]
        var re = r * magnitude
        var im = i * magnitude
        val phase = if (phases != null && n < phases.size) phases[n] else 0.0
        if (phase != 0.0) {
            val c = Math.cos(TWO_PI * phase)
            val s = Math.sin(TWO_PI * phase)
            val re0 = re
            val im0 = im
            re = c * re0 - s * im0
            im = s * re0 + c * im0
        }
        real[n + 1] = re.toFloat().toDouble()
        imag[n + 1] = im.toFloat().toDouble()
    }
    return PeriodicWave.custom(real, imag, sampleRate)
}
