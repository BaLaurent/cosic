package cosic.script.js.stdlib

import cosic.script.Realm
import cosic.pattern.JsFn
import cosic.script.js.JsFunction
import cosic.script.js.Hint
import cosic.script.js.JsObject
import cosic.script.js.toNumber
import cosic.script.js.toPrimitive
import cosic.script.js.toStr
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.truncate

/*
 * `Date`: a time value in milliseconds since 1970-01-01T00:00:00Z (NaN for an invalid date), computed with the arithmetic of
 * ECMA-262 §21.4, in the default time zone of the machine for the local-time methods. `Date.now()` and `new Date()` read
 * the clock of the host (`ScriptHost.nowSeconds()`, in seconds since 1970, so the host decides what time the program sees).
 *
 * Not there: the locale arguments of `toLocale*String` (they print the `en-US` forms), and `[Symbol.toPrimitive]` (so
 * `date + 1` adds numbers; V8 concatenates strings).
 */

internal class JsDate(prototype: JsObject, var time: Double) : JsObject(prototype) {
    override val className: String get() = "Date"
}

private const val MS_PER_DAY = 86_400_000.0
private const val MAX_TIME = 8.64e15

private val DAY_NAMES = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private val MONTH_NAMES = arrayOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
private val MONTH_FULL = arrayOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")

// ---- the arithmetic of the specification -----------------------------------------------------------------------------------

/** Days since 1970-01-01 of the civil date [y]-[m]-[d] (m is 1..12). */
private fun daysFromCivil(y0: Long, m: Int, d: Int): Long {
    val y = if (m <= 2) y0 - 1 else y0
    val era = Math.floorDiv(y, 400L)
    val yoe = y - era * 400
    val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    return era * 146097 + doe - 719468
}

private class Civil(val year: Long, val month: Int, val day: Int)

private fun civilFromDays(z0: Long): Civil {
    val z = z0 + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val y = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val m = (mp + (if (mp < 10) 3 else -9)).toInt()
    return Civil(if (m <= 2) y + 1 else y, m, d)
}

private fun makeDay(year: Double, month: Double, date: Double): Double {
    if (!year.isFinite() || !month.isFinite() || !date.isFinite()) return Double.NaN
    val y = truncate(year)
    val m = truncate(month)
    val dt = truncate(date)
    val ym = y + floor(m / 12)
    if (abs(ym) > 400_000) return Double.NaN
    val mn = (((m % 12) + 12) % 12).toInt()
    return daysFromCivil(ym.toLong(), mn + 1, 1).toDouble() + dt - 1
}

private fun makeTime(h: Double, m: Double, s: Double, ms: Double): Double {
    if (!h.isFinite() || !m.isFinite() || !s.isFinite() || !ms.isFinite()) return Double.NaN
    return truncate(h) * 3_600_000.0 + truncate(m) * 60_000.0 + truncate(s) * 1000.0 + truncate(ms)
}

private fun makeDate(day: Double, time: Double): Double {
    if (!day.isFinite() || !time.isFinite()) return Double.NaN
    val tv = day * MS_PER_DAY + time
    return if (tv.isFinite()) tv else Double.NaN
}

private fun timeClip(t: Double): Double = if (!t.isFinite() || abs(t) > MAX_TIME) Double.NaN else truncate(t) + 0.0

/** The fields of a time value: year, month (0-11), day of month, hours, minutes, seconds, milliseconds, day of the week. */
private class Fields(t: Double) {
    val year: Double
    val month: Double
    val date: Double
    val hours: Double
    val minutes: Double
    val seconds: Double
    val ms: Double
    val weekday: Int

    init {
        val day = floor(t / MS_PER_DAY)
        val inDay = t - day * MS_PER_DAY
        val civil = civilFromDays(day.toLong())
        year = civil.year.toDouble()
        month = (civil.month - 1).toDouble()
        date = civil.day.toDouble()
        hours = floor(inDay / 3_600_000.0)
        minutes = floor(inDay / 60_000.0) % 60
        seconds = floor(inDay / 1000.0) % 60
        ms = inDay % 1000
        weekday = (((day.toLong() + 4) % 7 + 7) % 7).toInt()
    }
}

// ---- the time zone --------------------------------------------------------------------------------------------------------

/** The offset of the local time zone from UTC at the instant [utc], in milliseconds. */
private fun localOffset(utc: Double): Double = TimeZone.getDefault().getOffset(utc.toLong()).toDouble()

