package cosic.audio.sources

import cosic.pattern.jsMathRound

/** One compiled bytebeat expression: the function of `t` that the byte-beat worklet calls once per frame. */
internal fun interface ByteBeatFn {
    fun eval(t: Double): Double
}

/**
 * Compiles the text of a bytebeat (`byteBeatExpression`, the `new Function(...mathNames, 'int', 'window', ...chyx, 't', 'return 0,\n' + code)` of worklets.mjs:
 * 852-985) into a [ByteBeatFn], or null when the text is not an expression this evaluator knows: the worklet would then have no function, or throw at its first
 * call, and the voice is silent. Blank text is the expression `0`; null text is no expression.
 *
 * It reads the JavaScript expressions bytebeats are made of, as numbers (a comparison is 1 or 0): the operators `, ?: ?? || && | ^ & == != === !== < <= > >= << >> >>>
 * + - * / % **`, the unary `+ - ~ !`, parentheses, number literals (decimal with fraction and exponent, `0x` `0o` `0b`, `_` separators), comments, and
 *
 * - `t`; `true`, `false`, `null`, `undefined`, `NaN`, `Infinity`;
 * - the properties of `Math` by their bare names, as the worklet binds them (`PI`, `sin(x)`, `floor(x)`, `pow(x, y)`, `max(...)`, ...), also as `Math.name`; `int` is
 *   `Math.floor`, and `random()` draws from [random];
 * - the helpers of chyx (`bitC`, `br`, `sinf`, `cosf`, `tanf`). `regG` needs a regular expression and is not available.
 *
 * Bitwise operators work on 32-bit integers (`ToInt32`), `%` is the remainder of the dividend's sign, and a name that stands for a function but is not called is
 * NaN (which every bitwise operator turns into 0, as `cos >> 0` is in the default beats). `a ?? b` is `a`, since no value of this evaluator is null or undefined
 * (`null` is 0 and `undefined` NaN). Anything else (strings, arrays, objects, assignments, other names, calls of other functions) fails the compilation.
 * Evaluation allocates nothing.
 */
internal fun compileByteBeat(code: String?, random: RandomSource = RandomSource { 0.5 }): ByteBeatFn? {
    if (code == null) return null
    val source = code.trim()
    if (source.isEmpty()) return ByteBeatFn { 0.0 }
    val root = try {
        ByteBeatParser(source, random).parseProgram()
    } catch (e: ByteBeatSyntaxError) {
        return null
    }
    return ByteBeatFn { t -> root.eval(t) }
}

/** `br()` with a size above 32 throws in the worklet, which ends the processor: a voice that hits it is silent from then on. */
internal class ByteBeatFailure : RuntimeException("br() Size cannot be greater than 32", null, false, false)

private val FAILURE = ByteBeatFailure()

private class ByteBeatSyntaxError : RuntimeException(null, null, false, false)

private abstract class Node {
    abstract fun eval(t: Double): Double
}

private fun truthy(d: Double): Boolean = d != 0.0 && d == d
private fun bool(b: Boolean): Double = if (b) 1.0 else 0.0

/**
 * `ToInt32` for the audio thread: the same value as `jsToInt32`, which allocates (a `BigDecimal`) for the numbers beyond the range of a long, and a bytebeat
 * easily makes those (`t * t * t * t`).
 */
internal fun toInt32NoAlloc(d: Double): Int {
    if (d != d || d == Double.POSITIVE_INFINITY || d == Double.NEGATIVE_INFINITY) return 0
    if (d > -9.0e18 && d < 9.0e18) return d.toLong().toInt()
    return (d % 4294967296.0).toLong().toInt()
}

private class Num(private val value: Double) : Node() {
    override fun eval(t: Double): Double = value
}

private object TimeVar : Node() {
    override fun eval(t: Double): Double = t
}

private const val NEGATE = 0
private const val PLUS = 1
private const val BIT_NOT = 2
private const val NOT = 3

