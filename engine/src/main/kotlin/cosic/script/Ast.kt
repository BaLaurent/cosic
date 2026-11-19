package cosic.script

import cosic.SourceRange
import cosic.pattern.ArithOp

/** An expression; [range] is the code it was parsed from, used to point at it in error messages. */
internal sealed class Expr(val range: SourceRange) {
    class Number(val value: Double, range: SourceRange) : Expr(range)

    /** A double-quoted or backtick string: mini-notation. [contentStart] is the offset of its first character. */
    class Mini(val content: String, val contentStart: Int, range: SourceRange) : Expr(range)

    /** A single-quoted string: plain text, not mini-notation. */
    class Text(val value: String, range: SourceRange) : Expr(range)

    class Bool(val value: Boolean, range: SourceRange) : Expr(range)

    class Ident(val name: String, range: SourceRange) : Expr(range)

    class ArrayLit(val items: List<Expr>, range: SourceRange) : Expr(range)

    class Negate(val operand: Expr, range: SourceRange) : Expr(range)

    class Binary(val op: ArithOp, val left: Expr, val right: Expr, range: SourceRange) : Expr(range)

    class Lambda(val params: List<String>, val body: Expr, range: SourceRange) : Expr(range)

    /** `callee(args)`; [range] covers the callee and the arguments. */
    class Call(val callee: Expr, val args: List<Expr>, range: SourceRange) : Expr(range)

    /** `receiver.name(args)`; [range] runs from the method name to the closing parenthesis. */
    class MethodCall(val receiver: Expr, val name: String, val args: List<Expr>, range: SourceRange) : Expr(range)
}

internal sealed class Stmt {
    class Let(val name: String, val value: Expr) : Stmt()

    /** `name: expr`; labels starting with an underscore are muted. */
    class Labeled(val label: String, val value: Expr) : Stmt() {
        val muted: Boolean get() = label.startsWith("_")
    }

    class Evaluate(val expr: Expr) : Stmt()
}
