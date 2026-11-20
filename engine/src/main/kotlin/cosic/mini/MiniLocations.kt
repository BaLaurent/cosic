package cosic.mini

/*
 * Where in the program the atoms of a mini-notation string are, for the editor to highlight them (mini.mjs:181-208).
 */

/**
 * `getLeafLocation(code, leaf, globalOffset)`: the `[from, to]` range of [leaf] in the program. [code] is the quoted
 * notation, [globalOffset] the position of its opening quote in the program, so that the first character of the
 * notation is at `globalOffset + 1`.
 *
 * The range of the atom as the parser read it includes the whitespace around the word; this takes it off again, but only
 * the spaces (U+0020): a newline or a tab stays in the range, as it does in Strudel.
 */
internal fun getLeafLocation(code: String, leaf: AtomNode, globalOffset: Int = 0): IntArray {
    val actual = code.substring(leaf.start, leaf.end)
    val parts = actual.split(leaf.source)
    val offsetStart = parts.getOrNull(0)?.count { it == ' ' } ?: 0
    val offsetEnd = parts.getOrNull(1)?.count { it == ' ' } ?: 0
    return intArrayOf(leaf.start + offsetStart + globalOffset, leaf.end - offsetEnd + globalOffset)
}

/**
 * The atoms of [ast] in the order Strudel's `patternifyAST` enters them: the children of a pattern first, in order,
 * then the arguments of the operators of each child, then the steps of a polymeter. (`getLeaves` collects the nodes the
 * conversion enters; an atom that is a rest is one too.) The operators of an element that is the argument of an euclidean
 * rhythm are not applied, so their arguments are not entered.
 */
internal fun collectLeaves(ast: MiniNode): List<AtomNode> {
    val leaves = ArrayList<AtomNode>()
    fun visit(node: MiniNode) {
        when (node) {
            is AtomNode -> leaves.add(node)
            is ElementNode -> visit(node.source)
            is PatternNode -> {
                for (child in node.source) visit(child)
                for (child in node.source) {
                    if (child !is ElementNode) continue
                    for (op in child.ops) {
                        when (op) {
                            is Op.Stretch -> visit(op.amount)
                            is Op.Bjorklund -> {
                                visit(op.pulse)
                                visit(op.step)
                                op.rotation?.let(::visit)
                            }
                            is Op.Tail -> visit(op.element)
                            is Op.Range -> visit(op.element)
                            is Op.Replicate, is Op.DegradeBy -> Unit
                        }
                    }
                }
                node.stepsPerCycle?.let(::visit)
            }
        }
    }
    visit(ast)
    return leaves
}
