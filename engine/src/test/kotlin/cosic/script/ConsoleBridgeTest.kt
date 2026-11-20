package cosic.script

import kotlin.test.Test
import kotlin.test.assertEquals

/** What a program prints with `console.log` for the objects the bridge makes, and how timers and the kernel's logger reach the host. */
class ConsoleBridgeTest {
    private fun lines(code: String): List<String> {
        val session = ReplSession.create()
        session.evaluate(code)
        return session.host.recentLines().map { it.message }
    }

    @Test
    fun aHapPrintsAsItShows() {
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd ]"), lines("""s("bd sd").queryArc(0, 1).slice(0, 1).forEach(h => console.log(h))"""))
    }

    @Test
    fun aFractionAndATimeSpanPrintAsTheyShow() {
        assertEquals(listOf("1/2", "0/1 → 3/2"), lines("console.log(Fraction(1, 2)); console.log(new TimeSpan(0, 1.5))"))
    }

    @Test
    fun aPatternPrintsItsName() {
        assertEquals(listOf("Pattern"), lines("""console.log(s("bd"))"""))
    }

    @Test
    fun theLogOfAPatternReachesTheConsoleWhenItIsPlayed() {
        val session = ReplSession.create()
        val pattern = session.evaluate("""s("bd").log()""").pattern
        val hap = pattern.queryArc(cosic.pattern.Rational.ZERO, cosic.pattern.Rational.ONE).single()
        val hooks = hap.context.hooks!!
        hooks.single().run(cosic.pattern.ScheduledHap(hap, 0L, 0.0, 0.5, 0.0, 1.0))
        assertEquals(listOf("[hap] 0/1 → 1/1: s:bd"), session.host.recentLines().map { it.message })
    }

    @Test
    fun aTimerOfTheProgramRunsAfterTheEvaluation() {
        val session = ReplSession.create()
        session.evaluate("setTimeout(() => console.log('later'), 10)")
        val deadline = System.nanoTime() + 5_000_000_000L
        while (session.host.recentLines().isEmpty() && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(listOf("later"), session.host.recentLines().map { it.message })
    }

    @Test
    fun theConsoleIsMutedWhileTheEnginePlaysAProgramThrough() {
        val session = ReplSession.create()
        session.host.quietly { session.evaluate("console.log('quiet')") }
        assertEquals(emptyList(), session.host.recentLines())
    }
}
