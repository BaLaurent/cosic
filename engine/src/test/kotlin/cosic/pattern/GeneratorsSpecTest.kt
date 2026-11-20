package cosic.pattern

import cosic.script.catalogue.Catalogue
import cosic.script.evaluateProgram
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The generators and small combinators (`run`, `binary` and its relatives, `base`, `xfade`, `beat`, `morph`, `parray`,
 * `partials`, `phases`, `FX`, `collect`, `hsla`, `hsl`) against the lines Strudel prints for them: its snapshots, and what its
 * own runtime says for the calls that have no example (parity/extra-snippets/2.4.json), and the JavaScript they are written in.
 */
class GeneratorsSpecTest {
    init {
        Catalogue.installAll()
    }

    private fun lines(code: String, cycles: Long = 1): List<String> = evaluateProgram(code).pattern.lines(0, cycles)

    private fun values(pattern: Pattern): List<Any?> = pattern.firstCycle().sortedBy { it.part.begin }.map { it.value }

    private fun numbers(vararg xs: Double): List<Any?> = xs.toList()

    // ---- run ----------------------------------------------------------------------------------------------------------------

    @Test
    fun runCountsUpFromZero() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | n:0 ]", "[ 1/4 → 1/2 | n:1 ]", "[ 1/2 → 3/4 | n:2 ]", "[ 3/4 → 1/1 | n:3 ]"),
            lines("n(run(4))"),
        )
        assertEquals(numbers(0.0, 1.0, 2.0, 3.0), values(GeneratorFunctions.run(4.0)))
    }

    @Test
    fun runTakesAPatternOfCounts() {
        assertEquals(
            listOf("[ 1/1 → 9/8 | n:0 ]", "[ 9/8 → 5/4 | n:1 ]"),
            lines("n(run(\"<4 8>\"))", 2).drop(4).take(2),
        )
        assertEquals(
            listOf("[ 0/1 → 1/4 | n:0 ]", "[ 1/4 → 1/2 | n:1 ]", "[ 1/2 → 5/8 | n:4 ]", "[ 5/8 → 3/4 | n:5 ]", "[ 3/4 → 7/8 | n:6 ]", "[ 7/8 → 1/1 | n:7 ]"),
            lines("n(run(\"4 8\"))"),
        )
    }

    @Test
    fun runOfANumberThatIsNotWholeKeepsTheLastEventOpen() {
        assertEquals(
            listOf("[ 0/1 → 2/7 | n:0 ]", "[ 2/7 → 4/7 | n:1 ]", "[ 4/7 → 6/7 | n:2 ]", "[ (6/7 → 1/1) ⇝ 8/7 | n:3 ]"),
            lines("n(run(3.5))"),
        )
        assertEquals(emptyList(), lines("n(run(0))"))
        assertEquals(listOf("[ 0/1 → 1/1 | n:0 ]", "[ 1/1 → 2/1 | n:0 ]"), lines("n(run(1))", 2))
    }

    // ---- binary ---------------------------------------------------------------------------------------------------------------

    @Test
    fun binaryIsTheBitsOfTheNumberMostSignificantFirst() {
        assertEquals(numbers(1.0, 0.0, 1.0), values(GeneratorFunctions.binary(5.0)))
        assertEquals(numbers(1.0, 0.0, 1.0, 0.0), values(GeneratorFunctions.binary(10.0)))
        assertEquals(listOf("[ 0/1 → 1/3 | s:hh ]", "[ 2/3 → 1/1 | s:hh ]"), lines("s(\"hh\").struct(binary(5))"))
        // one bit is one event of the whole cycle
        assertEquals(listOf("[ 0/1 → 1/1 | s:hh ]", "[ 1/1 → 2/1 | s:hh ]"), lines("s(\"hh\").struct(binary(1))", 2))
    }

    @Test
    fun binaryTakesAPatternOfNumbers() {
        assertEquals(listOf("[ 0/1 → 1/3 | s:hh ]", "[ 2/3 → 1/1 | s:hh ]"), lines("s(\"hh\").struct(binary(\"<5 6 7>\"))", 3).take(2))
        assertEquals(listOf("[ 0/1 → 1/3 | s:hh ]"), lines("s(\"hh\").struct(binary(\"5 8\"))"))
    }

    @Test
    fun binaryNPadsToTheBitsGiven() {
        assertEquals(numbers(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0), values(GeneratorFunctions.binaryN(5.0, 8.0)))
        assertEquals(numbers(1.0, 1.0, 1.0, 1.0), values(GeneratorFunctions.binaryN(255.0, 4.0)))
        // sixteen bits by default
        assertEquals(numbers(*DoubleArray(13), 1.0, 0.0, 1.0), values(GeneratorFunctions.binaryN(5.0)))
        assertEquals(numbers(*DoubleArray(13), 1.0, 0.0, 1.0), values(GeneratorFunctions.binaryN(5.0, Undefined)))
    }

    @Test
    fun binaryNTakesAPatternOfBits() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | n:0 ]", "[ 1/4 → 1/2 | n:1 ]", "[ 1/2 → 3/4 | n:0 ]", "[ 3/4 → 1/1 | n:1 ]"),
            lines("n(binaryN(5, 4))"),
        )
        assertEquals(12, lines("n(binaryN(5, \"<4 8>\"))", 2).size)
    }

    @Test
    fun binaryLIsTheBitsAsOneList() {
        assertEquals(listOf(numbers(1.0, 0.0, 1.0)), values(GeneratorFunctions.binaryL(5.0)))
        assertEquals(listOf(numbers(0.0, 0.0, 0.0, 0.0, 0.0, 1.0, 0.0, 1.0)), values(GeneratorFunctions.binaryNL(5.0, 8.0)))
        assertEquals(listOf("[ 0/1 → 1/1 | s:saw partials:[1 0 1] ]"), lines("s(\"saw\").partials(binaryL(5))"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:saw partials:[0 0 0 0 0 1 0 1] ]"), lines("s(\"saw\").partials(binaryNL(5, 8))"))
    }

    @Test
    fun binaryNLCountsABitCountThatIsNotWholeDown() {
        // `for (let i = bits - 1; i >= 0; i--)` with 2.5 bits: i is 1.5, then 0.5, and a shift by 1.5 is a shift by 1
        assertEquals(listOf(numbers(0.0, 1.0)), values(GeneratorFunctions.binaryNL(5.0, 2.5)))
        assertEquals(listOf(numbers()), values(GeneratorFunctions.binaryNL(5.0, 0.0)))
        assertEquals(TOO_DENSE, assertFailsWith<CosicError> { GeneratorFunctions.binaryNL(5.0, 1e9).firstCycle() }.message)
    }

    // ---- base -------------------------------------------------------------------------------------------------------------------

    @Test
    fun baseIsTheDigitsOfTheNumber() {
        assertEquals(numbers(1.0, 1.0, 0.0, 1.0), values(GeneratorFunctions.base(13.0, 2.0, 0.0)))
        assertEquals(numbers(15.0, 15.0), values(GeneratorFunctions.base(255.0, 16.0, 0.0)))
        assertEquals(numbers(1.0, 2.0, 3.0, 4.0), values(GeneratorFunctions.base(1234.0, Undefined, Undefined)))
        // no digits: a gap
        assertEquals(emptyList(), values(GeneratorFunctions.base(0.0, 10.0, 0.0)))
        assertEquals(emptyList(), values(GeneratorFunctions.base(-5.0, 2.0, 0.0)))
        // the last digit of a number that is not whole is what is left of it
        assertEquals(numbers(1.0, 0.0, 1.0, 0.5), values(GeneratorFunctions.base(10.5, 2.0, 0.0)))
    }

    @Test
    fun baseKeepsTheLastDigitsOfAMaximumLength() {
        assertEquals(numbers(3.0, 4.0), values(GeneratorFunctions.base(1234.0, 10.0, 2.0)))
        assertEquals(numbers(1.0, 2.0, 3.0, 4.0), values(GeneratorFunctions.base(1234.0, 10.0, 9.0)))
        assertEquals(
            listOf("[ 0/1 → 1/6 | n:1 ]", "[ 1/6 → 1/3 | n:7 ]", "[ 1/3 → 1/2 | n:5 ]", "[ 1/2 → 2/3 | n:5 ]", "[ 2/3 → 5/6 | n:4 ]", "[ 5/6 → 1/1 | n:3 ]"),
            lines("n(base(\"7175 543\", 10, 3))"),
        )
    }

    @Test
    fun baseTakesAListOfNumbersAndPatternsOfBasesAndLengths() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | n:1 ]", "[ 1/4 → 1/2 | n:2 ]", "[ 1/2 → 2/3 | n:1 ]", "[ 2/3 → 5/6 | n:1 ]", "[ 5/6 → 1/1 | n:0 ]"),
            lines("n(base([5, 12], 3))"),
        )
        assertEquals(
            listOf("[ 0/1 → 1/2 | n:3 ]", "[ 1/2 → 1/1 | n:4 ]", "[ 1/1 → 4/3 | n:2 ]", "[ 4/3 → 5/3 | n:3 ]", "[ 5/3 → 2/1 | n:4 ]"),
            lines("n(base(1234, 10, \"<2 3>\"))", 2),
        )
    }

    @Test
    fun baseOfABaseThatNeverRunsOutOfDigitsIsRefused() {
        // in JavaScript this loop does not end; 13 % 1 is 0 and floor(13 / 1) is 13
        assertEquals(TOO_DENSE, assertFailsWith<CosicError> { GeneratorFunctions.base(13.0, 1.0, 0.0).firstCycle() }.message)
        assertEquals(TOO_DENSE, assertFailsWith<CosicError> { GeneratorFunctions.base(13.0, 0.0, 0.0).firstCycle() }.message)
    }

    // ---- xfade -------------------------------------------------------------------------------------------------------------------

    @Test
    fun xfadeFadesFromTheLeftToTheRight() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:1 ]", "[ 0/1 → 1/1 | s:hh gain:0 ]"), lines("xfade(s(\"bd\"), 0, s(\"hh\"))"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:1 ]", "[ 0/1 → 1/1 | s:hh gain:0.5 ]"), lines("xfade(s(\"bd\"), 0.25, s(\"hh\"))"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:1 ]", "[ 0/1 → 1/1 | s:hh gain:1 ]"), lines("xfade(s(\"bd\"), 0.5, s(\"hh\"))"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:0.5 ]", "[ 0/1 → 1/1 | s:hh gain:1 ]"), lines("xfade(s(\"bd\"), 0.75, s(\"hh\"))"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:0 ]", "[ 0/1 → 1/1 | s:hh gain:1 ]"), lines("xfade(s(\"bd\"), 1, s(\"hh\"))"))
    }

    @Test
    fun xfadeMultipliesAGainThatIsAlreadySet() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd gain:0.25 ]", "[ 0/1 → 1/1 | s:hh gain:1 ]"), lines("xfade(s(\"bd\").gain(0.5), 0.75, s(\"hh\"))"))
    }

    @Test
    fun theMethodFadesFromThePatternItIsCalledOn() {
        assertEquals(lines("xfade(s(\"bd*2\"), \"<0 .5 1>\", s(\"hh*4\"))", 3), lines("s(\"bd*2\").xfade(\"<0 .5 1>\", s(\"hh*4\"))", 3))
    }

    @Test
    fun xfadeOfPlainValuesNamesThemValue() {
        assertEquals(listOf("[ 0/1 → 1/1 | value:bd gain:1 ]", "[ 0/1 → 1/1 | value:hh gain:1 ]"), lines("xfade(\"bd\", 0.5, \"hh\")"))
    }

    // ---- beat ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun beatPlaysAnEventOnTheStepsOfADivision() {
        assertEquals(listOf("[ 0/1 → 1/8 | s:bd ]", "[ 1/2 → 5/8 | s:bd ]"), lines("s(\"bd\").beat(\"0,4\", 8)"))
        assertEquals(listOf("[ 5/8 → 3/4 | s:sd ]"), lines("s(\"bd sd\").beat(5, 8)"))
        assertEquals(listOf("[ 3/8 → 5/8 | s:bd ]"), lines("s(\"bd\").beat(1.5, 4)"))
    }

    @Test
    fun beatTakesTheStepModuloTheDivision() {
        assertEquals(lines("s(\"bd\").beat(1, 8)", 2), lines("s(\"bd\").beat(9, 8)", 2))
        // the modulo keeps the sign of the step, so a negative step is silence
        assertEquals(emptyList(), lines("s(\"bd\").beat(-1, 8)", 2))
        assertEquals("Division by Zero", assertFailsWith<CosicError> { evaluateProgram("s(\"bd\").beat(1, 0)") }.message)
    }

    @Test
    fun beatTakesPatternsOfStepsAndDivisions() {
        assertEquals(listOf("[ 0/1 → 1/8 | s:bd ]", "[ 5/4 → 21/16 | s:bd ]"), lines("s(\"bd\").beat(\"<0 4>\", \"<8 16>\")", 2))
    }

    // ---- morph ---------------------------------------------------------------------------------------------------------------------

    @Test
    fun morphMovesTheEventsOfTheFirstRhythmTowardsTheSecond() {
        val straight = listOf(1.0, 0.0, 1.0, 0.0)
        val wonky = listOf(1.0, 1.0, 0.0, 0.0)
        assertEquals(listOf("[ 0/1 → 1/4 | true ]", "[ 1/2 → 3/4 | true ]"), GeneratorFunctions._morph(straight, wonky, 0.0).firstLines())
        assertEquals(listOf("[ 0/1 → 1/4 | true ]", "[ 3/8 → 5/8 | true ]"), GeneratorFunctions._morph(straight, wonky, 0.5).firstLines())
        assertEquals(listOf("[ 0/1 → 1/4 | true ]", "[ 1/4 → 1/2 | true ]"), GeneratorFunctions._morph(straight, wonky, 1.0).firstLines())
        // the events of each cycle are those of the first cycle, moved by whole cycles
        assertEquals(listOf("[ 1/1 → 5/4 | true ]", "[ 11/8 → 13/8 | true ]"), GeneratorFunctions._morph(straight, wonky, 0.5).lines(1, 2))
    }

    @Test
    fun morphThroughAProgram() {
        assertEquals(
            listOf("[ 0/1 → 1/4 | s:hh ]", "[ 3/8 → 5/8 | s:hh ]", "[ 1/1 → 5/4 | s:hh ]", "[ 11/8 → 13/8 | s:hh ]"),
            lines("s(\"hh\").struct(morph([1,0,1,0], [1,1,0,0], 0.5))", 2),
        )
        // the lists can be the colon lists of the mini-notation, and the position a pattern
        assertEquals(
            listOf("[ 0/1 → 1/4 | s:hh ]", "[ 1/2 → 3/4 | s:hh ]", "[ 1/1 → 5/4 | s:hh ]", "[ 11/8 → 13/8 | s:hh ]"),
            lines("s(\"hh\").struct(morph(\"1:0:1:0\", \"1:1:0:0\", \"<0 0.5 1>\"))", 2),
        )
    }

    @Test
    fun morphNeedsAsManyTrueEntriesInTheSecondList() {
        val failure = assertFailsWith<CosicError> { GeneratorFunctions._morph(listOf(1.0, 1.0, 1.0), listOf(1.0), 0.5) }
        assertEquals("undefined is not iterable (cannot read property Symbol(Symbol.iterator))", failure.message)
        // a value that is no list has no length to divide by
        assertEquals("Division by Zero", assertFailsWith<CosicError> { GeneratorFunctions._morph(1.0, listOf(1.0), 0.5) }.message)
    }

    // ---- lists ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun parrayHasTheValuesOfThePatternsAsOneList() {
        val pat = GeneratorFunctions.parray(listOf(fastcat(0.0, 1.0), pure(2.0)))
        assertEquals(listOf(numbers(0.0, 2.0), numbers(1.0, 2.0)), values(pat))
        assertEquals(Rational(2), pat.steps)
        assertEquals(listOf(numbers(5.0)), values(GeneratorFunctions.parray(listOf(5.0))))
    }

    @Test
    fun parrayOfNoPatternsIsAFunction() {
        // `curry(pack, null, 0)` is never applied to anything: the value stays the function
        assertTrue(GeneratorFunctions.parray(emptyList<Any?>()).firstCycle().single().value is JsFn)
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd ]"), lines("s(\"bd\").partials(parray([]))"))
    }

    @Test
    fun partialsAndPhasesSetTheListOfTheEvent() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:saw partials:[1 0.5 0.25] ]"), lines("s(\"saw\").partials([1, 0.5, 0.25])"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:saw phases:[0.5 0.25] ]"), lines("s(\"saw\").phases([0.5, 0.25])"))
        // a list can hold patterns
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:saw partials:[1 0] ]", "[ 1/2 → 1/1 | s:sine partials:[1 0] ]", "[ 1/1 → 3/2 | s:saw partials:[1 1] ]"),
            lines("s(\"saw sine\").partials([1, \"<0 1>\"])", 2).take(3),
        )
        assertEquals(
            listOf("[ 0/1 → 1/1 | s:saw partials:[1 1] phases:[0.5 0.5] ]"),
            lines("s(\"saw\").partials([1, 1]).phases([0.5, 0.5])"),
        )
        // anything else is the pattern of the value itself
        assertEquals(listOf("[ 0/1 → 1/1 | s:saw partials:1 ]", "[ 1/1 → 2/1 | s:saw partials:2 ]"), lines("s(\"saw\").partials(\"<1 2>\")", 2))
    }

    @Test
    fun theFunctionsPartialsAndPhasesNameTheFirstEntryOfTheList() {
        // `as('partials')` names the first value of a list: the quirk of the doc example, which sets `partials` to an object
        assertEquals(listOf("[ 0/1 → 1/1 | s:saw partials:{partials:1} ]"), lines("s(\"saw\").partials(partials([1, 0.5]))"))
        assertEquals(listOf("[ 0/1 → 1/1 | partials:1 s:saw ]"), lines("partials([1, 0.5]).s(\"saw\")"))
        assertEquals(listOf("[ 0/1 → 1/1 | phases:0.5 s:saw ]"), lines("phases([0.5, 0.25]).s(\"saw\")"))
    }

    @Test
    fun fxAddsEffectsToTheChain() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd FX:[{cutoff:500} {room:0.3} {delay:0.2}] ]", "[ 1/2 → 1/1 | s:sd FX:[{cutoff:500} {room:0.3} {delay:0.2}] ]"),
            lines("s(\"bd sd\").FX(lpf(500), room(0.3)).FX(delay(0.2))"),
        )
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd FX:[{cutoff:500 gain:2} {room:0.2}] ]"), lines("s(\"bd\").FX(lpf(500).gain(2), room(0.2))"))
        // the effects are patterns
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd FX:[{cutoff:500}] ]", "[ 1/2 → 1/1 | s:sd FX:[{cutoff:1000}] ]"),
            lines("s(\"bd sd\").FX(lpf(\"500 1000\"))"),
        )
        // no effect at all leaves the function `parray` of nothing in the chain
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd FX:[null] ]"), lines("s(\"bd\").FX()"))
    }

    @Test
    fun collectGroupsTheEventsThatHappenAtTheSameTime() {
        val haps = stack(pure(1.0), pure(2.0), fastcat(3.0, 4.0)).collect().firstCycle()
        assertEquals(3, haps.size)
        assertEquals(numbers(1.0, 2.0), (haps[0].value as List<*>).map { (it as Hap).value })
        assertEquals(TimeSpan(Rational.ZERO, Rational.ONE), haps[0].whole)
        assertEquals(numbers(3.0), (haps[1].value as List<*>).map { (it as Hap).value })
        assertEquals(TimeSpan(Rational.ZERO, Rational(1, 2)), haps[1].whole)
        assertEquals(numbers(4.0), (haps[2].value as List<*>).map { (it as Hap).value })
        assertEquals(HapContext.EMPTY, haps[0].context)
    }

    @Test
    fun collectThroughAProgram() {
        assertEquals(
            listOf("[ 0/1 → 1/1 | 3 ]", "[ 1/1 → 2/1 | 2 ]"),
            lines("note(\"<[c,e,g] [d,f]>\").collect().fmap(haps => haps.length)", 2),
        )
    }

    // ---- colours ----------------------------------------------------------------------------------------------------------------------

    @Test
    fun hslaSetsTheColourToACssString() {
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd color:hsla(0.5turn 50% 50% 1) ]"), lines("s(\"bd\").hsla(0.5, 0.5, 0.5, 1)"))
        assertEquals(listOf("[ 0/1 → 1/1 | s:bd color:hsl(0.5turn 50% 50%) ]"), lines("s(\"bd\").hsl(0.5, 0.5, 0.5)"))
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd color:hsla(0turn 100% 50% 0.8) ]", "[ 1/2 → 1/1 | s:sd color:hsla(0turn 100% 50% 0.8) ]", "[ 1/1 → 3/2 | s:bd color:hsla(0.5turn 100% 50% 0.8) ]"),
            lines("s(\"bd sd\").hsla(\"<0 0.5>\", 1, 0.5, 0.8)", 2).take(3),
        )
    }

    @Test
    fun hslaWritesThePercentagesTheWayJavaScriptWritesNumbers() {
        // 0.07 * 100 and 0.29 * 100 are not 7 and 29 in floating point
        assertEquals(
            listOf("[ 0/1 → 1/1 | s:bd color:hsla(0.1turn 7.000000000000001% 28.999999999999996% 1) ]"),
            lines("s(\"bd\").hsla(0.1, 0.07, 0.29, 1)"),
        )
    }
}
