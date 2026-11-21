package cosic.audio

import cosic.pattern.CosicError
import cosic.pattern.Undefined
import cosic.pattern.jsFn
import cosic.player.voiceParams
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a program changes about the engine (`setMaxPolyphony`, `setGainCurve`, the default values...) and what the voices make of it. */
class AudioSettingsTest {
    private val square = jsFn(1) { a -> (a[0] as Double) * (a[0] as Double) }

    private fun params(settings: AudioSettings?, controls: Map<String, Any?> = emptyMap()) =
        voiceParams(controls, synth("sine", 1.0, controls), 1.0, 0.5, 0.0, settings = settings)

    // ---- the default values (superdough.mjs:185-241) ------------------------------------------------------------------------------

    @Test
    fun theFactoryDefaultsAreSuperdoughs() {
        val s = AudioSettings()
        val expected = mapOf(
            "s" to "triangle", "gain" to 0.8, "postgain" to 1.0, "density" to ".03", "channels" to listOf(1.0, 2.0), "phaserdepth" to 0.75, "shapevol" to 1.0,
            "distortvol" to 1.0, "distorttype" to 0.0, "delay" to 0.0, "busgain" to 1.0, "byteBeatExpression" to "0", "delayfeedback" to 0.5, "delaysync" to 3.0 / 16.0,
            "orbit" to 1.0, "i" to 1.0, "velocity" to 1.0, "fft" to 8.0, "tremolodepth" to 1.0, "tremolophase" to 0.0, "release" to 0.01,
        )
        for ((key, value) in expected) assertEquals(value, s.getDefaultValue(key), key)
        assertEquals(Undefined, s.getDefaultValue("fanchor"), "a key that was never set is undefined")
        assertTrue(s.current.pristine)
    }

    @Test
    fun setDefaultValueIsLiveUntilTheValuesAreReset() {
        val s = AudioSettings()
        s.setDefaultValue("gain", 1.0)
        s.setDefaultValue("myKey", "x")
        assertEquals(1.0, s.getDefaultValue("gain"))
        assertEquals("x", s.getDefaultValue("myKey"))
        assertFalse(s.current.pristine)
        s.resetDefaultValues()
        assertEquals(0.8, s.getDefaultValue("gain"))
        assertEquals(Undefined, s.getDefaultValue("myKey"))
        assertTrue(s.current.pristine)
    }

    @Test
    fun setDefaultChangesTheBaseValuesAResetReturnsTo() {
        val s = AudioSettings()
        s.setDefault("gain", 0.4)
        assertEquals(0.8, s.getDefaultValue("gain"), "the live value follows the base only after a reset")
        s.resetDefaultValues()
        assertEquals(0.4, s.getDefaultValue("gain"))
        s.setDefaultValue("gain", 2.0)
        s.resetDefaults()
        assertEquals(2.0, s.getDefaultValue("gain"), "resetDefaults puts the base values back and leaves the live ones")
        s.resetDefaultValues()
        assertEquals(0.8, s.getDefaultValue("gain"))
    }

    @Test
    fun setDefaultValuesSetsEveryKey() {
        val s = AudioSettings()
        s.setDefaultValues(linkedMapOf("gain" to 0.1, "velocity" to 0.2))
        assertEquals(0.1, s.getDefaultValue("gain"))
        assertEquals(0.2, s.getDefaultValue("velocity"))
        assertEquals(1.0, s.getDefaultValue("postgain"), "the keys that are not named stay")
    }

    @Test
    fun aMapKeyIsComparedLikeJavaScriptDoes() {
        val s = AudioSettings()
        s.setDefaultValue(-0.0, "zero")
        assertEquals("zero", s.getDefaultValue(0.0), "-0 and 0 are the same key")
        assertEquals(Undefined, s.getDefaultValue("0"), "a number is not the string of it")
    }

    @Test
    fun setVersionDefaultsResetsAndAddsFanchorForVersionOnePointZero() {
        val s = AudioSettings()
        s.setDefaultValue("gain", 3.0)
        s.setVersionDefaults("1.0")
        assertEquals(0.8, s.getDefaultValue("gain"), "the live values are reset first")
        assertEquals(0.5, s.getDefaultValue("fanchor"))
        s.setVersionDefaults("2.0")
        assertEquals(Undefined, s.getDefaultValue("fanchor"))
        s.setVersionDefaults(1.0)
        assertEquals(Undefined, s.getDefaultValue("fanchor"), "only the string `1.0`")
        s.setVersionDefaults(null)
        assertTrue(s.current.pristine)
    }

    // ---- polyphony and orbits -----------------------------------------------------------------------------------------------------

