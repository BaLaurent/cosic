package cosic.parity

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The audio features of the parity comparison against Strudel's own offline renderer (Task 5.2), the Kotlin side of tools/strudel-ref/audio/features.mjs: the
 * definitions are written there, and [AudioFeaturesTest] checks the two against the vectors that script makes. Frames are [FRAME] samples every [HOP] samples of
 * one channel, whole frames only.
 */
internal object AudioFeatureSpec {
    const val FRAME = 2048
    const val HOP = 1024
    const val BAND_COUNT = 8
    const val FLOOR_DB = -100.0
    const val ONSET_RISE_DB = 6.0
    const val ONSET_MIN_DB = -50.0
    const val FIRST_CENTRE = 62.5
    private const val SQRT_HALF = 0.7071067811865476
    private const val SQRT_TWO = 1.4142135623730951
    private const val HALF = FRAME / 2

    private val hann = DoubleArray(FRAME) { 0.5 - 0.5 * cos(2 * PI * it / FRAME) }
    private val windowEnergy: Double = hann.sumOf { it * it }
    private val twiddleRe = DoubleArray(HALF) { cos(-2 * PI * it / FRAME) }
    private val twiddleIm = DoubleArray(HALF) { sin(-2 * PI * it / FRAME) }
    private val bitReversed = IntArray(FRAME).also { table ->
        val bits = Integer.numberOfTrailingZeros(FRAME)
        for (i in 0 until FRAME) {
            var r = 0
            for (b in 0 until bits) if (i and (1 shl b) != 0) r = r or (1 shl (bits - 1 - b))
            table[i] = r
        }
    }

    /** In-place radix-2 FFT of [re], [im] (length [FRAME]), unscaled. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        for (i in 0 until FRAME) {
            val j = bitReversed[i]
            if (j > i) {
                var t = re[i]
                re[i] = re[j]
                re[j] = t
                t = im[i]
                im[i] = im[j]
                im[j] = t
            }
        }
        var size = 2
        while (size <= FRAME) {
            val half = size / 2
            val step = FRAME / size
            var start = 0
            while (start < FRAME) {
                for (k in 0 until half) {
                    val wr = twiddleRe[k * step]
                    val wi = twiddleIm[k * step]
                    val a = start + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                start += size
            }
            size *= 2
        }
    }

    /** The bin range [low, high) of each band at [sampleRate]. */
    fun bandBins(sampleRate: Int): List<IntArray> = List(BAND_COUNT) { b ->
        val centre = FIRST_CENTRE * (1 shl b)
        val low = max(1.0, ceil(centre * SQRT_HALF * FRAME / sampleRate)).toInt()
        val high = min(HALF.toDouble(), ceil(centre * SQRT_TWO * FRAME / sampleRate)).toInt()
        intArrayOf(low, high)
    }

    private fun toDb(power: Double): Double = if (power > 0.0) max(10 * log10(power), FLOOR_DB) else FLOOR_DB

    /** The features of the channel of [data] that starts at [offset] and has a sample every [stride] values, [length] samples long. */
    fun channelFeatures(data: FloatArray, offset: Int, stride: Int, length: Int, sampleRate: Int): ChannelFeatures {
        val frames = if (length < FRAME) 0 else (length - FRAME) / HOP + 1
        val rms = DoubleArray(frames)
        val centroid = DoubleArray(frames)
        val bands = DoubleArray(frames * BAND_COUNT)
        val onsets = ArrayList<Int>()
        val re = DoubleArray(FRAME)
        val im = DoubleArray(FRAME)
        val power = DoubleArray(HALF + 1)
        val ranges = bandBins(sampleRate)
        val bandScale = 2 / (FRAME * windowEnergy)
        for (f in 0 until frames) {
            val start = f * HOP
            var sum = 0.0
            for (i in 0 until FRAME) {
                val v = data[offset + (start + i) * stride].toDouble()
                sum += v * v
                re[i] = v * hann[i]
                im[i] = 0.0
            }
            val level = sqrt(sum / FRAME)
            rms[f] = if (level > 0) max(20 * log10(level), FLOOR_DB) else FLOOR_DB
            fft(re, im)
            var total = 0.0
            var weighted = 0.0
            for (k in 1..HALF) {
                val p = re[k] * re[k] + im[k] * im[k]
                power[k] = p
                total += p
                weighted += k * p
            }
            centroid[f] = if (total > 0) (weighted / total) * sampleRate / FRAME else 0.0
            for (b in 0 until BAND_COUNT) {
                var s = 0.0
                for (k in ranges[b][0] until ranges[b][1]) s += power[k]
                bands[f * BAND_COUNT + b] = toDb(bandScale * s)
            }
            val previous = if (f > 0) rms[f - 1] else FLOOR_DB
            if (rms[f] > ONSET_MIN_DB && rms[f] - previous > ONSET_RISE_DB) onsets.add(f)
        }
        return ChannelFeatures(rms, centroid, bands, onsets.toIntArray())
    }

