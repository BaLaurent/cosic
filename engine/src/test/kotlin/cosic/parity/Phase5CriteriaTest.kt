package cosic.parity

import org.junit.Assume.assumeFalse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The completion criteria of Phase 5 of the parity plan for pattern parity, as tests: `known-gaps.txt` holds only cases whose code calls an entry point of
 * Hydra or CSound, each as `# out of scope: <entry point>`; `coverage-exclusions.txt` has no `# not implemented yet` line and no reason that names a task of the
 * plan, except for the dough engine, which is being done elsewhere (when it lands its lines go, and this stays true).
 */
class Phase5CriteriaTest {
    @Test
    fun knownGapsHoldOnlyCasesThatCallHydraOrCsound() {
        assumeFalse("known-gaps.txt is being rewritten", ParityData.writeGaps)
        val byId = ParityData.cases.associateBy { it.id }
        for ((id, reason) in KnownGaps.entries) {
            assertTrue(reason.startsWith("out of scope: "), "$id: the reason is `$reason`, not `out of scope: <entry point>`")
            val case = checkNotNull(byId[id]) { "$id is no case" }
            assertTrue(case.codes.any { OUT_OF_SCOPE_ENTRY_POINT.containsMatchIn(it) }, "$id calls no entry point of Hydra or CSound")
        }
    }

    @Test
    fun noExclusionIsLeftToDo() {
        assumeFalse("coverage-exclusions.txt is being rewritten", ParityData.writeGaps)
        val exclusions = readAnnotated(ParityData.coverageExclusionsFile)
        assertEquals(emptyList(), exclusions.filterValues { it == NOT_IMPLEMENTED_YET }.keys.toList(), "names that are still `$NOT_IMPLEMENTED_YET`")
        val pending = exclusions.filterValues { Regex("""\bTask \d""").containsMatchIn(it) || "no task in the plan" in it || "Phase 2 gap" in it }.keys
        assertEquals(emptySet(), pending - setOf("dough", "initDough"), "exclusions whose reason still says who has to do it")
    }
}