    @Test
    fun maxPolyphonyIsParseIntHeldToWhatThePoolCanSound() {
        val s = AudioSettings()
        assertEquals(128, s.current.voiceLimit)
        s.setMaxPolyphony(4.0)
        assertEquals(4, s.current.voiceLimit)
        s.setMaxPolyphony("6 voices")
        assertEquals(6, s.current.voiceLimit, "parseInt reads the digits it finds")
        s.setMaxPolyphony(3.9)
        assertEquals(3, s.current.voiceLimit)
        s.setMaxPolyphony(0.0)
        assertEquals(1, s.current.voiceLimit)
        s.setMaxPolyphony(1000.0)
        assertEquals(MAX_POLYPHONY_LIMIT, s.current.voiceLimit)
        s.setMaxPolyphony(Undefined)
        assertTrue(s.current.maxPolyphony.isNaN(), "`parseInt(undefined) ?? 128` is NaN, which no voice count is above: nothing is stolen")
        assertEquals(MAX_POLYPHONY_LIMIT, s.current.voiceLimit)
        assertTrue(s.current.pristine, "polyphony does not change what a voice is made of")
    }

    @Test
    fun multiChannelOrbitsIsLooseEqualityWithTrue() {
        val s = AudioSettings()
        for (on in listOf(true, 1.0, "1")) {
            s.setMultiChannelOrbits(on)
            assertTrue(s.current.multiChannelOrbits, "$on == true")
        }
        for (off in listOf(false, 0.0, "true", null, Undefined, 2.0)) {
            s.setMultiChannelOrbits(off)
            assertFalse(s.current.multiChannelOrbits, "$off == true")
        }
    }

    @Test
    fun everyChangeIsToldAndResetForgetsAll() {
        val s = AudioSettings()
        var told = 0
        s.onChange = { told++ }
        s.setMaxPolyphony(2.0)
        s.setMultiChannelOrbits(true)
        s.setGainCurve(square)
        s.setDefaultValue("gain", 1.0)
        assertEquals(4, told)
        s.reset()
        assertEquals(5, told)
        assertTrue(s.current.pristine)
        assertEquals(128, s.current.voiceLimit)
        assertFalse(s.current.multiChannelOrbits)
        s.reset()
        assertEquals(5, told, "nothing changed")
    }

    // ---- what voices make of them ---------------------------------------------------------------------------------------------------

    @Test
    fun withTheFactorySettingsAVoiceIsBuiltAsBefore() {
        for (settings in listOf(null, AudioSettings())) {
            val p = params(settings, mapOf("room" to 0.3))
            assertTrue(p.main[Param.gain].isNaN() && p.main[Param.orbit].isNaN() && p.main[Param.delayfeedback].isNaN(), "the stages' own defaults apply")
            assertEquals(DEFAULT_VOICE_RELEASE, p.defaultRelease)
        }
    }

    @Test
    fun aGainCurveWorksOnEveryGainOfEveryChain() {
        val s = AudioSettings()
        s.setGainCurve(square)
        val controls = mapOf(
            "gain" to 0.5, "postgain" to 2.0, "delay" to 0.5, "busgain" to 0.5, "dry" to 0.5, "shapevol" to 0.5, "distortvol" to 0.5, "tremolodepth" to 0.5,
            "FX" to listOf(mapOf("gain" to 0.5, "delay" to 0.5, "velocity" to 0.5)),
        )
        val p = params(s, controls)
        val main = p.main
        assertEquals(0.25, main[Param.gain])
        assertEquals(4.0, main[Param.postgain])
        assertEquals(0.25, main[Param.delay], "the delay of the event is curved")
        assertEquals(0.25, main[Param.busgain])
        assertEquals(0.25, main[Param.dry])
        assertEquals(0.25, main[Param.shapevol])
        assertEquals(0.25, main[Param.distortvol])
        assertEquals(0.25, main[Param.tremolodepth])
        assertEquals(1.0, main[Param.velocity], "an unset velocity is the default 1, curved")
        val element = p.fx.single()
        assertEquals(0.25, element[Param.gain])
        assertEquals(0.25, element[Param.velocity])
        assertEquals(0.5, element[Param.delay], "the delay of an FX element is not curved")
        assertTrue(element[Param.postgain].isNaN() && element[Param.dry].isNaN(), "postgain, busgain and dry belong to the event")
    }

    @Test
    fun aGainThatIsNotSetIsTheDefaultCurvedAndOneThatIsNoNumberIsOne() {
        val s = AudioSettings()
        s.setGainCurve(square)
        assertEquals(0.8 * 0.8, params(s)[Param.gain])
        assertEquals(1.0, params(s, mapOf("gain" to "loud"))[Param.gain], "nanFallback(gain, 1), then the curve")
        assertTrue(params(s)[Param.dry].isNaN(), "dry has no default: a curve only touches it when it is set")
        assertEquals(0.0, params(s)[Param.delay], "curve(0)")
        s.setGainCurve(jsFn(1) { a -> (a[0] as Double) + 0.5 })
        assertEquals(0.5, params(s)[Param.delay], "even the delay nobody asked for is the curve of its default")
    }

    @Test
    fun aGainCurveThatIsNoFunctionFailsTheEventsThatUseIt() {
        val s = AudioSettings()
        s.setGainCurve(5.0)
        assertEquals("gainCurveFunc is not a function", assertFailsWith<CosicError> { params(s) }.message)
        s.setGainCurve(Undefined)
        assertFailsWith<CosicError> { params(s) }
        s.setGainCurve(null)
        assertFailsWith<CosicError> { params(s) }
    }

