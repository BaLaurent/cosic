package cosic.script

import kotlin.test.Test
import kotlin.test.assertEquals

/** `Fraction`, `TimeSpan`, `Hap`, `State` and `Pattern` as a program sees and builds them (hap.mjs, timespan.mjs, fraction.mjs, state.mjs). */
class WrapperClassesTest {
    private fun play(code: String): List<String> = shown(evaluateProgram(code).pattern)

    private fun value(code: String): String = play("pure($code)").single().substringAfter("| ").removeSuffix(" ]")

    @Test
    fun aFractionDoesExactArithmetic() {
        assertEquals("3/4", value("Fraction(1, 2).add(Fraction(1, 4)).show()"))
        assertEquals("1/4", value("Fraction(1, 2).sub(0.25).show()"))
        assertEquals("1/6", value("Fraction(1, 3).mul(Fraction(1, 2)).show()"))
        assertEquals("3/2", value("Fraction(1, 2).div(Fraction(1, 3)).show()"))
        assertEquals("1/12", value("Fraction(1, 3).mod(Fraction(1, 4)).show()"))
    }

    @Test
    fun aFractionKnowsItsCyclePositions() {
        assertEquals("2/1", value("Fraction(5, 2).sam().show()"))
        assertEquals("3/1", value("Fraction(5, 2).nextSam().show()"))
        assertEquals("1/2", value("Fraction(5, 2).cyclePos().show()"))
        assertEquals("0/1", value("Fraction(3, 4).floor().show()"))
        assertEquals("1/1", value("Fraction(3, 4).ceil().show()"))
        assertEquals("2/1 → 3/1", value("Fraction(5, 2).wholeCycle().show()"))
    }

    @Test
    fun aFractionComparesAndConvertsToANumber() {
        assertEquals("true", value("Fraction(1, 2).lt(Fraction(2, 3))"))
        assertEquals("false", value("Fraction(1, 2).gte(Fraction(2, 3))"))
        assertEquals("true", value("Fraction(2, 4).equals(Fraction(1, 2))"))
        assertEquals("0.5", value("Fraction(1, 2).valueOf()"))
        assertEquals("1.5", value("Fraction(3, 2) + 0"))
        assertEquals("-1", value("Fraction(-3, 4).s"))
        assertEquals("3", value("Fraction(-3, 4).n"))
        assertEquals("4", value("Fraction(-3, 4).d"))
    }

    @Test
    fun aTimeSpanHasItsBoundsAndCutsAtCycles() {
        assertEquals("1/2", value("new TimeSpan(0, 1).midpoint().show()"))
        assertEquals("0/1 → 3/2", value("new TimeSpan(0, 1.5).show()"))
        assertEquals("3", value("new TimeSpan(0.5, 2.5).spanCycles().length"))
        assertEquals("1/1", value("new TimeSpan(0, 2).intersection(new TimeSpan(1, 3)).begin.show()"))
        assertEquals("undefined", value("new TimeSpan(0, 1).intersection(new TimeSpan(2, 3))"))
        assertEquals("1/2 → 3/2", value("new TimeSpan(0, 1).withTime(t => t.add(0.5)).show()"))
    }

    @Test
    fun aHapCanBeBuiltAndAsked() {
        assertEquals("[ 0/1 → 1/1 | s:bd ]", value("new Hap(new TimeSpan(0, 1), new TimeSpan(0, 1), { s: 'bd' }).show(true)"))
        assertEquals("[ 0/1 ⇜ (1/2 → 1/1) | 1 ]", value("new Hap(new TimeSpan(0, 1), new TimeSpan(0.5, 1), 1).show(true)"))
        assertEquals("true", value("new Hap(new TimeSpan(0, 1), new TimeSpan(0, 1), 1).hasOnset()"))
        assertEquals("false", value("new Hap(new TimeSpan(0, 1), new TimeSpan(0.5, 1), 1).hasOnset()"))
        assertEquals("[ 0/1 → 1/1 | 3 ]", value("new Hap(new TimeSpan(0, 1), new TimeSpan(0, 1), 1).withValue(v => v + 2).show(true)"))
    }

    @Test
    fun aStateHasASpanAndControls() {
        assertEquals("1", value("new State(new TimeSpan(0, 1), { a: 1 }).controls.a"))
        assertEquals("1/1", value("new State(new TimeSpan(0, 1)).setSpan(new TimeSpan(1, 2)).span.begin.show()"))
        assertEquals("2", value("new State(new TimeSpan(0, 1), { a: 1 }).setControls({ b: 2 }).controls.b"))
    }

    @Test
    fun aPatternCanBeBuiltFromAQueryFunction() {
        assertEquals(listOf("[ 0/1 → 1/1 | x ]"), play("new Pattern(state => [new Hap(state.span, state.span, 'x')])"))
        assertEquals(listOf("[ 0/1 → 1/1 | x ]"), play("Pattern(state => [new Hap(state.span, state.span, 'x')])"))
    }

    @Test
    fun filteringKeepsTheHapsTheFunctionLikes() {
        assertEquals(listOf("[ 1/2 → 1/1 | b ]"), play("\"a b\".filterHaps(h => h.part.begin.gt(0))"))
        assertEquals(listOf("[ 0/1 → 1/2 | a ]"), play("\"a b\".filterValues(v => v === 'a')"))
    }

    @Test
    fun theContextOfAHapHoldsTheLocationsOfItsSource() {
        // the string starts at offset 5 of `pure("a b"...)`, so its atoms are at 6 and 8
        assertEquals("6", value("\"a b\".queryArc(0, 1)[0].context.locations[0].start.offset"))
        assertEquals("8", value("\"a b\".queryArc(0, 1)[1].context.locations[0].start.offset"))
    }
}
