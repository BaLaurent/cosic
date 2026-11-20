package cosic.script

import cosic.SliderConfig
import cosic.SourceRange
import cosic.WidgetConfig
import cosic.pattern.CosicError
import cosic.pattern.jsStringToNumber
import java.util.concurrent.ConcurrentHashMap

/*
 * The transpiler: the passes that turn what a Strudel user types into the program that is run. It is a port of
 * `@strudel/transpiler` at the pinned commit (packages/transpiler/{transpiler,plugin-mini,plugin-sample,plugin-widgets}.mjs),
 * working on the syntax tree: where the original prints the rewritten tree with escodegen and evaluates the text, this
 * returns the rewritten tree for the interpreter to run. Synthetic nodes carry the range of the code they stand for,
 * so an error at run time points at what the user wrote.
 *
 * The passes, in the order the original runs them on every node (estree-walker, pre-order, children in source order):
 *
 *  - a double-quoted string and an untagged template literal become `m('<text>', <offset>)`, the mini-notation call
 *    (plugin-mini.mjs:49-84,158-179); the offset is the index of the opening quote or backtick;
 *  - a tagged template whose tag names an [EmbeddedLanguage] is left alone for the interpreter, and its locations are
 *    collected (plugin-mini.mjs:9-28);
 *  - a bare `samples(...)` call becomes `await samples(...)` (plugin-sample.mjs);
 *  - `slider(...)` becomes `sliderWithID('slider_<offset>', ...)`, and a call of a widget method such as `_scope()` gets
 *    its widget id as the first argument (plugin-widgets.mjs);
 *  - a labelled statement `x: expr` becomes `expr.p('x')` (transpiler.mjs:122-150);
 *  - on the way back up, a call of a name registered with [TranspileHooks.special] is rewritten.
 *
 * Afterwards the last statement becomes the value of the program (transpiler.mjs:78-103).
 */

/** Options of [transpile]; the defaults are those of the original (transpiler.mjs:33-41), without `wrapAsync`. */
internal data class TranspileOptions(
    /** Whether the value of the last statement is the value of the program. */
    val addReturn: Boolean = true,
    /** Whether the result reports mini locations, widgets and sliders; when false it reports none of them. */
    val emitMiniLocations: Boolean = true,
    /** Whether [TranspileResult.widgets] lists the widget calls. The calls are rewritten either way. */
    val emitWidgets: Boolean = true,
    /** Prefix of the widget ids, for several editors on one page. */
    val id: String? = null,
)

internal class TranspileResult(
    /** The rewritten program. It always starts at 0 and ends at the length of the code. */
    val program: Program,
    /** The source range of every atom of every mini-notation string, in the order the walk met them. */
    val miniLocations: List<SourceRange>,
    /** The widget calls, in walk order; sliders are not among them, see [sliders]. */
    val widgets: List<WidgetConfig>,
    val sliders: List<SliderConfig>,
    val warnings: List<String> = emptyList(),
)

/**
 * Where the atoms of a mini-notation string are: `getLeafLocations` of the mini package. The transpiler asks for every
 * string it turns into a mini-notation call and does not look inside the notation itself.
 */
internal fun interface MiniLeafLocator {
    /**
     * The `[from, to]` offsets of every leaf of the notation [quoted], which is the text between double quotes
     * including the quotes; [start] is the offset of the opening quote in the program.
     */
    fun leafLocations(quoted: String, start: Int): List<IntArray>

    companion object {
        /** Reports no locations. */
        val NONE = MiniLeafLocator { _, _ -> emptyList() }
    }
}

/**
 * The method names that [transpile] gives a widget id (`registerWidgetType` in plugin-widgets.mjs, called for these
 * seven by packages/codemirror/widget.mjs:133-170). The calls of other names are left as they are.
 */
internal val DEFAULT_WIDGET_TYPES: Set<String> =
    setOf("_pianoroll", "_punchcard", "_spiral", "_scope", "_pitchwheel", "_spectrum", "_print")

/**
 * Rewrites of calls to particular names, applied by [transpile] when the walk leaves the call, that is after the
 * mini-notation strings below it have become `m(...)` calls (plugin-kabelsalat.mjs:143-205, which does this for `K`).
 */
internal object TranspileHooks {
    private val specials = ConcurrentHashMap<String, (CallExpression, String) -> Node>()

