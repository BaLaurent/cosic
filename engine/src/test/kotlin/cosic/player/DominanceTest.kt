package cosic.player

import cosic.FakeLibrary
import cosic.audio.SR
import cosic.pattern.ScheduleHook
import cosic.pattern.ScheduledHap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val START = 2400L // play() starts 50 ms after the frames already rendered, at 48 kHz

/** What happens to a hap that has hooks: they run on the bus, and a dominant one makes the sound unnecessary (`getSchedule` of repl.mjs). */
class DominanceTest {
    private val bus = manualBus()
    private val seen = ArrayList<ScheduledHap>()
    private val hook = ScheduleHook { seen.add(it) }

    private fun play(code: String, make: (cosic.pattern.Pattern) -> cosic.pattern.Pattern = { it }): Scheduler {
        val scheduler = schedulerOf(make(patternOf(code)), bus)
        scheduler.play(START)
        scheduler.schedule(START)
        return scheduler
    }

    @Test
    fun aDominantHookGetsTheHapAndNoSoundIsMade() {
        val scheduler = play("""s("sine*2")""") { it.onSchedule(hook, dominant = true) }
        assertEquals(emptyList(), scheduler.queued().map { it.startFrame }, "no sound")
        assertEquals(0, seen.size, "the hook runs on the bus, not at once")
        bus.drain()
        assertEquals(1, seen.size, "the first of the two haps is due within the look-ahead")
    }

    @Test
    fun aHookThatIsNotDominantRunsAsWellAsTheSound() {
        val scheduler = play("""s("sine*2")""") { it.onSchedule(hook, dominant = false) }
        assertEquals(listOf(START), scheduler.queued().map { it.startFrame })
        bus.drain()
        assertEquals(1, seen.size)
    }

    @Test
    fun twoHooksRunInTheOrderTheyWereAttached() {
        val order = ArrayList<String>()
        play("""s("sine")""") { it.onSchedule({ order.add("first") }, false).onSchedule({ order.add("second") }, false) }
        bus.drain()
        assertEquals(listOf("first", "second"), order)
    }

    @Test
    fun oneDominantHookMakesTheHapDominantForGood() {
        val dominantFirst = play("""s("sine")""") { it.onSchedule(hook, true).onSchedule(hook, false) }
        assertEquals(emptyList(), dominantFirst.queued(), "the second, non-dominant hook does not give the sound back")
        val dominantLast = play("""s("sine")""") { it.onSchedule(hook, false).onSchedule(hook, true) }
        assertEquals(emptyList(), dominantLast.queued())
        bus.drain()
        assertEquals(4, seen.size)
    }

    @Test
    fun onlyTheHapsAHookWasAttachedToAreTaken() {
        // two layers: the first has a dominant hook, the second plays
        val scheduler = play("""s("sine")""") { _ ->
            cosic.pattern.stack(patternOf("""s("sine").gain(0.5)""").onSchedule(hook, true), patternOf("""s("sine").gain(0.25)"""))
        }
        assertEquals(1, scheduler.queued().size, "the second layer sounds")
        bus.drain()
        assertEquals(1, seen.size)
    }

    @Test
    fun hapsWithoutHooksAreNeverPosted() {
        play("""s("sine*4")""")
        assertEquals(0, bus.drain())
    }

    @Test
    fun theHookIsToldWhereAndWhenTheHapSoundsAndHowLong() {
        val scheduler = schedulerOf(patternOf("""s("sine sine")""").onSchedule(hook, false), bus)
        scheduler.play(START)
        scheduler.schedule(90_000)
        bus.drain()
        assertEquals(2, seen.size)
        val first = seen[0]
        assertEquals(START, first.frame)
        assertEquals(START.toDouble() / SR, first.targetTimeSec)
        assertEquals(0.5, first.cps)
        assertEquals(0.0, first.cycle)
        assertEquals(0.5, first.durationCycles)
        val second = seen[1]
        assertEquals(START + 48_000, second.frame)
        assertEquals(0.5, second.cycle)
        assertEquals(cosic.pattern.TimeSpan(cosic.pattern.Rational(1, 2), cosic.pattern.Rational.ONE), second.hap.whole, "the hap is the fragment the scheduler queried, its whole is the half cycle")
    }

