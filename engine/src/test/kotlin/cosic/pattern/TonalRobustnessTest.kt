package cosic.pattern

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The tonal functions run on the audio thread, on whatever a program makes: a value they cannot use must be reported as a
 * [CosicError] (which the scheduler turns into a message), never as a crash of another kind or a hang.
 */
class TonalRobustnessTest {
    @BeforeTest
    fun fresh() {
        Voicings.restore()
    }

    @AfterTest
    fun cleanUp() {
        Voicings.restore()
    }

    private val numbers = listOf(0.0, 1.0, -1.0, 0.5, -2.5, 7.0, 12.0, 100.0, -100.0, 1e15, -1e15, 1e21, 1e308, -1e308, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)

    private val texts = listOf(
        "", " ", "c", "C3", "c#", "bb", "Bbb4", "x", "cs3", "fs", "c-1", "c-", "c--1", "b#9", "H", "0b", "3##", "-2#", "1e1", "0x10", "Infinity", "NaN", "1P", "3M",
        "-8P", "bogus", "C major", "C:major", "major", "C", "C 2741", "C 101010110101", "Cb2 major", "Eb:minor:pentatonic", "C^7", "Am7", "Bb7b9/F", "C/E",
        "lefthand", "ireal", "triads", "legacy", "guidetones", "below", "above", "duck", "root", "c5", "\n", "C\n", "é", "\u0000",
    )

    private val values: List<Any?> = numbers + texts + listOf(
        true, false, null, Undefined, emptyList<Any?>(), listOf(1.0), listOf("a", "b"), listOf(listOf(1.0), listOf("C")),
        linkedMapOf<String, Any?>(), linkedMapOf("n" to 2.0), linkedMapOf("note" to "c3"), linkedMapOf("value" to 3.0), linkedMapOf("note" to null),
        linkedMapOf("n" to "3#", "anchor" to "c4"), linkedMapOf("n" to 1.0, "anchor" to 60.0), linkedMapOf("n" to 1.0, "anchor" to Double.NaN),
        linkedMapOf("chord" to "C", "n" to 2.0), linkedMapOf("chord" to ""), linkedMapOf("chord" to 5.0), linkedMapOf("chord" to "C", "dictionary" to "x"),
        linkedMapOf("chord" to "C^7", "dictionary" to linkedMapOf("m" to listOf("0 3 7"))), linkedMapOf("chord" to "C", "dictionary" to null),
        linkedMapOf("chord" to "C", "mode" to "bogus"), linkedMapOf("chord" to "C", "offset" to Double.NaN), linkedMapOf("chord" to "C", "n" to 1e308),
        linkedMapOf("chord" to "Am", "anchor" to linkedMapOf("note" to "g4")), linkedMapOf("chord" to "C", "octaves" to Double.POSITIVE_INFINITY),
        silence, pure(1.0),
    )

    private val arguments: List<Any?> = numbers + texts + listOf(null, Undefined, true, emptyList<Any?>(), listOf("C", "major"), listOf(listOf(1.0)), linkedMapOf("a" to 1.0), silence)

    private fun functions(): List<Pair<String, (Pattern, Any?) -> Pattern>> = listOf(
        "scale" to { p, a -> p.scale(a) },
        "transpose" to { p, a -> p.transpose(a) },
        "scaleTranspose" to { p, a -> p.scale("C major").scaleTranspose(a) },
        "voicing" to { p, _ -> p.voicing() },
        "voicings" to { p, a -> p.voicings(a) },
        "rootNotes" to { p, a -> p.rootNotes(a) },
        "arp" to { p, a -> p.arp(a) },
        "arpWith" to { p, _ -> p.arpWith { haps -> haps.firstOrNull() } },
    )

    @Test
    fun everyFunctionFailsOnlyWithACosicError() {
        val problems = ArrayList<String>()
        var succeeded = 0
        var failed = 0
        val started = System.nanoTime()
        for ((name, function) in functions()) {
            for (value in values) {
                for (argument in arguments) {
                    // the arguments matter little to the functions that take none, and the values little to those that fail on every one
                    if ((name == "voicing" || name == "arpWith") && argument !== arguments[0]) continue
                    try {
                        function(pure(value), argument).queryArc(Rational.ZERO, Rational.ONE)
                        succeeded++
                    } catch (e: CosicError) {
                        failed++
                    } catch (e: Throwable) {
                        problems.add("$name(${describe(argument)}) of ${describe(value)}: ${e::class.simpleName}: ${e.message}")
                        if (problems.size >= 20) break
                    }
                }
            }
        }
        assertEquals(emptyList(), problems)
        assertTrue(succeeded > 1000 && failed > 1000, "$succeeded calls worked and $failed failed")
        assertTrue((System.nanoTime() - started) / 1e9 < 60, "slow")
    }

    private fun describe(v: Any?): String = if (v is String) "\"" + v.replace("\n", "\\n") + "\"" else v.toString()

    @Test
    fun theHelpersOfTheLibraryNeverFailWithAnythingElseEither() {
        val problems = ArrayList<String>()
        fun attempt(what: String, block: () -> Unit) {
            try {
                block()
            } catch (e: CosicError) {
            } catch (e: Throwable) {
                problems.add("$what: ${e::class.simpleName}: ${e.message}")
            }
        }
        for (a in texts) {
            attempt("note($a)") { Tonal.note(a) }
            attempt("interval($a)") { Tonal.interval(a) }
            attempt("scaleGet($a)") { Tonal.scaleGet(a) }
            attempt("scaleTokenize($a)") { Tonal.scaleTokenize(a) }
            attempt("noteChroma($a)") { Tonal.noteChroma(a) }
            attempt("tokenizeChord($a)") { tokenizeChord(a) }
            attempt("rangeChromatic($a)") { Tonal.rangeChromatic(listOf(a, "C4")) }
            for (b in texts) {
                attempt("transpose($a, $b)") { Tonal.transpose(a, b) }
                attempt("intervalAdd($a, $b)") { Tonal.intervalAdd(a, b) }
                attempt("intervalSubstract($a, $b)") { Tonal.intervalSubstract(a, b) }
                attempt("enharmonic($a, $b)") { Tonal.enharmonic(a, b) }
            }
        }
        for (n in numbers) {
            attempt("intervalFromSemitones($n)") { Tonal.intervalFromSemitones(n) }
            attempt("midiToNoteName($n)") { Tonal.midiToNoteName(n) }
            attempt("midi2note($n)") { midi2note(n, true) }
            attempt("rangeChromatic($n)") { Tonal.rangeChromatic(listOf(n, 60.0)) }
            attempt("scaleStep($n)") { scaleStep(listOf(60.0, 63.0), n, 2.0) }
            attempt("stepInNamedScale($n)") { stepInNamedScale(n, "C major", "c4") }
        }
        assertEquals(emptyList(), problems)
    }
}
