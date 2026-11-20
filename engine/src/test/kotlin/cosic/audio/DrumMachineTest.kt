package cosic.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The synthesised drum machines: `s("bd").bank("cosic808")` asks the kit for the sound `cosic808_bd`. Machine sounds
 * are rendered like the bare kit's (four variants, 0.9 peak, cached), so these tests cover what is particular to
 * them: which sounds exist, and that each one sounds like what it is called.
 */
class DrumMachineTest {
    private val sounds: List<String> = DRUM_MACHINES.flatMap { (machine, names) -> names.map { "${machine}_$it" } }

    private fun DrumKit.samplesOf(key: String, variant: Int = 0): FloatArray = assertNotNull(sample(key, variant), "$key:$variant").samples

    // ---------------------------------------------------------------------------------- the sounds

    @Test
    fun machinesAreNamedInLowerCaseAndTheirSoundsWithDrumAbbreviations() {
        val abbreviations = setOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr", "rd", "cb", "cl", "sh", "tb", "perc")
        assertTrue(DRUM_MACHINES.isNotEmpty())
        for ((machine, names) in DRUM_MACHINES) {
            assertTrue(Regex("[a-z0-9]+").matches(machine), "'$machine' must not contain the '_' that joins it to a sound")
            assertEquals(names.toSet().size, names.size, "$machine lists a sound twice")
            for (name in names) assertTrue(name in abbreviations, "$machine has '$name', which is no drum abbreviation")
        }
    }

    @Test
    fun everyMachineSoundIsAudibleNormalisedAndFinite() {
        val kit = DrumKit(SR)
        for (key in sounds) {
            for (variant in 0..3) {
                val data = assertNotNull(kit.sample(key, variant), "$key:$variant")
                assertEquals(SR, data.sampleRate)
                assertTrue(allFinite(data.samples), "$key:$variant has NaN or infinity")
                val p = peak(data.samples)
                assertTrue(p <= 0.9f + 1e-6f, "$key:$variant peaks at $p")
                assertTrue(p >= 0.9f - 1e-3f, "$key:$variant is not normalised ($p)")
                assertTrue(rms(data.samples) > 0.01, "$key:$variant is silent")
            }
        }
    }

    @Test
    fun everyMachineSoundHasDiedAwayByTheTimeItEnds() {
        val kit = DrumKit(SR)
        for (key in sounds) {
            for (variant in 0..3) {
                val x = kit.samplesOf(key, variant)
                val tail = rms(x, x.size - 480, x.size)
                assertTrue(tail < 0.01, "$key:$variant is still sounding at -${-dbOf(tail / 0.9).toInt()} dB when it is cut off")
            }
        }
    }

    @Test
    fun onlyTheListedSoundsOfAMachineExist() {
        val kit = DrumKit(SR)
        for (key in listOf("cosic808_xx", "cosic707_bd", "cosic808", "cosic808_", "_bd", "808_bd", "cosic808_bd_", "cosic808_bd:1", "cosic808__bd")) {
            assertNull(kit.sample(key, 0), key)
        }
        val machines = DRUM_MACHINES.entries.toList()
        for (machine in machines) {
            for (other in machines) {
                for (name in other.value - machine.value.toSet()) assertNull(kit.sample("${machine.key}_$name", 0), "${machine.key} has no $name")
            }
        }
        assertNull(kit.sample("cb", 0), "the machines' sounds are not part of the bare kit")
    }

    @Test
    fun variantsDifferWrapModuloFourAndAreCached() {
        val kit = DrumKit(SR)
        for (key in sounds) {
            val variants = (0..3).map { kit.samplesOf(key, it) }
            for (a in 0..3) for (b in a + 1..3) assertFalse(variants[a].contentEquals(variants[b]), "$key variants $a and $b are identical")
            assertSame(kit.sample(key, 0), kit.sample(key, 4))
            assertSame(kit.sample(key, 3), kit.sample(key, -1))
        }
    }

    @Test
    fun aMachineSoundIsNeitherTheBareKitSoundNorTheOtherMachinesOne() {
        val kit = DrumKit(SR)
        for (name in DRUM_NAMES) {
            val versions = listOf(name) + DRUM_MACHINES.filterValues { name in it }.keys.map { "${it}_$name" }
            for (a in versions.indices) for (b in a + 1 until versions.size) {
                assertFalse(kit.samplesOf(versions[a]).contentEquals(kit.samplesOf(versions[b])), "${versions[a]} and ${versions[b]} are the same sound")
            }
        }
    }

    // ------------------------------------------------------------------------------ how they sound

