package cosic.player

import cosic.BankInfo
import cosic.FakeLibrary
import cosic.SampleData
import cosic.SoundLibrary
import cosic.audio.SR
import cosic.audio.VoiceSource
import cosic.audio.Waveform
import cosic.libraryWithBank
import cosic.pattern.ControlMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

class SoundResolverTest {
    private fun sample(value: Float) = SampleData(floatArrayOf(value, value), 44_100)

    private fun resolver(library: SoundLibrary = FakeLibrary()) = SoundResolver(SR, library)

    private fun controls(vararg entries: Pair<String, Any>): ControlMap = mapOf(*entries)

    private fun SoundResolver.source(vararg entries: Pair<String, Any>): VoiceSource =
        assertIs<SoundLookup.Ready>(resolve(controls(*entries))).source

    @Test
    fun synthNamesResolveToTheirWaveformIncludingTheAliases() {
        val expected = mapOf(
            "sine" to Waveform.SINE, "sawtooth" to Waveform.SAWTOOTH, "saw" to Waveform.SAWTOOTH,
            "square" to Waveform.SQUARE, "triangle" to Waveform.TRIANGLE, "tri" to Waveform.TRIANGLE,
            "white" to Waveform.WHITE, "pink" to Waveform.PINK, "brown" to Waveform.BROWN,
        )
        for ((name, waveform) in expected) {
            assertEquals(waveform, assertIs<VoiceSource.Synth>(resolver().source("s" to name)).waveform, name)
        }
    }

    @Test
    fun aSynthNameBeatsABankOfTheSameName() {
        val library = libraryWithBank("sine", listOf(sample(0.5f)))
        assertIs<VoiceSource.Synth>(resolver(library).source("s" to "sine"))
        assertEquals(emptyList(), library.requested, "the bank is not even consulted for its samples")
    }

    @Test
    fun aBankBeatsTheDrumKitOfTheSameName() {
        val mine = sample(0.5f)
        val source = assertIs<VoiceSource.Sample>(resolver(libraryWithBank("bd", listOf(mine))).source("s" to "bd"))
        assertSame(mine, source.data)
    }

    @Test
    fun drumKitSoundsAreUnpitchedSamplesAtTheEngineRate() {
        for (name in listOf("bd", "sd", "hh", "oh", "cp", "rim", "lt", "mt", "ht", "cr")) {
            val source = assertIs<VoiceSource.Sample>(resolver().source("s" to name), name)
            assertNull(source.rootNote, name)
            assertEquals(SR, source.data.sampleRate, name)
        }
    }

    @Test
    fun nSelectsTheDrumVariantWrappedByFour() {
        val resolver = resolver()
        fun data(n: Double) = assertIs<VoiceSource.Sample>(resolver.source("s" to "bd", "n" to n)).data
        assertSame(data(1.0), data(5.0))
        assertSame(data(1.0), data(-3.0))
        assertSame(data(0.0), data(4.0))
        assertNotSame(data(0.0), data(1.0))
        assertSame(data(2.0), data(2.9), "n counts whole numbers")
    }

    @Test
    fun withoutASoundNameEventsPlayTheDefaultTriangle() {
        assertEquals(Waveform.TRIANGLE, assertIs<VoiceSource.Synth>(resolver().source("note" to 60.0)).waveform)
        assertEquals(Waveform.TRIANGLE, assertIs<VoiceSource.Synth>(resolver().source()).waveform)
    }

    @Test
    fun theBankControlPrefixesTheSoundName() {
        val mine = sample(0.5f)
        val library = libraryWithBank("my_bd", listOf(mine))
        assertSame(mine, assertIs<VoiceSource.Sample>(resolver(library).source("s" to "bd", "bank" to "my")).data)
        val plain = assertIs<VoiceSource.Sample>(resolver(library).source("s" to "bd")).data
        assertNotSame(mine, plain, "without the bank control the drum kit's bd plays")
    }

    @Test
    fun anUnpitchedBankPicksTheEntryNWrappedByItsCount() {
        val entries = listOf(sample(0.1f), sample(0.2f), sample(0.3f))
        val resolver = resolver(libraryWithBank("kit", entries))
        fun entry(n: Double) = assertIs<VoiceSource.Sample>(resolver.source("s" to "kit", "n" to n)).data
        assertSame(entries[0], entry(0.0))
        assertSame(entries[1], entry(4.0))
        assertSame(entries[2], entry(-1.0))
        assertNull(assertIs<VoiceSource.Sample>(resolver.source("s" to "kit")).rootNote)
    }

    @Test
    fun aPitchedBankPicksTheNearestRootAndPassesItOn() {
        val entries = listOf(sample(0.1f), sample(0.2f), sample(0.3f))
        val resolver = resolver(libraryWithBank("keys", entries, roots = intArrayOf(48, 60, 72)))
        fun pick(note: Double?, n: Double = 0.0): VoiceSource.Sample {
            val controls = mutableMapOf<String, Any>("s" to "keys", "n" to n)
            if (note != null) controls["note"] = note
            val lookup = assertIs<SoundLookup.Ready>(resolver.resolve(controls))
            return assertIs(lookup.source)
        }

        assertSame(entries[1], pick(61.0).data)
        assertEquals(60, pick(61.0).rootNote)
        assertSame(entries[2], pick(100.0).data)
        assertEquals(72, pick(100.0).rootNote)
        assertEquals(48, pick(null).rootNote, "no note means note 48")
        assertSame(entries[0], pick(48.0, n = 2.0).data, "n is ignored by a pitched bank")
    }

    @Test
    fun anUnknownSoundIsReportedByItsKey() {
        assertEquals("nope", assertIs<SoundLookup.Unknown>(resolver().resolve(controls("s" to "nope"))).key)
        assertEquals(
            "my_nope",
            assertIs<SoundLookup.Unknown>(resolver().resolve(controls("s" to "nope", "bank" to "my"))).key,
        )
    }

    @Test
    fun aSampleThatIsNotDecodedYetIsLoadingAndHasBeenRequested() {
        val library = FakeLibrary(banks = mapOf("kit" to BankInfo("kit", 2, null)))
        val lookup = resolver(library).resolve(controls("s" to "kit", "n" to 1.0))
        assertSame(SoundLookup.Loading, lookup)
        assertEquals(listOf("kit" to 1), library.requested, "asking is what makes the library start decoding")
    }

    @Test
    fun theKeyFormOfLookupServesPreviews() {
        val resolver = resolver()
        assertIs<SoundLookup.Ready>(resolver.resolve("sine", 0.0, 60.0))
        assertIs<SoundLookup.Ready>(resolver.resolve("bd", 2.0, null))
        assertIs<SoundLookup.Unknown>(resolver.resolve("nope", 0.0, null))
    }
}