private class Unary(private val op: Int, private val a: Node) : Node() {
    override fun eval(t: Double): Double {
        val x = a.eval(t)
        return when (op) {
            NEGATE -> -x
            PLUS -> x
            BIT_NOT -> toInt32NoAlloc(x).inv().toDouble()
            else -> bool(!truthy(x))
        }
    }
}

private const val ADD = 0
private const val SUB = 1
private const val MUL = 2
private const val DIV = 3
private const val REM = 4
private const val POW = 5
private const val SHL = 6
private const val SHR = 7
private const val USHR = 8
private const val LT = 9
private const val LE = 10
private const val GT = 11
private const val GE = 12
private const val EQ = 13
private const val NE = 14
private const val AND = 15
private const val XOR = 16
private const val OR = 17

private class Binary(private val op: Int, private val a: Node, private val b: Node) : Node() {
    override fun eval(t: Double): Double {
        val x = a.eval(t)
        val y = b.eval(t)
        return when (op) {
            ADD -> x + y
            SUB -> x - y
            MUL -> x * y
            DIV -> x / y
            REM -> x % y
            POW -> Math.pow(x, y)
            SHL -> (toInt32NoAlloc(x) shl (toInt32NoAlloc(y) and 31)).toDouble()
            SHR -> (toInt32NoAlloc(x) shr (toInt32NoAlloc(y) and 31)).toDouble()
            USHR -> ((toInt32NoAlloc(x) ushr (toInt32NoAlloc(y) and 31)).toLong() and 0xFFFFFFFFL).toDouble()
            LT -> bool(x < y)
            LE -> bool(x <= y)
            GT -> bool(x > y)
            GE -> bool(x >= y)
            EQ -> bool(x == y)
            NE -> bool(x != y)
            AND -> (toInt32NoAlloc(x) and toInt32NoAlloc(y)).toDouble()
            XOR -> (toInt32NoAlloc(x) xor toInt32NoAlloc(y)).toDouble()
            else -> (toInt32NoAlloc(x) or toInt32NoAlloc(y)).toDouble()
        }
    }
}

private class LogicalAnd(private val a: Node, private val b: Node) : Node() {
    override fun eval(t: Double): Double {
        val x = a.eval(t)
        return if (truthy(x)) b.eval(t) else x
    }
}

private class LogicalOr(private val a: Node, private val b: Node) : Node() {
    override fun eval(t: Double): Double {
        val x = a.eval(t)
        return if (truthy(x)) x else b.eval(t)
    }
}

private class Conditional(private val test: Node, private val yes: Node, private val no: Node) : Node() {
    override fun eval(t: Double): Double = if (truthy(test.eval(t))) yes.eval(t) else no.eval(t)
}

private class Comma(private val a: Node, private val b: Node) : Node() {
    override fun eval(t: Double): Double {
        a.eval(t)
        return b.eval(t)
    }
}

/** A function of one number. */
private fun interface Fn1 {
    fun apply(x: Double): Double
}

private fun interface Fn2 {
    fun apply(x: Double, y: Double): Double
}

private class Call1(private val fn: Fn1, private val a: Node) : Node() {
    override fun eval(t: Double): Double = fn.apply(a.eval(t))
}

private class Call2(private val fn: Fn2, private val a: Node, private val b: Node) : Node() {
    override fun eval(t: Double): Double = fn.apply(a.eval(t), b.eval(t))
}

private class CallRandom(private val random: RandomSource) : Node() {
    override fun eval(t: Double): Double = random.next()
}

/** `Math.max` and `Math.min` of any number of arguments (none: -Infinity and Infinity). */
private class CallExtreme(private val args: Array<Node>, private val max: Boolean) : Node() {
    override fun eval(t: Double): Double {
        var result = if (max) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
        var nan = false
        for (arg in args) {
            val v = arg.eval(t)
            if (v != v) nan = true
            result = if (max) Math.max(result, v) else Math.min(result, v)
        }
        return if (nan) Double.NaN else result
    }
}

