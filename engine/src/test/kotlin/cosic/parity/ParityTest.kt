package cosic.parity

import cosic.pattern.CosicError
import cosic.pattern.Voicings
import org.junit.AfterClass
import org.junit.Assert.fail
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Replays Strudel's own tests against Cosic (K2 of the parity plan): every `@example` of its documentation and every
 * REPL tune, with the haps Strudel's snapshot suite recorded, plus the snippets Cosic authors under
 * `parity/extra-snippets/`. Each program is evaluated, queried over `[0, cycles)`, sorted by part and printed like
 * `hap.show(true)`; the strings must be identical.
 *
 * `known-gaps.txt` lists the cases that are expected to fail until their feature lands. An unlisted mismatch fails the
 * test, and so does a listed case that now matches (remove it from the file). With `-Dcosic.parity.writeGaps=true`
 * nothing fails: the file is rewritten with every case that fails, keeping the reasons already written there.
 * `engine/build/reports/parity.md` is the pass/fail list of everything.
 */
@RunWith(Parameterized::class)
internal class ParityTest(private val case: ParityCase) {

    @Test
    fun matchesStrudel() {
        val expected = case.expected
        if (expected == null) {
            ParityReport.record(case, Status.SKIP, case.skipReason)
            assumeTrue("${case.id}: ${case.skipReason}", false)
            return
        }
        val problem = mismatch(case, expected, withOracleFilter { runCase(case) })
        ParityReport.record(case, if (problem == null) Status.PASS else Status.FAIL, problem)
        if (ParityData.writeGaps) return
        if (ParityData.only != null) {
            if (problem != null) fail("${case.id} does not match Strudel\n${shortened(case.codes.joinToString("\n---\n"))}\n$problem")
            return
        }
        val listed = case.id in KnownGaps.entries
        when {
            problem == null && listed -> fail("remove '${case.id}' from known-gaps.txt: it now matches Strudel")
            problem != null && !listed -> fail(
                "${case.id} does not match Strudel\n${shortened(case.codes.joinToString("\n---\n"))}\n$problem\n" +
                    "(expected to fail for now? list it with ./gradlew :engine:test --tests 'cosic.parity.*' -Dcosic.parity.writeGaps=true)",
            )
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> =
            ParityData.cases.filter { ParityData.only?.containsMatchIn(it.id) ?: true }.map { arrayOf<Any>(it) }

        /** Strudel's own run starts in a fresh process: the voicing dictionaries are the ones it ships, whatever another test did to them. */
        @JvmStatic
        @BeforeClass
        fun startWithTheShippedVoicings() = Voicings.restore()

        /**
         * The extras register voicings into the global registry (`voicingAlias('^7', ...)` adds a symbol to the real `ireal` dictionary):
         * what stays there would be seen by the tests that run after this one in the same JVM.
         */
        @JvmStatic
        @AfterClass
        fun writeOutputs() {
            Voicings.restore()
            ParityReport.write(File(moduleDir(), "build/reports/parity.md"))
            if (ParityData.writeGaps) ParityReport.rewriteKnownGaps()
        }
    }
}

/** known-gaps.txt: the ids of the cases that are expected to fail, with the reason. */
internal object KnownGaps {
    val entries: Map<String, String> by lazy { readAnnotated(ParityData.knownGapsFile) }
}

/**
 * A call of an entry point of Hydra or CSound (decision 4 of the parity plan): `initHydra`, `clearHydra`, `H(`, `loadOrc`, `loadCsound` and its spellings, `.csound(` and
 * `.csoundm(`. A case whose code has one is out of scope in the app, where the call is an error (`OutOfScopeTest`).
 */
internal val OUT_OF_SCOPE_ENTRY_POINT =
    Regex("""\binitHydra\b|\bclearHydra\b|\bH\(|\bloadOrc\b|\bloadCsound\b|\bloadCSound\b|\bloadcsound\b|\.csoundm?\(""")

/** known-gaps.txt must only name cases that exist and that can fail. */
internal class KnownGapsTest {
    @Test
    fun everyListedGapIsACaseThatCanFail() {
        assumeFalse("known-gaps.txt is being rewritten", ParityData.writeGaps)
        val byId = ParityData.cases.associateBy { it.id }
        val unknown = KnownGaps.entries.keys.filter { it !in byId }
        val nothingToCompare = KnownGaps.entries.keys.filter { byId[it]?.expected == null && it in byId }
        check(unknown.isEmpty()) { "known-gaps.txt names cases that do not exist: $unknown" }
        check(nothingToCompare.isEmpty()) { "known-gaps.txt names cases that are skipped, so they cannot fail: $nothingToCompare" }
    }
}

// ---- running a case ----------------------------------------------------------------------------------------------

/** What Cosic did with a case; the counterpart of [ExpectedOutcome]. */
internal class ActualOutcome(
    val haps: List<String>,
    /** The message evaluation failed with, or null when it succeeded. */
    val error: String?,
    val cps: Double?,
    val miniLocations: List<List<Int>>?,
)

private const val CASE_TIMEOUT_SECONDS = 60L

/**
 * Cases run on their own threads: a deep program needs more stack than a test thread has, and a program that never
 * finishes must not hang the suite. A thread that runs past the time limit cannot be stopped; it is abandoned (daemon).
 */
private val workers = Executors.newCachedThreadPool { runnable ->
    Thread(null, runnable, "parity-worker", 256L * 1024 * 1024).apply { isDaemon = true }
}

private fun failed(message: String?) = ActualOutcome(emptyList(), message ?: "evaluation failed", null, null)

internal fun runCase(case: ParityCase): ActualOutcome {
    val future = workers.submit(Callable { if (case.repl) runReplCase(case) else evaluateOnce(case) })
    return try {
        future.get(CASE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        future.cancel(true)
        failed("timed out after ${CASE_TIMEOUT_SECONDS}s")
    } catch (e: ExecutionException) {
        val cause = e.cause ?: e
        failed("${cause::class.simpleName}: ${cause.message}")
    }
}

/** `queryCode` of Strudel's test/runtime.mjs: evaluate, sort by part, query `[0, cycles)`, print every hap. */
private fun evaluateOnce(case: ParityCase): ActualOutcome {
    val pattern = try {
        evaluateForQuery(case.codes.single())
    } catch (e: CosicError) {
        return failed(e.message)
    }
    val haps = try {
        queriedHaps(pattern, case)
    } catch (e: CosicError) {
        return failed(e.message) // only running out of stack or memory gets here: a failing query answers nothing
    }
    return ActualOutcome(haps, null, null, null)
}

// ---- comparing ---------------------------------------------------------------------------------------------------

/** Why [actual] differs from what Strudel produced, or null when they agree. */
internal fun mismatch(case: ParityCase, expected: ExpectedOutcome, actual: ActualOutcome): String? {
    val problems = ArrayList<String>()
    if (expected.error != null) {
        when {
            actual.error == null -> problems += "Strudel fails with '${expected.error}', but evaluation succeeded"
            case.exactMessage && actual.error != expected.error ->
                problems += "error message differs\n  expected: ${expected.error}\n  actual:   ${actual.error}"
        }
    } else if (actual.error != null) {
        problems += "evaluation failed: ${actual.error}"
    }
    // After an error a plain program has no haps to compare; a REPL session keeps running its previous pattern.
    if (case.repl || (expected.error == null && actual.error == null)) {
        if (expected.haps != actual.haps) problems += hapDifference(expected.haps, actual.haps)
    }
    if (case.repl) {
        if (expected.cps != null && (actual.cps == null || Math.abs(expected.cps - actual.cps) > 1e-9)) {
            problems += "tempo differs: expected ${expected.cps} cps, got ${actual.cps}"
        }
        if (expected.miniLocations != null && expected.miniLocations != actual.miniLocations) {
            problems += "mini-notation locations differ\n  expected: ${expected.miniLocations}\n  actual:   ${actual.miniLocations}"
        }
    }
    return problems.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

private const val SHOWN_LINES = 10

private fun hapDifference(expected: List<String>, actual: List<String>): String {
    val first = (0 until maxOf(expected.size, actual.size)).first { expected.getOrNull(it) != actual.getOrNull(it) }
    return buildString {
        append("haps differ: expected ${expected.size}, got ${actual.size}; first difference at index $first")
        if (expected.size == actual.size && expected.sorted() == actual.sorted()) {
            append(" (the same haps in a different order: an ordering bug in a combinator, not a value bug)")
        }
        append("\n  expected:")
        expected.drop(first).take(SHOWN_LINES).forEach { append("\n    ").append(it) }
        append("\n  actual:")
        actual.drop(first).take(SHOWN_LINES).forEach { append("\n    ").append(it) }
    }
}

private fun shortened(code: String, limit: Int = 1200): String = if (code.length <= limit) code else code.take(limit) + "\n..."

/**
 * The `engine` directory (the data lives in `engine/src/test/resources/parity`), or the working directory when the data
 * directory was moved with `-Dcosic.parity.dir`.
 */
internal fun moduleDir(): File {
    var dir: File? = ParityData.dir
    repeat(4) { dir = dir?.parentFile }
    return dir?.takeIf { File(it, "build.gradle.kts").exists() } ?: File(".").absoluteFile
}

// ---- the report --------------------------------------------------------------------------------------------------

internal enum class Status(val word: String) { PASS("pass"), FAIL("FAIL"), SKIP("skip") }

/**
 * Collects the outcome of every case. [write] produces `engine/build/reports/parity.md`, the living point-by-point
 * checklist; [rewriteKnownGaps] is the `-Dcosic.parity.writeGaps=true` mode.
 */
internal object ParityReport {
    private class Entry(val case: ParityCase, val status: Status, val detail: String?)

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun record(case: ParityCase, status: Status, detail: String?) {
        entries[case.id] = Entry(case, status, detail)
    }

    @Synchronized
    fun write(file: File) {
        val all = ParityData.cases.mapNotNull { entries[it.id] }
        file.parentFile.mkdirs()
        file.writeText(
            buildString {
                append("# Parity report\n\n")
                append("Every Strudel `@example` and REPL tune at the pinned commit, and the extra snippets, run through Cosic ")
                append("and compared hap for hap. Generated by `cosic.parity.ParityTest`; `gap` marks a case listed in known-gaps.txt.\n\n")
                append("| group | pass | fail | skipped | not run | total |\n|---|---:|---:|---:|---:|---:|\n")
                for (source in CaseSource.entries) {
                    val cases = ParityData.cases.filter { it.source == source }
                    val ran = all.filter { it.case.source == source }
                    append("| ${source.prefix} | ${ran.count { it.status == Status.PASS }} | ${ran.count { it.status == Status.FAIL }} | ")
                    append("${ran.count { it.status == Status.SKIP }} | ${cases.size - ran.size} | ${cases.size} |\n")
                }
                section("Examples, by Strudel function", CaseSource.EXAMPLES, all)
                section("Tunes", CaseSource.TUNES, all)
                section("Extra snippets", CaseSource.EXTRA, all)
                outOfScope(all)
            },
        )
    }

    private fun StringBuilder.section(title: String, source: CaseSource, all: List<Entry>) {
        val ran = all.filter { it.case.source == source }
        append("\n## $title\n\n")
        if (source == CaseSource.EXAMPLES) {
            append("| function | pass | total | examples |\n|---|---:|---:|---|\n")
            for ((name, group) in ran.groupBy { it.case.name }) {
                val shown = group.joinToString(", ") { "#${it.case.index} ${label(it)}" }
                append("| `$name` | ${group.count { it.status == Status.PASS }} | ${group.size} | $shown |\n")
            }
        } else {
            append("| case | result | detail |\n|---|---|---|\n")
            for (entry in ran) {
                val detail = entry.detail?.lineSequence()?.firstOrNull().orEmpty().replace("|", "\\|").take(160)
                append("| `${entry.case.name}` | ${label(entry)} | $detail |\n")
            }
        }
    }

    /** The cases that call Hydra or CSound, which the oracle's runtime mocks away and the app reports as errors. */
    private fun StringBuilder.outOfScope(all: List<Entry>) {
        val cases = all.filter { entry -> entry.case.codes.any { OUT_OF_SCOPE_ENTRY_POINT.containsMatchIn(it) } }
        append("\n## Out of scope: Hydra and CSound\n\n")
        append("Decision 4 of the parity plan rules both out: in the app their entry points are the error `'<name>' is not available in this app` (`OutOfScopeTest`). ")
        append("Strudel's own test runtime mocks `csound`, `loadCsound`, `loadOrc` and the like with functions that do nothing and `ParityHost` repeats that, ")
        append("so what these cases have besides the CSound calls is compared like any other.\n\n")
        if (cases.isEmpty()) {
            append("No case calls one.\n")
            return
        }
        append("| case | result |\n|---|---|\n")
        for (entry in cases) append("| `${entry.case.id}` | ${label(entry)} |\n")
    }

    private fun label(entry: Entry): String =
        entry.status.word + if (entry.status == Status.FAIL && entry.case.id in KnownGaps.entries) " (gap)" else ""

    /** Rewrites known-gaps.txt with every failing case, keeping the reasons written there; new ones get the default. */
    @Synchronized
    fun rewriteKnownGaps() {
        if (entries.size != ParityData.cases.size) {
            println("known-gaps.txt left alone: only ${entries.size} of ${ParityData.cases.size} cases ran (filtered run)")
            return
        }
        val reasons = KnownGaps.entries
        val failing = ParityData.cases.filter { entries.getValue(it.id).status == Status.FAIL }
        writeAnnotated(
            ParityData.knownGapsFile,
            listOf(
                "Parity cases that are expected to fail until their feature lands, one per line: <id>  # reason",
                "ids: examples:<function>#<index>, tunes:<tune>, extra:<file>/<snippet> (the file stem of parity/extra/<file>.json)",
                "ParityTest fails on a case that mismatches and is not listed, and on a listed case that now matches.",
                "Regenerate: ./gradlew :engine:test --tests 'cosic.parity.*' -Dcosic.parity.writeGaps=true",
                "Phase 5: only a case whose code calls an entry point of Hydra or CSound (`initHydra`, `H(`, `loadOrc`, `loadCsound`, `.csound`, `.csoundm`) may be listed, as",
                "`<id>  # out of scope: <entry point>`. None needs it: the oracle's own runtime mocks `csound`, `loadCsound`, `loadOrc` and the like with functions that do nothing,",
                "`ParityHost` repeats those mocks, so the tunes `csoundDemo` and `loungeSponge` are compared with Strudel's haps like any other (the CSound calls are mocked away);",
                "what a program of the app gets from those entry points is the error `'<name>' is not available in this app`, which `OutOfScopeTest` checks.",
                "The 19 examples marked `skipped` in examples.json are Strudel's own skip list (the oracle has no snapshot for them), not gaps.",
            ),
            failing.associate { it.id to (reasons[it.id]?.takeIf(String::isNotEmpty) ?: NOT_IMPLEMENTED_YET) },
        )
    }
}