private fun toLocal(utc: Double): Double = utc + localOffset(utc)

/** The instant whose local time is [local]: the earlier of two when the clock repeats, moved forward when it skips. */
private fun fromLocal(local: Double): Double {
    if (!local.isFinite() || abs(local) > MAX_TIME + 2 * MS_PER_DAY) return Double.NaN
    val whole = local.toLong()
    val ldt = LocalDateTime.ofEpochSecond(Math.floorDiv(whole, 1000L), (Math.floorMod(whole, 1000L) * 1_000_000L).toInt(), ZoneOffset.UTC)
    return ZonedDateTime.ofLocal(ldt, TimeZone.getDefault().toZoneId(), null).toInstant().toEpochMilli().toDouble()
}

private fun zoneText(utc: Double): String {
    val zone = TimeZone.getDefault()
    val offsetMinutes = (localOffset(utc) / 60_000).toInt()
    val sign = if (offsetMinutes < 0) "-" else "+"
    val a = abs(offsetMinutes)
    val name = zone.getDisplayName(zone.inDaylightTime(java.util.Date(utc.toLong())), TimeZone.LONG, Locale.US)
    return "GMT$sign${pad(a / 60, 2)}${pad(a % 60, 2)} ($name)"
}

private fun pad(n: Number, width: Int): String = n.toString().padStart(width, '0')

private fun yearText(y: Double): String = (if (y < 0) "-" else "") + pad(abs(y).toLong(), 4)

// ---- formatting -----------------------------------------------------------------------------------------------------------

internal fun isoString(t: Double): String {
    val f = Fields(t)
    val y = f.year
    val year = if (y in 0.0..9999.0) pad(y.toLong(), 4) else (if (y < 0) "-" else "+") + pad(abs(y).toLong(), 6)
    return "$year-${pad(f.month.toInt() + 1, 2)}-${pad(f.date.toInt(), 2)}T${pad(f.hours.toInt(), 2)}:${pad(f.minutes.toInt(), 2)}:${pad(f.seconds.toInt(), 2)}.${pad(f.ms.toInt(), 3)}Z"
}

private fun dateText(t: Double): String {
    val f = Fields(toLocal(t))
    return "${DAY_NAMES[f.weekday]} ${MONTH_NAMES[f.month.toInt()]} ${pad(f.date.toInt(), 2)} ${yearText(f.year)}"
}

private fun timeText(t: Double): String {
    val f = Fields(toLocal(t))
    return "${pad(f.hours.toInt(), 2)}:${pad(f.minutes.toInt(), 2)}:${pad(f.seconds.toInt(), 2)} ${zoneText(t)}"
}

private fun utcText(t: Double): String {
    val f = Fields(t)
    return "${DAY_NAMES[f.weekday]}, ${pad(f.date.toInt(), 2)} ${MONTH_NAMES[f.month.toInt()]} ${yearText(f.year)} ${pad(f.hours.toInt(), 2)}:${pad(f.minutes.toInt(), 2)}:${pad(f.seconds.toInt(), 2)} GMT"
}

private fun localeDate(t: Double): String {
    val f = Fields(toLocal(t))
    return "${f.month.toInt() + 1}/${f.date.toInt()}/${f.year.toLong()}"
}

private fun localeTime(t: Double): String {
    val f = Fields(toLocal(t))
    val h = f.hours.toInt()
    val h12 = if (h % 12 == 0) 12 else h % 12
    return "$h12:${pad(f.minutes.toInt(), 2)}:${pad(f.seconds.toInt(), 2)} ${if (h < 12) "AM" else "PM"}"
}

// ---- parsing --------------------------------------------------------------------------------------------------------------

private val ISO_DATE = Regex(
    "^([+-]\\d{6}|\\d{4})(?:-(\\d{2})(?:-(\\d{2}))?)?" +
        "(?:[T ](\\d{2}):(\\d{2})(?::(\\d{2})(?:\\.(\\d+))?)?)?" +
        "(Z|z|[+-]\\d{2}:?\\d{2})?$",
)

/** `Date.parse`: ISO 8601 forms, and the forms `toString`, `toUTCString` and everyday writing give (`Jan 5, 2020 10:00`, `1/2/2020`). */
internal fun parseDate(input: String): Double {
    ISO_DATE.matchEntire(input)?.let { return parseIso(it) }
    return parseLegacy(input.trim())
}

