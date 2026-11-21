package cosic.audio

import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The shared pools of effect chains and vocoders: they grow ahead of the voices that need them, and no voice goes without. */
class ChainPoolsTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /** A voice with [chains] `FX(...)` elements: its level is `0.1 * 0.5^chains` (main gain times the gain of every element's own chain). */
    private fun fxVoice(chains: Int, seconds: Double = 5.0): VoiceParams =
        vp(dcSource(), seconds, "gain" to 0.1, "clip" to 1.0, "FX" to List(chains) { mapOf("gain" to 0.5) })

    private fun demandOf(count: Int, voice: VoiceParams): ChainDemand = ChainDemand().also { repeat(count) { _ -> it.add(voice) } }

    private fun levelAt(x: FloatArray, seconds: Double): Double = x[(seconds * SR).toInt()].toDouble()

    // ------------------------------------------------------------------ GrowablePool

    @Test
    fun aPoolHandsOutWhatItHoldsAndTakesItBack() {
        val pool = GrowablePool(4, 2) { StringBuilder() }
        assertEquals(2, pool.capacity)
        val a = assertNotNull(pool.take())
        val b = assertNotNull(pool.take())
        assertEquals(2, pool.inUse)
        assertEquals(0, pool.madeOnTheSpot)
        pool.give(a)
        pool.give(b)
        assertEquals(0, pool.inUse)
        assertEquals(2, pool.available)
        assertEquals(2, pool.capacity, "nothing new was made")
    }

    @Test
    fun anInstanceIsMadeOnTheSpotWhenNobodyPreparedThePool() {
        val pool = GrowablePool(4, 1) { Any() }
        assertNotNull(pool.take())
        assertNotNull(pool.take(), "the pool is empty, but the voice still gets its instance")
        assertEquals(1, pool.madeOnTheSpot)
        assertEquals(2, pool.capacity)
    }

    @Test
    fun aPoolNeverGrowsBeyondItsLimit() {
        val pool = GrowablePool(3, 0) { Any() }
        repeat(3) { assertNotNull(pool.take()) }
        assertNull(pool.take(), "all three exist and are taken")
        pool.ensureFree(100)
        assertEquals(3, pool.capacity)
    }

    @Test
    fun ensureFreeMakesRoomOnTopOfWhatIsInUse() {
        val pool = GrowablePool(100, 4) { Any() }
        val held = List(3) { pool.take() }
        assertEquals(3, held.size)
        pool.ensureFree(10) // 3 in use + 10 more = 13
        assertEquals(13, pool.capacity)
        assertEquals(10, pool.available)
        pool.ensureFree(5) // already enough
        assertEquals(13, pool.capacity)
        pool.ensureFree(0)
        pool.ensureFree(-4)
        assertEquals(13, pool.capacity)
        repeat(10) { assertNotNull(pool.take()) }
        assertEquals(0, pool.madeOnTheSpot, "everything came from what was made ahead")
    }

    @Test
    fun aPoolGrownFromAnotherThreadLosesNothingWhileTheAudioThreadTakesAndGives() {
        val pool = GrowablePool(512, 2) { Any() }
        val stop = AtomicBoolean(false)
        val scheduler = thread(name = "pool-grower") {
            var wanted = 1
            while (!stop.get()) {
                pool.ensureFree(wanted)
                wanted = wanted % 40 + 1
            }
        }
        val held = ArrayList<Any>()
        var rounds = 0
        while (rounds < 20_000) {
            val burst = rounds % 17
            for (i in 0 until burst) pool.take()?.let { held.add(it) }
            while (held.size > burst / 2) pool.give(held.removeAt(held.size - 1))
            rounds++
        }
        stop.set(true)
        scheduler.join()
        while (held.isNotEmpty()) pool.give(held.removeAt(held.size - 1))
        assertEquals(0, pool.inUse)
        assertEquals(pool.capacity, pool.available, "every instance that was made is back in the pool: none lost, none counted twice")
        assertTrue(pool.capacity <= 512)
    }

    // ------------------------------------------------------------------ FX chains in a mixer

    @Test
    fun fortyOverlappingVoicesWithFxElementsAllGetAllTheirChains() {
        val voice = fxVoice(3)
        val single = levelAt(renderLeft(voice, SR / 10), 0.05)
        assertEquals(0.1 * 0.5 * 0.5 * 0.5, single, 1e-6, "a lone voice: main gain times the gain of its three chains")

        val mixer = Mixer(SR)
        repeat(40) { assertTrue(mixer.startVoice(voice)) }
        val left = channel(mixer.renderFrames(SR / 10), 0)
        assertEquals(40 * single, levelAt(left, 0.05), 1e-4, "a voice that lost a chain would be twice as loud as the others")
        assertEquals(120, mixer.fxPool.inUse, "40 voices with 3 chains each")
        assertTrue(mixer.fxPool.capacity >= 120)
    }

    @Test
    fun voicesWithTheMaximumOfFxElementsOverTheWholePoolAllGetThem() {
        val voice = fxVoice(MAX_FX)
        val mixer = Mixer(SR)
        val voices = 64 // 256 chains: eight times what the pool starts with
        repeat(voices) { assertTrue(mixer.startVoice(voice)) }
        val left = channel(mixer.renderFrames(SR / 20), 0)
        assertEquals(voices * 0.1 * 0.5 * 0.5 * 0.5 * 0.5, levelAt(left, 0.02), 1e-3)
        assertEquals(voices * MAX_FX, mixer.fxPool.inUse)
    }

    @Test
    fun aPoolPreparedForTheQueuedEventsMakesNothingOnTheAudioThread() {
        val voice = fxVoice(3)
        val mixer = Mixer(SR)
        mixer.prepare(demandOf(40, voice)) // what the scheduler does as it queues the events
        assertTrue(mixer.fxPool.capacity >= 120)
        repeat(40) { assertTrue(mixer.startVoice(voice)) }
        assertEquals(120, mixer.fxPool.inUse)
        assertEquals(0, mixer.fxPool.madeOnTheSpot, "every chain was there before its voice started")
    }

    @Test
    fun withoutPreparationTheMixerMakesTheMissingChainsAndSaysSo() {
        val mixer = Mixer(SR)
        repeat(40) { assertTrue(mixer.startVoice(fxVoice(3))) }
        assertEquals(120 - FX_GROUP_POOL, mixer.fxPool.madeOnTheSpot)
    }

    @Test
    fun theChainsComeBackWhenTheVoicesEndAndAreReused() {
        val mixer = Mixer(SR)
        val voice = fxVoice(4, seconds = 0.05)
        mixer.prepare(demandOf(30, voice))
        repeat(30) { mixer.startVoice(voice) }
        assertEquals(120, mixer.fxPool.inUse)
        val capacity = mixer.fxPool.capacity
        mixer.renderFrames(SR) // every voice is over
        assertEquals(0, mixer.fxPool.inUse, "the chains were given back")
        repeat(30) { mixer.startVoice(voice) }
        assertEquals(capacity, mixer.fxPool.capacity, "the second round needed nothing new")
        assertEquals(0, mixer.fxPool.madeOnTheSpot)
    }

    @Test
    fun theDemandOfQueuedVoicesCountsTheirChainsAndVocoders() {
        val demand = ChainDemand()
        demand.add(fxVoice(3))
        demand.add(vp(dcSource(), 1.0, "stretch" to 1.0))
        demand.add(vp(dcSource(), 1.0, "FX" to listOf(mapOf("stretch" to 1.0), mapOf("gain" to 1.0), mapOf("stretch" to -1.0))))
        demand.add(vp(dcSource(), 1.0))
        assertEquals(3 + 3, demand.fxChains)
        assertEquals(1 + 2, demand.vocoders, "the event's own stretch and the stretch of two FX elements")
        demand.clear()
        assertEquals(0, demand.fxChains)
        assertEquals(0, demand.vocoders)
    }

    @Test
    fun voicesWithFxElementsDoNotAllocateOnceThePoolIsPrepared() {
        val mixer = Mixer(SR)
        val out = FloatArray(512)
        val voices = arrayOf(
            vp(dcSource(), 0.2, "gain" to 0.1, "FX" to List(3) { mapOf("gain" to 0.5, "pan" to 0.3) }, "pan" to 0.8),
            synthVp("sine", 0.3, "note" to 62.0, "gain" to 0.1, "FX" to List(MAX_FX) { mapOf("gain" to 0.7) }),
            vp(dcSource(), 0.1, "FX" to listOf(mapOf("velocity" to 0.5)), "FXrelease" to 0.2),
        )
        val demand = ChainDemand()
        for (v in voices) repeat(14) { _ -> demand.add(v) }

        fun workload() {
            // the scheduler's side: it asks for room as it queues, every block
            mixer.prepare(demand)
            for (round in 0 until 14) for (v in voices) mixer.startVoice(v)
            for (block in 0 until 120) {
                mixer.prepare(demand)
                mixer.render(out, 256)
            }
            mixer.stopAll(5)
            mixer.render(out, 256)
        }

        workload()
        workload()
        val before = threads.currentThreadAllocatedBytes
        workload()
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "starting, rendering and ending voices with FX chains allocated $allocated bytes")
        assertEquals(0, mixer.fxPool.madeOnTheSpot, "the audio thread made no chain: the pool was prepared every block")
    }

    // ------------------------------------------------------------------ PoolPreparer: growth off the audio thread

    @Test
    fun aBackgroundPreparerGrowsThePoolsAheadOfFortyOverlappingFxVoicesAndNoneGoesWithoutItsChains() {
        val voice = fxVoice(3)
        val single = levelAt(renderLeft(voice, SR / 10), 0.05)
        val mixer = Mixer(SR)
        val preparer = PoolPreparer(mixer, background = true)

        preparer.request(demandOf(40, voice)) // what the engine does every block with the queued events: it returns at once
        assertTrue(preparer.awaitIdle(10_000), "the worker finished growing the pools")
        assertTrue(mixer.fxPool.capacity >= 120, "capacity ${mixer.fxPool.capacity}")
        assertTrue(mixer.isPrepared(120, 0))

        repeat(40) { assertTrue(mixer.startVoice(voice)) }
        assertEquals(120, mixer.fxPool.inUse)
        assertEquals(0, mixer.chainsMadeOnTheSpot, "the pool was grown by the worker, not by the audio thread")
        val left = channel(mixer.renderFrames(SR / 10), 0)
        assertEquals(40 * single, levelAt(left, 0.05), 1e-4, "every voice has all three chains")
    }

    @Test
    fun anInlinePreparerGrowsThePoolsOnTheSpotForOfflineRenders() {
        val voice = fxVoice(3)
        val mixer = Mixer(SR)
        val preparer = PoolPreparer(mixer, background = false)
        preparer.request(demandOf(40, voice))
        assertTrue(mixer.fxPool.capacity >= 120, "grown before request returned")
        repeat(40) { assertTrue(mixer.startVoice(voice)) }
        assertEquals(0, mixer.chainsMadeOnTheSpot)
    }

    @Test
    fun aPreparerAsksTheWorkerOnlyWhenThePoolsAreShort() {
        val voice = fxVoice(2)
        val mixer = Mixer(SR)
        val preparer = PoolPreparer(mixer, background = true)
        preparer.request(demandOf(4, voice)) // 8 chains: the initial pool holds FX_GROUP_POOL
        assertTrue(preparer.awaitIdle(10_000))
        val capacity = mixer.fxPool.capacity
        preparer.request(demandOf(4, voice))
        assertTrue(preparer.awaitIdle(10_000))
        assertEquals(capacity, mixer.fxPool.capacity, "a demand that is covered makes nothing")
        preparer.request(ChainDemand())
        assertTrue(preparer.awaitIdle(1_000))
        assertEquals(capacity, mixer.fxPool.capacity)
    }
}