    @Test
    fun kicksSettleOnABassNoteAndTheEightOhEightBoomsLongerThanTheNineOhNine() {
        val kit = DrumKit(SR)
        val boom = kit.samplesOf("cosic808_bd")
        val punch = kit.samplesOf("cosic909_bd")
        for (x in listOf(boom, punch)) {
            assertTrue(energyBelow(x, 300.0) > 0.6, "a kick is mostly low frequency")
            assertTrue(estimateFrequency(x, SR / 5, SR / 2) in 40.0..65.0, "a kick settles on a sub-bass note")
        }
        assertTrue(remainingAt(boom, 0.5) > 0.1, "the 808 kick booms on for half a second")
        assertTrue(remainingAt(punch, 0.3) < remainingAt(boom, 0.3), "the 909 kick is the punchier, shorter one")
    }

    @Test
    fun hatsAreHighAndOpenHatsRingLongerThanClosedOnes() {
        val kit = DrumKit(SR)
        for (machine in DRUM_MACHINES.keys) {
            val closed = kit.samplesOf("${machine}_hh")
            val open = kit.samplesOf("${machine}_oh")
            assertTrue(energyBelow(closed, 3000.0) < 0.1, "$machine hh should be mostly high frequency")
            assertTrue(energyBelow(open, 3000.0) < 0.1, "$machine oh should be mostly high frequency")
            assertTrue(remainingAt(closed, 0.10) < 0.1, "$machine hh should be over after 100 ms")
            assertTrue(remainingAt(open, 0.10) > 0.3, "$machine oh should still ring after 100 ms")
        }
    }

    @Test
    fun tomsRiseInPitchFromLowToHigh() {
        val kit = DrumKit(SR)
        for (machine in DRUM_MACHINES.keys) {
            val pitches = listOf("lt", "mt", "ht").map { estimateFrequency(kit.samplesOf("${machine}_$it"), SR * 15 / 100, SR * 35 / 100) }
            assertTrue(pitches[0] < pitches[1] && pitches[1] < pitches[2], "$machine toms ring at $pitches Hz")
            assertTrue(pitches[0] > 50.0 && pitches[2] < 400.0, "$machine toms ring at $pitches Hz")
        }
    }

    @Test
    fun theNineOhNineSnareIsBrighterAndNoisierThanTheEightOhEightSnare() {
        val kit = DrumKit(SR)
        assertTrue(energyBelow(kit.samplesOf("cosic909_sd"), 3000.0) < energyBelow(kit.samplesOf("cosic808_sd"), 3000.0))
    }

    @Test
    fun clapsEndInAReverbLikeTail() {
        val kit = DrumKit(SR)
        for (machine in DRUM_MACHINES.keys) {
            val x = kit.samplesOf("${machine}_cp")
            assertTrue(remainingAt(x, 0.08) > 0.1, "$machine clap should still sound after its bursts")
            assertTrue(remainingAt(x, 0.25) < remainingAt(x, 0.08), "$machine clap tail should keep fading")
        }
    }

    @Test
    fun theRimshotIsAShortClick() {
        val kit = DrumKit(SR)
        for (machine in DRUM_MACHINES.keys) assertTrue(kit.samplesOf("${machine}_rim").size <= SR / 10, "$machine rim should be shorter than 100 ms")
    }

    @Test
    fun theCowbellIsMidrangeAndTheClaveIsHighAndShort() {
        val kit = DrumKit(SR)
        val cowbell = kit.samplesOf("cosic808_cb")
        assertTrue(energyBelow(cowbell, 300.0) < 0.05, "the cowbell has no bass")
        assertTrue(energyBelow(cowbell, 6000.0) > 0.7, "the cowbell is band-passed below the hats")
        assertTrue(remainingAt(cowbell, 0.15) < 0.2, "the cowbell decays quickly")

        val clave = kit.samplesOf("cosic808_cl")
        assertTrue(energyBelow(clave, 1000.0) < 0.1, "the clave is a high tone")
        assertTrue(remainingAt(clave, 0.05) < 0.05, "the clave is over within 50 ms")
    }

    @Test
    fun crashesAndRidesAreBrightAndRingForOverASecond() {
        val kit = DrumKit(SR)
        for ((machine, names) in DRUM_MACHINES) {
            for (name in names.filter { it == "cr" || it == "rd" }) {
                val x = kit.samplesOf("${machine}_$name")
                assertTrue(energyBelow(x, 3000.0) < 0.3, "$machine $name should be mostly bright")
                assertTrue(remainingAt(x, 1.0) > 0.1, "$machine $name should still ring after a second")
            }
        }
    }
}
