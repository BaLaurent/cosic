package cosic.player

import cosic.FakeLibrary
import cosic.pattern.ScheduleHook
import cosic.pattern.ScheduledHap
import kotlin.test.Test
import kotlin.test.assertEquals

private const val RATE = 1000 // frames per second: a cycle is 2000 frames at cps 0.5 and 1000 at cps 1

/** A hap whose `cps` control is a tempo sets that tempo for itself and everything after it (cyclist.mjs:80-118). */
class PerHapCpsTest {
    private fun scheduler(code: String, bus: cosic.io.OutputBus = manualBus()): Scheduler =
        schedulerOf(patternOf(code), bus, RATE, FakeLibrary())

    @Test
    fun aHapThatSetsTheTempoMovesTheHapsAfterItAndKeepsItsOwnStart() {
        val scheduler = scheduler("""seq(s("sine").cps(1), s("sine"))""")
        scheduler.play(0)
        scheduler.schedule(2500)

        val events = scheduler.queued()
        // cycle 0: the first at 0, the second half a cycle later, which is 500 frames at cps 1 (1000 at cps 0.5)
        assertEquals(listOf(0L, 500L, 1000L, 1500L, 2000L, 2500L), events.map { it.startFrame }.take(6))
        assertEquals(1.0, scheduler.cps)
        assertEquals(1.0, scheduler.anchor.cps)
    }

    @Test
    fun theLengthOfTheHapThatSetsTheTempoIsMeasuredInTheNewTempo() {
        val scheduler = scheduler("""seq(s("sine").cps(1), s("sine"))""")
        scheduler.play(0)
        scheduler.schedule(500)
        val durations = scheduler.queued().map { it.voice.durationSec }
        // each is half a cycle; at cps 1 that is half a second (it would be a second at the tempo the program started with)
        assertEquals(listOf(0.5, 0.5), durations.take(2))
    }

    @Test
    fun aHapThatSetsTheTempoGoesBeforeTheOthersThatStartAtTheSameTime() {
        // the first of the stack has no tempo and is queried first; the second sets cps 2, so it is routed first and the first is timed by it
        val scheduler = scheduler("""stack(s("sine"), s("sine").cps(2))""")
        scheduler.play(0)
        scheduler.schedule(0)
        val durations = scheduler.queued().map { it.voice.durationSec }
        assertEquals(listOf(0.5, 0.5), durations, "both are a cycle at cps 2, not a cycle at cps 0.5 (2 s) for the one that came first")
    }

    @Test
    fun hapsAreRoutedByTheStartOfTheirWholeWhateverOrderTheQueryGaveThem() {
        val bus = manualBus()
        val seen = ArrayList<Double>()
        // both start in the first chunk; the stack lists the one that starts later first
        val pattern = patternOf("""stack(s("sine").late(1/32), s("sine"))""").onSchedule({ seen.add(it.cycle) }, false)
        val scheduler = Scheduler(RATE, SoundResolver(RATE, FakeLibrary()), bus).also { it.setProgram(pattern, 0.5, emptyList()) }
        scheduler.play(0)
        scheduler.schedule(0)
        bus.drain()
        assertEquals(listOf(0.0, 0.03125), seen)
    }

    @Test
    fun theHooksOfAHapSeeTheTempoItPlaysAt() {
        val bus = manualBus()
        val seen = ArrayList<ScheduledHap>()
        val pattern = patternOf("""seq(s("sine").cps(1), s("sine"))""").onSchedule(ScheduleHook { seen.add(it) }, false)
        val scheduler = Scheduler(RATE, SoundResolver(RATE, FakeLibrary()), bus).also { it.setProgram(pattern, 0.5, emptyList()) }
        scheduler.play(0)
        scheduler.schedule(500)
        bus.drain()
        assertEquals(listOf(1.0, 1.0), seen.take(2).map { it.cps })
        assertEquals(listOf(0L, 500L), seen.take(2).map { it.frame })
    }

    @Test
    fun aCpsThatIsNoTempoIsIgnored() {
        for (code in listOf("""s("sine").cps(0)""", """s("sine").cps(-1)""", """s("sine").cps(1000)""")) {
            val scheduler = scheduler(code)
            scheduler.play(0)
            scheduler.schedule(2500)
            assertEquals(0.5, scheduler.cps, code)
            assertEquals(listOf(0L, 2000L), scheduler.queued().map { it.startFrame }.take(2), code)
        }
    }

    @Test
    fun theTempoOfTheProgramStillWinsWhenItChangesWhileThereIsNoCps() {
        val scheduler = scheduler("""s("sine")""")
        scheduler.play(0)
        scheduler.schedule(0)
        scheduler.setCps(1.0)
        assertEquals(1.0, scheduler.cps)
        assertEquals(1.0, scheduler.anchor.cps)
    }
}
