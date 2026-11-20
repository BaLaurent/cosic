package cosic.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The buses voices send to (`bus`, `busgain`) and what a reader (`bmod`, the `bus` source) sees of them. */
class AudioBusesTest {
    private val ctx = BlockContext(SR).also { it.n = 100 }
    private val unit = AParam(1.0, -3.4e38, 3.4e38)

    private fun block(value: Float, channels: Int = 1, right: Float = value): AudioBlock = AudioBlock(BLOCK_FRAMES).also {
        it.l.fill(value, 0, 100)
        it.r.fill(right, 0, 100)
        it.channels = channels
    }

    @Test
    fun sendsAreSummedPerBusAndAMonoSendReachesBothChannels() {
        val buses = AudioBuses(SR)
        buses.beginBlock()
        assertNull(buses.tap(3), "nothing was ever sent")
        buses.send(3, block(0.5f), unit, ctx)
        buses.send(3, block(0.25f, 2, 1f), AParam(2.0, -3.4e38, 3.4e38), ctx)
        buses.send(4, block(1f), unit, ctx)
        val tap = buses.tap(3)!!
        assertEquals(0.5f + 0.5f, tap.left[7])
        assertEquals(0.5f + 2f, tap.right[7])
        assertEquals(1f, buses.tap(4)!!.left[0])
        assertNull(buses.tap(5))
    }

    @Test
    fun aReadersOwnSendsArriveOneBlockLater() {
        val buses = AudioBuses(SR)
        buses.beginBlock()
        buses.send(1, block(0.5f), unit, ctx)
        buses.beginReaders()
        buses.send(1, block(0.25f), unit, ctx)
        assertEquals(0.5f, buses.tap(1)!!.left[0], "a reader sees what the non-readers sent in the block, not its own")
        buses.beginBlock()
        assertEquals(0.25f, buses.tap(1)!!.left[0], "and its own send is there at the start of the next block")
        buses.send(1, block(0.5f), unit, ctx)
        assertEquals(0.75f, buses.tap(1)!!.left[0])
        buses.beginBlock()
        assertEquals(0f, buses.tap(1)!!.left[0], "nothing is carried over for ever")
    }

    @Test
    fun busesOutsideTheRangeAreIgnoredAndClearEmptiesThem() {
        val buses = AudioBuses(SR)
        buses.beginBlock()
        buses.send(-1, block(1f), unit, ctx)
        buses.send(MAX_BUSES, block(1f), unit, ctx)
        assertNull(buses.tap(-1))
        assertNull(buses.tap(MAX_BUSES))
        buses.send(2, block(1f), unit, ctx)
        buses.clear()
        assertEquals(0f, buses.tap(2)!!.left[0])
    }

    @Test
    fun aVoiceWithABusModulatorIsAReader() {
        val orbits = OrbitSet(SR, IrExecutor.INLINE)
        val voice = Voice(SR, orbits, AudioBuses(SR), FxGroupPool(SR))
        val plain = synthVp("sine", 0.1)
        voice.start(plain, 0L, 1L, false)
        assertFalse(voice.isBusReader)
        val modulator = ModSpec(ModKind.BMOD, "0", "main", "cutoff", null, mapOf("bus" to 1.0), emptyMap())
        val reader = VoiceParams(plain.source, plain.durationSec, plain.cps, plain.cycle, plain.main, plain.fx, listOf(modulator))
        voice.start(reader, 0L, 2L, false)
        assertTrue(voice.isBusReader)
    }
}