    /**
     * Registers [rewrite] for the calls `name(...)` and `object.name(...)`; a later registration of the same name
     * replaces the earlier one. It receives the call as the walk left it and the source of the whole program, and
     * returns the node that takes its place; the walk does not look at that node again.
     */
    fun special(name: String, rewrite: (call: CallExpression, source: String) -> Node) {
        specials[name] = rewrite
    }

    internal fun lookup(name: String): ((CallExpression, String) -> Node)? = specials[name]
}

/**
 * Transpiles [code]: parses it, applies the passes described at the top of this file and reports what they found.
 * A syntax error is a [CosicError]; so is a labelled statement that does not label an expression.
 *
 * [widgetTypes] are the method names that get a widget id, usually [DEFAULT_WIDGET_TYPES]. [leafLocations] finds the
 * atoms of the mini-notation strings.
 */
internal fun transpile(
    code: String,
    options: TranspileOptions = TranspileOptions(),
    widgetTypes: Set<String> = emptySet(),
    leafLocations: MiniLeafLocator = MiniLeafLocator.NONE,
): TranspileResult = TranspilePass(parseProgram(code), options, widgetTypes, leafLocations).run()

/** What the walk does with a node it enters (the `this.replace` and `this.skip` of estree-walker). */
private class Step(@JvmField val replacement: Node?, @JvmField val skip: Boolean)

