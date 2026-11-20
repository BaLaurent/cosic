package cosic.player

import cosic.audio.VoiceParams
import cosic.audio.VoiceSource
import cosic.audio.Waveform
import cosic.pattern.ControlMap
import kotlin.test.Test
import kotlin.test.assertEquals

class SoundEventTest {
    private val source = VoiceSource.Synth(Waveform.SQUARE)

    @Test
    fun everyControlLandsInTheVoiceParameterOfTheSameName() {
        val controls: ControlMap = mapOf(
            "note" to 1.0, "gain" to 2.0, "velocity" to 3.0, "postgain" to 4.0, "pan" to 5.0, "speed" to 6.0,
            "begin" to 7.0, "end" to 8.0, "cut" to 9.0, "clip" to 10.0, "attack" to 11.0, "decay" to 12.0,
            "sustain" to 13.0, "release" to 14.0, "cutoff" to 15.0, "resonance" to 16.0, "hcutoff" to 17.0, "hresonance" to 18.0,
            "bandf" to 19.0, "bandq" to 20.0, "shape" to 21.0, "crush" to 22.0, "coarse" to 23.0, "delay" to 24.0,
            "delaytime" to 25.0, "delayfeedback" to 26.0, "room" to 27.0, "roomsize" to 28.0,
        )
        val expected = VoiceParams(
            source, 0.75,
            note = 1.0, gain = 2.0, velocity = 3.0, postgain = 4.0, pan = 5.0, speed = 6.0, begin = 7.0, end = 8.0,
            cut = 9.0, clip = 10.0, attack = 11.0, decay = 12.0, sustain = 13.0, release = 14.0, lpf = 15.0, lpq = 16.0,
            hpf = 17.0, hpq = 18.0, bpf = 19.0, bpq = 20.0, shape = 21.0, crush = 22.0, coarse = 23.0, delay = 24.0,
            delaytime = 25.0, delayfeedback = 26.0, room = 27.0, size = 28.0,
        )
        assertEquals(expected, voiceParams(controls, source, 0.75))
    }

    @Test
    fun controlsThatWereNotSetStayNullSoTheVoiceAppliesItsOwnDefaults() {
        assertEquals(VoiceParams(source, 1.5), voiceParams(emptyMap(), source, 1.5))
    }

    @Test
    fun theControlsThatChooseTheSoundAreNotVoiceParameters() {
        val controls: ControlMap = mapOf("s" to "bd", "n" to 3.0, "bank" to "my")
        assertEquals(VoiceParams(source, 1.0), voiceParams(controls, source, 1.0))
    }

    @Test
    fun aValueThatIsNotANumberCountsAsNotSetInsteadOfCrashingTheAudioThread() {
        val controls: ControlMap = mapOf("gain" to "loud", "cutoff" to true)
        assertEquals(VoiceParams(source, 1.0), voiceParams(controls, source, 1.0))
    }

    @Test
    fun theKeysOfCosicBeforeStrudelAreNotRead() {
        val controls: ControlMap = mapOf("lpf" to 500.0, "hpf" to 100.0, "lpq" to 3.0, "size" to 0.5)
        assertEquals(VoiceParams(source, 1.0), voiceParams(controls, source, 1.0))
    }

    @Test
    fun aNoteNameIsConvertedToItsMidiNumberAtVoiceTime() {
        assertEquals(48.0, voiceParams(mapOf("note" to "c"), source, 1.0).note)
        assertEquals(63.0, voiceParams(mapOf("note" to "eb4"), source, 1.0).note)
        assertEquals(60.0, voiceParams(mapOf("note" to 60.0), source, 1.0).note)
        assertEquals("not a note: \"foo\"", kotlin.test.assertFailsWith<cosic.pattern.CosicError> { voiceParams(mapOf("note" to "foo"), source, 1.0) }.message)
    }
}