    @Test
    fun theDefaultValuesFillTheControlsAnEventLeavesUnset() {
        val s = AudioSettings()
        s.setDefaultValues(
            mapOf(
                "gain" to 1.0, "velocity" to 0.5, "postgain" to 3.0, "orbit" to 4.0, "i" to 7.0, "delayfeedback" to 0.9, "delaysync" to 0.25, "phaserdepth" to 0.1,
                "shapevol" to 0.2, "distortvol" to 0.3, "tremolodepth" to 0.4, "busgain" to 0.6, "delay" to 0.7, "release" to 0.5,
            ),
        )
        val p = params(s, mapOf("FX" to listOf(emptyMap<String, Any?>())))
        val main = p.main
        assertEquals(1.0, main[Param.gain])
        assertEquals(0.5, main[Param.velocity])
        assertEquals(3.0, main[Param.postgain])
        assertEquals(4.0, main[Param.orbit])
        assertEquals(7.0, main[Param.i])
        assertEquals(0.9, main[Param.delayfeedback])
        assertEquals(0.25, main[Param.delaysync])
        assertEquals(0.1, main[Param.phaserdepth])
        assertEquals(0.2, main[Param.shapevol])
        assertEquals(0.3, main[Param.distortvol])
        assertEquals(0.4, main[Param.tremolodepth])
        assertEquals(0.6, main[Param.busgain])
        assertEquals(0.7, main[Param.delay])
        assertEquals(0.5, p.defaultRelease)
        assertTrue(main[Param.release].isNaN(), "the release of the envelopes is the source's own: only the life of the chain takes the default")
        val element = p.fx.single()
        assertEquals(1.0, element[Param.gain], "an element has its own gain, and the default fills it")
        assertEquals(0.7, element[Param.delay])
        assertTrue(element[Param.orbit].isNaN() && element[Param.i].isNaN() && element[Param.postgain].isNaN(), "the event alone has an orbit, a bus and a post gain")
    }

    @Test
    fun anEventsOwnControlsBeatTheDefaults() {
        val s = AudioSettings()
        s.setDefaultValues(mapOf("gain" to 1.0, "orbit" to 4.0, "phaserdepth" to 0.1))
        val main = params(s, mapOf("gain" to 0.3, "orbit" to 2.0, "phaserdepth" to 0.9)).main
        assertEquals(0.3, main[Param.gain])
        assertEquals(2.0, main[Param.orbit])
        assertEquals(0.9, main[Param.phaserdepth])
    }

    @Test
    fun aDefaultThatIsNoNumberFillsNothingExceptGainWhichIsOne() {
        val s = AudioSettings()
        s.setDefaultValues(mapOf("gain" to "loud", "orbit" to "two", "delaysync" to null))
        val main = params(s).main
        assertEquals(1.0, main[Param.gain])
        assertTrue(main[Param.orbit].isNaN() && main[Param.delaysync].isNaN())
    }

    @Test
    fun theDefaultDistortionTypeCanBeAName() {
        val s = AudioSettings()
        s.setDefaultValue("distorttype", "fold")
        assertEquals("fold", params(s).main.str("distorttype"))
        assertEquals("hard", params(s, mapOf("distorttype" to "hard")).main.str("distorttype"))
        assertNull(params(s, mapOf("distorttype" to 2.0)).main.str("distorttype"), "a number the event sets is used")
        s.setDefaultValue("distorttype", 3.0)
        assertEquals(3.0, params(s).main[Param.distorttype])
    }

    @Test
    fun theDefaultChannelsApplyUnlessTheEventOrAMultiChannelOrbitDecides() {
        val s = AudioSettings()
        s.setDefaultValue("channels", listOf(3.0, 4.0))
        assertContentEquals(doubleArrayOf(3.0, 4.0), assertNotNull(params(s).main.list("channels")))
        assertContentEquals(doubleArrayOf(5.0, 6.0), assertNotNull(params(s, mapOf("channels" to listOf(5.0, 6.0))).main.list("channels")), "the event's own")
        s.setMultiChannelOrbits(true)
        assertNull(params(s).main.list("channels"), "`multiChannelOrbits && orbit > 0` gives the orbit its pair of channels instead")
        assertNotNull(params(s, mapOf("orbit" to 0.0)).main.list("channels"), "orbit 0 is not multi-channel")
        s.setDefaultValue("channels", listOf(1.0, 2.0))
        s.setMultiChannelOrbits(false)
        assertNull(params(s, mapOf("gain" to 0.5)).main.list("channels"), "[1, 2] is what the mixer does anyway")
    }

    @Test
    fun theSettingsNeedNoEventOfTheirOwnForAPlayingVoiceToChange() {
        val s = AudioSettings()
        val before = params(s)
        s.setGainCurve(square)
        assertTrue(before.main[Param.gain].isNaN(), "a voice already built is not touched")
        assertEquals(0.64, params(s)[Param.gain], 1e-12)
    }

    private operator fun VoiceParams.get(p: Param): Double = main[p]
}