private class CallHypot(private val args: Array<Node>) : Node() {
    override fun eval(t: Double): Double {
        var sum = 0.0
        var infinite = false
        var nan = false
        for (arg in args) {
            val v = arg.eval(t)
            if (v == Double.POSITIVE_INFINITY || v == Double.NEGATIVE_INFINITY) infinite = true
            if (v != v) nan = true
            sum += v * v
        }
        return if (infinite) Double.POSITIVE_INFINITY else if (nan) Double.NaN else Math.sqrt(sum)
    }
}

/** chyx's `bitC(x, y, z)`: `x & y ? z : 0`. */
private class CallBitC(private val x: Node?, private val y: Node?, private val z: Node?) : Node() {
    override fun eval(t: Double): Double {
        val a = x?.eval(t) ?: Double.NaN
        val b = y?.eval(t) ?: Double.NaN
        val c = z?.eval(t) ?: Double.NaN
        return if ((toInt32NoAlloc(a) and toInt32NoAlloc(b)) != 0) c else 0.0
    }
}

/** chyx's `br(x, size = 8)`: the lowest `size` bits of `x` in reverse order. */
private class CallBr(private val x: Node?, private val size: Node?) : Node() {
    override fun eval(t: Double): Double {
        val value = x?.eval(t) ?: Double.NaN
        val n = size?.eval(t) ?: 8.0
        if (n > 32.0) throw FAILURE
        var result = 0
        var idx = 0
        while (idx < n) {
            val y = 1 shl (idx and 31)
            val z = 1 shl (toInt32NoAlloc(n - (idx + 1)) and 31)
            if ((toInt32NoAlloc(value) and y) != 0) result = result or z
            idx++
        }
        return result.toDouble()
    }
}

private val INV_LN2 = 1.4426950408889634

/** `Math.log2`: exact for the powers of two, which `ln(x) / ln(2)` is not. */
private fun jsLog2(x: Double): Double {
    if (x != x || x < 0.0) return Double.NaN
    if (x == 0.0) return Double.NEGATIVE_INFINITY
    if (x == Double.POSITIVE_INFINITY) return x
    val e = Math.getExponent(x)
    if (e < java.lang.Double.MIN_EXPONENT) return Math.log(x) * INV_LN2
    return e + Math.log(Math.scalb(x, -e)) * INV_LN2
}

private val MATH_CONSTANTS: Map<String, Double> = mapOf(
    "E" to Math.E, "LN10" to Math.log(10.0), "LN2" to Math.log(2.0), "LOG10E" to 1.0 / Math.log(10.0), "LOG2E" to 1.0 / Math.log(2.0),
    "PI" to Math.PI, "SQRT1_2" to Math.sqrt(0.5), "SQRT2" to Math.sqrt(2.0),
)

