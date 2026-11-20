package com.octopusman.cosic.midi

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The MIDI service against the system's MIDI manager and a virtual device the test APK provides ([TestMidiDeviceService], which echoes what it
 * is sent): the device is listed, messages sent to it arrive in the order of their time and not before it, a long system exclusive message
 * goes over in pieces and is heard whole, and a message split over two sends is heard whole. The echo comes back through the service's input
 * listening, so both directions are tested through the framework.
 */
@RunWith(AndroidJUnit4::class)
class MidiServiceTest {
    private class Heard(val name: String, val message: List<Int>, val at: Long)

    private lateinit var service: MidiService
    private val heard = LinkedBlockingQueue<Heard>()

    private fun waitFor(what: String, millis: Long = 10_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (!condition()) {
            check(System.nanoTime() < deadline) { "timed out waiting for $what" }
            Thread.sleep(20)
        }
    }

    @Before
    fun start() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        service = MidiService(context)
        service.monitor = { name, message -> heard.add(Heard(name, message.map { it.toInt() and 0xFF }, System.nanoTime())) }
        service.start()
    }

    @After
    fun stop() {
        service.close()
    }

    private fun outputIndex(): Int {
        waitFor("the test device in the list of outputs") { service.devices.value.outputs.any { it.contains("Test Device") } }
        return service.devices.value.outputs.indexOfFirst { it.contains("Test Device") }
    }

    /** Listens to the device and waits until the echo comes back: the connection to both of its ports is up. */
    private fun connected(): Int {
        val index = outputIndex()
        waitFor("the test device in the inputs") { service.devices.value.inputs.any { it.contains("Test Device") } }
        service.setListening(true)
        val deadline = System.nanoTime() + 15_000_000_000L
        while (System.nanoTime() < deadline) {
            service.send(index, byteArrayOf(0xFE.toByte()), 0L, -1.0e9) // active sensing, a probe
            if (heard.poll(500, TimeUnit.MILLISECONDS) != null) {
                Thread.sleep(200)
                heard.clear()
                return index
            }
        }
        error("the echo of the test device never came")
    }

    @Test
    fun theVirtualDeviceIsListedAsAnOutputAndAnInputByItsName() {
        assertTrue(service.supported)
        val index = outputIndex()
        waitFor("the test device in the inputs") { service.devices.value.inputs.any { it.contains("Test Device") } }
        assertEquals(service.devices.value.outputs[index], service.outputNames()[index])
        assertEquals(service.devices.value.outputs[index], service.inputNames().first { it.contains("Test Device") })
    }

    @Test
    fun messagesArriveInTheOrderOfTheirTimeAndNotBeforeIt() {
        val index = connected()
        val sentAt = System.nanoTime()
        // no clock is attached: the frame means nothing, the offset is counted from now. A time long past means at once.
        service.send(index, byteArrayOf(0x80.toByte(), 60, 64), 0L, 400.0)
        service.send(index, byteArrayOf(0x90.toByte(), 60, 100), 0L, 150.0)
        service.send(index, byteArrayOf(0xB0.toByte(), 7, 1), 0L, -1.0e9)
        val first = heard.poll(5, TimeUnit.SECONDS)
        val second = heard.poll(5, TimeUnit.SECONDS)
        val third = heard.poll(5, TimeUnit.SECONDS)
        assertNotNull("three messages arrive", third)
        assertEquals(listOf(listOf(0xB0, 7, 1), listOf(0x90, 60, 100), listOf(0x80, 60, 64)), listOf(first!!.message, second!!.message, third!!.message))
        val noteOnAfter = (second.at - sentAt) / 1e6
        val noteOffAfter = (third.at - sentAt) / 1e6
        assertTrue("the note on came $noteOnAfter ms after it was queued, due at 150", noteOnAfter in 145.0..450.0)
        assertTrue("the note off came $noteOffAfter ms after it was queued, due at 400", noteOffAfter in 395.0..700.0)
        assertTrue("the input is called by the name of its device", first.name.contains("Test Device"))
    }

    @Test
    fun aLongSystemExclusiveMessageGoesInPiecesAndIsHeardWhole() {
        val index = connected()
        val sysex = ByteArray(2000) { 1 }.also {
            it[0] = 0xF0.toByte()
            it[it.size - 1] = 0xF7.toByte()
        }
        service.send(index, sysex, 0L, -1.0e9)
        val message = heard.poll(5, TimeUnit.SECONDS)
        assertNotNull("the message arrives", message)
        assertEquals(2000, message!!.message.size)
        assertEquals(0xF0, message.message.first())
        assertEquals(0xF7, message.message.last())
    }

    @Test
    fun aMessageSplitOverTwoSendsIsHeardWhole() {
        val index = connected()
        service.send(index, byteArrayOf(0xB0.toByte(), 7), 0L, -1.0e9)
        service.send(index, byteArrayOf(0x7F), 0L, -1.0e9)
        val message = heard.poll(5, TimeUnit.SECONDS)
        assertNotNull("the message arrives", message)
        assertEquals(listOf(0xB0, 7, 0x7F), message!!.message)
    }

    @Test
    fun listeningStopsWhenAskedTo() {
        val index = connected()
        service.setListening(false)
        Thread.sleep(300)
        heard.clear()
        service.send(index, byteArrayOf(0x90.toByte(), 60, 100), 0L, -1.0e9)
        assertEquals("nothing is heard once listening is off", null, heard.poll(1, TimeUnit.SECONDS))
    }
}
