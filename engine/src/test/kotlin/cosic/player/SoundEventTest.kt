package cosic.player

import cosic.audio.VoiceParams
import cosic.audio.VoiceSource
import cosic.audio.Waveform
import cosic.pattern.ControlMap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SoundEventTest {
    private val source = VoiceSource.Synth(Waveform.SQUARE)

    @Test
    fun everyControlLandsInTheVoiceParameterOfTheSameName() {
        val controls: ControlMap = mapOf(
            "note" to 1.0, "gain" to 2.0, "velocity" to 3.0, "postgain" to 4.0, "pan" to 5.0, "speed" to 6.0,
            "begin" to 7.0, "end" to 8.0, "cut" to 9.0, "clip" to 10.0, "attack" to 11.0, "decay" to 12.0,
            "sustain" to 13.0, "release" to 14.0, "lpf" to 15.0, "lpq" to 16.0, "hpf" to 17.0, "hpq" to 18.0,
            "bpf" to 19.0, "bpq" to 20.0, "shape" to 21.0, "crush" to 22.0, "coarse" to 23.0, "delay" to 24.0,
            "delaytime" to 25.0, "delayfeedback" to 26.0, "room" to 27.0, "size" to 28.0,
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
        val controls: ControlMap = mapOf("gain" to "loud", "lpf" to true)
        assertEquals(VoiceParams(source, 1.0), voiceParams(controls, source, 1.0))
    }

    @Test
    fun aVoiceSendsToTheEffectsOnlyWithAPositiveDelayOrRoomLevel() {
        fun sends(vararg controls: Pair<String, Double>) = voiceParams(mapOf(*controls), source, 1.0).sendsToEffects

        assertFalse(sends())
        assertFalse(sends("delay" to 0.0, "room" to 0.0))
        assertFalse(sends("delay" to Double.NaN, "room" to -1.0))
        assertTrue(sends("delay" to 0.1))
        assertTrue(sends("room" to 0.1))
        assertTrue(sends("room" to 5.0), "the voice clamps this to 1, which still sends")
    }
}