private fun parseIso(m: MatchResult): Double {
    val g = m.groupValues
    val year = g[1].toDouble()
    val month = if (g[2].isEmpty()) 1.0 else g[2].toDouble()
    val day = if (g[3].isEmpty()) 1.0 else g[3].toDouble()
    val hasTime = g[4].isNotEmpty()
    val hour = if (hasTime) g[4].toDouble() else 0.0
    val minute = if (hasTime) g[5].toDouble() else 0.0
    val second = if (g[6].isEmpty()) 0.0 else g[6].toDouble()
    val ms = if (g[7].isEmpty()) 0.0 else (g[7] + "00").substring(0, 3).toDouble()
    if (g[1] == "-000000") return Double.NaN
    if (month < 1 || month > 12 || day < 1 || day > 31 || hour > 24 || minute > 59 || second > 59) return Double.NaN
    if (hour == 24.0 && (minute != 0.0 || second != 0.0 || ms != 0.0)) return Double.NaN
    val local = makeDate(makeDay(year, month - 1, day), makeTime(hour, minute, second, ms))
    val zone = g[8]
    return timeClip(
        when {
            zone.isEmpty() -> if (hasTime) fromLocal(local) else local
            zone == "Z" || zone == "z" -> local
            else -> local - zoneOffsetMillis(zone)
        },
    )
}

/** `+01:00` or `+0100` as milliseconds east of UTC. */
private fun zoneOffsetMillis(zone: String): Double {
    val sign = if (zone[0] == '-') -1 else 1
    val digits = zone.substring(1).replace(":", "")
    val hours = digits.substring(0, 2).toInt()
    val minutes = if (digits.length >= 4) digits.substring(2, 4).toInt() else 0
    return sign * (hours * 3_600_000.0 + minutes * 60_000.0)
}

private val LEGACY_TOKEN = Regex("\\d{1,2}:\\d{2}(?::\\d{2}(?:\\.\\d+)?)?|\\d+|[A-Za-z]+|[/+-]")

/** The time zone offset a legacy date gives after `GMT` or a time: `+2`, `+02`, `+0200`, `+02:00`; null when [t] is not one. */
private fun legacyOffset(sign: Char, t: String): Double? {
    val minutesOfDay = when {
        t.contains(':') -> t.split(':').let { it[0].toInt() * 60 + it[1].toInt() }
        t.length <= 2 -> t.toInt() * 60
        t.length <= 4 -> t.toInt().let { (it / 100) * 60 + it % 100 }
        else -> return null
    }
    return (if (sign == '-') -1 else 1) * minutesOfDay * 60_000.0
}

private fun parseLegacy(text: String): Double {
    val clean = text.replace(Regex("\\([^)]*\\)"), " ")
    var month = -1
    val numbers = ArrayList<Int>()
    var hour = 0.0
    var minute = 0.0
    var second = 0.0
    var ms = 0.0
    var hasTime = false
    var sawZone = false
    var zoneOffset: Double? = null
    var pm: Boolean? = null
    val tokens = LEGACY_TOKEN.findAll(clean).map { it.value }.toList()
    if (tokens.isEmpty() || tokens.joinToString("").length < clean.filter { !it.isWhitespace() && it != ',' && it != '.' }.length) return Double.NaN
    var i = 0
    while (i < tokens.size) {
        val t = tokens[i]
        when {
            t == "/" -> Unit
            (t == "+" || t == "-") && (hasTime || sawZone) && i + 1 < tokens.size && tokens[i + 1][0].isDigit() -> {
                zoneOffset = legacyOffset(t[0], tokens[i + 1]) ?: return Double.NaN
                i++
            }
            t == "+" || t == "-" -> Unit
            t.contains(':') -> {
                val parts = t.split(':')
                hasTime = true
                hour = parts[0].toDouble()
                minute = parts[1].toDouble()
                if (parts.size > 2) {
                    val s = parts[2].split('.')
                    second = s[0].toDouble()
                    if (s.size > 1) ms = (s[1] + "00").substring(0, 3).toDouble()
                }
            }
            t[0].isDigit() -> numbers.add(t.toInt())
            t[0].isLetter() -> {
                val w = t.lowercase()
                when {
                    w == "am" -> pm = false
                    w == "pm" -> pm = true
                    w == "gmt" || w == "utc" || w == "ut" || w == "z" -> {
                        sawZone = true
                        if (zoneOffset == null) zoneOffset = 0.0
                    }
                    w.length >= 3 && MONTH_FULL.any { it.startsWith(w) } -> month = MONTH_FULL.indexOfFirst { it.startsWith(w) }
                    w.length >= 3 && DAY_NAMES.any { it.lowercase() == w.take(3) } -> Unit
                    else -> return Double.NaN
                }
            }
        }
        i++
    }
    var year: Int
    val day: Int
    if (month >= 0) {
        when (numbers.size) {
            0 -> return Double.NaN
            1 -> {
                if (numbers[0] > 31) {
                    year = numbers[0]
                    day = 1
                } else {
                    day = numbers[0]
                    year = 2001
                }
            }
            else -> if (numbers[0] > 31) {
                year = numbers[0]
                day = numbers[1]
            } else {
                day = numbers[0]
                year = numbers[1]
            }
        }
    } else {
        if (numbers.size < 3) return Double.NaN
        if (numbers[0] > 31) {
            year = numbers[0]
            month = numbers[1] - 1
            day = numbers[2]
        } else {
            month = numbers[0] - 1
            day = numbers[1]
            year = numbers[2]
        }
    }
    if (year < 50) year += 2000 else if (year < 100) year += 1900
    if (month !in 0..11 || day < 1 || day > 31) return Double.NaN
    if (pm != null) {
        if (hour > 12) return Double.NaN
        hour = (hour % 12) + (if (pm) 12 else 0)
    }
    val local = makeDate(makeDay(year.toDouble(), month.toDouble(), day.toDouble()), makeTime(hour, minute, second, ms))
    return timeClip(if (zoneOffset == null) fromLocal(local) else local - zoneOffset)
}

