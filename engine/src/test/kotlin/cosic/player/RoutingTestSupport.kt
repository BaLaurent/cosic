package cosic.player

import cosic.FakeLibrary
import cosic.SoundLibrary
import cosic.audio.SR
import cosic.io.OutputBus
import cosic.pattern.Pattern
import cosic.script.evaluateProgram

/** An output bus that runs nothing by itself: a test runs what was posted with `drain()`, on its own thread. */
internal fun manualBus(): OutputBus = OutputBus(autoStart = false)

/** The program [code] as a pattern, evaluated at the default tempo. */
internal fun patternOf(code: String): Pattern = evaluateProgram(code).pattern

/** A scheduler at [rate] playing [pattern] at [cps], posting to [bus]. */
internal fun schedulerOf(
    pattern: Pattern,
    bus: OutputBus = manualBus(),
    rate: Int = SR,
    library: SoundLibrary = FakeLibrary(),
    cps: Double = 0.5,
): Scheduler = Scheduler(rate, SoundResolver(rate, library), bus).also { it.setProgram(pattern, cps, emptyList()) }

/** The events the scheduler has queued, taken off its queue. */
internal fun Scheduler.queued(): List<SoundEvent> = generateSequence { takeDue(Long.MAX_VALUE) }.toList()
