package cosic.player

import cosic.FakeLibrary
import cosic.SampleData
import cosic.SoundLibrary
import cosic.TestBank
import cosic.audio.DRUM_MACHINES
import cosic.audio.SR
import cosic.audio.SampleSourceParams
import cosic.audio.VoiceSource
import cosic.audio.Waveform
import cosic.libraryWithBank
import cosic.pattern.ControlMap
import cosic.pattern.CosicError
import cosic.pattern.logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SoundResolverTest {
    private fun sample(value: Float) = SampleData(floatArrayOf(value, value), 44_100)

    private fun resolver(library: SoundLibrary = FakeLibrary()) = SoundResolver(SR, library)

    private fun controls(vararg entries: Pair<String, Any>): ControlMap = mapOf(*entries)

    private fun SoundResolver.source(vararg entries: Pair<String, Any>): VoiceSource =
        assertIs<SoundLookup.Ready>(resolve(controls(*entries))).source

    private fun SoundResolver.sample(vararg entries: Pair<String, Any>): SampleSourceParams =
        assertIs<VoiceSource.Sample>(source(*entries)).params

    @Test
    fun synthNamesResolveToTheirWaveformIncludingTheAliasesInAnyCase() {
        val expected = mapOf(
            "sine" to Waveform.SINE, "sawtooth" to Waveform.SAWTOOTH, "saw" to Waveform.SAWTOOTH,
            "square" to Waveform.SQUARE, "triangle" to Waveform.TRIANGLE, "tri" to Waveform.TRIANGLE,
            "white" to Waveform.WHITE, "pink" to Waveform.PINK, "brown" to Waveform.BROWN,
        )
        for ((name, waveform) in expected) {
            assertEquals(waveform, assertIs<VoiceSource.Synth>(resolver().source("s" to name)).waveform, name)
            assertEquals(waveform, assertIs<VoiceSource.Synth>(resolver().source("s" to name.uppercase())).waveform, name.uppercase())
        }
    }

    @Test
    fun aRegisteredBankBeatsASynthOfTheSameNameBecauseTheLastRegistrationWins() {
        val mine = sample(0.5f)
        assertSame(mine, resolver(libraryWithBank("sine", listOf(mine))).sample("s" to "sine").data)
        assertEquals(Waveform.SQUARE, assertIs<VoiceSource.Synth>(resolver(libraryWithBank("sine", listOf(mine))).source("s" to "square")).waveform)
    }

    @Test
    fun aBankBeatsTheDrumKitOfTheSameName() {
        val mine = sample(0.5f)
        assertSame(mine, resolver(libraryWithBank("bd", listOf(mine))).sample("s" to "bd").data)
    }

    @Test
    fun drumKitSoundsAreUnpitchedSamplesAtTheEngineRate() {
        for (name in listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr")) {
            val params = resolver().sample("s" to name)
            assertEquals(0.0, params.transposeSemitones, name)
            assertEquals(SR, params.data.sampleRate, name)
        }
    }

    @Test
    fun nPicksTheDrumVariantRoundedAndWrappedByFour() {
        val resolver = resolver()
        fun data(n: Double) = resolver.sample("s" to "bd", "n" to n).data
        assertSame(data(1.0), data(5.0))
        assertSame(data(1.0), data(-3.0))
        assertSame(data(0.0), data(4.0))
        assertNotSame(data(0.0), data(1.0))
        assertSame(data(3.0), data(2.9), "n is rounded, as getSoundIndex does")
        assertSame(data(1.0), data(0.6))
    }

    @Test
    fun aDrumSoundIsRepitchedByNoteRelativeToC2() {
        val resolver = resolver()
        assertEquals(12.0, resolver.sample("s" to "bd", "note" to 48.0).transposeSemitones)
        assertEquals(0.0, resolver.sample("s" to "bd", "note" to 36.0).transposeSemitones)
        assertEquals(12.0, resolver.sample("s" to "bd", "note" to "c3").transposeSemitones, "note names count too")
        assertEquals(33.0, resolver.sample("s" to "bd", "freq" to 440.0).transposeSemitones, 1e-9, "freq wins: A4 is 69")
    }

    @Test
    fun drumMachineSoundsAreUnpitchedSamplesAtTheEngineRate() {
        for ((machine, names) in DRUM_MACHINES) {
            for (name in names) {
                val params = resolver().sample("s" to name, "bank" to machine)
                assertEquals(0.0, params.transposeSemitones, "${machine}_$name")
                assertEquals(SR, params.data.sampleRate, "${machine}_$name")
            }
        }
    }

    @Test
    fun theBankControlChoosesTheMachineAndNItsVariantWrappedByFour() {
        val resolver = resolver()
        fun data(machine: String, n: Double) = resolver.sample("s" to "sd", "bank" to machine, "n" to n).data
        assertNotSame(data("cosic808", 0.0), data("cosic909", 0.0))
        assertNotSame(data("cosic808", 0.0), data("cosic808", 1.0))
        assertSame(data("cosic808", 1.0), data("cosic808", 5.0))
        assertSame(data("cosic909", 1.0), data("cosic909", -3.0))
    }

    @Test
    fun aMachineSoundCanBeNamedInFullInsteadOfThroughTheBankControl() {
        val resolver = resolver()
        assertSame(resolver.sample("s" to "bd", "bank" to "cosic808").data, resolver.sample("s" to "cosic808_bd").data)
    }

    @Test
    fun aBankBeatsTheMachineSoundOfTheSameName() {
        val mine = sample(0.5f)
        val library = libraryWithBank("cosic808_bd", listOf(mine))
        assertSame(mine, resolver(library).sample("s" to "bd", "bank" to "cosic808").data)
        assertNotSame(mine, resolver(library).sample("s" to "bd", "bank" to "cosic909").data, "only that machine's sound is replaced")
    }

    @Test
    fun withoutASoundNameEventsPlayTheDefaultTriangle() {
        assertEquals(Waveform.TRIANGLE, assertIs<VoiceSource.Synth>(resolver().source("note" to 60.0)).waveform)
        assertEquals(Waveform.TRIANGLE, assertIs<VoiceSource.Synth>(resolver().source()).waveform)
    }

    @Test
    fun aSoundNameThatIsNoStringPlaysATriangleAndWarns() {
        val log = ArrayList<Pair<String, String?>>()
        val saved = logger
        logger = { message, type, _ -> log += message to type }
        try {
            assertEquals(Waveform.TRIANGLE, assertIs<VoiceSource.Synth>(resolver().source("s" to 808.0)).waveform)
            assertEquals(Waveform.TRIANGLE, assertIs<VoiceSource.Synth>(resolver().source("s" to listOf(1.0, 2.0))).waveform)
        } finally {
            logger = saved
        }
        assertEquals(listOf("getSound: expected string got \"808\". fall back to triangle", "getSound: expected string got \"1,2\". fall back to triangle"), log.map { it.first })
    }

    @Test
    fun theRestNamesAreSilentEvenWithABank() {
        val resolver = resolver()
        for (rest in listOf("-", "~", "_")) {
            assertSame(SoundLookup.Silent, resolver.resolve(controls("s" to rest)), rest)
            assertSame(SoundLookup.Silent, resolver.resolve(controls("s" to rest, "bank" to "cosic808")), "$rest with a bank")
        }
    }

    @Test
    fun theBankControlPrefixesTheSoundNameWhenBothAreTruthy() {
        val mine = sample(0.5f)
        val library = libraryWithBank("my_bd", listOf(mine))
        assertSame(mine, resolver(library).sample("s" to "bd", "bank" to "my").data)
        val plain = resolver(library).sample("s" to "bd").data
        assertNotSame(mine, plain, "without the bank control the drum kit's bd plays")
        assertNotSame(mine, resolver(library).sample("s" to "bd", "bank" to "").data, "an empty bank counts as none")
        assertNotSame(mine, resolver(library).sample("s" to "bd", "bank" to 0.0).data, "so does 0")
    }

    @Test
    fun soundNamesAreFoundInAnyCase() {
        val mine = sample(0.5f)
        val library = libraryWithBank("RolandTR909_bd", listOf(mine))
        assertSame(mine, resolver(library).sample("s" to "BD", "bank" to "rolandtr909").data)
        assertSame(mine, resolver(library).sample("s" to "rolandtr909_BD").data)
    }

    @Test
    fun anUnpitchedBankPicksTheEntryNRoundedAndWrappedByItsCount() {
        val entries = listOf(sample(0.1f), sample(0.2f), sample(0.3f))
        val resolver = resolver(libraryWithBank("kit", entries))
        fun entry(n: Double) = resolver.sample("s" to "kit", "n" to n)
        assertSame(entries[0], entry(0.0).data)
        assertSame(entries[1], entry(4.0).data)
        assertSame(entries[2], entry(-1.0).data)
        assertSame(entries[2], entry(1.5).data, "1.5 rounds to 2")
        assertSame(entries[0], resolver.sample("s" to "kit").data)
        assertEquals(0.0, resolver.sample("s" to "kit").transposeSemitones)
    }

    @Test
    fun anUnpitchedBankIsRepitchedByTheNoteOrFrequencyRelativeToC2() {
        val resolver = resolver(libraryWithBank("kit", listOf(sample(0.1f))))
        assertEquals(12.0, resolver.sample("s" to "kit", "note" to 48.0).transposeSemitones)
        assertEquals(-12.0, resolver.sample("s" to "kit", "note" to 24.0).transposeSemitones)
        assertEquals(24.0, resolver.sample("s" to "kit", "note" to "c4").transposeSemitones)
        assertEquals(0.0, resolver.sample("s" to "kit", "note" to 36.0).transposeSemitones)
        assertEquals(33.0, resolver.sample("s" to "kit", "freq" to 440.0).transposeSemitones, 1e-9)
    }

    @Test
    fun aPitchedBankPicksTheNearestKeyAndRepitchesByTheDistanceToIt() {
        val entries = listOf(sample(0.1f), sample(0.2f), sample(0.3f))
        val resolver = resolver(libraryWithBank("keys", entries, roots = intArrayOf(48, 60, 72)))
        fun pick(note: Double?): SampleSourceParams {
            val controls = mutableMapOf<String, Any>("s" to "keys")
            if (note != null) controls["note"] = note
            return assertIs<VoiceSource.Sample>(assertIs<SoundLookup.Ready>(resolver.resolve(controls)).source).params
        }

        assertSame(entries[1], pick(61.0).data)
        assertEquals(1.0, pick(61.0).transposeSemitones)
        assertSame(entries[2], pick(100.0).data)
        assertEquals(28.0, pick(100.0).transposeSemitones)
        assertSame(entries[0], pick(null).data, "no note means C2, 36: nearest to 48")
        assertEquals(-12.0, pick(null).transposeSemitones)
        assertSame(entries[0], pick(54.0).data, "a tie goes to the first key of the map")
    }

    @Test
    fun aTieBetweenKeysGoesToTheFirstOneInTheOrderOfTheMapNotThePitch() {
        val entries = listOf(sample(0.1f), sample(0.2f))
        val resolver = resolver(libraryWithBank("keys", entries, roots = intArrayOf(60, 48)))
        assertSame(entries[0], assertIs<VoiceSource.Sample>(assertIs<SoundLookup.Ready>(resolver.resolve(controls("s" to "keys", "note" to 54.0))).source).params.data)
    }

    @Test
    fun nChoosesTheFileInsideTheKeyOfAPitchedBank() {
        val entries = listOf(sample(0.1f), sample(0.2f))
        val bank = cosic.SampleBank("pair", listOf(cosic.BankGroup("c3", 48.0, listOf("pair/0", "pair/1"))), null, false)
        val library = FakeLibrary(emptyMap(), mapOf(("pair" to 0) to entries[0], ("pair" to 1) to entries[1])).also { it.register(bank) }
        val resolver = resolver(library)
        assertSame(entries[1], resolver.sample("s" to "pair", "n" to 1.0).data)
        assertSame(entries[0], resolver.sample("s" to "pair", "n" to 2.0).data, "wraps by the number of files of the key")
    }

    @Test
    fun aNoteThatIsNotANoteNameIsAnError() {
        val resolver = resolver(libraryWithBank("kit", listOf(sample(0.1f))))
        assertFailsWith<CosicError> { resolver.resolve(controls("s" to "kit", "note" to "hello")) }
    }

    @Test
    fun aBankWhoseKeyIsNoNoteCannotBePlayed() {
        val bank = cosic.SampleBank("odd", listOf(cosic.BankGroup("c3", 48.0, listOf("odd/0")), cosic.BankGroup("xyz", Double.NaN, listOf("odd/1"))), null, false)
        val library = FakeLibrary(emptyMap(), mapOf(("odd" to 0) to sample(0.1f), ("odd" to 1) to sample(0.2f))).also { it.register(bank) }
        val error = assertFailsWith<CosicError> { resolver(library).resolve(controls("s" to "odd")) }
        assertEquals("not a note: \"xyz\"", error.message)
    }

    @Test
    fun nudgeIsPassedOnToTheSource() {
        val resolver = resolver(libraryWithBank("kit", listOf(sample(0.1f))))
        assertEquals(0.02, resolver.sample("s" to "kit", "nudge" to 0.02).nudgeSeconds)
        assertEquals(0.0, resolver.sample("s" to "kit").nudgeSeconds)
        assertEquals(0.02, resolver.sample("s" to "bd", "nudge" to 0.02).nudgeSeconds, "also for the drum kit")
    }

    @Test
    fun anUnknownSoundIsReportedByItsKey() {
        assertEquals("nope", assertIs<SoundLookup.Unknown>(resolver().resolve(controls("s" to "nope"))).key)
        assertEquals(
            "my_nope",
            assertIs<SoundLookup.Unknown>(resolver().resolve(controls("s" to "nope", "bank" to "my"))).key,
        )
        assertEquals(
            "cosic808_xx",
            assertIs<SoundLookup.Unknown>(resolver().resolve(controls("s" to "xx", "bank" to "cosic808"))).key,
        )
    }

    @Test
    fun aSampleThatIsNotDecodedYetIsLoadingAndHasBeenRequested() {
        val library = FakeLibrary(banks = mapOf("kit" to TestBank(2)))
        val lookup = resolver(library).resolve(controls("s" to "kit", "n" to 1.0))
        assertSame(SoundLookup.Loading, lookup)
        assertEquals(listOf("kit" to 1), library.requested, "asking is what makes the library start decoding")
    }

    @Test
    fun theKeyFormOfLookupServesPreviews() {
        val resolver = resolver()
        assertIs<SoundLookup.Ready>(resolver.resolve("sine", 0.0, 60.0))
        assertIs<SoundLookup.Ready>(resolver.resolve("bd", 2.0, null))
        assertIs<SoundLookup.Ready>(resolver.resolve("cosic909_bd", 2.0, null))
        assertIs<SoundLookup.Unknown>(resolver.resolve("nope", 0.0, null))
        assertEquals(24.0, assertIs<VoiceSource.Sample>(assertIs<SoundLookup.Ready>(resolver.resolve("bd", 0.0, 60.0)).source).params.transposeSemitones)
    }
}
