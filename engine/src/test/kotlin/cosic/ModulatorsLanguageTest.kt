package cosic

import cosic.audio.ModKind
import cosic.audio.SR
import cosic.audio.VoiceParams
import cosic.player.Scheduler
import cosic.player.SoundResolver
import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** From code to the modulators of a voice: what `.lfo()`, `.env()` and `.bmod()` write into the control map reaches the engine as [cosic.audio.ModSpec]s. */
class ModulatorsLanguageTest {
    private fun firstVoice(code: String): VoiceParams {
        val program = evaluateProgram(code)
        val scheduler = Scheduler(SR, SoundResolver(SR, FakeLibrary()))
        scheduler.setProgram(program.pattern, program.cps, emptyList())
        scheduler.play(0)
        scheduler.schedule(0)
        return checkNotNull(scheduler.takeDue(Long.MAX_VALUE)) { "no event for $code" }.voice
    }

    @Test
    fun anLfoWithoutAControlModulatesTheControlWrittenJustBeforeIt() {
        val spec = firstVoice("""s("saw").lpf(500).lfo()""").mods.single()
        assertEquals(ModKind.LFO, spec.kind)
        assertEquals("cutoff", spec.control, "lpf is cutoff")
        assertEquals("lpf", spec.target!!.node)
        assertEquals("frequency", spec.target!!.param)
        assertEquals("main", spec.fxi)
    }

    @Test
    fun theConfigKeysAreCanonicalisedAndSplitIntoNumbersAndNames() {
        val spec = firstVoice("""s("saw").lpf(500).lfo({ c: "lpf", r: 4, sh: "sine", dep: 0.5, sk: 0.3 })""").mods.single()
        assertEquals("cutoff", spec.control)
        assertEquals(mapOf("rate" to 4.0, "depth" to 0.5, "skew" to 0.3), spec.numbers)
        assertEquals(mapOf("shape" to "sine"), spec.strings)
    }

    @Test
    fun severalModulatorsKeepTheirOrderAndTheirIds() {
        // the doc example: the first lfo has nothing before it but the sound (s), the second one the cutoff
        val mods = firstVoice("""s("saw").lfo().lpf(500).lfo({ s: 0.3 })""").mods
        assertEquals(listOf("0", "1"), mods.map { it.id })
        assertEquals(listOf("s", "cutoff"), mods.map { it.control })
        assertEquals("source", mods[0].target!!.node)
        assertEquals(mapOf("sync" to 0.3), mods[1].numbers)
    }

    @Test
    fun anEnvelopeAndABusModulatorAreFoundToo() {
        val mods = firstVoice("""s("saw").lpf(500).env({ a: 1, d: 0.2, depth: 3 }).bmod({ b: 1, c: "gain", dc: 0.1 })""").mods
        assertEquals(listOf(ModKind.ENV, ModKind.BMOD), mods.map { it.kind })
        assertEquals(mapOf("attack" to 1.0, "decay" to 0.2, "depth" to 3.0), mods[0].numbers)
        assertEquals("cutoff", mods[0].control)
        assertEquals("gain", mods[1].control)
        assertEquals(mapOf("bus" to 1.0, "dc" to 0.1), mods[1].numbers)
    }

    @Test
    fun aModulatorOfAControlThatCannotBeModulatedHasNoTarget() {
        val spec = firstVoice("""s("saw").lpf(500).lfo({ c: "n" })""").mods.single()
        assertNull(spec.target, "n only chooses a sample: it is no node of the chain")
    }

    @Test
    fun aSubControlTargetsAParameterOfTheModulatorItself() {
        val mods = firstVoice("""s("saw").lpf(500).lfo().lfo({ c: "lfo_0", sc: "depth", r: 2 })""").mods
        assertEquals("lfo_0", mods[1].control)
        assertEquals("depth", mods[1].subControl)
        assertEquals("lfo", mods[1].target!!.node)
        assertEquals("depth", mods[1].target!!.param)
    }
}
