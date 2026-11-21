package cosic.audio

import java.lang.management.ManagementFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The audio thread must not allocate while rendering: garbage collection there means dropouts. */
class RenderAllocationTest {
    private val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean

    /**
     * Voices of every kind, then a crowd above the polyphony limit that steals the oldest. A source plays once, so every voice has the source of its own, made here, off the
     * measured path, as the sound lookup makes them.
     */
    private fun workloadVoices(): List<VoiceParams> {
        val voices = ArrayList<VoiceParams>()
        voices.add(
            synthVp(
                "sawtooth", 0.4, "note" to 50.0, "cutoff" to 900.0, "resonance" to 5.0, "delay" to 0.5, "delayfeedback" to 0.7,
                "room" to 0.4, "pan" to 0.1, "release" to 0.2, "FXrelease" to 0.5,
            ),
        )
        voices.add(synthVp("square", 0.4, "note" to 62.0, "hcutoff" to 200.0, "bandf" to 2000.0, "shape" to 0.6, "pan" to 0.9, "orbit" to 2.0))
        voices.add(synthVp("pink", 0.3, "crush" to 5.0, "coarse" to 3.0, "room" to 0.6, "roomsize" to 0.9, "dry" to 0.5))
        voices.add(synthVp("brown", 0.3, "delay" to 0.3, "delaytime" to 0.12, "bus" to 1.0, "analyze" to 1.0))
        // analysed voices: a stereo one (down-mixed into the tap), a mono one, under a numeric and a widget id
        voices.add(synthVp("sine", 0.3, "note" to 60.0, "pan" to 0.8, "analyze" to "_widget__scope_0"))
        voices.add(vp(VoiceSource.Sample(SampleSourceParams(sineSample(200.0, 0.5))), 0.4, "analyze" to 1.0))
        voices.add(
            vp(
                VoiceSource.Sample(SampleSourceParams(sineSample(200.0, 0.5), transposeSemitones = 12.0)), 0.5,
                "speed" to 1.3, "begin" to 0.1, "end" to 0.9, "cut" to 1.0,
            ),
        )
        voices.add(
            vp(
                VoiceSource.Sample(SampleSourceParams(sineSample(300.0, 0.5))), 0.5,
                "speed" to -1.0, "cut" to 1.0, "room" to 0.2, "delay" to 0.2, "channels" to listOf(1.0, 2.0),
            ),
        )
        voices.add(
            vp(
                VoiceSource.Sample(SampleSourceParams(stereoSample(0.5), nudgeSeconds = 0.003)), 0.5,
                "loop" to 1.0, "loopBegin" to 0.2, "loopEnd" to 0.7, "unit" to "c", "speed" to 2.0, "pan" to 0.3,
            ),
        )
        voices.add(synthVp("sine", 0.2, "note" to 64.0, "djf" to 0.3, "duckorbit" to 1.0, "duckdepth" to 0.5))
        // the families of sources that carry their own state: worklets that start on the render quantum grid, noise, FM and vibrato on a pitch bus, a drum with its own shaper
        voices.add(synthVp("supersaw", 0.4, "note" to 48.0, "unison" to 5.0, "spread" to 0.6, "detune" to 0.3, "pan" to 0.2))
        voices.add(synthVp("pulse", 0.4, "note" to 55.0, "pw" to 0.3, "pwrate" to 4.0, "pwsweep" to 0.2))
        voices.add(synthVp("bytebeat", 0.4, "n" to 3.0))
        voices.add(synthVp("sbd", 0.4, "note" to 31.0))
        voices.add(synthVp("white", 0.2, "gain" to 0.3))
        voices.add(synthVp("crackle", 0.3, "density" to 0.2))
        voices.add(synthVp("sine", 0.4, "note" to 57.0, "fmi" to 2.0, "fmh" to 1.5, "vib" to 5.0, "vibmod" to 0.4, "penv" to 7.0, "pattack" to 0.01))
        voices.add(synthVp("triangle", 0.4, "note" to 60.0, "partials" to listOf(1.0, 0.5, 0.25), "noise" to 0.3))
        // a crowd above the polyphony limit steals voices (the ramp is scheduled on the timelines)
        val crowd = listOf("sawtooth", "square", "pink", "brown")
        for (i in 0 until 140) voices.add(synthVp(crowd[i % 4], 0.4, "note" to 50.0 + i % 12))
        return voices
    }

    /**
     * The bytes that [run] allocates on this thread, the least of [ATTEMPTS] runs after [warmUps] runs to warm up. [prepare] makes what a run needs off the measured path (a source plays
     * once, so every run has voices of its own). Code that allocates does so every time it runs; the sporadic few bytes of a run are the JIT's (a deoptimisation boxes a value of an
     * interpreter frame), which come and go with the timing of the compiler and are not the audio thread's.
     */
    private fun <T> leastAllocated(warmUps: Int, prepare: () -> T, run: (T) -> Unit): Long {
        repeat(warmUps) { run(prepare()) }
        var least = Long.MAX_VALUE
        for (attempt in 0 until ATTEMPTS) {
            val input = prepare()
            val before = threads.currentThreadAllocatedBytes
            run(input)
            least = minOf(least, threads.currentThreadAllocatedBytes - before)
            if (least == 0L) break
        }
        return least
    }

