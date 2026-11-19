package cosic.script

import cosic.SourceRange
import cosic.pattern.Rational
import cosic.pattern.cycleSpan
import cosic.pattern.r
import cosic.pattern.timeRand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BuiltinsTest {
    private fun onsets(code: String, cycle: Long = 0L): List<Rational> =
        evaluateProgram(code).pattern.query(cycleSpan(cycle)).filter { it.hasOnset() }.map { it.whole!!.begin }.sorted()

    private fun count(code: String, cycle: Long = 0L): Int = evaluateProgram(code).pattern.query(cycleSpan(cycle)).size

    // ---- registry ---------------------------------------------------------------------

    @Test
    fun everyNameAndAliasOfThePlanIsRegistered() {
        val names = listOf(
            "silence", "seq", "sequence", "fastcat", "cat", "slowcat", "stack", "arrange", "run",
            "sine", "cosine", "saw", "isaw", "tri", "square", "rand", "perlin", "irand",
            "setcps", "setcpm", "hush", "samples",
            "fast", "slow", "early", "late", "hurry", "rev", "palindrome", "iter", "iterBack", "ply", "inside", "outside",
            "swingBy", "swing", "off",
            "struct", "mask", "euclid", "euclidRot", "segment", "chunk", "every", "firstOf", "lastOf", "superimpose", "layer",
            "jux", "juxBy", "chop", "striate",
            "degrade", "degradeBy", "sometimesBy", "sometimes", "often", "rarely", "almostNever", "almostAlways",
            "someCyclesBy", "someCycles",
            "add", "sub", "mul", "div", "range", "scale",
            "s", "sound", "n", "note", "bank", "gain", "velocity", "postgain", "pan", "speed", "begin", "end", "cut",
            "clip", "legato", "attack", "att", "decay", "dec", "sustain", "sus", "release", "rel",
            "lpf", "cutoff", "ctf", "lpq", "resonance", "hpf", "hcutoff", "hpq", "hresonance", "bpf", "bandf", "bpq", "bandq",
            "shape", "crush", "coarse", "delay", "delaytime", "delayt", "delayfeedback", "delayfb", "room", "size", "roomsize",
        )
        for (name in names) assertNotNull(Builtins.lookup(name), "missing builtin '$name'")
    }

    @Test
    fun namesAndAliasesAreUnique() {
        val seen = HashSet<String>()
        for (builtin in Builtins.all) {
            for (name in listOf(builtin.name) + builtin.aliases) assertTrue(seen.add(name), "duplicate name '$name'")
        }
    }

    @Test
    fun aliasesResolveToTheirBuiltin() {
        assertEquals("seq", Builtins.lookup("fastcat")?.name)
        assertEquals("every", Builtins.lookup("firstOf")?.name)
        assertEquals("lpf", Builtins.lookup("ctf")?.name)
        assertEquals("clip", Builtins.lookup("legato")?.name)
    }

    @Test
    fun kindsFollowThePlan() {
        for (name in listOf("silence", "seq", "cat", "stack", "arrange", "run", "irand")) assertEquals(BuiltinKind.CONSTRUCTOR, Builtins.lookup(name)?.kind, name)
        for (name in listOf("sine", "cosine", "saw", "isaw", "tri", "square", "rand", "perlin")) assertEquals(BuiltinKind.SIGNAL, Builtins.lookup(name)?.kind, name)
        for (name in listOf("setcps", "setcpm", "hush", "samples")) assertEquals(BuiltinKind.SETTING, Builtins.lookup(name)?.kind, name)
        for (name in listOf("fast", "every", "layer", "add", "scale", "jux")) assertEquals(BuiltinKind.METHOD, Builtins.lookup(name)?.kind, name)
        for (name in listOf("s", "n", "note", "gain", "lpf")) assertEquals(BuiltinKind.CONTROL, Builtins.lookup(name)?.kind, name)
        assertEquals(ParamKind.ANY, Builtins.lookup("seq")?.variadic)
        assertEquals(ParamKind.FN, Builtins.lookup("layer")?.variadic)
        assertEquals(listOf(ParamKind.NUM_PAT, ParamKind.FN), Builtins.lookup("every")?.params)
    }

    // ---- constructors and signals -----------------------------------------------------

    @Test
    fun seqPlaysItsArgumentsInOneCycle() {
        val expected = listOf("s=bd@0-1/2", "s=sd@1/2-3/4", "s=sd@3/4-1")
        assertEquals(expected, run("seq(s(\"bd\"), s(\"sd sd\"))"))
        assertEquals(expected, run("sequence(s(\"bd\"), s(\"sd sd\"))"))
        assertEquals(expected, run("fastcat(s(\"bd\"), s(\"sd sd\"))"))
    }

    @Test
    fun catPlaysOneArgumentPerCycle() {
        assertEquals(listOf("s=bd@0-1"), run("cat(s(\"bd\"), s(\"sd\"))", 0))
        assertEquals(listOf("s=sd@1-2"), run("cat(s(\"bd\"), s(\"sd\"))", 1))
        assertEquals(listOf("s=sd@1-2"), run("slowcat(s(\"bd\"), s(\"sd\"))", 1))
    }

    @Test
    fun stackPlaysEverythingTogether() {
        assertEquals(listOf("s=bd@0-1", "s=sd@0-1"), run("stack(s(\"bd\"), s(\"sd\"))"))
        assertEquals(emptyList(), run("stack()"))
        assertEquals(emptyList(), run("seq()"))
    }

    @Test
    fun arrangeLoopsThroughSections() {
        val code = "arrange([2, s(\"bd\")], [1, s(\"sd\")])"
        assertEquals(listOf("bd", "bd", "sd", "bd", "bd", "sd"), (0L..5L).map { c -> values(code, c).single()["s"] })
        assertEquals("'arrange' expects [cycles, pattern] pairs", failure("arrange(s(\"bd\"))").message)
        assertEquals("'arrange' expects [cycles, pattern] pairs", failure("arrange([0, s(\"bd\")])").message)
    }

    @Test
    fun arrangeSectionsKeepPlayingWhereTheyLeftOff() {
        val code = "arrange([2, cat(s(\"a\"), s(\"b\"), s(\"c\"), s(\"d\"))], [1, s(\"x\")])"
        assertEquals(listOf("a", "b", "x", "c", "d", "x"), (0L..5L).map { c -> values(code, c).single()["s"] })
    }

    @Test
    fun runCountsUp() {
        assertEquals(listOf("n=0@0-1/4", "n=1@1/4-1/2", "n=2@1/2-3/4", "n=3@3/4-1"), run("n(run(4))"))
        assertEquals(2, count("n(run(\"<2 4>\"))", 0))
        assertEquals(4, count("n(run(\"<2 4>\"))", 1))
        assertEquals(emptyList(), run("n(run(0))"))
    }

    @Test
    fun silenceIsEmpty() {
        assertEquals(emptyList(), run("\$: silence"))
        assertEquals(listOf("s=bd@0-1"), run("stack(silence, s(\"bd\"))"))
    }

    @Test
    fun signalsAreSampledOverTheWholeOfEachEvent() {
        assertEquals(
            listOf("pan=0.125 s=hh@0-1/4", "pan=0.375 s=hh@1/4-1/2", "pan=0.625 s=hh@1/2-3/4", "pan=0.875 s=hh@3/4-1"),
            run("s(\"hh*4\").pan(saw)"),
        )
        assertEquals(listOf("pan=0.875 s=hh@0-1/4"), run("s(\"hh\").fast(4).pan(isaw)").take(1))
    }

    @Test
    fun everySignalIsAvailableAndStaysInTheUnitRange() {
        for (name in listOf("sine", "cosine", "saw", "isaw", "tri", "square", "rand", "perlin")) {
            val gains = values("s(\"hh*16\").gain($name)").map { it["gain"] as Double }
            assertEquals(16, gains.size, name)
            assertTrue(gains.all { it >= 0.0 && it <= 1.0 }, name)
        }
        assertEquals(setOf(0.0, 1.0), values("s(\"hh*16\").gain(square)").map { it["gain"] }.toSet())
    }

    @Test
    fun randReadsTheMiddleOfEachEvent() {
        val gains = values("s(\"hh*8\").gain(rand)").map { it["gain"] }
        assertEquals((0L..7L).map { timeRand(r(2 * it + 1, 16), 0) }, gains)
    }

    @Test
    fun irandMakesWholeNumbers() {
        val ns = values("s(\"hh*32\").n(irand(8))").map { it["n"] as Double }
        assertTrue(ns.all { it == Math.floor(it) && it in 0.0..7.0 })
        assertTrue(ns.toSet().size > 3)
    }

    // ---- time -------------------------------------------------------------------------

    @Test
    fun fastAndSlow() {
        assertEquals(listOf("s=bd@0-1/4", "s=sd@1/4-1/2", "s=bd@1/2-3/4", "s=sd@3/4-1"), run("s(\"bd sd\").fast(2)"))
        assertEquals(listOf("s=bd@0-1"), run("s(\"bd sd\").slow(2)", 0))
        assertEquals(listOf("s=sd@1-2"), run("s(\"bd sd\").slow(2)", 1))
        assertEquals(emptyList(), run("s(\"bd sd\").fast(0)"))
        assertEquals(emptyList(), run("s(\"bd sd\").slow(0)"))
    }

    @Test
    fun timeArgumentsMayBePatterns() {
        assertEquals(2, count("s(\"bd sd\").fast(\"<1 2>\")", 0))
        assertEquals(4, count("s(\"bd sd\").fast(\"<1 2>\")", 1))
        assertEquals(listOf("s=bd@0-1/2", "s=bd@1/2-3/4", "s=sd@3/4-1"), run("s(\"bd sd\").fast(\"1 2\")"))
    }

    @Test
    fun earlyAndLate() {
        assertEquals(listOf("s=b@0-1/4", "s=c@1/4-1/2", "s=d@1/2-3/4", "s=a@3/4-1"), run("s(\"a b c d\").early(0.25)"))
        assertEquals(listOf("s=d@0-1/4", "s=a@1/4-1/2", "s=b@1/2-3/4", "s=c@3/4-1"), run("s(\"a b c d\").late(0.25)"))
        assertEquals(run("s(\"a b c d\").late(0.25)"), run("s(\"a b c d\").early(-0.25)"))
    }

    @Test
    fun hurryAlsoRaisesTheSpeed() {
        assertEquals(
            listOf("s=bd speed=2@0-1/4", "s=sd speed=2@1/4-1/2", "s=bd speed=2@1/2-3/4", "s=sd speed=2@3/4-1"),
            run("s(\"bd sd\").hurry(2)"),
        )
        assertEquals(listOf("s=bd speed=4@0-1/2", "s=bd speed=4@1/2-1"), run("s(\"bd\").speed(2).hurry(2)"))
    }

    @Test
    fun revAndPalindrome() {
        assertEquals(listOf("s=d@0-1/4", "s=c@1/4-1/2", "s=b@1/2-3/4", "s=a@3/4-1"), run("s(\"a b c d\").rev()"))
        assertEquals(listOf("s=a@0-1/2", "s=b@1/2-1"), run("s(\"a b\").palindrome()", 0))
        assertEquals(listOf("s=b@1-3/2", "s=a@3/2-2"), run("s(\"a b\").palindrome()", 1))
    }

    @Test
    fun iterRotatesEachCycle() {
        val code = "s(\"a b c d\").iter(4)"
        assertEquals(listOf("a", "b", "c", "d"), values(code, 0).map { it["s"] })
        assertEquals(listOf("b", "c", "d", "a"), values(code, 1).map { it["s"] })
        assertEquals(listOf("c", "d", "a", "b"), values(code, 2).map { it["s"] })
        assertEquals(listOf("d", "a", "b", "c"), values("s(\"a b c d\").iterBack(4)", 1).map { it["s"] })
    }

    @Test
    fun plyRepeatsEvents() {
        assertEquals(listOf("s=bd@0-1/4", "s=bd@1/4-1/2", "s=sd@1/2-3/4", "s=sd@3/4-1"), run("s(\"bd sd\").ply(2)"))
        assertEquals(6, count("s(\"bd sd\").ply(3)"))
    }

    @Test
    fun insideAndOutside() {
        assertEquals(listOf(1.0, 0.0, 3.0, 2.0), values("n(\"0 1 2 3\").inside(2, rev)").map { it["n"] })
        val code = "cat(n(\"0 1\"), n(\"2 3\")).outside(2, rev)"
        assertEquals(listOf(3.0, 2.0), values(code, 0).map { it["n"] })
        assertEquals(listOf(1.0, 0.0), values(code, 1).map { it["n"] })
    }

    @Test
    fun swingDelaysTheSecondHalfOfEachSlot() {
        val expected = listOf(r(0), r(5, 24), r(1, 4), r(11, 24), r(1, 2), r(17, 24), r(3, 4), r(23, 24))
        assertEquals(expected, onsets("s(\"hh*8\").swingBy(1/3, 4)"))
        assertEquals(expected, onsets("s(\"hh*8\").swing(4)"))
        assertEquals(onsets("s(\"hh*4\")"), onsets("s(\"hh*4\").swingBy(1/3, 4)"))
    }

    @Test
    fun offLayersATransformedDelayedCopy() {
        assertEquals(
            listOf("s=bd speed=2@-3/4-1/4|0-1/4", "s=bd@0-1", "s=bd speed=2@1/4-5/4|1/4-1"),
            run("s(\"bd\").off(0.25, x => x.speed(2))"),
        )
    }

    // ---- structure --------------------------------------------------------------------

    @Test
    fun structImposesARhythm() {
        assertEquals(listOf("s=bd@0-1/4", "s=bd@1/2-3/4", "s=bd@3/4-1"), run("s(\"bd\").struct(\"x ~ x x\")"))
        assertEquals(listOf("s=bd@0-1/4", "s=bd@3/4-1"), run("s(\"bd\").struct(\"t f f t\")"))
    }

    @Test
    fun maskSilencesPartsOfAPattern() {
        assertEquals(listOf("s=hh@0-1/4", "s=hh@1/2-3/4", "s=hh@3/4-1"), run("s(\"hh*4\").mask(\"1 0 1 1\")"))
    }

    @Test
    fun euclidSpreadsHits() {
        assertEquals(listOf("s=bd@0-1/8", "s=bd@3/8-1/2", "s=bd@3/4-7/8"), run("s(\"bd\").euclid(3, 8)"))
        assertEquals(listOf("s=bd@1/8-1/4", "s=bd@1/2-5/8", "s=bd@3/4-7/8"), run("s(\"bd\").euclidRot(3, 8, 2)"))
        assertEquals(3, count("s(\"bd\").euclid(\"<3 5>\", 8)", 0))
        assertEquals(5, count("s(\"bd\").euclid(\"<3 5>\", 8)", 1))
    }

    @Test
    fun segmentSamplesSignals() {
        assertEquals(
            listOf("n=1.25@0-1/4", "n=3.75@1/4-1/2", "n=6.25@1/2-3/4", "n=8.75@3/4-1"),
            run("n(saw.range(0, 10)).segment(4)"),
        )
        assertEquals(listOf("s=bd@0-1/2", "s=bd@1/2-1"), run("s(\"bd\").segment(2)"))
    }

    @Test
    fun chunkTransformsOneSliceAtATime() {
        val code = "n(\"0 1 2 3\").chunk(4, x => x.add(10))"
        assertEquals(listOf(10.0, 1.0, 2.0, 3.0), values(code, 0).map { it["n"] })
        assertEquals(listOf(0.0, 11.0, 2.0, 3.0), values(code, 1).map { it["n"] })
        assertEquals(listOf(10.0, 1.0, 2.0, 3.0), values(code, 4).map { it["n"] })
    }

    @Test
    fun everyAndLastOf() {
        assertEquals(run("s(\"bd sd\").every(2, fast(2))", 0), run("s(\"bd sd\").firstOf(2, fast(2))", 0))
        assertEquals(listOf(2, 2, 4, 2), (0L..3L).map { count("s(\"bd sd\").lastOf(3, fast(2))", it) })
        assertEquals(listOf(4, 2, 2, 4), (0L..3L).map { count("s(\"bd sd\").every(3, fast(2))", it) })
        assertEquals(listOf(4, 4), (0L..1L).map { count("s(\"bd sd\").every(\"<1 1>\", fast(2))", it) })
    }

    @Test
    fun superimposeAndLayer() {
        assertEquals(listOf("s=bd@0-1", "s=bd speed=2@0-1"), run("s(\"bd\").superimpose(x => x.speed(2))"))
        assertEquals(
            listOf("s=bd@0-1/4", "s=sd@0-1/2", "s=sd@1/4-1/2", "s=bd@1/2-3/4", "s=bd@1/2-1", "s=sd@3/4-1"),
            run("s(\"bd sd\").layer(rev, x => x.fast(2))"),
        )
        assertEquals("'layer' expects a function, got a pattern", failure("layer(rev, x => x.fast(2), s(\"bd sd\"))").message)
        assertEquals(run("s(\"bd sd\").layer(rev, x => x.fast(2))"), run("s(\"bd sd\").every(1, layer(rev, x => x.fast(2)))"))
    }

    @Test
    fun juxPansTheOriginalLeftAndTheCopyRight() {
        assertEquals(
            listOf("pan=0 s=bd@0-1/2", "pan=1 s=sd@0-1/2", "pan=0 s=sd@1/2-1", "pan=1 s=bd@1/2-1"),
            run("s(\"bd sd\").jux(rev)"),
        )
        assertEquals(
            listOf("pan=0.25 s=bd@0-1/2", "pan=0.75 s=sd@0-1/2", "pan=0.25 s=sd@1/2-1", "pan=0.75 s=bd@1/2-1"),
            run("s(\"bd sd\").juxBy(0.5, rev)"),
        )
    }

    @Test
    fun chopCutsSamplesIntoPieces() {
        assertEquals(
            listOf("begin=0 end=0.5 s=bd@0-1/4", "begin=0.5 end=1 s=bd@1/4-1/2", "begin=0 end=0.5 s=sd@1/2-3/4", "begin=0.5 end=1 s=sd@3/4-1"),
            run("s(\"bd sd\").chop(2)"),
        )
        assertEquals(
            listOf("begin=0.5 end=0.75 s=bd@0-1/2", "begin=0.75 end=1 s=bd@1/2-1"),
            run("s(\"bd\").begin(0.5).chop(2)"),
        )
    }

    @Test
    fun striateInterlacesSlices() {
        assertEquals(
            listOf("begin=0 end=0.5 s=bd@0-1/4", "begin=0 end=0.5 s=sd@1/4-1/2", "begin=0.5 end=1 s=bd@1/2-3/4", "begin=0.5 end=1 s=sd@3/4-1"),
            run("s(\"bd sd\").striate(2)"),
        )
    }

    // ---- randomness -------------------------------------------------------------------

    @Test
    fun degradeDropsAboutHalf() {
        val kept = (0L..15L).filter { timeRand(r(it, 16), 0) >= 0.5 }.map { r(it, 16) }
        assertEquals(kept, onsets("s(\"hh*16\").degrade()"))
        assertEquals(kept, onsets("s(\"hh*16\").degradeBy(0.5)"))
        assertEquals((0L..15L).filter { timeRand(r(it, 16), 0) >= 0.2 }.map { r(it, 16) }, onsets("s(\"hh*16\").degradeBy(0.2)"))
    }

    @Test
    fun sometimesByAppliesTheFunctionToARandomShare() {
        val expected = (0L..7L).map { i ->
            val sped = timeRand(r(i, 8), 0) < 0.5
            "s=hh" + (if (sped) " speed=2" else "") + "@${r(i, 8)}-${r(i + 1, 8)}"
        }
        assertEquals(expected, run("s(\"hh*8\").sometimesBy(0.5, x => x.speed(2))"))
    }

    @Test
    fun theSometimesFamilyUsesFixedProbabilities() {
        for ((name, p) in listOf("sometimes" to 0.5, "often" to 0.75, "rarely" to 0.25, "almostNever" to 0.1, "almostAlways" to 0.9)) {
            assertEquals(
                run("s(\"hh*16\").sometimesBy($p, x => x.speed(2))"),
                run("s(\"hh*16\").$name(x => x.speed(2))"),
                name,
            )
        }
    }

    @Test
    fun someCyclesDecidesPerCycle() {
        for (c in 0L..19L) {
            val expected = if (timeRand(r(c), 1) < 0.5) 2 else 1
            assertEquals(expected, count("s(\"bd\").someCyclesBy(0.5, fast(2))", c), "cycle $c")
            assertEquals(expected, count("s(\"bd\").someCycles(fast(2))", c), "cycle $c")
        }
    }

    // ---- values -----------------------------------------------------------------------

    @Test
    fun arithmeticOnControlValues() {
        assertEquals(listOf("n=2@0-1/2", "n=3@1/2-1"), run("n(\"0 1\").add(2)"))
        assertEquals(listOf("n=-1@0-1/2", "n=0@1/2-1"), run("n(\"0 1\").sub(1)"))
        assertEquals(listOf("n=3@0-1/2", "n=6@1/2-1"), run("n(\"1 2\").mul(3)"))
        assertEquals(listOf("n=2@0-1/2", "n=4@1/2-1"), run("n(\"4 8\").div(2)"))
    }

    @Test
    fun arithmeticArgumentsMayBePatterns() {
        assertEquals(listOf("n=10@0-1/2", "n=21@1/2-1"), run("n(\"0 1\").add(\"10 20\")"))
        assertEquals(listOf("n=11@0-1/2", "n=12@1/2-1"), run("n(\"1 2\").add(\"<10 20>\")", 0))
        assertEquals(listOf("n=21@1-3/2", "n=22@3/2-2"), run("n(\"1 2\").add(\"<10 20>\")", 1))
    }

    @Test
    fun arithmeticPrefersNoteOverN() {
        assertEquals(listOf("n=2 note=60@0-1"), run("note(\"c3\").n(2).add(12)"))
    }

    @Test
    fun arithmeticOnTwoControlMaps() {
        assertEquals(listOf("n=1 note=48@0-1"), run("note(\"c3\").add(n(1))"))
        assertEquals(listOf("n=3 note=48@0-1"), run("note(\"c3\").n(2).add(n(1))"))
    }

    @Test
    fun arithmeticNeedsSomethingNumericToChange() {
        val e = queryFailure("s(\"bd\").add(12)")
        assertEquals("add: nothing numeric to change", e.message)
        assertEquals(SourceRange(8, 15), e.range)
        assertEquals("mul: nothing numeric to change", queryFailure("s(\"bd\").mul(2)").message)
    }

    @Test
    fun rangeRescalesUnitValues() {
        assertEquals(
            listOf("gain=0.5625 s=hh@0-1/4", "gain=0.6875 s=hh@1/4-1/2", "gain=0.8125 s=hh@1/2-3/4", "gain=0.9375 s=hh@3/4-1"),
            run("s(\"hh*4\").gain(saw.range(0.5, 1))"),
        )
        assertEquals(listOf("gain=2.5 s=hh@0-1"), run("s(\"hh\").gain(\"0.5\".range(0, 5))"))
    }

    @Test
    fun scaleRoundsDegreesAndWrapsOctaves() {
        assertEquals(listOf("note=47@0-1/2", "note=60@1/2-1"), run("n(\"-1 7\").scale(\"C:major\")"))
        assertEquals(listOf("note=48@0-1/2", "note=50@1/2-1"), run("n(\"0.4 0.6\").scale(\"C:major\")"))
        assertEquals(listOf("note=48@0-1/3", "note=53@1/3-2/3", "note=58@2/3-1"), run("n(\"0 2 4\").scale(\"C:minor:pentatonic\")"))
    }

    @Test
    fun scaleNamesMayBePatterns() {
        assertEquals(listOf("note=52@0-1"), run("n(\"2\").scale(\"<C:major C:minor>\")", 0))
        assertEquals(listOf("note=51@1-2"), run("n(\"2\").scale(\"<C:major C:minor>\")", 1))
    }

    @Test
    fun scaleErrors() {
        val unknown = failure("n(\"0\").scale('C:foo')")
        assertEquals("Unknown scale 'C:foo'", unknown.message)
        assertEquals(SourceRange(7, 21), unknown.range)
        assertEquals("Unknown scale 'C:foo'", queryFailure("n(\"0\").scale(\"C:foo\")").message)
        assertEquals("Unknown scale 'C:foo'", queryFailure("n(\"0\").scale(\"<C:foo>\")").message)
        assertEquals("scale: needs n values", queryFailure("s(\"bd\").scale(\"C:major\")").message)
    }

    // ---- controls ---------------------------------------------------------------------

    private val numericControls = listOf(
        "gain" to "gain", "velocity" to "velocity", "postgain" to "postgain", "pan" to "pan", "speed" to "speed",
        "begin" to "begin", "end" to "end", "cut" to "cut", "clip" to "clip", "legato" to "clip",
        "attack" to "attack", "att" to "attack", "decay" to "decay", "dec" to "decay", "sustain" to "sustain",
        "sus" to "sustain", "release" to "release", "rel" to "release", "lpf" to "lpf", "cutoff" to "lpf", "ctf" to "lpf",
        "lpq" to "lpq", "resonance" to "lpq", "hpf" to "hpf", "hcutoff" to "hpf", "hpq" to "hpq", "hresonance" to "hpq",
        "bpf" to "bpf", "bandf" to "bpf", "bpq" to "bpq", "bandq" to "bpq", "shape" to "shape", "crush" to "crush",
        "coarse" to "coarse", "delay" to "delay", "delaytime" to "delaytime", "delayt" to "delaytime",
        "delayfeedback" to "delayfeedback", "delayfb" to "delayfeedback", "room" to "room", "size" to "size",
        "roomsize" to "size",
    )

    @Test
    fun numericControlsStoreTheirCanonicalKey() {
        for ((name, key) in numericControls) {
            assertEquals(mapOf<String, Any>("s" to "bd", key to 0.25), values("s(\"bd\").$name(0.25)").single(), "method .$name")
            assertEquals(mapOf<String, Any>(key to 0.25), values("$name(0.25)").single(), "constructor $name")
        }
    }

    @Test
    fun soundControl() {
        assertEquals(listOf("s=bd@0-1/2", "s=sd@1/2-1"), run("s(\"bd sd\")"))
        assertEquals(listOf("s=bd@0-1/2", "s=sd@1/2-1"), run("sound(\"bd sd\")"))
        assertEquals(listOf("n=2 s=bd@0-1/2", "s=sd@1/2-1"), run("s(\"bd:2 sd\")"))
        assertEquals(listOf("s=808@0-1"), run("s(808)"))
        assertEquals(listOf("s=808@0-1/2", "s=909@1/2-1"), run("s(\"808 909\")"))
        assertEquals(listOf("s=bd@0-1"), run("s(\"x\").s(\"bd\")"))
    }

    @Test
    fun numberAndNoteControls() {
        assertEquals(listOf("n=0@0-1/2", "n=1@1/2-1"), run("n(\"0 1\")"))
        assertEquals(listOf("note=48@0-1/2", "note=52@1/2-1"), run("note(\"c3 e3\")"))
        assertEquals(listOf("note=60@0-1"), run("note(60)"))
        assertEquals(listOf("note=61@0-1"), run("note(\"c#4\")"))
        assertEquals(listOf("note=51@0-1"), run("note('eb3')"))
        assertEquals("'n' expects a number, got 'bd'", queryFailure("n(\"bd\")").message)
    }

    @Test
    fun badNoteNames() {
        val e = failure("note('x')")
        assertEquals("Not a note: 'x'", e.message)
        assertEquals(SourceRange(0, 9), e.range)
        assertEquals("Not a note: 'x'", queryFailure("note(\"x\")").message)
    }

    @Test
    fun bankControl() {
        assertEquals(listOf("bank=tr909 s=bd@0-1"), run("s(\"bd\").bank(\"tr909\")"))
    }

    @Test
    fun controlsMergeWithWhatIsThereAlready() {
        assertEquals(listOf("gain=0.5 pan=1 s=bd@0-1"), run("s(\"bd\").gain(0.5).pan(1)"))
        assertEquals(listOf("gain=2 s=bd@0-1"), run("s(\"bd\").gain(1).gain(2)"))
    }

    @Test
    fun controlsTakeStructureFromTheLeft() {
        assertEquals(listOf("n=0 s=bd@0-1/2", "n=1 s=sd@1/2-1"), run("s(\"bd sd\").n(\"0 1\")"))
        assertEquals(listOf("gain=1@0-1/2", "gain=0.5@1/2-1"), run("gain(\"1 0.5\")"))
    }

    @Test
    fun controlArgumentsMayBePatterns() {
        assertEquals(listOf("gain=0.5 s=bd@0-1/2", "gain=0.5 s=bd@1/2-1"), run("s(\"bd*2\").gain(\"<0.5 1>\")", 0))
        assertEquals(listOf("gain=1 s=bd@1-3/2", "gain=1 s=bd@3/2-2"), run("s(\"bd*2\").gain(\"<0.5 1>\")", 1))
    }

    @Test
    fun controlsOnPlainValuesStartFromANote() {
        assertEquals(listOf("gain=0.5 note=1@0-1/2", "gain=0.5 note=2@1/2-1"), run("\"1 2\".gain(0.5)"))
        assertEquals(listOf("gain=0.5 s=bd@0-1"), run("\"bd\".gain(0.5)"))
    }

    @Test
    fun controlsAcceptTheirPatternLast() {
        assertEquals(run("s(\"bd\").gain(0.5)"), run("gain(0.5, s(\"bd\"))"))
    }
}