    /** The features of interleaved [channels]-channel [data] (the first [length] frames of it). */
    fun features(data: FloatArray, channels: Int, length: Int, sampleRate: Int): List<ChannelFeatures> =
        List(channels) { channelFeatures(data, it, channels, length, sampleRate) }
}

/** The features of one channel: per frame the level and the spectral centroid, per frame and band the energy (`bands[frame * 8 + band]`), and the onset frames. */
internal class ChannelFeatures(val rms: DoubleArray, val centroid: DoubleArray, val bands: DoubleArray, val onsets: IntArray) {
    val frames: Int get() = rms.size
}

/** The thresholds the plan fixes (Task 5.2); a failing entry is a bug to fix, never a number to loosen. */
internal object AudioThresholds {
    /** Frames (and bands of frames) quieter than this on both sides do not count. */
    const val AUDIBLE_DB = -60.0
    const val ONSET_FRAMES = 1
    const val RMS_MEDIAN_DB = 1.5

    /** How far below the detection threshold the other render may be at an onset and still have it (the level tolerance); see [AudioCompare.unmatched]. */
    const val ONSET_MARGIN_DB = RMS_MEDIAN_DB
    const val RMS_P95_DB = 4.0
    const val CENTROID_MEDIAN_RELATIVE = 0.10
    const val BAND_MEDIAN_DB = 3.0
}

/** What the comparison of one entry measured, and what failed. */
internal class AudioComparison(
    val activeFrames: Int,
    val rmsMedianDb: Double,
    val rmsP95Db: Double,
    val centroidMedianRelative: Double,
    val bandMedianDb: Double,
    val bandSamples: Int,
    /** Onset frames of the reference and of the render that match nothing within the allowed distance, per channel; null when onsets were not compared. */
    val unmatchedReferenceOnsets: List<List<Int>>?,
    val unmatchedOnsets: List<List<Int>>?,
    val referenceOnsets: Int,
    val failures: List<String>,
) {
    val passed: Boolean get() = failures.isEmpty()
}

internal object AudioCompare {
    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sortedArray()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** The value below which 95% of [values] lie (nearest rank). */
    private fun percentile95(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        val s = values.sortedArray()
        return s[max(ceil(0.95 * s.size).toInt() - 1, 0)]
    }

    /** Onset frames of [reference] and [got] that have no partner within [maxDistance] frames (each onset is paired once, in order). */
    fun pair(reference: IntArray, got: IntArray, maxDistance: Int): Pair<List<Int>, List<Int>> {
        val taken = BooleanArray(got.size)
        val lostReference = ArrayList<Int>()
        for (r in reference) {
            var best = -1
            for (i in got.indices) {
                if (taken[i] || kotlin.math.abs(got[i] - r) > maxDistance) continue
                if (best < 0 || kotlin.math.abs(got[i] - r) < kotlin.math.abs(got[best] - r)) best = i
            }
            if (best >= 0) taken[best] = true else lostReference.add(r)
        }
        val lostGot = got.indices.filter { !taken[it] }.map { got[it] }
        return lostReference to lostGot
    }

