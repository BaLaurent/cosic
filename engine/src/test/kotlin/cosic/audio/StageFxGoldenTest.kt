package cosic.audio

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

/**
 * The tremolo, phaser and compressor stages against the real superdough graphs rendered by Chromium's `OfflineAudioContext`
 * (`tools/strudel-ref/stagefx-oracle.mjs`; the numbers of the tolerances are the worst differences observed, see `COSIC_DSP_VERBOSE=true`).
 */
class StageFxGoldenTest {
    private fun check(case: StageFxCase, atol: Double, rtol: Double, blockSizes: IntArray = intArrayOf(256)) {
        val actual = case.run(blockSizes)
        // The oracle stores one channel when both were identical in the browser (a mono voice through the compressor is L = R).
        assertTrue(actual.size >= case.output.size, "${case.name}: ${actual.size} channels, expected ${case.output.size}")
        for (ch in case.output.indices) worstDifference("${case.name} [ch $ch, blocks ${blockSizes.toList()}]", case.output[ch], actual[ch], atol, rtol)
        if (case.output.size == 1 && actual.size == 2) assertContentEquals(actual[0], actual[1], "${case.name}: a mono input must come out as L = R")
    }

    // Blocks of 256, 128 (whole quanta) and ones that cut the quanta and the 32-frame divisions of the compressor anywhere.
    private val splits = listOf(intArrayOf(256), intArrayOf(128), intArrayOf(97), intArrayOf(33, 17, 200, 1, 255), intArrayOf(7))

    @Test
    fun tremoloMatchesTheBrowserWhateverTheBlockSize() {
        val cases = StageFxGolden.cases("tremolo")
        assertTrue(cases.size >= 7)
        // The worst differences observed are 0: every case is bit for bit what the browser renders.
        for (case in cases) for (split in splits) check(case, 1e-6, 1e-6, split)
    }

    @Test
    fun phaserMatchesTheBrowserWhateverTheBlockSize() {
        val cases = StageFxGolden.cases("phaser")
        assertTrue(cases.size >= 7)
        // Observed: 0 (bit for bit), at worst 7e-9.
        for (case in cases) for (split in splits) check(case, 1e-6, 1e-6, split)
    }

    @Test
    fun compressorMatchesTheBrowserWhateverTheBlockSize() {
        val cases = StageFxGolden.cases("compressor")
        assertTrue(cases.size >= 10)
        // Observed: 0 (bit for bit) for the fresh nodes, at worst 1e-7 after the lead-in and after the panner.
        for (case in cases) for (split in splits) check(case, 1e-6, 1e-6, split)
    }
}
