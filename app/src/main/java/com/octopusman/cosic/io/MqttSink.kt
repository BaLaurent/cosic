package com.octopusman.cosic.io

import android.content.Context
import cosic.CosicEngine
import cosic.io.mqtt.MqttDevice

/**
 * The MQTT connections of the app: what `.mqtt()` and `.robot()` of a program send through. It owns the device (the I/O thread, the sockets), the
 * store of the passwords the user typed and the question that asks for a new one. One per process; [attachTo] plugs it into an engine's output bus.
 *
 * The device never blocks the caller: the hooks of the output dispatcher only enqueue messages, and connecting happens on threads of its own.
 * Sockets need the process to stay alive while the screen is off, which the playback service already does while a program plays.
 */
class MqttSink private constructor(val prompt: MqttPasswordPrompt, credentials: MqttCredentialStore, log: (String) -> Unit) {
    /** The log lines of the device (connected, refused, lost); [attachTo] sets where they go. */
    @Volatile
    private var sinkLog: (String) -> Unit = log

    val device: MqttDevice = MqttDevice(credentials, prompt) { sinkLog(it) }

    /** Makes [engine] send `.mqtt()` haps through this sink, and writes the device's messages to the console of [engine]. */
    fun attachTo(engine: CosicEngine) {
        sinkLog = { engine.console.log(it) }
        engine.outputs.mqtt = device
    }

    /** Closes the connections and answers the open password questions with "none": when playback stops for good. */
    fun stopAll() {
        device.stopAll()
        prompt.cancelAll()
    }

    companion object {
        fun create(context: Context): MqttSink =
            MqttSink(MqttPasswordPrompt(), MqttCredentialStore.create(context.applicationContext)) { }
    }
}
