package cosic.kabel

/*
 * Kabelsalat's graph language, ported from @kabelsalat/core@0.4.0 (graph.js, compiler.js) and @kabelsalat/lib@0.4.1 (lib.js,
 * ugens.js, synth.js, utils.js), which Strudel's `K(...)` / `worklet` runs (AGPL-3.0-or-later, like Strudel itself).
 * The notes of the port are docs/parity/notes/3.18.md; the specification is docs/parity/spec/kabelsalat-port.md.
 */

import cosic.pattern.Undefined
import cosic.script.js.JsArray
import cosic.script.js.JsObject

/** The node type of a poly node (several channels). */
internal const val POLY = "poly"

/** The node type of the root of a graph: every output of the program is an input of it. */
internal const val EXIT = "exit"

/** The node type of a constant (`n(0.5)`): inlined where it is used. */
internal const val CONST = "n"

/** What compiling a graph cannot do. The message is what the user sees, in the words of the JavaScript error Strudel would have thrown. */
internal class KabelError(message: String) : RuntimeException(message, null, false, false)

/**
 * A node of the graph (`class Node` of graph.js): a [type], an optional [value] (only constants and `time` have one) and the nodes it takes its
 * inputs from, [ins] (a JavaScript array, because programs read it: `.pick(...sine([220, 330]).ins)`). Its prototype carries the methods of
 * every registered node type (`a.add(b)`, `a.lpf(c)`).
 */
internal class KNode(val kr: KabelRealm, var type: String, var value: Any? = Undefined) : JsObject(kr.nodePrototype) {
    var ins: JsArray = JsArray(kr.realm, ArrayList())

    override val className: String get() = "Node"

    /** `node.withIns(...items)`: sets the inputs; returns the node. */
    fun withIns(items: List<Any?>): KNode {
        ins = JsArray(kr.realm, ArrayList(items))
        return this
    }

    val inputs: ArrayList<Any?> get() = ins.items
}

/**
 * What `Node.parseInput` returns when an input has no usable type (graph.js:26-32 logs and returns a plain `0`): not a node, so it is skipped by the
 * topological sort and compiling the graph fails (`Cannot read properties of undefined (reading 'type')`) only if the input is reachable.
 */
internal object InvalidInput : JsObject(null) {
    override val className: String get() = "InvalidInput"
}

internal fun isPoly(x: Any?): Boolean = x is KNode && x.type == POLY

internal fun polyIns(x: Any?): List<Any?> = (x as KNode).inputs