private class TranspilePass(
    parsed: ParseResult,
    private val options: TranspileOptions,
    private val widgetTypes: Set<String>,
    private val locator: MiniLeafLocator,
) {
    private val program = parsed.program
    private val source = parsed.source
    private val disabled = miniDisableRanges(parsed.comments, source.length)
    private val miniLocations = ArrayList<SourceRange>()
    private val widgets = ArrayList<WidgetConfig>()
    private val sliders = ArrayList<SliderConfig>()
    private val warnings = ArrayList<String>()

    /** For each function the walk is inside, whether it is async; the innermost last. */
    private val enclosingAsync = ArrayList<Boolean>()

    fun run(): TranspileResult {
        val walked = visit(program, null) as Program
        val body = ArrayList(walked.body)
        // The last statement is the value of the program; without an expression there to be it, the value is `silence`.
        if (body.isEmpty()) {
            warnings.add("empty body -> fallback to silence")
            body.add(silenceStatement())
        } else if (body.last() !is ExpressionStatement) {
            body.add(silenceStatement())
        }
        if (options.addReturn) {
            val last = body.last() as ExpressionStatement
            body[body.lastIndex] = ReturnStatement(last.start, last.end, last.expression)
        }
        val result = Program(0, source.length, body)
        return if (options.emitMiniLocations) TranspileResult(result, miniLocations, widgets, sliders, warnings)
        else TranspileResult(result, emptyList(), emptyList(), emptyList(), warnings)
    }

    // ---- the walk ----------------------------------------------------------------------------------------------

    /** estree-walker's `visit` (3.0.3, sync.js:48-112): enter, then the children of what enter left, then leave. */
    private fun visit(node: Node, parent: Node?): Node {
        val step = enter(node, parent)
        val current = step?.replacement ?: node
        // A node that enter skipped has its children neither visited nor left.
        if (step != null && step.skip) return current
        val function = current as? FunctionNode
        if (function != null) enclosingAsync.add(function.async)
        val mapped = current.mapChildren { child -> visit(child, current) }
        if (function != null) enclosingAsync.removeAt(enclosingAsync.lastIndex)
        return leave(mapped) ?: mapped
    }

    private fun enter(node: Node, parent: Node?): Step? = when (node) {
        is Literal -> enterLiteral(node, parent)
        is TemplateLiteral -> enterTemplate(node, parent)
        is TaggedTemplateExpression -> enterTagged(node)
        is CallExpression -> enterCall(node, parent)
        is LabeledStatement -> Step(labelToP(node), skip = false)
        else -> null
    }

    private fun leave(node: Node): Node? {
        if (node !is CallExpression) return null
        var callee = node.callee
        if (callee is ChainExpression) callee = callee.expression
        val name = when (callee) {
            is MemberExpression -> if (callee.computed) null else (callee.property as? Identifier)?.name
            is Identifier -> callee.name
            else -> null
        } ?: return null
        val rewrite = TranspileHooks.lookup(name) ?: return null
        return rewrite(node, source)
    }

    // ---- mini-notation -------------------------------------------------------------------------------------------

    /** A double-quoted string is a mini-notation string; one in a `mini-off` region, or the name of a property, is a plain string. */
    private fun enterLiteral(node: Literal, parent: Node?): Step? {
        if (node.raw.isEmpty() || node.raw[0] != '"') return null
        if (isMiniDisabled(node.start)) return null
        // Deliberate (LANG DEV-1): in Strudel `{ "a b": 1 }` becomes `{ m('a b', 1): 1 }`, which does not run.
        if (parent is Property && parent.key === node && !parent.computed) return null
        val value = node.value as String
        collectMiniLocations(value, node.start)
        return Step(miniCall(value, node), skip = true)
    }

    /** A template literal outside a tagged template is a mini-notation string, of its first piece only: `${}` and what follows are dropped. */
    private fun enterTemplate(node: TemplateLiteral, parent: Node?): Step? {
        if (parent is TaggedTemplateExpression) return null
        if (isMiniDisabled(node.start)) return null
        val raw = node.quasis[0].raw
        collectMiniLocations(raw, node.start)
        return Step(miniCall(raw, node), skip = true)
    }

    /**
     * A template tagged with the name of an [EmbeddedLanguage] stays as it is: the interpreter hands the first piece to
     * the language. Only its locations are collected here, and the walk does not enter it (plugin-mini.mjs:9-28).
     * Unlike a plain template it is not affected by `mini-off`.
     */
    private fun enterTagged(node: TaggedTemplateExpression): Step? {
        val tag = node.tag as? Identifier ?: return null
        val language = EmbeddedLanguages.lookup(tag.name) ?: return null
        if (options.emitMiniLocations) {
            miniLocations.addAll(language.locations(node.quasi.quasis[0].raw, node.quasi.start + 1))
        }
        return Step(null, skip = true)
    }

    private fun collectMiniLocations(value: String, offset: Int) {
        if (!options.emitMiniLocations) return
        for (location in locator.leafLocations("\"$value\"", offset)) miniLocations.add(SourceRange(location[0], location[1]))
    }

    private fun isMiniDisabled(offset: Int): Boolean {
        for (range in disabled) if (offset >= range[0] && offset < range[1]) return true
        return false
    }

    // ---- calls: samples, slider, widgets ---------------------------------------------------------------------------

    private fun enterCall(call: CallExpression, parent: Node?): Step? {
        val callee = call.callee
        if (callee is Identifier) {
            // `await` is added to a bare `samples(...)`, at any depth; the walk then meets the call again, now under the await.
            if (callee.name == "samples" && parent !is AwaitExpression) {
                // Strudel evaluates the text it prints, where such an `await` in an ordinary function is a syntax error.
                if (enclosingAsync.isNotEmpty() && !enclosingAsync.last()) {
                    throw CosicError("await is only valid in async functions and the top level bodies of modules", call.range)
                }
                return Step(AwaitExpression(call.start, call.end, call), skip = false)
            }
            if (callee.name == "slider") return Step(sliderWithId(call, callee), skip = false)
        } else if (callee is MemberExpression) {
            val property = callee.property as? Identifier ?: return null
            if (property.name in widgetTypes) return Step(withWidgetId(call, property), skip = false)
        }
        return null
    }

    /**
     * `slider(value, min, max, step)` becomes `sliderWithID('slider_<offset of value>', value, min, max, step)` and
     * is recorded (plugin-widgets.mjs:36-58,72-84). The bounds are the number literals among the arguments.
     */
    private fun sliderWithId(call: CallExpression, callee: Identifier): CallExpression {
        val args = call.arguments
        // The original reads `node.arguments[0].start` and fails with this message when there is no argument.
        val first = args.firstOrNull() ?: throw CosicError("Cannot read properties of undefined (reading 'start')", call.range)
        val config = SliderConfig(
            from = first.start,
            to = first.end,
            value = (first as? Literal)?.raw,
            min = literalNumber(args.getOrNull(1)) ?: 0.0,
            max = literalNumber(args.getOrNull(2)) ?: 1.0,
            step = literalNumber(args.getOrNull(3)),
            id = "slider_${first.start}",
        )
        sliders.add(config)
        val id = stringLiteral(first.start, first.end, config.id)
        val name = Identifier(callee.start, callee.end, "sliderWithID")
        return CallExpression(call.start, call.end, name, listOf<Node>(id) + args, call.optional)
    }

    /** A call of a widget method gets the widget id as its first argument (plugin-widgets.mjs:14-34,86-105). */
    private fun withWidgetId(call: CallExpression, method: Identifier): CallExpression {
        val type = method.name
        val config = WidgetConfig(type, call.start, call.end, widgets.count { it.type == type }, options.id)
        if (options.emitWidgets) widgets.add(config)
        val id = stringLiteral(method.start, method.end, config.widgetId)
        return CallExpression(call.start, call.end, call.callee, listOf<Node>(id) + call.arguments, call.optional)
    }

    /** The value of a number literal, or of a string literal that reads as a number; null for anything else. */
    private fun literalNumber(node: Node?): Double? {
        val literal = node as? Literal ?: return null
        return when (val value = literal.value) {
            is Double -> value
            is String -> jsStringToNumber(value).takeIf { !it.isNaN() }
            else -> null
        }
    }

    // ---- labels --------------------------------------------------------------------------------------------------

    /** `x: expr` becomes `expr.p('x')` (transpiler.mjs:122-150); every labelled statement is one, at any depth. */
    private fun labelToP(node: LabeledStatement): ExpressionStatement {
        val body = node.body as? ExpressionStatement
            ?: throw CosicError("Labeled statements must label an expression", node.label.range)
        val label = node.label
        val method = Identifier(label.start, label.end, "p")
        val member = MemberExpression(node.start, node.end, body.expression, method, computed = false, optional = false)
        val argument = stringLiteral(label.start, label.end, label.name)
        val call = CallExpression(node.start, node.end, member, listOf(argument), optional = false)
        return ExpressionStatement(node.start, node.end, call)
    }

    // ---- synthetic nodes -----------------------------------------------------------------------------------------

    /** `m('<value>', <offset of at>)`, standing where [at] was. */
    private fun miniCall(value: String, at: Node): CallExpression {
        val callee = Identifier(at.start, at.end, "m")
        val offset = Literal(at.start, at.end, at.start.toDouble(), at.start.toString())
        return CallExpression(at.start, at.end, callee, listOf(stringLiteral(at.start, at.end, value), offset), optional = false)
    }

    private fun stringLiteral(start: Int, end: Int, value: String): Literal = Literal(start, end, value, singleQuoted(value))

    private fun silenceStatement(): ExpressionStatement {
        val end = source.length
        return ExpressionStatement(end, end, Identifier(end, end, "silence"))
    }
}