    @Test
    fun theDurationOfTheHapIsTheOneStrudelCallsHapDuration() {
        for ((code, expected) in listOf("""s("sine").clip(0.5)""" to 0.5, """s("sine").duration(0.25)""" to 0.25, """s("sine").duration(0.5).clip(0.5)""" to 0.25)) {
            seen.clear()
            val scheduler = schedulerOf(patternOf(code).onSchedule(hook, false), bus)
            scheduler.play(START)
            scheduler.schedule(START)
            bus.drain()
            assertEquals(expected, seen.single().durationCycles, code)
        }
    }

    @Test
    fun theDryRunDoesNotLookAtHapsADominantHookTakesOver() {
        val sounds = SoundResolver(SR, FakeLibrary())
        val taken = patternOf("""s("nope")""").onSchedule(hook, true)
        assertEquals(emptyList(), dryRun(taken, 0, sounds, 0.5, bus))
        val shared = patternOf("""s("nope")""").onSchedule(hook, false)
        assertEquals(listOf("sound nope not found! Is it loaded?"), dryRun(shared, 0, sounds, 0.5, bus))
    }

    @Test
    fun anUnknownSoundOfATakenOverHapIsNotWarnedAboutWhilePlaying() {
        val scheduler = play("""s("nope")""") { it.onSchedule(hook, true) }
        assertEquals(emptyList(), scheduler.warnings)
    }

    @Test
    fun whileEverythingGoesToOscTheSoundIsSilencedAndTheOscOutputGetsTheHaps() {
        val osc = ArrayList<ScheduledHap>()
        bus.oscTarget = true
        bus.oscOutput = ScheduleHook { osc.add(it) }
        val scheduler = play("""s("sine*2")""")
        assertEquals(emptyList(), scheduler.queued())
        bus.drain()
        assertEquals(1, osc.size)
        assertEquals(emptyList(), dryRun(patternOf("""s("nope")"""), 0, SoundResolver(SR, FakeLibrary()), 0.5, bus), "nothing is played, so nothing is looked up")
    }

    @Test
    fun aHapWithADominantHookIsNotSentToOscEither() {
        val osc = ArrayList<ScheduledHap>()
        bus.oscTarget = true
        bus.oscOutput = ScheduleHook { osc.add(it) }
        play("""s("sine")""") { it.onSchedule(hook, true) }
        bus.drain()
        assertEquals(1, seen.size)
        assertEquals(0, osc.size)
    }

    @Test
    fun aHapWithAHookThatIsNotDominantGoesToOscAndTheHook() {
        val osc = ArrayList<ScheduledHap>()
        bus.oscTarget = true
        bus.oscOutput = ScheduleHook { osc.add(it) }
        play("""s("sine")""") { it.onSchedule(hook, false) }
        bus.drain()
        assertEquals(1, seen.size)
        assertEquals(1, osc.size)
    }

    @Test
    fun aHookThatFailsIsReportedOnTheConsoleAndLaterHapsStillRun() {
        var calls = 0
        val scheduler = schedulerOf(
            patternOf("""s("sine*2")""").onSchedule({ if (calls++ == 0) throw IllegalStateException("first one fails") else seen.add(it) }, false),
            bus,
        )
        scheduler.play(START)
        scheduler.schedule(90_000)
        bus.drain()
        assertEquals(1, seen.size)
        assertTrue(bus.console.entries.any { it.message == "[getSchedule] error: first one fails" && it.type == "error" })
        assertEquals(2, scheduler.queued().size, "the sounds are made anyway")
    }
}