private val FUNCTIONS_1: Map<String, Fn1> = mapOf(
    "abs" to Fn1 { Math.abs(it) },
    "acos" to Fn1 { Math.acos(it) },
    "acosh" to Fn1 { kotlin.math.acosh(it) },
    "asin" to Fn1 { Math.asin(it) },
    "asinh" to Fn1 { kotlin.math.asinh(it) },
    "atan" to Fn1 { Math.atan(it) },
    "atanh" to Fn1 { kotlin.math.atanh(it) },
    "ceil" to Fn1 { Math.ceil(it) },
    "cbrt" to Fn1 { Math.cbrt(it) },
    "expm1" to Fn1 { Math.expm1(it) },
    "clz32" to Fn1 { Integer.numberOfLeadingZeros(toInt32NoAlloc(it)).toDouble() },
    "cos" to Fn1 { Math.cos(it) },
    "cosh" to Fn1 { Math.cosh(it) },
    "exp" to Fn1 { Math.exp(it) },
    "floor" to Fn1 { Math.floor(it) },
    "int" to Fn1 { Math.floor(it) },
    "fround" to Fn1 { it.toFloat().toDouble() },
    "log" to Fn1 { Math.log(it) },
    "log1p" to Fn1 { Math.log1p(it) },
    "log2" to Fn1 { jsLog2(it) },
    "log10" to Fn1 { Math.log10(it) },
    "round" to Fn1 { jsMathRound(it) },
    "sign" to Fn1 { if (it != it || it == 0.0) it else if (it > 0.0) 1.0 else -1.0 },
    "sin" to Fn1 { Math.sin(it) },
    "sinh" to Fn1 { Math.sinh(it) },
    "sqrt" to Fn1 { Math.sqrt(it) },
    "tan" to Fn1 { Math.tan(it) },
    "tanh" to Fn1 { Math.tanh(it) },
    "trunc" to Fn1 { if (it != it) it else if (it < 0.0) Math.ceil(it) else Math.floor(it) },
    "sinf" to Fn1 { Math.sin((it * Math.PI) / 128.0) },
    "cosf" to Fn1 { Math.cos((it * Math.PI) / 128.0) },
    "tanf" to Fn1 { Math.tan((it * Math.PI) / 128.0) },
)

private val FUNCTIONS_2: Map<String, Fn2> = mapOf(
    "atan2" to Fn2 { y, x -> Math.atan2(y, x) },
    "pow" to Fn2 { x, y -> Math.pow(x, y) },
    "imul" to Fn2 { x, y -> (toInt32NoAlloc(x) * toInt32NoAlloc(y)).toDouble() },
)

/** The names of functions that are not called with a fixed number of arguments, or at all: they exist, so a bare use of them is a value (NaN) and not an error. */
private val OTHER_FUNCTIONS = setOf("max", "min", "hypot", "random", "bitC", "br", "regG")

/** The globals that stand for a value in `new Function`'s scope. */
private val GLOBAL_VALUES: Map<String, Double> = mapOf("NaN" to Double.NaN, "Infinity" to Double.POSITIVE_INFINITY, "undefined" to Double.NaN, "null" to 0.0, "true" to 1.0, "false" to 0.0)

private const val TOKEN_NUMBER = 0
private const val TOKEN_NAME = 1
private const val TOKEN_PUNCT = 2
private const val TOKEN_END = 3

private const val MAX_DEPTH = 120

/** A recursive-descent parser of the expressions above that builds the evaluation tree. */
private class ByteBeatParser(private val src: String, private val random: RandomSource) {
    private var pos = 0
    private var kind = TOKEN_END
    private var text = ""
    private var number = 0.0
    private var depth = 0

    fun parseProgram(): Node {
        next()
        val node = parseComma()
        if (kind != TOKEN_END) fail()
        return node
    }

    private fun fail(): Nothing = throw ByteBeatSyntaxError()

    private fun enter() {
        if (++depth > MAX_DEPTH) fail()
    }

    private fun leave() {
        depth--
    }

    private fun isPunct(p: String) = kind == TOKEN_PUNCT && text == p

    private fun accept(p: String): Boolean {
        if (!isPunct(p)) return false
        next()
        return true
    }

    private fun expect(p: String) {
        if (!accept(p)) fail()
    }

    // ---- the lexer
    private fun next() {
        skipSpace()
        if (pos >= src.length) {
            kind = TOKEN_END
            text = ""
            return
        }
        val c = src[pos]
        when {
            c.isDigit() || (c == '.' && pos + 1 < src.length && src[pos + 1].isDigit()) -> readNumber()
            c == '_' || c == '$' || Character.isLetter(c) -> {
                val start = pos
                while (pos < src.length && (src[pos] == '_' || src[pos] == '$' || Character.isLetterOrDigit(src[pos]))) pos++
                kind = TOKEN_NAME
                text = src.substring(start, pos)
            }
            else -> readPunct()
        }
    }

