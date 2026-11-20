package cosic.script

import cosic.parity.ParityData
import cosic.pattern.CosicError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The corpus of the parity harness: every example of Strudel's reference, every tune and every program the plan's tasks
 * wrote. acorn reads all of them (tools/strudel-ref/parser-oracle.mjs says so while it writes corpus-digest.json), so
 * every one of them must parse, with no exception, and to the same tree: the digest is that of acorn's tree in the format
 * of [dumpAst]. A program added to the corpus later has no digest yet and only has to parse.
 */
class CorpusParseTest {
    private class Program(val id: String, val code: String)

    /** The programs with the ids the oracle tool gives them: a REPL snippet counts one program per code. */
    private val programs: List<Program> by lazy {
        ParityData.cases.flatMap { case ->
            case.codes.mapIndexed { index, code -> Program(if (case.repl) "${case.id}/$index" else case.id, code) }
        }
    }

    private val digests: Map<String, String> by lazy {
        val text = javaClass.getResourceAsStream("/script/parser/corpus-digest.json")!!.bufferedReader().readText()
        Json.parseToJsonElement(text).jsonObject.mapValues { it.value.jsonPrimitive.content }
    }

    @Test
    fun everyProgramOfTheCorpusParses() {
        val failures = ArrayList<String>()
        for (p in programs) {
            try {
                val result = parseProgram(p.code)
                assertEquals(0, result.program.start)
                assertEquals(p.code.length, result.program.end)
            } catch (e: CosicError) {
                failures.add("${p.id}: '${e.message}' at ${e.range}")
            }
        }
        if (failures.isNotEmpty()) fail("${failures.size} of ${programs.size} programs do not parse:\n" + failures.take(40).joinToString("\n"))
    }

    @Test
    fun everyProgramOfTheCorpusParsesToAcornsTree() {
        val failures = ArrayList<String>()
        var compared = 0
        for (p in programs) {
            val expected = digests[p.id] ?: continue
            val actual = try {
                astDigest(parseProgram(p.code).program)
            } catch (e: CosicError) {
                failures.add("${p.id}: '${e.message}' at ${e.range}")
                continue
            }
            compared++
            if (actual != expected) failures.add("${p.id}: the tree differs from acorn's (digest $actual, expected $expected)")
        }
        assertTrue(compared > 500, "only $compared programs have a digest")
        if (failures.isNotEmpty()) fail("${failures.size} of $compared programs differ from acorn:\n" + failures.take(40).joinToString("\n"))
    }

    @Test
    fun theCorpusIsTheWholeParityData() {
        assertTrue(programs.size >= 587, "only ${programs.size} programs")
        assertEquals(programs.size, programs.map { it.id }.toSet().size, "program ids are not unique")
    }
}
