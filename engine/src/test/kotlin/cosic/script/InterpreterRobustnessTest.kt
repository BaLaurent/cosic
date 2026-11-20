package cosic.script

import cosic.pattern.CosicError
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.fail

/**
 * Runs deterministic mutations of the snippets of the oracle test (characters and words deleted, swapped, replaced,
 * operators inserted). Most of them are programs that no longer mean anything, but each one either does not parse, or
 * runs to a value, or ends in a [CosicError]: a program, however wrong, must never make the interpreter fail in any
 * other way (a ClassCastException, a null pointer, an unresolved name in the tree...).
 */
class InterpreterRobustnessTest {
    private fun resource(name: String): String =
        javaClass.getResourceAsStream("/script/interpreter/$name")!!.bufferedReader().readText()

    private val codes: List<String> by lazy {
        val out = ArrayList<String>()
        var lines = ArrayList<String>()
        var name: String? = null
        for (line in resource("cases.txt").split('\n')) {
            if (line.startsWith("## ")) continue
            if (line.startsWith("### ")) {
                if (name != null) out.add(lines.joinToString("\n"))
                name = line
                lines = ArrayList()
            } else if (name != null) {
                lines.add(line)
            }
        }
        if (name != null) out.add(lines.joinToString("\n"))
        out
    }

    private val words = listOf("undefined", "null", "0", "this", "[]", "{}", "() => 1", "'s'", "-1", "NaN", "...a", "a", "f", "o")
    private val operators = listOf("await ", "new ", "...", "?.", "!", "typeof ", "delete ", "void ", "-", "++", "[", "(", "`", "=> ", "in ", "instanceof ")

    private fun mutate(code: String, random: Random): String {
        if (code.isEmpty()) return code
        val at = random.nextInt(code.length)
        return when (random.nextInt(6)) {
            0 -> code.removeRange(at, at + 1)
            1 -> code.removeRange(at, minOf(code.length, at + 1 + random.nextInt(8)))
            2 -> code.substring(0, at) + operators[random.nextInt(operators.size)] + code.substring(at)
            3 -> code.substring(0, at) + words[random.nextInt(words.size)] + code.substring(at)
            4 -> {
                val lines = code.split('\n').toMutableList()
                if (lines.size > 1) {
                    val i = random.nextInt(lines.size)
                    val j = random.nextInt(lines.size)
                    val t = lines[i]
                    lines[i] = lines[j]
                    lines[j] = t
                }
                lines.joinToString("\n")
            }
            else -> {
                // Replace the word around [at] with another one.
                var s = at
                var e = at
                while (s > 0 && code[s - 1].isLetterOrDigit()) s--
                while (e < code.length && code[e].isLetterOrDigit()) e++
                code.substring(0, s) + words[random.nextInt(words.size)] + code.substring(e)
            }
        }
    }

    @Test
    fun mutatedProgramsNeverBreakTheInterpreter() {
        val random = Random(20261120)
        var ran = 0
        var parsed = 0
        for (code in codes) {
            repeat(60) {
                val mutant = mutate(code, random)
                ran++
                val realm = testRealm()
                realm.limits = Limits(steps = 20_000, millis = 3_000, maxDepth = 200)
                try {
                    realm.runRaw(mutant)
                    parsed++
                } catch (e: CosicError) {
                    // an error of the program, or a syntax error: that is fine
                } catch (e: Throwable) {
                    fail("the interpreter failed with ${e::class.simpleName}: ${e.message}\non the program:\n$mutant\n${e.stackTraceToString().lines().take(12).joinToString("\n")}")
                }
            }
        }
        if (parsed < ran / 10) fail("only $parsed of $ran mutants ran: the mutations are too destructive to test anything")
    }

    @Test
    fun theCasesThemselvesRunWithoutAnInternalError() {
        // Sanity check of the harness: the unmutated snippets are programs that run (or end in a CosicError).
        val failures = ArrayList<String>()
        for (code in codes) {
            val realm = testRealm()
            try {
                realm.runRaw(code)
            } catch (e: CosicError) {
                // fine
            } catch (e: Throwable) {
                failures.add("${e::class.simpleName}: ${e.message} on $code")
            }
        }
        if (failures.isNotEmpty()) fail(failures.take(5).joinToString("\n"))
    }
}
