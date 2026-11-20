package cosic.script.tidal

import cosic.pattern.CosicError
import cosic.script.evaluateProgram
import cosic.script.shown
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `tidal` through the REPL: the patterns of Tidal's Haskell are those of the JavaScript functions they call (EXTRA T2).
 * The expected haps are what Strudel's own `tidal` makes of the same code (`parity/extra/2.11.json` has them for the
 * parity run).
 */
class TidalTest {
    private fun play(code: String, cycles: Int = 1): List<String> = shown(evaluateProgram(code).pattern, cycles)

    private fun failure(code: String): String = assertFailsWith<CosicError> { evaluateProgram(code) }.message

    @Test
    fun aTidalProgramRegistersItsPatternsAsD1ToD9() {
        assertEquals(listOf("[ 0/1 → 1/2 | s:bd ]", "[ 1/2 → 1/1 | s:bd ]"), play("tidal`d1 \$ s \"bd*2\"`"))
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:hh ]", "[ 0/1 → 1/1 | s:bd ]", "[ 1/2 → 1/1 | s:hh ]"),
            play("tidal`d1 \$ s \"bd\"\n\nd2 \$ s \"hh*2\"`"),
        )
    }

    @Test
    fun theOperatorsAreThoseOfTidal() {
        assertEquals(
            listOf("[ 0/1 → 1/2 | s:bd speed:2 ]", "[ 1/2 → 1/1 | s:bd speed:2 ]"),
            play("tidal`d1 \$ s \"bd*2\" # speed 2`"),
        )
    }

    @Test
    fun thePreambleOfStrudelIsNotNeeded() {
        assertEquals(play("tidal`d1 \$ s \"bd*2\"`"), play("await initTidal()\ntidal`d1 \$ s \"bd*2\"`"))
    }

    @Test
    fun unsupportedHaskellFailsWithTheErrorOfHs2js() {
        assertEquals("unhandled branch type negation", failure("tidal`d1 \$ s \"bd\" # speed (-1)`"))
        assertEquals("unhandled branch type tuple", failure("tidal`(1, 2)`"))
        assertEquals("unhandled leaf type comment", failure("tidal`-- just a comment`"))
        assertTrue(failure("tidal`d1 \$ s \"bd\" # `").startsWith("invalid syntax"))
    }
}
