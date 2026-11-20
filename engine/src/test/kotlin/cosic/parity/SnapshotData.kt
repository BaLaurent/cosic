package cosic.parity

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/*
 * The data of the parity harness (K2 of the parity plan): what Strudel produced for its own examples and tunes, and
 * for the snippets Cosic authors, as exported by tools/strudel-ref/export.mjs into engine/src/test/resources/parity/.
 */

/** Where a parity case comes from; the prefix of its id. */
internal enum class CaseSource(val prefix: String) {
    EXAMPLES("examples"),
    TUNES("tunes"),
    EXTRA("extra"),
}

/** What Strudel produced: the haps (as `hap.show(true)` strings) and, for REPL cases, the tempo and mini locations. */
internal class ExpectedOutcome(
    val haps: List<String>,
    /** The message Strudel failed with, or null when it ran fine. */
    val error: String?,
    val cps: Double?,
    val miniLocations: List<List<Int>>?,
)

/**
 * One program to run and compare. [id] is `examples:<function>#<index>`, `tunes:<tune>` or `extra:<snippet>`, the form
 * known-gaps.txt uses. A case with no [expected] has nothing to compare against and is skipped for [skipReason].
 */
internal class ParityCase(
    val id: String,
    val source: CaseSource,
    /** The Strudel function (examples), tune or snippet name. */
    val name: String,
    val index: Int,
    /** One program; a REPL case evaluates them in order, in one session. */
    val codes: List<String>,
    val repl: Boolean,
    val cycles: Int,
    /** Tunes and extras are compared after `roundFloatStrings`, examples verbatim. */
    val roundFloats: Boolean,
    val exactMessage: Boolean,
    val expected: ExpectedOutcome?,
    val skipReason: String?,
) {
    override fun toString(): String = id
}

internal object ParityData {
    /** `engine/src/test/resources/parity`, found from the test's working directory (Gradle runs tests in `engine/`). */
    val dir: File by lazy {
        System.getProperty("cosic.parity.dir")?.let { return@lazy File(it) }
        listOf("src/test/resources/parity", "engine/src/test/resources/parity")
            .map(::File)
            .firstOrNull { it.isDirectory }
            ?.absoluteFile
            ?: error("engine/src/test/resources/parity not found from ${File(".").absolutePath}; set -Dcosic.parity.dir")
    }

    val writeGaps: Boolean = System.getProperty("cosic.parity.writeGaps") == "true"

    /** `-Dcosic.parity.only=<regex>`: run only the cases whose id matches; known-gaps.txt is ignored then. */
    val only: Regex? = System.getProperty("cosic.parity.only")?.takeIf { it.isNotBlank() }?.let { Regex(it) }

    val knownGapsFile: File get() = File(dir, "known-gaps.txt")
    val coverageExclusionsFile: File get() = File(dir, "coverage-exclusions.txt")
    val namesFile: File get() = File(dir, "strudel-names.json")

    /** Examples, then tunes, then extra snippets (by file, in file order). */
    val cases: List<ParityCase> by lazy {
        val all = loadExamples() + loadTunes() + loadExtras()
        val duplicate = all.groupBy { it.id }.filterValues { it.size > 1 }.keys
        check(duplicate.isEmpty()) { "duplicate parity case ids: $duplicate" }
        all
    }

    private fun parseArray(file: File): JsonArray = Json.parseToJsonElement(file.readText()).jsonArray

    private fun loadExamples(): List<ParityCase> = parseArray(File(dir, "examples.json")).map { entry ->
        val o = entry.jsonObject
        val name = o.string("name")
        val index = o.getValue("index").jsonPrimitive.int
        val skipped = o.getValue("skipped").jsonPrimitive.boolean
        val haps = o.stringList("haps")
        ParityCase(
            id = "${CaseSource.EXAMPLES.prefix}:$name#$index",
            source = CaseSource.EXAMPLES,
            name = name,
            index = index,
            codes = listOf(o.string("code")),
            repl = false,
            cycles = o.getValue("cycles").jsonPrimitive.int,
            roundFloats = false,
            exactMessage = false,
            expected = haps?.let { ExpectedOutcome(it, null, null, null) },
            skipReason = when {
                skipped -> "skipped by Strudel's own skip list"
                haps == null -> "Strudel has no snapshot for it"
                else -> null
            },
        )
    }

