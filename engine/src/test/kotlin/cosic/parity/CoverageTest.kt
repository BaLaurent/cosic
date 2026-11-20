package cosic.parity

import cosic.script.next.Realm
import cosic.script.next.SessionHost
import cosic.script.next.catalogue.Catalogue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.TreeSet
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Every global, `Pattern` method and control that Strudel's oracle runtime exposes (strudel-names.json) must either be
 * implemented by Cosic or be listed in coverage-exclusions.txt with a reason. The allowed reasons are browser and REPL
 * internals with no meaning for a user (`getDrawContext`, `getAudioContext`, ...), Hydra, CSound, and the names the
 * parity plan rules out; "not implemented yet" is the temporary reason the generator writes, and Phase 5 of the plan
 * requires that none is left.
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
                    "Strudel names (globals, Pattern methods and controls of the oracle runtime) that Cosic has no builtin for,",
                    "one per line: <name>  # reason. A name is covered once Cosic implements it; delete its line then.",
                    "Reasons other than \"$NOT_IMPLEMENTED_YET\" are deliberate exclusions: browser and REPL internals, Hydra,",
                    "CSound, and what decision 4 of the parity plan rules out.",
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
     * Whether a program can use [name] today: the catalogue gives a global or a method of `Pattern.prototype` of that
     * name. (A realm of its own, without the oracle's mocks, which would make names such as `filter` look implemented.)
     */
    private fun isImplemented(name: String): Boolean = name in implemented

    private val implemented: Set<String> by lazy {
        val realm = Realm(SessionHost())
        Catalogue.install(realm)
        (realm.global.ownKeys() + realm.patternPrototype.ownKeys()).toSet()
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
