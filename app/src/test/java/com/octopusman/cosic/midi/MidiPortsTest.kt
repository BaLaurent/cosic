package com.octopusman.cosic.midi

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

class MidiPortsTest {
    @Test
    fun portsAreNumberedByDeviceIdAndThenPortNumber() {
        val lists = MidiPortLists.of(
            listOf(
                MidiDeviceDescription(9, "Keys", receivePorts = listOf(1, 0), sendPorts = listOf(0)),
                MidiDeviceDescription(3, "Synth", receivePorts = listOf(0), sendPorts = emptyList()),
            ),
        )
        assertEquals(listOf("Synth" to 0, "Keys" to 0, "Keys" to 1), lists.outputs.map { it.name to it.portNumber })
        assertEquals(listOf(3, 9, 9), lists.outputs.map { it.deviceId })
        assertEquals(listOf("Keys"), lists.inputs.map { it.name })
    }

    @Test
    fun everyPortHasTheNameOfItsDevice() {
        val lists = MidiPortLists.of(listOf(MidiDeviceDescription(1, "Arturia KeyStep 32", listOf(0, 1, 2), listOf(0, 1))))
        assertEquals(List(3) { "Arturia KeyStep 32" }, lists.outputs.map { it.name })
        assertEquals(List(2) { "Arturia KeyStep 32" }, lists.inputs.map { it.name })
    }

    @Test
    fun noDevicesNoPorts() {
        val lists = MidiPortLists.of(emptyList())
        assertEquals(emptyList(), lists.outputs)
        assertEquals(emptyList(), lists.inputs)
    }

    @Test
    fun theNameOfADeviceFallsBackToItsProductAndThenANumber() {
        assertEquals("Keys", midiDeviceName("Keys", "Product", 4))
        assertEquals("Product", midiDeviceName(null, "Product", 4))
        assertEquals("Product", midiDeviceName("  ", "Product", 4))
        assertEquals("MIDI device 4", midiDeviceName(null, null, 4))
    }

    @Test
    fun aMessageThatFitsIsNotCopied() {
        val message = byteArrayOf(0x90.toByte(), 60, 100)
        assertSame(message, chunkMessage(message, 1024).single())
        assertSame(message, chunkMessage(message, 3).single())
    }

    @Test
    fun aLongMessageIsCutIntoPiecesThatFit() {
        val sysex = ByteArray(10) { (it + 1).toByte() }
        val pieces = chunkMessage(sysex, 4)
        assertEquals(listOf(4, 4, 2), pieces.map { it.size })
        assertContentEquals(sysex, pieces.reduce { a, b -> a + b })
    }
}