    private fun loadTunes(): List<ParityCase> = parseArray(File(dir, "tunes.json")).map { entry ->
        val o = entry.jsonObject
        val name = o.string("name")
        val haps = o.stringList("haps")
        ParityCase(
            id = "${CaseSource.TUNES.prefix}:$name",
            source = CaseSource.TUNES,
            name = name,
            index = 0,
            codes = listOf(o.string("code")),
            repl = false,
            cycles = o.getValue("cycles").jsonPrimitive.int,
            roundFloats = true,
            exactMessage = false,
            expected = haps?.let { ExpectedOutcome(it, null, null, null) },
            skipReason = if (haps == null) "Strudel has no snapshot for it" else null,
        )
    }

    private fun loadExtras(): List<ParityCase> {
        val files = File(dir, "extra").listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().sortedBy { it.name }
        return files.flatMap { file ->
            parseArray(file).mapIndexed { position, entry ->
                val o = entry.jsonObject
                val name = o.string("name")
                val repl = o["mode"]?.jsonPrimitive?.contentOrNull == "repl"
                ParityCase(
                    id = "${CaseSource.EXTRA.prefix}:$name",
                    source = CaseSource.EXTRA,
                    name = name,
                    index = position,
                    codes = if (repl) o.getValue("codes").jsonArray.map { it.jsonPrimitive.content } else listOf(o.string("code")),
                    repl = repl,
                    cycles = o.getValue("cycles").jsonPrimitive.int,
                    roundFloats = true,
                    exactMessage = o["exactMessage"]?.jsonPrimitive?.booleanOrNull == true,
                    expected = ExpectedOutcome(
                        haps = o.stringList("haps") ?: error("${file.name}: $name has no haps"),
                        error = o["error"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content,
                        cps = o["cps"]?.takeUnless { it is JsonNull }?.jsonPrimitive?.double,
                        miniLocations = o["miniLocations"]?.takeUnless { it is JsonNull }?.jsonArray?.map { pair ->
                            pair.jsonArray.map { it.jsonPrimitive.int }
                        },
                    ),
                    skipReason = null,
                )
            }
        }
    }

    private fun JsonObject.string(key: String): String = getValue(key).jsonPrimitive.content

    /** The strings of an array member, or null when the member is JSON null. */
    private fun JsonObject.stringList(key: String): List<String>? {
        val element: JsonElement = getValue(key)
        return if (element is JsonNull) null else element.jsonArray.map { it.jsonPrimitive.content }
    }
}

// ---- known-gaps.txt and coverage-exclusions.txt ------------------------------------------------------------------

/** The reason the generators write for an entry nobody has explained yet. */
internal const val NOT_IMPLEMENTED_YET = "not implemented yet"

private val ENTRY = Regex("(\\S+)(?:\\s+#\\s*(.*))?")

/**
 * Reads a `<key>  # reason` file: blank lines and lines starting with `#` are comments, the key is the first run of
 * non-blank characters (it may itself contain `#`). A missing file is empty. The result keeps the file's order.
 */
internal fun readAnnotated(file: File): LinkedHashMap<String, String> {
    val entries = LinkedHashMap<String, String>()
    if (!file.exists()) return entries
    file.readLines().forEachIndexed { number, raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
        val match = ENTRY.matchEntire(line) ?: error("${file.name}:${number + 1}: expected '<key>  # reason'")
        check(match.groupValues[1] !in entries) { "${file.name}:${number + 1}: ${match.groupValues[1]} is listed twice" }
        entries[match.groupValues[1]] = match.groupValues[2]
    }
    return entries
}

/** Writes [entries] as `<key>  # reason` lines under the comment [header]. */
internal fun writeAnnotated(file: File, header: List<String>, entries: Map<String, String>) {
    val text = buildString {
        header.forEach { append("# ").append(it).append('\n') }
        append('\n')
        for ((key, reason) in entries) {
            append(key)
            if (reason.isNotEmpty()) append("  # ").append(reason)
            append('\n')
        }
    }
    file.writeText(text)
}