    private fun skipSpace() {
        while (pos < src.length) {
            val c = src[pos]
            if (c.isWhitespace() || c == '\uFEFF') {
                pos++
            } else if (c == '/' && pos + 1 < src.length && src[pos + 1] == '/') {
                while (pos < src.length && src[pos] != '\n' && src[pos] != '\r') pos++
            } else if (c == '/' && pos + 1 < src.length && src[pos + 1] == '*') {
                val end = src.indexOf("*/", pos + 2)
                if (end < 0) fail()
                pos = end + 2
            } else {
                break
            }
        }
    }

    private fun readNumber() {
        val start = pos
        kind = TOKEN_NUMBER
        if (src[pos] == '0' && pos + 1 < src.length && src[pos + 1].lowercaseChar() in "xob") {
            val radix = when (src[pos + 1].lowercaseChar()) { 'x' -> 16; 'o' -> 8; else -> 2 }
            pos += 2
            val digits = StringBuilder()
            while (pos < src.length && (Character.digit(src[pos], radix) >= 0 || src[pos] == '_')) {
                if (src[pos] != '_') digits.append(src[pos])
                pos++
            }
            if (digits.isEmpty()) fail()
            number = java.math.BigInteger(digits.toString(), radix).toDouble()
        } else {
            while (pos < src.length && (src[pos].isDigit() || src[pos] == '_')) pos++
            if (pos < src.length && src[pos] == '.') {
                pos++
                while (pos < src.length && (src[pos].isDigit() || src[pos] == '_')) pos++
            }
            if (pos < src.length && (src[pos] == 'e' || src[pos] == 'E')) {
                var p = pos + 1
                if (p < src.length && (src[p] == '+' || src[p] == '-')) p++
                if (p < src.length && src[p].isDigit()) {
                    while (p < src.length && src[p].isDigit()) p++
                    pos = p
                }
            }
            number = src.substring(start, pos).replace("_", "").toDoubleOrNull() ?: fail()
        }
        // a number must not run into a name: `3in`
        if (pos < src.length && (src[pos] == '_' || src[pos] == '$' || Character.isLetter(src[pos]))) fail()
        text = src.substring(start, pos)
    }

    private fun readPunct() {
        kind = TOKEN_PUNCT
        for (p in PUNCTUATORS) {
            if (src.startsWith(p, pos)) {
                text = p
                pos += p.length
                return
            }
        }
        fail()
    }

    // ---- the grammar, lowest precedence first
    private fun parseComma(): Node {
        var node = parseConditional()
        while (accept(",")) node = Comma(node, parseConditional())
        return node
    }

    private fun parseConditional(): Node {
        enter()
        val test = parseBinary(0)
        val node = if (accept("?")) {
            val yes = parseConditional()
            expect(":")
            Conditional(test, yes, parseConditional())
        } else {
            test
        }
        leave()
        return node
    }

    private fun parseBinary(level: Int): Node {
        if (level >= LEVELS.size) return parseUnary(true)
        var left = parseBinary(level + 1)
        while (kind == TOKEN_PUNCT) {
            val op = LEVELS[level].firstOrNull { it == text } ?: break
            next()
            val right = parseBinary(level + 1)
            left = when (op) {
                "||", "??" -> if (op == "||") LogicalOr(left, right) else left
                "&&" -> LogicalAnd(left, right)
                else -> Binary(BINARY_OPS.getValue(op), left, right)
            }
        }
        return left
    }

    private fun parseUnary(allowPow: Boolean): Node {
        enter()
        val node: Node
        if (kind == TOKEN_PUNCT && text in UNARY_OPS) {
            val op = when (text) { "-" -> NEGATE; "+" -> PLUS; "~" -> BIT_NOT; else -> NOT }
            next()
            val operand = parseUnary(false)
            if (isPunct("**")) fail() // `-a ** b` is a SyntaxError
            node = Unary(op, operand)
        } else {
            val base = parseCall()
            node = if (allowPow && accept("**")) Binary(POW, base, parseUnary(true)) else base
        }
        leave()
        return node
    }

