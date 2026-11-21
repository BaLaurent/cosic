package cosic.kabel

import cosic.pattern.CosicError
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The graphs of resources/kabel/graph-vectors.json were made by the real `@kabelsalat/core` and `@kabelsalat/lib` (tools/strudel-ref/kabel/graph-vectors.mjs):
 * for each program the real `evaluate` + `compile` gave the number of registers and the list of ugens with their inputs, or the error they threw, and the
 * real worklet was asked to load the result. The port must agree on all of it: the same programs fail, with the same message, the same graphs have the same
 * nodes in the same order (the registers and ugens), and the programs whose worklet fails to load are the ones whose [KabelProgram.loadFault] is set.
 */
class KabelGraphVectorsTest {
    private val vectors: JsonArray = Json.parseToJsonElement(
        checkNotNull(javaClass.getResourceAsStream("/kabel/graph-vectors.json")) { "graph-vectors.json is missing" }.bufferedReader().readText(),
    ).jsonArray

    /**
     * Programs that fail in both, with a message the port words differently on purpose: a syntax error is V8's text there and the parser's here, and a chain of
     * 100000 nodes ends in V8's recursion limit there and in the limit of [MAX_NODES] here.
     */
    private val messageMayDiffer = setOf("syntax-error", "chain-100000")

    /** The inputs of a ugen as the oracle wrote them: numbers, strings (a constant's text keeps its quotes, a register is `r[N]`), null for undefined. */
    private fun inputOf(e: kotlinx.serialization.json.JsonElement): String = when {
        e is JsonNull -> "undefined"
        e is JsonPrimitive && e.isString -> e.content
        e is JsonPrimitive -> e.content
        else -> e.toString()
    }

    private fun describe(input: UgenInput): String = when (input) {
        is UgenInput.Num -> cosic.pattern.jsNumberToString(input.value)
        is UgenInput.Str -> "\"${input.value}\""
        is UgenInput.Reg -> "r[${input.index}]"
        UgenInput.Missing -> "undefined"
    }

    @Test
    fun theGraphsAreTheOnesTheRealCompilerMakes() {
        assertTrue(vectors.size > 300, "the vector file looks truncated: ${vectors.size}")
        val failures = ArrayList<String>()
        for (v in vectors) {
            val o = v.jsonObject
            val id = o.getValue("id").jsonPrimitive.content
            val code = o.getValue("code").jsonPrimitive.content
            val expectedError = o["error"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val actual: Result<KabelProgram> = try {
                Result.success(compileKabel(code))
            } catch (e: CosicError) {
                Result.failure(e)
            } catch (e: KabelError) {
                Result.failure(e)
            }
            if (expectedError != null) {
                val message = actual.exceptionOrNull()?.message
                when {
                    actual.isSuccess -> failures.add("$id: should fail with '$expectedError' but compiled: $code")
                    message != expectedError && id !in messageMayDiffer -> failures.add("$id: wrong error: expected '$expectedError' got '$message': $code")
                }
                continue
            }
            val program = actual.getOrElse {
                failures.add("$id: should compile but failed with '${it.message}': $code")
                continue
            }
            val registers = o.getValue("registers").jsonPrimitive.intOrNull
            if (registers != program.nodeCount) failures.add("$id: registers $registers, nodes ${program.nodeCount}: $code")
            val ugens = o.getValue("ugens").jsonArray
            val types = ugens.map { it.jsonObject.getValue("type").jsonPrimitive.content }
            if (types != program.ugens.map { it.type }) {
                failures.add("$id: ugens $types, got ${program.ugens.map { it.type }}: $code")
            } else {
                for ((k, u) in ugens.withIndex()) {
                    val expected = u.jsonObject.getValue("inputs").jsonArray.map { inputOf(it) }
                    val got = program.ugens[k].inputs.map { describe(it) }
                    if (expected != got) failures.add("$id: ugen $k (${types[k]}) inputs $expected, got $got: $code")
                }
            }
            val workletFails = o["functionError"]?.takeIf { it !is JsonNull } != null || o["workletError"]?.takeIf { it !is JsonNull } != null
            if (workletFails != (program.loadFault != null)) {
                failures.add("$id: the worklet ${if (workletFails) "fails to load" else "loads"}, the port says loadFault=${program.loadFault}: $code")
            }
        }
        assertEquals(emptyList(), failures, "${failures.size} of ${vectors.size} graphs differ:\n" + failures.joinToString("\n"))
    }
}
