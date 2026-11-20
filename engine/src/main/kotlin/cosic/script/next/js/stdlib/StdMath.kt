package cosic.script.next.js.stdlib

import cosic.pattern.jsMathRound
import cosic.script.next.Realm
import cosic.script.next.js.jsPow
import cosic.script.next.js.toInt32
import cosic.script.next.js.toNumber
import cosic.script.next.js.toUint32
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.truncate

/*
 * `Math`. The functions are fdlibm's (`StrictMath`) like V8's, except the ones listed in StdMathPorts.kt; `pow` is the
 * `**` operator's, and `random` is `java.util.Random`.
 */

internal fun installMath(realm: Realm, std: StdRealm) {
    val math = JsNamespace(realm, "Math")
    realm.defineGlobal("Math", math)

    math.defineOwn("E", Math.E, 0)
    math.defineOwn("LN10", 2.302585092994046, 0)
    math.defineOwn("LN2", 0.6931471805599453, 0)
    math.defineOwn("LOG10E", 0.4342944819032518, 0)
    math.defineOwn("LOG2E", 1.4426950408889634, 0)
    math.defineOwn("PI", Math.PI, 0)
    math.defineOwn("SQRT1_2", 0.7071067811865476, 0)
    math.defineOwn("SQRT2", 1.4142135623730951, 0)

    fun unary(name: String, f: (Double) -> Double) {
        realm.defineMethod(math, name, 1) { _, args -> f(realm.toNumber(args.arg(0))) }
    }
    unary("abs") { abs(it) }
    unary("acos") { StrictMath.acos(it) }
    unary("acosh") { v8Acosh(it) }
    unary("asin") { StrictMath.asin(it) }
    unary("asinh") { v8Asinh(it) }
    unary("atan") { StrictMath.atan(it) }
    unary("atanh") { v8Atanh(it) }
    unary("cbrt") { v8Cbrt(it) }
    unary("ceil") { ceil(it) }
    unary("cos") { StrictMath.cos(it) }
    unary("cosh") { v8Cosh(it) }
    unary("exp") { v8Exp(it) }
    unary("expm1") { StrictMath.expm1(it) }
    unary("floor") { floor(it) }
    unary("fround") { it.toFloat().toDouble() }
    unary("log") { StrictMath.log(it) }
    unary("log10") { StrictMath.log10(it) }
    unary("log1p") { StrictMath.log1p(it) }
    unary("log2") { v8Log2(it) }
    unary("round") { jsMathRound(it) }
    unary("sign") { if (it.isNaN() || it == 0.0) it else if (it > 0) 1.0 else -1.0 }
    unary("sin") { StrictMath.sin(it) }
    unary("sinh") { StrictMath.sinh(it) }
    unary("sqrt") { Math.sqrt(it) }
    unary("tan") { StrictMath.tan(it) }
    unary("tanh") { v8Tanh(it) }
    unary("trunc") { truncate(it) }

    realm.defineMethod(math, "clz32", 1) { _, args -> Integer.numberOfLeadingZeros(realm.toUint32(args.arg(0)).toInt()).toDouble() }
    realm.defineMethod(math, "imul", 2) { _, args -> (realm.toInt32(args.arg(0)) * realm.toInt32(args.arg(1))).toDouble() }
    realm.defineMethod(math, "atan2", 2) { _, args -> v8Atan2(realm.toNumber(args.arg(0)), realm.toNumber(args.arg(1))) }
    realm.defineMethod(math, "pow", 2) { _, args -> jsPow(realm.toNumber(args.arg(0)), realm.toNumber(args.arg(1))) }
    realm.defineMethod(math, "hypot", 2) { _, args -> v8Hypot(DoubleArray(args.size) { realm.toNumber(args[it]) }) }
    realm.defineMethod(math, "random", 0) { _, _ -> std.random.nextDouble() }
    realm.defineMethod(math, "max", 2) { _, args ->
        var result = Double.NEGATIVE_INFINITY
        var nan = false
        for (a in args) {
            val v = realm.toNumber(a)
            if (v.isNaN()) nan = true
            else if (v > result || (v == 0.0 && result == 0.0 && 1.0 / result < 0)) result = v
        }
        if (nan) Double.NaN else result
    }
    realm.defineMethod(math, "min", 2) { _, args ->
        var result = Double.POSITIVE_INFINITY
        var nan = false
        for (a in args) {
            val v = realm.toNumber(a)
            if (v.isNaN()) nan = true
            else if (v < result || (v == 0.0 && result == 0.0 && 1.0 / v < 0)) result = v
        }
        if (nan) Double.NaN else result
    }
}
