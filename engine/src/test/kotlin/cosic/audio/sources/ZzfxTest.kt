package cosic.audio.sources

import cosic.pattern.CosicError
import cosic.pattern.Undefined
import java.lang.management.ManagementFactory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The behaviour of ZZFX that Z1-Z9 of the sources appendix state (the numbers are those of `buildSamples` at 48 kHz); `ZzfxGoldenTest` checks the rest against the real code. */
class ZzfxTest {
    private val sr = 48000

    private fun controls(vararg pairs: Pair<String, Any?>) = mapOf(*pairs).asSourceControls()

    private fun spec(vararg pairs: Pair<String, Any?>, duration: Double = 0.1, seed: Long = 1L) = SourceSpec(sr, controls(*pairs), duration, 1.0, seed)

    private fun peak(b: FloatArray, from: Int = 0, to: Int = b.size) = (from until to).maxOf { abs(b[it]) }

    @Test
    fun z1TheBufferOfAPlainSine() {
        val b = buildZzfx(sr, SourceRng(1), volume = 1.0, randomness = 0.0, frequency = 440.0, attack = 0.0, sustain = 0.1, release = 0.1)
        assertEquals(9609, b.size)
        assertEquals(0.0, b[0].toDouble(), 1e-12)
        assertEquals(0.00639600, b[1].toDouble(), 1e-7)
        assertEquals(0.495459, b[9].toDouble(), 1e-6)
        assertTrue(peak(b) in 0.999..1.0)
        assertTrue(abs(b[9608]) <= 2.1e-4f)
        assertTrue(abs(b[7209]) <= 0.5f)
    }

    @Test
    fun z2TheParametersOfZSine() {
        val p = zzfxParams("z_sine", controls("note" to 69.0), 0.1)
        assertContentEquals(doubleArrayOf(0.25, 0.0, 440.0, 0.0, 0.1, 0.1, 0.0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.8, 0.0, 0.0), p)
        assertTrue(peak(buildZzfx(sr, SourceRng(1), p)) >= 0.1995f)
    }

    @Test
    fun z3ZSquareIsATriangleThroughAZeroCurve() {
        val p = zzfxParams("z_square", controls("note" to 60.0, "curve" to 3.0), 0.1)
        assertEquals(0.0, p[7])
        assertEquals(-1.0, p[6])
        val b = buildZzfx(sr, SourceRng(1), p)
        // after the 9 sample attack the sustain is 0.25 * 0.8 and every sample has the sign of the triangle
        val ones = (200 until 4000).count { abs(abs(b[it]) - 0.2f) < 1e-6f }
        assertEquals(3800, ones)
    }

    @Test
    fun z4PlainZzfxResolvesToTheTriangleBranch() {
        assertEquals(-1.0, zzfxParams("zzfx", controls(), 0.1)[6])
        assertEquals(0.0, zzfxParams("z_sine", controls(), 0.1)[6])
        assertEquals(1.0, zzfxParams("z_triangle", controls(), 0.1)[6])
        assertEquals(2.0, zzfxParams("z_sawtooth", controls(), 0.1)[6])
        assertEquals(3.0, zzfxParams("z_tan", controls(), 0.1)[6])
        assertEquals(4.0, zzfxParams("z_noise", controls(), 0.1)[6])
        // the event's own s decides, and the comparison is case sensitive
        assertEquals(2.0, zzfxParams("z_sine", controls("s" to "z_sawtooth"), 0.1)[6])
        assertEquals(-1.0, zzfxParams("z_sine", controls("s" to "Z_SINE"), 0.1)[6])
        // the same waveform, the same buffer
        val triangle = buildZzfx(sr, SourceRng(1), zzfxParams("zzfx", controls("note" to 50.0), 0.05))
        val named = buildZzfx(sr, SourceRng(1), zzfxParams("z_triangle", controls("note" to 50.0), 0.05))
        assertContentEquals(named, triangle)
    }

    @Test
    fun z5ABitCrusherHoldsEachSample() {
        val b = buildZzfx(sr, SourceRng(1), volume = 1.0, randomness = 0.0, frequency = 440.0, sustain = 0.05, bitCrush = 0.05)
        for (i in 0..3) assertEquals(0f, b[i])
        for (i in 5..8) assertEquals(b[4], b[i])
        assertTrue(b[9] != b[8])
    }

    @Test
    fun z6TheDefaultNote() {
        assertEquals(65.4064, zzfxParams("z_sine", controls(), 0.1)[2], 1e-4)
        assertEquals(8.1758, zzfxParams("z_sine", controls("note" to 0.0), 0.1)[2], 1e-4)
        assertEquals(261.6256, zzfxParams("z_sine", controls("note" to "c4"), 0.1)[2], 1e-4)
        // freq wins, octave does nothing
        assertEquals(330.0, zzfxParams("z_sine", controls("note" to 70.0, "freq" to 330.0), 0.1)[2], 0.0)
        assertEquals(440.0, zzfxParams("z_sine", controls("note" to 69.0, "octave" to 2.0), 0.1)[2], 1e-9)
        // a note that is no number leaves the default of buildSamples
        assertEquals(220.0, zzfxParams("z_sine", controls("note" to true), 0.1)[2])
        assertFailsWith<CosicError> { zzfxParams("z_sine", controls("note" to "x"), 0.1) }
    }