    /**
     * Whether [features] rises at least [AudioThresholds.ONSET_RISE_DB] less [AudioThresholds.ONSET_MARGIN_DB] (and is above the onset floor less the margin) in a frame within
     * [distance] of [frame]. The detector is a threshold, and two renders whose levels agree to a fraction of a dB can fall on either side of it; an onset the other render
     * does not detect but nearly does (within the 1.5 dB the level criterion allows) is the same onset. A hit that is missing, or on the wrong frame, still has none.
     */
    private fun nearlyRises(features: ChannelFeatures, frame: Int, distance: Int): Boolean {
        for (f in max(0, frame - distance)..min(features.frames - 1, frame + distance)) {
            val previous = if (f > 0) features.rms[f - 1] else AudioFeatureSpec.FLOOR_DB
            if (features.rms[f] > AudioFeatureSpec.ONSET_MIN_DB - AudioThresholds.ONSET_MARGIN_DB &&
                features.rms[f] - previous > AudioFeatureSpec.ONSET_RISE_DB - AudioThresholds.ONSET_MARGIN_DB
            ) return true
        }
        return false
    }

    /** The onsets of [reference] and of [got] that have no partner within [maxDistance] frames, after the tolerance of [nearlyRises]. */
    fun unmatched(reference: ChannelFeatures, got: ChannelFeatures, maxDistance: Int): Pair<List<Int>, List<Int>> {
        val (lostReference, lostGot) = pair(reference.onsets, got.onsets, maxDistance)
        return lostReference.filter { !nearlyRises(got, it, maxDistance) } to lostGot.filter { !nearlyRises(reference, it, maxDistance) }
    }

    /**
     * [reference] (what Strudel rendered) against [got] (Cosic's), channel by channel. Levels and bands are compared on the frames where either is above
     * [AudioThresholds.AUDIBLE_DB] (bands: on the pairs where either band is), pooled over the channels; the centroid and the onsets only when [tonal] (an entry with noise or randomness is compared on level and bands only).
     */
    fun compare(reference: List<ChannelFeatures>, got: List<ChannelFeatures>, tonal: Boolean): AudioComparison {
        val failures = ArrayList<String>()
        val rmsDiff = ArrayList<Double>()
        val centroidDiff = ArrayList<Double>()
        val bandDiff = ArrayList<Double>()
        var active = 0
        for (c in reference.indices) {
            val r = reference[c]
            val g = got[c]
            val frames = min(r.frames, g.frames)
            for (f in 0 until frames) {
                if (max(r.rms[f], g.rms[f]) <= AudioThresholds.AUDIBLE_DB) continue
                active++
                rmsDiff.add(kotlin.math.abs(r.rms[f] - g.rms[f]))
                centroidDiff.add(kotlin.math.abs(r.centroid[f] - g.centroid[f]) / max(r.centroid[f], 1e-9))
                for (b in 0 until AudioFeatureSpec.BAND_COUNT) {
                    val rb = r.bands[f * AudioFeatureSpec.BAND_COUNT + b]
                    val gb = g.bands[f * AudioFeatureSpec.BAND_COUNT + b]
                    if (max(rb, gb) > AudioThresholds.AUDIBLE_DB) bandDiff.add(kotlin.math.abs(rb - gb))
                }
            }
        }
        val rmsMedian = median(rmsDiff.toDoubleArray())
        val rmsP95 = percentile95(rmsDiff.toDoubleArray())
        val centroidMedian = median(centroidDiff.toDoubleArray())
        val bandMedian = median(bandDiff.toDoubleArray())
        if (rmsMedian > AudioThresholds.RMS_MEDIAN_DB) failures.add("level: median |diff| %.2f dB > %.1f".format(rmsMedian, AudioThresholds.RMS_MEDIAN_DB))
        if (rmsP95 > AudioThresholds.RMS_P95_DB) failures.add("level: p95 |diff| %.2f dB > %.1f".format(rmsP95, AudioThresholds.RMS_P95_DB))
        if (tonal && centroidMedian > AudioThresholds.CENTROID_MEDIAN_RELATIVE) {
            failures.add("centroid: median relative diff %.1f%% > %.0f%%".format(centroidMedian * 100, AudioThresholds.CENTROID_MEDIAN_RELATIVE * 100))
        }
        if (bandMedian > AudioThresholds.BAND_MEDIAN_DB) failures.add("bands: median |diff| %.2f dB > %.1f".format(bandMedian, AudioThresholds.BAND_MEDIAN_DB))
        var lostReference: List<List<Int>>? = null
        var lostGot: List<List<Int>>? = null
        var referenceOnsets = 0
        if (tonal) {
            val pairs = reference.indices.map { AudioCompare.unmatched(reference[it], got[it], AudioThresholds.ONSET_FRAMES) }
            lostReference = pairs.map { it.first }
            lostGot = pairs.map { it.second }
            referenceOnsets = reference.sumOf { it.onsets.size }
            for (c in pairs.indices) {
                if (pairs[c].first.isNotEmpty()) failures.add("onsets: channel $c: reference frames ${pairs[c].first} have no onset within ±${AudioThresholds.ONSET_FRAMES} frame")
                if (pairs[c].second.isNotEmpty()) failures.add("onsets: channel $c: render frames ${pairs[c].second} have no reference onset within ±${AudioThresholds.ONSET_FRAMES} frame")
            }
        }
        return AudioComparison(active, rmsMedian, rmsP95, centroidMedian, bandMedian, bandDiff.size, lostReference, lostGot, referenceOnsets, failures)
    }

