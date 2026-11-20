package cosic.audio.dsp

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/** The kernels run on the audio thread: once built (and warmed up), `process` must not allocate. */
class DspKernelsAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun processNeverAllocates() {
        val sr = 48000
        val sourceLeft = FloatArray(256) { ((it * 37) % 19 - 9) / 10f }
        val sourceRight = FloatArray(256) { ((it * 53) % 23 - 11) / 12f }
        val left = FloatArray(256)
        val right = FloatArray(256)
        val distortValues = FloatArray(256) { 0.2f + it / 256f }
        val postgainValues = FloatArray(256) { 1f - it / 512f }

        val ladder = LadderFilter(sr)
        val distort = DistortProcessor()
        val coarse = CoarseProcessor()
        val djf = DjfFilter(sr)
        val transient = TransientShaper(sr).also { it.configure(0.5, -0.5, 0.0, 1000.0) }
        val vocoder = PhaseVocoder()
        val vowel = VowelFilter(sr).also { it.configure("a") }
        var frame = 0L

        fun refill() {
            System.arraycopy(sourceLeft, 0, left, 0, 256)
            System.arraycopy(sourceRight, 0, right, 0, 256)
        }

        fun workload() {
            for (round in 0 until 30) {
                refill()
                ladder.process(left, right, 0, 256, 1000.0 + 50 * round, 5.0, 0.69)
                for (algorithm in 0 until 9) {
                    refill()
                    distort.algorithmIndex = algorithm
                    distort.process(left, right, 0, 256, 1.0, 0.8)
                }
                refill()
                distort.processModulated(left, right, 0, 256, distortValues, postgainValues)
                refill()
                ShapeCrushCoarse.shape(left, right, 0, 256, 0.5, 0.8)
                ShapeCrushCoarse.crush(left, right, 0, 256, 6.0)
                coarse.process(left, right, 0, 256, 2.5, frame)
                djf.process(left, right, 0, 256, 0.25)
                djf.process(left, right, 0, 256, 0.75)
                djf.process(left, right, 0, 256, 0.5)
                transient.process(left, right, 0, 256, frame)
                vowel.process(left, right, 0, 256)
                refill()
                vocoder.process(left, right, 0, 256, frame, when (round % 3) { 0 -> 1.0; 1 -> -2.0; else -> 0.0 })
                frame += 256
            }
        }

        workload() // warm up: class loading, JIT, lazy tables
        workload()
        val before = threads.currentThreadAllocatedBytes
        workload()
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "the kernels allocated $allocated bytes")
    }

    @Test
    fun reconfiguringAnExistingKernelDoesNotAllocateEither() {
        val transient = TransientShaper(48000)
        val vowel = VowelFilter(48000)
        val vocoder = PhaseVocoder()
        val ladder = LadderFilter(48000)
        val djf = DjfFilter(48000)
        val coarse = CoarseProcessor()
        val names = arrayOf("a", "e", "i", "o", "u", "æ", "on", "unknown")
        fun configureAll(round: Int) {
            transient.configure(0.1 * round, -0.1 * round, 0.5, 9.0)
            vowel.configure(names[round % names.size])
            vocoder.reset()
            ladder.reset()
            djf.reset()
            coarse.reset()
            Distortion.algorithmIndex("fold")
        }
        for (i in 0 until 20000) configureAll(i) // warm up: class loading, JIT
        val before = threads.currentThreadAllocatedBytes
        for (i in 0 until 500) configureAll(i)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "configure/reset allocated $allocated bytes")
    }
}