    @Test
    fun theSustainIsWhatTheAttackAndTheDecayLeaveOfTheDuration() {
        assertEquals(0.07, zzfxParams("z_sine", controls("attack" to 0.02, "decay" to 0.01), 0.1)[4], 1e-12)
        assertEquals(0.0, zzfxParams("z_sine", controls("attack" to 0.2), 0.1)[4])
        assertEquals(0.3, zzfxParams("z_sine", controls("sustain" to 0.3), 0.1)[17])
        assertEquals(0.5, zzfxParams("z_sine", controls("znoise" to 0.5), 0.1)[13])
    }

    @Test
    fun z7ATheZzfxListReplacesTheParameters() {
        val list = List(20) { (it + 1).toDouble() }
        assertContentEquals(DoubleArray(20) { (it + 1).toDouble() }, zzfxParams("z_sine", controls("zzfx" to list, "note" to 69.0), 0.1))
        // what the list leaves out is the default of buildSamples: randomness 0.05, volume 1, sustain volume 1
        val short = zzfxParams("z_sine", controls("zzfx" to listOf(0.5, Undefined, 100.0)), 0.1)
        assertEquals(0.5, short[0])
        assertEquals(0.05, short[1])
        assertEquals(100.0, short[2])
        assertEquals(1.0, short[17])
        assertEquals(0.1, short[5])
        // null is 0, a string is spread into its characters, a number is no list
        assertEquals(0.0, zzfxParams("z_sine", controls("zzfx" to listOf(null)), 0.1)[0])
        assertEquals(2.0, zzfxParams("z_sine", controls("zzfx" to "2"), 0.1)[0])
        assertFailsWith<CosicError> { zzfxParams("z_sine", controls("zzfx" to 3.0), 0.1) }
    }

    @Test
    fun z8TheRandomStartFrequencyIsReproducible() {
        val a = buildZzfx(sr, SourceRng(5), randomness = 0.5, frequency = 300.0, sustain = 0.01)
        val b = buildZzfx(sr, SourceRng(5), randomness = 0.5, frequency = 300.0, sustain = 0.01)
        val c = buildZzfx(sr, SourceRng(6), randomness = 0.5, frequency = 300.0, sustain = 0.01)
        assertContentEquals(a, b)
        assertTrue(!a.contentEquals(c))
    }

    @Test
    fun z9TheSourceFinishesExactlyAtTheEndOfTheBuffer() {
        val s = spec("note" to 69.0)
        val expected = buildZzfx(sr, SourceRng(1), zzfxParams("z_sine", s.controls, s.durationSec))
        val source = assertNotNull(ZzfxSynths.create("z_sine", s))
        source.start(1000L)
        val left = FloatArray(256)
        val right = FloatArray(256)
        var total = 0
        while (!source.isFinished) {
            val n = source.render(left, right, 256)
            total += n
            assertTrue(n <= 256)
            if (n < 256) assertTrue(source.isFinished)
        }
        assertEquals(expected.size, total)
        assertEquals(0, source.render(left, right, 256))
    }

    @Test
    fun aLongSoundIsCutOffAtSixtySeconds() {
        val p = zzfxParams("z_sine", controls(), 1000.0)
        val g = ZzfxGenerator(8000, SourceRng(1), p)
        assertEquals(60 * 8000, g.frames)
    }

    @Test
    fun theSynthsKnowTheirNames() {
        assertEquals(listOf("zzfx", "z_sine", "z_sawtooth", "z_triangle", "z_square", "z_tan", "z_noise"), ZzfxSynths.names)
        assertEquals("z_sine", ZzfxSynths.canonical("Z_Sine"))
        assertEquals("z_sine", ZzfxSynths.canonical(" z_sine ".trim()))
        assertNull(ZzfxSynths.canonical("sine"))
        assertNull(ZzfxSynths.create("sine", spec()))
        assertNull(ZzfxSynths.create("z_triangle2", spec()))
    }

    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    @Test
    fun startingAndRenderingDoesNotAllocate() {
        val names = ZzfxSynths.names
        val specs = listOf(
            spec("note" to 57.0, "zdelay" to 0.01),
            spec("note" to 57.0, "znoise" to 0.5, "zmod" to 20.0),
            spec("note" to 57.0, "curve" to 2.5, "zcrush" to 0.1, "lfo" to 0.02, "tremolo" to 0.5),
            spec("note" to 57.0, "slide" to 3.0, "pitchJump" to 100.0, "pitchJumpTime" to 0.01),
        )
        val left = FloatArray(256)
        val right = FloatArray(256)
        fun run(measure: Boolean): Long {
            val made = Array(names.size * specs.size) { ZzfxSynths.create(names[it % names.size], specs[it / names.size])!! }
            val before = threads.currentThreadAllocatedBytes
            for (s in made) {
                s.start(0L)
                while (!s.isFinished) s.render(left, right, 256)
            }
            return if (measure) threads.currentThreadAllocatedBytes - before else 0L
        }
        repeat(5) { run(false) }
        val allocated = run(true)
        assertEquals(0L, allocated, "starting and rendering allocated $allocated bytes")
    }
}
