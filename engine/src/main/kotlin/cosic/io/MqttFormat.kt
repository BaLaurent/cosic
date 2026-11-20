package cosic.io

import cosic.io.mqtt.MqttMessage
import cosic.io.mqtt.MqttRoute
import cosic.pattern.Undefined
import cosic.pattern.jsMerge
import cosic.pattern.jsToString
import cosic.pattern.jsonStringify

/**
 * What a hap becomes as an MQTT message (the hook of `Pattern.prototype.mqtt` in `packages/mqtt/mqtt.mjs`):
 *  - a control map (or a list, which JavaScript spreads into a map with the indices as keys) is sent as JSON text; without a topic in the route
 *    its `topic` entry gives it, `/` + the entry (a list joined with `/`); with `addMeta`, `duration` (the length of the hap in seconds) and
 *    `cps` follow the entries, replacing ones of those names in place;
 *  - a string is sent as it is;
 *  - anything else cannot be sent: Paho refuses a number or a boolean as the payload of a message.
 * Entries whose names start with `_cosic_` are the app's routing data and are not sent. Null is returned, with [onDrop] told why, for a message that
 * cannot go out: a value of another type, or a topic that is empty or has a wildcard (`+`, `#`) or a NUL in it.
 */
internal fun mqttMessage(
    value: Any?,
    route: MqttRoute,
    durationSec: Double,
    cps: Double,
    onDrop: (String) -> Unit = {},
): MqttMessage? {
    var topic = route.topic
    val text: String
    when (value) {
        is String -> text = value
        is Map<*, *>, is List<*> -> {
            val entries = LinkedHashMap<String, Any?>()
            if (value is Map<*, *>) {
                for ((k, v) in value) if (!k.toString().startsWith("_cosic_")) entries[k.toString()] = v
            } else {
                (value as List<*>).forEachIndexed { i, v -> entries[i.toString()] = v }
            }
            if (topic == null && value is Map<*, *> && value.containsKey("topic")) {
                val t = value["topic"]
                topic = "/" + if (t is List<*>) t.joinToString("/") { if (it == null || it === Undefined) "" else jsToString(it) } else jsToString(t)
            }
            val sent = if (route.addMeta) jsMerge(entries, linkedMapOf("duration" to durationSec, "cps" to cps)) else entries
            text = jsonStringify(sent) ?: "{}"
        }
        else -> {
            onDrop("cannot send a ${if (value == null) "null" else value.javaClass.simpleName.lowercase()} value, only maps and strings")
            return null
        }
    }
    if (topic.isNullOrEmpty()) {
        onDrop("no topic: give one to mqtt() or put a topic in the values")
        return null
    }
    if (topic.any { it == '+' || it == '#' || it == '\u0000' }) {
        onDrop("topic $topic has a wildcard or a NUL in it")
        return null
    }
    return MqttMessage(topic, text.toByteArray(Charsets.UTF_8))
}