/**
 * The ranges the `mini-off` and `mini-on` comments disable (transpiler.mjs:274-291): a comment whose text starts with
 * `mini-off` opens one, one starting with `mini-on` closes the innermost open one. A range left open runs to the end of
 * the code, and a `mini-on` with nothing open does nothing.
 */
private fun miniDisableRanges(comments: List<Comment>, codeEnd: Int): List<IntArray> {
    val ranges = ArrayList<IntArray>()
    val open = ArrayList<Int>()
    for (comment in comments) {
        val text = comment.value.trim { isJsWhitespace(it) }
        if (text.startsWith("mini-off")) {
            open.add(comment.start)
        } else if (text.startsWith("mini-on") && open.isNotEmpty()) {
            ranges.add(intArrayOf(open.removeAt(open.lastIndex), comment.end))
        }
    }
    while (open.isNotEmpty()) ranges.add(intArrayOf(open.removeAt(open.lastIndex), codeEnd))
    return ranges
}

/** [text] as a JavaScript string literal in single quotes, the way escodegen writes one; for the `raw` of synthetic literals. */
private fun singleQuoted(text: String): String {
    val out = StringBuilder(text.length + 2).append('\'')
    for (c in text) {
        when (c) {
            '\\' -> out.append("\\\\")
            '\'' -> out.append("\\'")
            '\n' -> out.append("\\n")
            '\r' -> out.append("\\r")
            '\t' -> out.append("\\t")
            '\b' -> out.append("\\b")
            '\u000c' -> out.append("\\f")
            '\u2028' -> out.append("\\u2028")
            '\u2029' -> out.append("\\u2029")
            else -> if (c < ' ') out.append("\\x").append("%02X".format(c.code)) else out.append(c)
        }
    }
    return out.append('\'').toString()
}
