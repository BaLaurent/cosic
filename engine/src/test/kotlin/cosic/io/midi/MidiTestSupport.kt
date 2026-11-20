package cosic.io.midi

import cosic.pattern.Hap
import cosic.pattern.Rational
import cosic.pattern.ScheduledHap
import cosic.pattern.TimeSpan
import cosic.pattern.Undefined
import cosic.pattern.jsOrdered
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The golden vectors `node tools/strudel-ref/midi-oracle.mjs` records: what the real @strudel/midi (with the real webmidi) sends. */
internal object MidiVectors {
    val root: JsonObject by lazy {
        val text = checkNotNull(MidiVectors::class.java.getResourceAsStream("/midi/vectors.json")) { "missing /midi/vectors.json" }
            .use { it.readBytes().toString(Charsets.UTF_8) }
        Json.parseToJsonElement(text).jsonObject
    }

    fun list(key: String): List<JsonObject> = root.getValue(key).jsonArray.map { it.jsonObject }

    /** A value of the oracle's JSON as a value of a program: `{"$": "NaN"}` and the like are the numbers and `undefined` JSON cannot hold. */
    fun value(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonArray -> e.map(::value)
        is JsonObject -> {
            val tag = e["$"]
            if (tag != null) {
                when (val name = tag.jsonPrimitive.content) {
                    "undefined" -> Undefined
                    "NaN" -> Double.NaN
                    "Infinity" -> Double.POSITIVE_INFINITY
                    "-Infinity" -> Double.NEGATIVE_INFINITY
                    "-0" -> -0.0
                    "pattern" -> cosic.pattern.pure(1.0)
                    else -> error("unknown tag $name")
                }
            } else {
                val out = LinkedHashMap<String, Any?>()
                for ((k, v) in e) out[k] = value(v)
                jsOrdered(out)
            }
        }
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.content == "true" || e.content == "false" -> e.boolean
            else -> e.content.toDouble()
        }
    }

    fun rational(e: JsonElement): Rational {
        val pair = e.jsonArray
        return Rational(pair[0].jsonPrimitive.content.toLong(), pair[1].jsonPrimitive.content.toLong())
    }
}

/** The hap the oracle's case describes, as the scheduler hands it to hooks. */
internal fun scheduledHap(case: JsonObject, frame: Long = 480_000L): ScheduledHap {
    val whole = case.getValue("whole").jsonArray
    val span = TimeSpan(MidiVectors.rational(whole[0]), MidiVectors.rational(whole[1]))
    val hap = Hap(span, span, MidiVectors.value(case.getValue("value")))
    val cps = case.getValue("cps").jsonPrimitive.content.toDouble()
    return ScheduledHap(hap, frame, frame / 48_000.0, cps, span.begin.toDouble(), hap.duration.toDouble())
}
