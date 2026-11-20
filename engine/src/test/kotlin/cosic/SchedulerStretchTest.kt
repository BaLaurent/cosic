package cosic

import cosic.audio.ChainDemand
import cosic.audio.SR
import cosic.pattern.Pattern
import cosic.pattern.pure
import cosic.pattern.stack
import cosic.player.Scheduler
import cosic.player.SoundEvent
import cosic.player.SoundResolver
import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val CYCLE = 96_000L // frames per cycle at cps 0.5 and 48 kHz
private const val START = 2400L // play() starts 50 ms after the frames already rendered
private const val LEAD = 2048L // the streaming phase vocoder's latency

private fun schedulerOf(pattern: Pattern): Scheduler =
    Scheduler(SR, SoundResolver(SR, FakeLibrary())).also { it.setProgram(pattern, 0.5, emptyList()) }

private fun schedulerOf(code: String): Scheduler = schedulerOf(evaluateProgram(code).pattern)

private fun Scheduler.drain(): List<SoundEvent> = generateSequence { takeDue(Long.MAX_VALUE) }.toList()

/** What superdough does for `stretch`: the whole event starts early by the latency of the vocoder, so that the sound comes out on time. */
class SchedulerStretchTest {
    @Test
    fun anEventWithStretchStartsOneVocoderLatencyBeforeItsOnset() {
        val scheduler = schedulerOf("""s("sine*4").stretch(1)""")
        scheduler.play(START)
        scheduler.schedule(START + CYCLE)
        assertEquals(
            listOf(2400L, 26_400, 50_400, 74_400, 98_400).map { it - LEAD },
            scheduler.drain().map { it.startFrame }.take(5),
        )
    }

    @Test
    fun stretchZeroShiftsToo() {
        val scheduler = schedulerOf("""s("sine").stretch(0)""")
        scheduler.play(START)
        scheduler.schedule(START)
        assertEquals(listOf(START - LEAD), scheduler.drain().map { it.startFrame })
    }

    @Test
    fun anEventWithoutStretchIsNotShifted() {
        val scheduler = schedulerOf("""s("sine*4")""")
        scheduler.play(START)
        scheduler.schedule(START + CYCLE)
        assertEquals(listOf(2400L, 26_400, 50_400, 74_400), scheduler.drain().map { it.startFrame }.take(4))
    }

    @Test
    fun eventsStayInStartOrderWhenOnlySomeStartEarlyAndTheyComeFromDifferentChunks() {
        // chunks are 1/16 cycle = 6000 frames; the sines are 1500 frames apart, so an early event of one chunk starts before the last sines of the chunk before it
        val scheduler = schedulerOf("""stack(s("sine*64"), s("square*16").stretch(1))""")
        scheduler.play(START)
        scheduler.schedule(START + CYCLE)
        val starts = scheduler.drain().map { it.startFrame }
        assertEquals(starts.sorted(), starts, "the queue is in order of start frame")
        assertTrue(starts.contains(START + 6000 - LEAD), "the early start of the first square of the second chunk is in")
        val sines = starts.count { (it - START) % 1500 == 0L }
        assertTrue(sines >= 64, "every sine is there as well")
    }

    @Test
    fun theHighlightOfAnEventWithStretchStaysWhereItIsHeard() {
        val scheduler = schedulerOf("""s("sine*4").stretch(1)""")
        scheduler.play(START)
        scheduler.schedule(START)
        val highlights = scheduler.highlights.snapshot()
        assertEquals(START, highlights.first().startFrame, "the voice starts early, the sound (and the highlight) is on the onset")
    }

    // ------------------------------------------------------------------ what the queued events will take from the mixer's pools

    private fun fxEvent(chains: Int, stretch: Boolean = false, fxStretch: Boolean = false): Pattern {
        val fx = List(chains) { i -> if (fxStretch && i == 0) mapOf("gain" to 0.5, "stretch" to 1.0) else mapOf("gain" to 0.5) }
        val controls = LinkedHashMap<String, Any?>()
        controls["s"] = "sine"
        if (stretch) controls["stretch"] = 1.0
        controls["FX"] = fx
        return pure(controls)
    }

    @Test
    fun theQueuedEventsReportTheChainsAndVocodersTheyWillTake() {
        val scheduler = schedulerOf(stack(fxEvent(3), fxEvent(2, stretch = true), fxEvent(1, fxStretch = true), pure(mapOf("s" to "sine"))))
        scheduler.play(START)
        scheduler.schedule(START)
        val demand = ChainDemand()
        scheduler.addQueuedDemand(demand)
        assertEquals(3 + 2 + 1, demand.fxChains)
        assertEquals(2, demand.vocoders, "the stretch of the second event and the FX element of the third")
        demand.clear()
        scheduler.drain()
        scheduler.addQueuedDemand(demand)
        assertEquals(0, demand.fxChains, "once the events have started the voices hold their chains and the demand is gone")
    }
}