// ---- the Date global ------------------------------------------------------------------------------------------------------

private fun Realm.now(): Double = floor(host.nowSeconds() * 1000.0)

internal fun installDate(realm: Realm, std: StdRealm) {
    val proto = realm.newSharedObject()
    std.datePrototype = proto
    val ctor = realm.constructor(
        "Date", 7, proto,
        { _, _ -> dateString(realm.now()) },
        { args -> JsDate(proto, constructedTime(realm, args)) },
    )
    realm.defineGlobal("Date", ctor)

    realm.defineMethod(ctor, "now", 0) { _, _ -> realm.now() }
    realm.defineMethod(ctor, "parse", 1) { _, args -> parseDate(realm.toStr(args.arg(0))) }
    realm.defineMethod(ctor, "UTC", 7) { _, args -> timeClip(fieldsToTime(realm, args)) }

    /** The time value of the receiver; a getter says `this is not a Date object.`, the other methods name themselves (V8's two wordings). */
    fun thisTime(self: Any?, method: String? = null): Double = (self as? JsDate)?.time ?: throw realm.typeError(
        if (method == null) "this is not a Date object." else "Method Date.prototype.$method called on incompatible receiver ${describeReceiver(realm, self)}",
    )

    realm.defineMethod(proto, "getTime", 0) { self, _ -> thisTime(self) }
    realm.defineMethod(proto, "valueOf", 0) { self, _ -> thisTime(self) }
    realm.defineMethod(proto, "getTimezoneOffset", 0) { self, _ ->
        val t = thisTime(self)
        if (t.isNaN()) t else (t - toLocal(t)) / 60_000.0 + 0.0
    }
    fun getter(name: String, local: Boolean, pick: (Fields) -> Double) {
        realm.defineMethod(proto, name, 0) { self, _ ->
            val t = thisTime(self)
            if (t.isNaN()) t else pick(Fields(if (local) toLocal(t) else t))
        }
    }
    getter("getFullYear", true) { it.year }
    getter("getMonth", true) { it.month }
    getter("getDate", true) { it.date }
    getter("getDay", true) { it.weekday.toDouble() }
    getter("getHours", true) { it.hours }
    getter("getMinutes", true) { it.minutes }
    getter("getSeconds", true) { it.seconds }
    getter("getMilliseconds", true) { it.ms }
    getter("getUTCFullYear", false) { it.year }
    getter("getUTCMonth", false) { it.month }
    getter("getUTCDate", false) { it.date }
    getter("getUTCDay", false) { it.weekday.toDouble() }
    getter("getUTCHours", false) { it.hours }
    getter("getUTCMinutes", false) { it.minutes }
    getter("getUTCSeconds", false) { it.seconds }
    getter("getUTCMilliseconds", false) { it.ms }
    getter("getYear", true) { it.year - 1900 }

    realm.defineMethod(proto, "setTime", 1) { self, args ->
        thisTime(self, "setTime")
        val t = timeClip(realm.toNumber(args.arg(0)))
        (self as JsDate).time = t
        t
    }
    // setMilliseconds(ms) .. setFullYear(y, m, d): [first] is the field the first argument replaces, [count] how many it may give.
    fun setter(name: String, local: Boolean, first: Int, count: Int) {
        realm.defineMethod(proto, name, count) { self, args ->
            val date = self as? JsDate ?: throw realm.typeError("Method Date.prototype.$name called on incompatible receiver ${describeReceiver(realm, self)}")
            val given = DoubleArray(minOf(count, maxOf(args.size, 1))) { realm.toNumber(args.arg(it)) }
            var t = date.time
            if (t.isNaN()) {
                if (first != 0) return@defineMethod Double.NaN
                t = 0.0
            } else if (local) {
                t = toLocal(t)
            }
            val f = Fields(t)
            val fields = doubleArrayOf(f.year, f.month, f.date, f.hours, f.minutes, f.seconds, f.ms)
            for (k in given.indices) fields[first + k] = given[k]
            val built = makeDate(makeDay(fields[0], fields[1], fields[2]), makeTime(fields[3], fields[4], fields[5], fields[6]))
            val result = timeClip(if (local) fromLocal(built) else built)
            date.time = result
            result
        }
    }
    for (local in listOf(true, false)) {
        val u = if (local) "" else "UTC"
        setter("set${u}Milliseconds", local, 6, 1)
        setter("set${u}Seconds", local, 5, 2)
        setter("set${u}Minutes", local, 4, 3)
        setter("set${u}Hours", local, 3, 4)
        setter("set${u}Date", local, 2, 1)
        setter("set${u}Month", local, 1, 2)
        setter("set${u}FullYear", local, 0, 3)
    }

    fun formatter(name: String, format: (Double) -> String) {
        realm.defineMethod(proto, name, 0) { self, _ ->
            val t = thisTime(self, name)
            if (t.isNaN()) "Invalid Date" else format(t)
        }
    }
    formatter("toString") { dateString(it) }
    formatter("toDateString") { dateText(it) }
    formatter("toTimeString") { timeText(it) }
    formatter("toUTCString") { utcText(it) }
    realm.alias(proto, "toGMTString", "toUTCString")
    formatter("toLocaleDateString") { localeDate(it) }
    formatter("toLocaleTimeString") { localeTime(it) }
    formatter("toLocaleString") { "${localeDate(it)}, ${localeTime(it)}" }
    realm.defineMethod(proto, "toISOString", 0) { self, _ ->
        val t = thisTime(self, "toISOString")
        if (t.isNaN()) throw realm.rangeError("Invalid time value")
        isoString(t)
    }
    realm.defineMethod(proto, "toJSON", 1) { self, _ ->
        val o = realm.toObject(self)
        val tv = realm.toPrimitive(o, Hint.NUMBER)
        if (tv is Double && !tv.isFinite()) return@defineMethod null
        val iso = o.get("toISOString")
        if (iso !is JsFunction && iso !is JsFn) throw realm.typeError("toISOString is not a function")
        realm.callFunction(iso, o, emptyList())
    }
}