    @Test
    fun renderingAndStartingVoicesDoNotAllocate() {
        val mixer = Mixer(SR)
        val out = FloatArray(512)
        val allocated = leastAllocated(3, ::workloadVoices) { voices ->
            for (i in voices.indices) mixer.startVoice(voices[i])
            for (block in 0 until 200) {
                mixer.render(out, 256)
                mixer.scopes.endBlock(256) // the analysers take in the block, as the engine does at the end of each one
            }
            mixer.stopAll(5)
            mixer.render(out, 256)
        }
        assertEquals(0L, allocated, "rendering allocated $allocated bytes")
        assertTrue(mixer.scopes.read("1", 64, FloatArray(64)) && mixer.scopes.read("_widget__scope_0", 64, FloatArray(64)), "the analysers were fed")
    }

    /**
     * Voices with `FX(...)` chains and vocoders, the pools grown ahead of them by the background preparer as the engine does: queued events ask until the pools hold what they
     * need, the voices start, and while they sound every block asks again with nothing queued. The audio thread allocates nothing once the pools have grown, and makes no chain.
     */
    @Test
    fun fxVoicesWithABackgroundPreparedPoolDoNotAllocateOnTheAudioThread() {
        val mixer = Mixer(SR)
        val preparer = PoolPreparer(mixer, background = true)
        val out = FloatArray(512)
        val none = ChainDemand() // what the scheduler has queued once the events have started
        fun fxVoices() = listOf(
            vp(dcSource(), 0.2, "gain" to 0.1, "FX" to List(3) { mapOf("gain" to 0.5, "pan" to 0.3) }, "pan" to 0.8),
            synthVp("sine", 0.3, "note" to 62.0, "gain" to 0.1, "FX" to List(MAX_FX) { mapOf("gain" to 0.7) }),
            vp(dcSource(), 0.1, "FX" to listOf(mapOf("velocity" to 0.5)), "FXrelease" to 0.2),
        )
        class Round(val voices: List<VoiceParams>, val queued: ChainDemand)
        val allocated = leastAllocated(
            3,
            {
                val voices = List(14) { fxVoices() }.flatten()
                Round(voices, ChainDemand().also { demand -> for (v in voices) demand.add(v) })
            },
        ) { round ->
            val voices = round.voices
            val queued = round.queued
            val deadline = System.nanoTime() + 10_000_000_000L
            do {
                preparer.request(queued)
                if (mixer.isPrepared(queued.fxChains, queued.vocoders)) break
                Thread.onSpinWait()
            } while (System.nanoTime() < deadline)
            for (i in voices.indices) mixer.startVoice(voices[i])
            for (block in 0 until 120) {
                preparer.request(none)
                mixer.render(out, 256)
            }
            mixer.stopAll(5)
            mixer.render(out, 256)
        }
        assertEquals(0L, allocated, "requesting, starting, rendering and ending voices with FX chains allocated $allocated bytes on the audio thread")
        assertEquals(0, mixer.chainsMadeOnTheSpot, "the audio thread made no chain: the worker had grown the pools")
    }

    private companion object {
        const val ATTEMPTS = 6
    }

    /**
     * Kabelsalat programs, the generic processor as the first stage of a chain: a source with an envelope, a filter and feedback delays; an effect on the sound of the event (biquad,
     * noise, a parameter that follows a lfo); programs that are `FX(...)` elements ahead of the main chain. Instances are made (and their rings allocated) before the measured part;
     * rendering them must not allocate. The loop that runs a program is compiled by the JIT late and may deoptimize once on a branch it had not seen (a few dozen bytes, once), so the
     * warm-up runs until it has seen them all.
     */
    private fun kabelVoices(): List<VoiceParams> = listOf(
        kabelVp(
            0.4, "saw(sFreq).mul(sGate.adsr(0.01, 0.1, 0.5, 0.1)).lpf(0.4).add(x => x.delay(0.1).mul(0.5)).add(x => x.delay(n(0.01).mul(2)).mul(0.2)).out()",
            emptyList(), "note" to 50.0, "room" to 0.2,
        ),
        kabelVp(
            0.3, "audioin().qlpf(sine(2).rangex(200, 4000), 5).add(noise().mul(0.05)).add(pink().mul(0.02)).add(brown().mul(0.01)).add(rng().mul(0.01)).mul(0.5).out()",
            emptyList(), "note" to 60.0, "pan" to 0.3, "cutoff" to 3000.0,
        ),
        kabelVp(
            0.3, "audioin().mul(0.5).out()", emptyList(), "note" to 55.0,
            "FX" to listOf(mapOf("workletSrc" to "audioin().mul(0.7).clip(-0.5, 0.5).out()", "workletInputs" to emptyList<Any?>())),
        ),
        kabelVp(
            0.3, "impulse(8).seq(220, 330, 440).lag(0.05).sine().mul(sGate.ar(0.01, 0.1)).add(dust(40).mul(0.1)).mul(0.5).out([0, 1])",
            emptyList(), "note" to 62.0, "delay" to 0.3, "delaytime" to 0.1,
        ),
    )

    @Test
    fun kabelsalatVoicesDoNotAllocate() {
        val mixer = Mixer(SR)
        val out = FloatArray(512)

        fun workload(voices: List<VoiceParams>) {
            for (i in voices.indices) mixer.startVoice(voices[i])
            for (block in 0 until 120) mixer.render(out, 256)
            mixer.stopAll(5)
            mixer.render(out, 256)
        }

        repeat(30) { workload(kabelVoices()) }
        val measured = kabelVoices()
        val before = threads.currentThreadAllocatedBytes
        workload(measured)
        val allocated = threads.currentThreadAllocatedBytes - before
        assertEquals(0L, allocated, "rendering allocated $allocated bytes")
    }
}
