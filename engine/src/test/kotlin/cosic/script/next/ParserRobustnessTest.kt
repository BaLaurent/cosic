package cosic.script.next

import cosic.parity.ParityData
import cosic.pattern.CosicError
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Whatever the code looks like, the parser and the tokenizer answer: a tree whose offsets are sound, or a [CosicError]
 * whose range lies inside the code. Nothing else may come out (no index error, no stack overflow). The inputs are
 * mutations of real programs: characters deleted, duplicated, swapped, replaced by syntax or by awkward Unicode, and code
 * cut off anywhere.
 */
class ParserRobustnessTest {
    private val sources: List<String> by lazy {
        val corpus = ParityData.cases.flatMap { it.codes }.filter { it.length < 500 }
        val oracle = javaClass.getResourceAsStream("/script/parser/cases.txt")!!.bufferedReader().readText()
            .split(Regex("(?m)^### .*$")).map { it.trim('\n') }.filter { it.isNotEmpty() && !it.startsWith("## ") && it.length < 300 }
        corpus + oracle
    }

    private val alphabet = listOf(
        "\"", "'", "`", "\${", "}", "{", "(", ")", "[", "]", ",", ";", ":", ".", "=", "+", "-", "*", "/", "%", "<", ">", "!", "?", "&", "|", "^", "~",
        "@", "#", "\\", " ", "\n", "\r\n", "\u2028", "\ufeff", "\u00a0", "\uD800", "\uDC00", "\uD83D\uDE00", "\u0000", "\\u0061", "\\u{", "=>", "...", "?.",
        "async ", "await ", "let ", "const ", "function ", "class ", "yield ", "new ", "typeof ", "if (", "else ", "for (", "of ", "in ", "return ",
        "0x", "1e", "1_", ".5", "0b2", "10n", "/*", "*/", "//",
    )

    private fun mutate(source: String, random: Random): String {
        var text = source
        repeat(1 + random.nextInt(4)) {
            val at = if (text.isEmpty()) 0 else random.nextInt(text.length + 1)
            text = when (random.nextInt(7)) {
                0 -> text.removeRange(at, minOf(text.length, at + 1 + random.nextInt(3)))
                1 -> text.substring(0, at) + text.substring(at, minOf(text.length, at + 1 + random.nextInt(8))) + text.substring(at)
                2 -> text.substring(0, at) + alphabet[random.nextInt(alphabet.size)] + text.substring(at)
                3 -> text.substring(0, at)
                4 -> if (at + 1 < text.length) text.substring(0, at) + text[at + 1] + text[at] + text.substring(at + 2) else text
                5 -> text.substring(0, at) + "\n" + text.substring(at)
                else -> text.removeRange(at, minOf(text.length, at + 1)) + alphabet[random.nextInt(alphabet.size)]
            }
        }
        return text
    }

    private fun describe(code: String): String = "\"" + code.take(200).replace("\\", "\\\\").replace("\n", "\\n").replace("\r", "\\r") + "\""

    private fun assertSound(code: String, node: Node, parent: Node?) {
        assertTrue(node.start in 0..node.end && node.end <= code.length, "${describe(code)}: bad range of $node")
        if (parent != null) assertTrue(node.start >= parent.start && node.end <= parent.end, "${describe(code)}: $node is not inside $parent")
        var previousStart = node.start
        node.forEachChild { child ->
            assertTrue(child.start >= previousStart, "${describe(code)}: children of $node are not in source order")
            previousStart = child.start
            assertSound(code, child, node)
        }
    }

    @Test
    fun mutatedProgramsEitherParseToASoundTreeOrFailWithAnError() {
        val random = Random(20241120)
        var parsed = 0
        var failed = 0
        repeat(30_000) {
            val code = mutate(sources[random.nextInt(sources.size)], random)
            try {
                val result = parseProgram(code)
                parsed++
                assertTrue(result.program.start == 0 && result.program.end == code.length, "${describe(code)}: program range")
                assertSound(code, result.program, null)
                var previousEnd = 0
                for (c in result.comments) {
                    assertTrue(c.start >= previousEnd && c.end <= code.length && c.end > c.start, "${describe(code)}: comment range")
                    previousEnd = c.end
                }
            } catch (e: CosicError) {
                failed++
                val range = e.range
                assertTrue(!e.message.isBlank(), "${describe(code)}: empty message")
                assertTrue(range != null && range.start >= 0 && range.start <= range.end && range.end <= code.length, "${describe(code)}: range $range of '${e.message}'")
                assertTrue(range.end > range.start || code.isEmpty(), "${describe(code)}: empty range for '${e.message}'")
            } catch (t: Throwable) {
                fail("${describe(code)}: ${t::class.simpleName}: ${t.message}")
            }
            // The editor highlights while the user types: the tokenizer must hold up on the same inputs.
            var end = 0
            for (span in tokenize(code)) {
                assertTrue(span.start >= end && span.end > span.start && span.end <= code.length, "${describe(code)}: bad span $span")
                end = span.end
            }
        }
        assertTrue(parsed > 3_000 && failed > 3_000, "the mutations should give both: $parsed parsed, $failed failed")
    }
}