    /** A primary expression, or a call of a name. */
    private fun parseCall(): Node {
        if (kind == TOKEN_NUMBER) {
            val node = Num(number)
            next()
            return node
        }
        if (kind == TOKEN_PUNCT) {
            if (!accept("(")) fail()
            val inner = parseComma()
            expect(")")
            return inner
        }
        if (kind != TOKEN_NAME) fail()
        var name = text
        next()
        if (name == "Math" && isPunct(".")) { // Math.name
            next()
            if (kind != TOKEN_NAME) fail()
            name = text
            next()
        } else if (name == "Math" || name == "window") {
            return Num(Double.NaN) // an object: every bitwise operator makes it 0
        }
        if (isPunct("(")) {
            next()
            return parseArguments(name)
        }
        return value(name)
    }

    /** A name used as a value. */
    private fun value(name: String): Node {
        if (name == "t") return TimeVar
        MATH_CONSTANTS[name]?.let { return Num(it) }
        GLOBAL_VALUES[name]?.let { return Num(it) }
        if (name in FUNCTIONS_1 || name in FUNCTIONS_2 || name in OTHER_FUNCTIONS) return Num(Double.NaN)
        fail()
    }

    /** The arguments of a call of [name]; the opening parenthesis is consumed. */
    private fun parseArguments(name: String): Node {
        val args = ArrayList<Node>()
        if (!accept(")")) {
            do {
                if (kind == TOKEN_PUNCT && isPunct(")")) break // a trailing comma
                args.add(parseConditional())
            } while (accept(","))
            expect(")")
        }
        val missing = Num(Double.NaN)
        FUNCTIONS_1[name]?.let { return Call1(it, args.getOrElse(0) { missing }) }
        FUNCTIONS_2[name]?.let { return Call2(it, args.getOrElse(0) { missing }, args.getOrElse(1) { missing }) }
        return when (name) {
            "max" -> CallExtreme(args.toTypedArray(), true)
            "min" -> CallExtreme(args.toTypedArray(), false)
            "hypot" -> CallHypot(args.toTypedArray())
            "random" -> CallRandom(random)
            "bitC" -> CallBitC(args.getOrNull(0), args.getOrNull(1), args.getOrNull(2))
            "br" -> CallBr(args.getOrNull(0), args.getOrNull(1))
            else -> fail() // an unknown function, or regG (a regular expression)
        }
    }

    private companion object {
        /** Longest first, so that `>>>` is not read as `>>` and `>`. */
        val PUNCTUATORS = listOf(
            ">>>", "===", "!==", "**", "<<", ">>", "<=", ">=", "==", "!=", "&&", "||", "??",
            "(", ")", ",", "?", ":", "+", "-", "*", "/", "%", "<", ">", "&", "|", "^", "~", "!", ".",
        )

        /** Binary operators from the loosest to the tightest. */
        val LEVELS = listOf(
            listOf("||", "??"),
            listOf("&&"),
            listOf("|"),
            listOf("^"),
            listOf("&"),
            listOf("==", "!=", "===", "!=="),
            listOf("<", "<=", ">", ">="),
            listOf("<<", ">>", ">>>"),
            listOf("+", "-"),
            listOf("*", "/", "%"),
        )

        val BINARY_OPS = mapOf(
            "+" to ADD, "-" to SUB, "*" to MUL, "/" to DIV, "%" to REM, "<<" to SHL, ">>" to SHR, ">>>" to USHR,
            "<" to LT, "<=" to LE, ">" to GT, ">=" to GE, "==" to EQ, "===" to EQ, "!=" to NE, "!==" to NE,
            "&" to AND, "^" to XOR, "|" to OR,
        )

        val UNARY_OPS = setOf("-", "+", "~", "!")
    }
}
