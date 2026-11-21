package cosic.parity

import cosic.script.ReplSession
import cosic.script.catalogue.OUT_OF_SCOPE_FUNCTIONS
import cosic.script.catalogue.OUT_OF_SCOPE_METHODS
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.TreeSet
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every global, `Pattern` method and control that Strudel's oracle runtime exposes (strudel-names.json) must either be
 * implemented by Cosic or be listed in coverage-exclusions.txt with a reason. The reasons are an internal of Strudel's or a
 * mock of its test runtime that no program uses (the REPL, Web Audio, the sound registry, pattern.mjs, Tone.js), what the parity plan
 * rules out (Hydra and CSound, whose names are defined but are errors that say so, see `registerOutOfScope`: they count as not
 * implemented here), and a task of the plan that still has to cover the name. "not implemented yet" is the temporary reason the generator
 * writes, and Phase 5 of the plan requires that none is left.
 *
 * With `-Dcosic.parity.writeGaps=true` nothing fails: the file is rewritten with the names that are not implemented,
 * keeping the reasons already written there.
 */
class CoverageTest {

    @Test
    fun everyStrudelNameIsImplementedOrExcluded() {
        val names = strudelNames()
        val exclusions = readAnnotated(ParityData.coverageExclusionsFile)
        if (ParityData.writeGaps) {
            writeAnnotated(
                ParityData.coverageExclusionsFile,
                listOf(
                    "Strudel names (globals, Pattern methods and controls of the oracle runtime) that Cosic does not implement,",
                    "one per line: <name>  # reason. A name is covered once Cosic implements it; delete its line then.",
                    "A reason says why the name is left out: an internal of Strudel's (REPL, Web Audio, the sound registry, pattern.mjs) or a mock of its test runtime",
                    "that no program uses, `out of scope: ...` (Hydra and CSound, ruled out by decision 4 of the parity plan: the name is defined and is an error that says so),",
                    "or `Task N.N: ...` for a name that a task of the plan has still to cover (only the dough engine, which is being done).",
                    "\"$NOT_IMPLEMENTED_YET\" is the temporary reason the generator writes for a new name; the final acceptance requires that none is left.",
                    "Regenerate: ./gradlew :engine:test --tests 'cosic.parity.*' -Dcosic.parity.writeGaps=true",
                ),
                names.filterNot { isImplemented(it) }.associateWith { exclusions[it]?.takeIf(String::isNotEmpty) ?: NOT_IMPLEMENTED_YET },
            )
            return
        }
        val missing = names.filter { !isImplemented(it) && it !in exclusions }
        val stale = exclusions.filter { (name, reason) -> reason == NOT_IMPLEMENTED_YET && isImplemented(name) }.keys
        val unknown = exclusions.keys.filter { it !in names }
        val problems = buildList {
            if (missing.isNotEmpty()) add("not implemented and not excluded (${missing.size}): ${preview(missing)}")
            if (stale.isNotEmpty()) add("implemented now, remove from coverage-exclusions.txt (${stale.size}): ${preview(stale)}")
            if (unknown.isNotEmpty()) add("excluded but not a Strudel name (${unknown.size}): ${preview(unknown)}")
        }
        assertTrue(
            problems.isEmpty(),
            problems.joinToString("\n") + "\n(list the missing names with ./gradlew :engine:test --tests 'cosic.parity.*' -Dcosic.parity.writeGaps=true)",
        )
    }

    private fun preview(names: Collection<String>, limit: Int = 60): String =
        names.take(limit).joinToString(" ") + if (names.size > limit) " ... and ${names.size - limit} more" else ""

    /**
     * Whether a program can use [name] today: its world has a global or a method of `Pattern.prototype` of that name. That is the catalogue
     * plus what the REPL adds to it when it evaluates (`setcps`, `setcpm`, `all`, `each`, `hush`, `p`, ...), in a realm of its own, without
     * the oracle's mocks, which would make names such as `filter` look implemented. The entry points of Hydra and CSound do not count: they are
     * defined so that a program that calls one gets an error that says what is not there.
     */
    private fun isImplemented(name: String): Boolean = name in implemented && name !in outOfScope

    private val outOfScope: Set<String> = (OUT_OF_SCOPE_FUNCTIONS + OUT_OF_SCOPE_METHODS).toSet()

    private val implemented: Set<String> by lazy {
        val session = ReplSession()
        session.evaluate("")
        (session.realm.global.ownKeys() + session.realm.patternPrototype.ownKeys()).toSet()
    }

    private fun strudelNames(): Set<String> {
        val json = Json.parseToJsonElement(ParityData.namesFile.readText()).jsonObject
        val names = TreeSet<String>()
        for (list in listOf("globals", "methods", "controls")) {
            json.getValue(list).jsonArray.mapTo(names) { it.jsonPrimitive.content }
        }
        return names
    }
}
