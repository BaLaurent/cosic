package cosic.script.catalogue

import cosic.EvalEffects
import cosic.io.mqtt.MqttException
import cosic.io.mqtt.MqttRoute
import cosic.io.mqtt.MqttUri
import cosic.io.mqttMessage
import cosic.pattern.Controls
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.pattern.Registry
import cosic.pattern.ScheduleHook
import cosic.pattern.ScheduledHap
import cosic.pattern.Undefined
import cosic.pattern.jsToNumber
import cosic.pattern.jsToString
import cosic.pattern.jsTruthy
import cosic.player.LOOKAHEAD_SECONDS
import java.util.concurrent.atomic.AtomicBoolean

/*
 * `packages/mqtt/mqtt.mjs`: `.mqtt(username, password, topic, host, client, latency, addMeta)` sends the values of a pattern to an MQTT broker as
 * JSON, `.robot(id, address)` is the same for the robots of the Strudel workshops, and `move` and `motor` are plain controls that go with them.
 *
 * `.mqtt()` is a dominant hook (`onSchedule`, K8): the haps it is on are sent and do not sound. The hook runs on the dispatcher of the output bus and
 * only hands the message to the device (see [cosic.io.mqtt.MqttOutput]), which sends it when the hap is due; a message whose connection is not up
 * then is dropped, as in Strudel. The connection itself is made when the program is evaluated.
 */

private const val DEFAULT_HOST = "wss://localhost:8883/"
private const val DEFAULT_ROBOT_ADDRESS = "ws://192.168.8.248:9001/mqtt"

/** The hook of one `.mqtt()` call: the route it was given, and where to find the device and its clock when a hap is scheduled. */
private class MqttHook(private val route: MqttRoute, private val effects: EvalEffects) : ScheduleHook {
    private val missingReported = AtomicBoolean(false)

    override fun run(hap: ScheduledHap) {
        val outputs = effects.outputs
        val sink = outputs.mqtt
        if (sink == null) {
            if (missingReported.compareAndSet(false, true)) outputs.console.error("mqtt", "no MQTT output on this device")
            return
        }
        val durationSec = hap.hap.duration.div(Rational.of(hap.cps)).toDouble()
        val message = mqttMessage(hap.hap.value, route, durationSec, hap.cps) { outputs.console.error("mqtt", it) } ?: return
        val onset = outputs.clock?.nanoTimeAtFrame(hap.frame) ?: (System.nanoTime() + Math.round(LOOKAHEAD_SECONDS * 1e9))
        sink.send(route, message, onset + Math.round(route.latencySec * 1e9))
    }
}

private fun CallContext.plainString(i: Int, name: String): String? {
    val v = kernelArg(i)
    return when {
        v === Undefined || v == null -> null
        v is Pattern -> fail("mqtt(): arguments must be plain values (use single-quoted strings)")
        v is String -> v
        v is Double || v is Boolean -> jsToString(v)
        else -> fail("mqtt(): $name must be a string")
    }
}

private fun CallContext.mqtt(pat: Pattern): Pattern {
    for (i in 0 until 7) if (kernelArg(i) is Pattern) fail("mqtt(): arguments must be plain values (use single-quoted strings)")
    val username = plainString(0, "username")
    val password = plainString(1, "password")
    val topic = plainString(2, "topic")
    val host = if (arg(3) === Undefined) DEFAULT_HOST else jsToString(kernelArg(3))
    val client = plainString(4, "client")
    val latency = if (arg(5) === Undefined) 0.0 else jsToNumber(kernelArg(5)).let { if (it.isNaN()) 0.0 else it }
    val addMeta = if (arg(6) === Undefined) true else jsTruthy(kernelArg(6))
    return attach(pat, MqttRoute(host, client?.takeIf { it.isNotEmpty() }, username, password, topic, latency, addMeta))
}

private fun CallContext.attach(pat: Pattern, route: MqttRoute): Pattern {
    try {
        MqttUri.parse(route.host)
    } catch (e: MqttException) {
        fail(e.message ?: "Invalid argument ${route.host} for host.")
    }
    effects.outputs.mqtt?.prepare(route)
    return pat.onSchedule(MqttHook(route, effects), true)
}

internal fun registerMqtt() {
    // `export const { move, motor } = createParams('move', 'motor')`: controls like any other, for the values the robots take.
    for (name in listOf("move", "motor")) {
        val spec = Controls.registerCustom(listOf(name), false, emptyList())
        Registry.dual(
            listOf(name),
            global = { a -> Controls.applyControl(spec, a.getOrElse(0) { Undefined }, a.getOrNull(1)) },
            method = { pat, a -> Controls.applyControl(spec, a.getOrElse(0) { Undefined }, pat) },
        )
    }

    catalogue("Mqtt") {
        method(listOf("mqtt"), 0) { ctx -> ctx.mqtt(ctx.bridge.receiverOf(ctx.self, "mqtt")) }

        // `robot(robot_id, address)`: `this.mqtt(undefined, undefined, '/move/' + robot_id, address)`
        method(listOf("robot"), 1) { ctx ->
            val pat = ctx.bridge.receiverOf(ctx.self, "robot")
            val address = if (ctx.arg(1) === Undefined) DEFAULT_ROBOT_ADDRESS else jsToString(ctx.kernelArg(1))
            ctx.attach(pat, MqttRoute(address, null, null, null, "/move/" + jsToString(ctx.kernelArg(0))))
        }
    }
}
