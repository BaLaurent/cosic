package cosic.script

import cosic.pattern.Tonal
import cosic.pattern.Undefined
import cosic.script.js.JsFunction
import cosic.sound.LooseJson

/**
 * One documented function for the reference screen: what Strudel's own JSDoc says about it. [signature] reads as the function is
 * called (`.fast(factor)` on a pattern, `stack(...items)` as a function, `sine` for a value), [doc] is the first sentence of its
 * description, [example] the first `@example`, a program that runs on its own. [snippet] is the same program prepared for pasting into
 * an existing one: a pattern needs a `$:` label there, because a program with labels plays only what is labelled.
 */
public data class ReferenceEntry(
    val name: String,
    val aliases: List<String>,
    val category: String,
    val signature: String,
    val doc: String,
    val example: String,
    val snippet: String,
)

/**
 * Every function a program can call that Strudel documents with a description and an example, grouped by category: the text is
 * Strudel's JSDoc (tools/reference/build-reference.mjs writes it to `cosic/reference/functions.json`), the list is what this
 * engine implements, so a function that is not implemented yet appears when it is.
 */
public fun reference(): List<ReferenceEntry> = listed

/** The scale names that can follow a root note, as in `C:minor` (spaces written as colons: `C:harmonic:minor`): every scale of the tonal library. */
public fun scaleModes(): List<String> = Tonal.scaleModeNames()

/**
 * Whether a program can use [name]: as a global function or value, or as a method of patterns. The editor's autocomplete offers the names
 * Strudel documents, and only those that this is true for.
 */
public fun isDefined(name: String): Boolean = world.defines(name)

private val world: World by lazy { World.read() }

/**
 * One entry of `functions.json`, as the generator wrote it. [form] is how the first example calls the function (`"method"`: `.fast(2)`,
 * `"function"`: `stack(a, b)`, null: it does not call it); [params] the JSDoc parameter names, `""` when the example calls it without
 * arguments, null when the JSDoc names none; [excluded] why the reference leaves it out although it is implemented, when it does.
 */
internal class DocData(
    val name: String,
    val aliases: List<String>,
    val category: String,
    val form: String?,
    val params: String?,
    val doc: String,
    val example: String,
    val snippet: String,
    val excluded: String?,
)

/** Every function of `functions.json`, the ones this engine does not implement and the excluded ones too. */
internal val documented: List<DocData> by lazy { readDocumented() }

private val listed: List<ReferenceEntry> by lazy {
    val entries = documented.filter { it.excluded == null && world.defines(it.name) }
    val names = entries.map { it.name }.toSet()
    val claims = entries.flatMap { it.aliases.toSet() }.groupingBy { it }.eachCount()
    // An alias is a way to find an entry: not one that is an entry of its own (`density` is a control, whatever `fast` says), the
    // entry's own name, or a name two entries claim (JSDoc gives `ir` to `irbegin` as well as to `iresponse`), and one the engine has.
    entries.map { d ->
        val aliases = d.aliases.filter { it != d.name && it !in names && claims[it] == 1 && world.defines(it) }
        ReferenceEntry(d.name, aliases, d.category, world.signature(d), d.doc, d.example, d.snippet)
    }
}

/** What a program has in its world: the globals and the methods of patterns, the REPL's own functions among them. */
internal class World private constructor(session: ReplSession) {
    private val globals = session.realm.global
    private val methods = session.realm.patternPrototype

    /** Whether a program can use [name]: as a global, or as a method of a pattern. */
    fun defines(name: String): Boolean = isFunction(name) || isValue(name) || methods.hasOwn(name)

    /** A global that is called (`stack`, `s`). */
    fun isFunction(name: String): Boolean = globals.getOwnProp(name)?.let { it.isAccessor || it.value is JsFunction } == true

    /** A global that is no function but a pattern to use as it is (`sine`, `silence`). */
    fun isValue(name: String): Boolean = globals.getOwnProp(name)?.let { !it.isAccessor && it.value !is JsFunction && it.value !== Undefined } == true

    fun isMethod(name: String): Boolean = methods.hasOwn(name)

    /**
     * How [d] is called: the bare name for a value, else the name with its parameters (`...` when the JSDoc names none), with a leading
     * dot for a method of patterns. A function that is only one of the two is shown as that one; when it is both, as its first example calls it.
     */
    fun signature(d: DocData): String {
        if (isValue(d.name) && !isMethod(d.name)) return d.name
        val method = isMethod(d.name)
        val function = isFunction(d.name)
        val dotted = if (method && function) d.form != "function" else method
        return "${if (dotted) "." else ""}${d.name}(${d.params ?: "..."})"
    }

    companion object {
        /** The world of a program, read from a session of its own that touches nothing the playing one uses. */
        fun read(): World {
            val session = ReplSession(routeLogs = false)
            session.installReplFunctions()
            return World(session)
        }
    }
}

private fun readDocumented(): List<DocData> {
    val stream = DocData::class.java.getResourceAsStream("/cosic/reference/functions.json")
        ?: throw IllegalStateException("missing resource cosic/reference/functions.json (node tools/reference/build-reference.mjs makes it)")
    val text = stream.use { it.readBytes().toString(Charsets.UTF_8) }
    return (LooseJson.parse(text) as List<*>).map { item ->
        val entry = item as Map<*, *>
        DocData(
            name = entry["name"] as String,
            aliases = (entry["aliases"] as List<*>).map { it as String },
            category = entry["category"] as String,
            form = entry["form"] as String?,
            params = entry["params"] as String?,
            doc = entry["doc"] as String,
            example = entry["example"] as String,
            snippet = entry["snippet"] as String,
            excluded = entry["excluded"] as String?,
        )
    }
}
