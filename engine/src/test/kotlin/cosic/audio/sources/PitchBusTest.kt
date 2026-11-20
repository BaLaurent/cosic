package cosic.audio.sources

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PitchBusTest {
    private class Constant(val cents: Double = 0.0, val hz: Double = 0.0) : PitchModulator, FrequencyModulator {
        override fun add(bus: PitchBus, n: Int) {
            if (cents != 0.0) {
                for (i in 0 until n) bus.detuneCents[i] += cents
                bus.detuneActive = true
            }
            if (hz != 0.0) {
                for (i in 0 until n) bus.frequencyAddHz[i] += hz
                bus.frequencyActive = true
            }
        }
    }

    @Test
    fun aNewBusIsSilentAndInactive() {
        val bus = PitchBus(64)
        assertFalse(bus.detuneActive)
        assertFalse(bus.frequencyActive)
        assertEquals(64, bus.detuneCents.size)
        assertEquals(64, bus.frequencyAddHz.size)
    }

    @Test
    fun modulatorsAddUpAndClearZeroesAndDeactivates() {
        val bus = PitchBus(16)
        bus.clear(16)
        Constant(cents = 50.0).add(bus, 16)
        Constant(cents = 25.0, hz = 100.0).add(bus, 16)
        assertTrue(bus.detuneActive)
        assertTrue(bus.frequencyActive)
        assertEquals(75.0, bus.detuneCents[15])
        assertEquals(100.0, bus.frequencyAddHz[0])
        bus.clear(16)
        assertFalse(bus.detuneActive)
        assertFalse(bus.frequencyActive)
        assertTrue(bus.detuneCents.all { it == 0.0 })
        assertTrue(bus.frequencyAddHz.all { it == 0.0 })
    }

    @Test
    fun anArrayThatIsNotActiveIsAlwaysEntirelyZeroEvenAfterAShorterBlock() {
        val bus = PitchBus(256)
        bus.clear(256)
        Constant(cents = 10.0, hz = 5.0).add(bus, 256) // a full block ...
        bus.clear(100) // ... followed by a short one: the tail of the full block must not survive
        Constant(cents = 1.0).add(bus, 100)
        bus.clear(256)
        assertFalse(bus.detuneActive)
        assertTrue(bus.detuneCents.all { it == 0.0 }, "the detune of the short block left something behind")
        assertTrue(bus.frequencyAddHz.all { it == 0.0 }, "the frequency of the full block left something behind")
    }

    @Test
    fun aBlockLongerThanTheBusIsRefused() {
        assertFailsWith<IllegalArgumentException> { PitchBus(8).clear(9) }
    }
}