private fun dateString(t: Double): String = if (t.isNaN()) "Invalid Date" else "${dateText(t)} ${timeText(t)}"

/** The time value `new Date(...args)` makes: nothing is now, one argument a copy, a string or a number, more are the fields in local time. */
private fun constructedTime(realm: Realm, args: List<Any?>): Double {
    if (args.isEmpty()) return realm.now()
    if (args.size == 1) {
        val v = args[0]
        if (v is JsDate) return v.time
        val prim = realm.toPrimitive(v, Hint.DEFAULT)
        return if (prim is String) parseDate(prim) else timeClip(realm.toNumber(prim))
    }
    return timeClip(fromLocal(fieldsToTime(realm, args, local = true)))
}

/** The time value of `Date.UTC(year, month = 0, day = 1, hours = 0, ...)`; with [local] the same fields as local time (not yet converted). */
private fun fieldsToTime(realm: Realm, args: List<Any?>, local: Boolean = false): Double {
    val n = DoubleArray(7) { if (it < args.size) realm.toNumber(args[it]) else if (it == 2) 1.0 else 0.0 }
    val year = n[0]
    val full = if (!year.isNaN() && truncate(year) in 0.0..99.0) 1900 + truncate(year) else year
    if (args.isEmpty() && !local) return Double.NaN
    return makeDate(makeDay(full, n[1], n[2]), makeTime(n[3], n[4], n[5], n[6]))
}
