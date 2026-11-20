package com.octopusman.cosic.midi

/**
 * What the app needs to know of a MIDI device to number its ports (a plain description, so that the numbering can be tested without Android).
 * [receivePorts] are the ports that take MIDI data from the app (`PortInfo.TYPE_INPUT` of Android, the outputs of a program), [sendPorts] those that
 * give MIDI data to the app (`TYPE_OUTPUT`, the inputs of a program); both are the port numbers the device reports.
 */
internal data class MidiDeviceDescription(val id: Int, val name: String, val receivePorts: List<Int>, val sendPorts: List<Int>)

/** One port of one device, listed by the [name] of its device (the name a program gives to `midi('...')` or `midin('...')` is looked for in it). */
internal data class MidiPortRef(val deviceId: Int, val portNumber: Int, val name: String)

/**
 * The outputs and inputs of all devices in the order a program counts them: devices by their id, and the ports of a device by their number
 * (`midi(1)` is the second port of that list). Every port of a device has the name of the device.
 */
internal class MidiPortLists(val outputs: List<MidiPortRef>, val inputs: List<MidiPortRef>) {
    companion object {
        val EMPTY = MidiPortLists(emptyList(), emptyList())

        fun of(devices: List<MidiDeviceDescription>): MidiPortLists {
            val sorted = devices.sortedBy { it.id }
            val outputs = sorted.flatMap { d -> d.receivePorts.sorted().map { MidiPortRef(d.id, it, d.name) } }
            val inputs = sorted.flatMap { d -> d.sendPorts.sorted().map { MidiPortRef(d.id, it, d.name) } }
            return MidiPortLists(outputs, inputs)
        }
    }
}

/** The name a device is listed by: its name, else its product, else a number. */
internal fun midiDeviceName(name: String?, product: String?, id: Int): String =
    name?.takeIf { it.isNotBlank() } ?: product?.takeIf { it.isNotBlank() } ?: "MIDI device $id"

/**
 * Splits [bytes] into pieces of at most [max] bytes, for a port that takes no more in one call (`MidiReceiver.getMaxMessageSize()`): a long
 * system exclusive message goes over in several calls, which the receiver puts together. A message that fits is not copied.
 */
internal fun chunkMessage(bytes: ByteArray, max: Int): List<ByteArray> {
    if (max <= 0 || bytes.size <= max) return listOf(bytes)
    val out = ArrayList<ByteArray>((bytes.size + max - 1) / max)
    var at = 0
    while (at < bytes.size) {
        val end = minOf(bytes.size, at + max)
        out.add(bytes.copyOfRange(at, end))
        at = end
    }
    return out
}
