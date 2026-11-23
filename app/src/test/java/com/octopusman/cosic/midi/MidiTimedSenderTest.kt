package com.octopusman.cosic.midi

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MidiTimedSenderTest {
    private class Delivered(val port: String, val value: Int, val at: Long)

    private fun senderInto(log: MutableList<Delivered>, latch: CountDownLatch? = null) = MidiTimedSender<String>(
        deliver = { port, bytes ->
            log.add(Delivered(port, bytes[0].toInt(), System.nanoTime()))
            latch?.countDown()
        },
    )

    private fun ms(n: Long) = n * 1_000_000L

    @Test
    fun messagesGoOutInTheOrderOfTheirTimeNotOfTheirQueueing() {
        val log = CopyOnWriteArrayList<Delivered>()
        val latch = CountDownLatch(3)
        val sender = senderInto(log, latch)
        val now = System.nanoTime()
        sender.send("p", byteArrayOf(3), now + ms(60))
        sender.send("p", byteArrayOf(1), now + ms(20))
        sender.send("p", byteArrayOf(2), now + ms(40))
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2, 3), log.map { it.value })
    }

    @Test
    fun aMessageIsNotSentBeforeItIsDueAndNotMuchAfter() {
        val log = CopyOnWriteArrayList<Delivered>()
        val latch = CountDownLatch(1)
        val sender = senderInto(log, latch)
        val due = System.nanoTime() + ms(80)
        sender.send("p", byteArrayOf(1), due)
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        val at = log.single().at
        assertTrue(at >= due - ms(2), "sent ${(due - at) / 1e6} ms early")
        assertTrue(at <= due + ms(40), "sent ${(at - due) / 1e6} ms late")
    }

    @Test
    fun messagesThatAreLateGoOutAtOnceInOrder() {
        val log = CopyOnWriteArrayList<Delivered>()
        val latch = CountDownLatch(3)
        val sender = senderInto(log, latch)
        val before = System.nanoTime()
        val past = before - 5_000_000_000L
        sender.send("p", byteArrayOf(1), past)
        sender.send("p", byteArrayOf(2), past)
        sender.send("p", byteArrayOf(3), Long.MIN_VALUE / 4)
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        // the one with the earliest time is first; the two of the same time keep their order
        assertEquals(listOf(3, 1, 2), log.map { it.value })
        assertTrue(log.all { it.at - before < ms(200) })
    }

    @Test
    fun aMessageQueuedAfterALongWaitStartedStillGoesOutOnTime() {
        val log = CopyOnWriteArrayList<Delivered>()
        val latch = CountDownLatch(2)
        val sender = senderInto(log, latch)
        val now = System.nanoTime()
        sender.send("p", byteArrayOf(2), now + ms(400))
        Thread.sleep(50)
        // the sender is waiting for the first: an earlier message must wake it
        val due = System.nanoTime() + ms(30)
        sender.send("p", byteArrayOf(1), due)
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2), log.map { it.value })
        assertTrue(log[0].at - due < ms(25), "the early message waited ${(log[0].at - due) / 1e6} ms")
    }

    @Test
    fun portsAreKeptApartAndCanBeForgotten() {
        val log = CopyOnWriteArrayList<Delivered>()
        val latch = CountDownLatch(2)
        val sender = senderInto(log, latch)
        val now = System.nanoTime()
        sender.send("a", byteArrayOf(1), now + ms(100))
        sender.send("b", byteArrayOf(2), now + ms(100))
        sender.send("a", byteArrayOf(3), now + ms(120))
        sender.clear("a")
        assertEquals(1, sender.pending, "only the message for b is left")
        sender.send("b", byteArrayOf(4), now + ms(130))
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("b" to 2, "b" to 4), log.map { it.port to it.value })
    }

    @Test
    fun theQueueIsBounded() {
        val release = CountDownLatch(1)
        val sender = MidiTimedSender<String>(deliver = { _, _ -> release.await() })
        val far = System.nanoTime() + 60_000_000_000L
        repeat(9000) { sender.send("p", byteArrayOf(1), far) }
        assertEquals(9000 - 8192, sender.dropped.toInt())
        sender.clear()
        release.countDown()
    }

    @Test
    fun aDeliveryThatThrowsLosesItsMessageAndTheSenderGoesOn() {
        val log = CopyOnWriteArrayList<Delivered>()
        val latch = CountDownLatch(1)
        val sender = MidiTimedSender<String>(
            deliver = { port, bytes ->
                if (bytes[0].toInt() == 1) throw IllegalStateException("the port is closed")
                log.add(Delivered(port, bytes[0].toInt(), System.nanoTime()))
                latch.countDown()
            },
        )
        val now = System.nanoTime()
        sender.send("p", byteArrayOf(1), now)
        sender.send("p", byteArrayOf(2), now + ms(20))
        assertTrue(latch.await(2, TimeUnit.SECONDS), "the message after the failing one was not delivered")
        assertEquals(listOf(2), log.map { it.value })
    }

    @Test
    fun theThreadComesBackAfterItsIdleExit() {
        // the thread is not killed by a quiet period: a message sent much later still goes out
        val log = CopyOnWriteArrayList<Delivered>()
        val latch = CountDownLatch(2)
        val sender = senderInto(log, latch)
        sender.send("p", byteArrayOf(1), System.nanoTime())
        Thread.sleep(100)
        sender.send("p", byteArrayOf(2), System.nanoTime() + ms(10))
        assertTrue(latch.await(2, TimeUnit.SECONDS))
        assertEquals(listOf(1, 2), log.map { it.value })
    }
}