    /**
     * For an entry whose sound is random by design (the random start phases of a supersaw make the level of every note another one in every render, Strudel's own included),
     * the per-frame figures of [compare] cannot agree between two renders. What does is the long-term level, the mean power over the whole entry (within the 1.5 dB of the level
     * criterion) and the long-term energy of the octave bands (the median of the audible bands within 3 dB, as the band criterion). Same thresholds, measured where the sound is stable.
     * The reference's figures are the mean of several renders of Strudel (tools/strudel-ref/audio/render.mjs, `longTerm` in the reference file).
     */
    fun compareLongTerm(reference: LongTerm, got: LongTerm, frames: Int): AudioComparison {
        val failures = ArrayList<String>()
        val levelDiff = abs(reference.levelDb - got.levelDb)
        val bandDiff = ArrayList<Double>()
        for (b in 0 until AudioFeatureSpec.BAND_COUNT) {
            if (max(reference.bandsDb[b], got.bandsDb[b]) > AudioThresholds.AUDIBLE_DB) bandDiff.add(abs(reference.bandsDb[b] - got.bandsDb[b]))
        }
        val bandMedian = median(bandDiff.toDoubleArray())
        if (levelDiff > AudioThresholds.RMS_MEDIAN_DB) failures.add("long-term level: |diff| %.2f dB > %.1f".format(levelDiff, AudioThresholds.RMS_MEDIAN_DB))
        if (bandMedian > AudioThresholds.BAND_MEDIAN_DB) failures.add("long-term bands: median |diff| %.2f dB > %.1f".format(bandMedian, AudioThresholds.BAND_MEDIAN_DB))
        return AudioComparison(frames, levelDiff, levelDiff, 0.0, bandMedian, bandDiff.size, null, null, 0, failures)
    }
}

/** The long-term figures of an entry: the mean power of all its frames and of each octave band, in dB (`longTerm` of features.mjs). */
internal class LongTerm(val levelDb: Double, val bandsDb: DoubleArray) {
    companion object {
        private fun meanDb(db: DoubleArray): Double {
            if (db.isEmpty()) return AudioFeatureSpec.FLOOR_DB
            return max(10 * log10(db.sumOf { Math.pow(10.0, it / 10) } / db.size), AudioFeatureSpec.FLOOR_DB)
        }

        fun of(channels: List<ChannelFeatures>): LongTerm = LongTerm(
            meanDb(channels.flatMap { it.rms.toList() }.toDoubleArray()),
            DoubleArray(AudioFeatureSpec.BAND_COUNT) { b ->
                meanDb(channels.flatMap { c -> List(c.frames) { c.bands[it * AudioFeatureSpec.BAND_COUNT + b] } }.toDoubleArray())
            },
        )
    }
}
