package cosic.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.round
import kotlin.math.sin

private const val MAX_GAIN = 4.0
private const val MAX_SPEED = 8.0
private const val MAX_CLIP = 16.0
private const val MAX_ENVELOPE_SECONDS = 10.0
private const val MAX_SHAPE = 0.99
private const val MAX_COARSE = 64.0
private const val MIN_NOTE = -24.0
private const val MAX_NOTE = 132.0
private const val MAX_CUT_GROUP = 1e9
private const val DEFAULT_FILTER_Q = 0.707
private const val DEFAULT_BAND_Q = 1.0

/** Per-block buffers shared by every voice: one mono scratch buffer plus the stereo mix and the two send buses. */
internal class VoiceBuffers(frames: Int) {
    val mono = FloatArray(frames)
    val dry = StereoBuffer(frames)
    val delaySend = StereoBuffer(frames)
    val reverbSend = StereoBuffer(frames)
}

/** [v], or [default] when absent or NaN. */
private fun valueOr(v: Double?, default: Double): Double = if (v == null || v.isNaN()) default else v

/** [valueOr] clamped to [lo]..[hi]. */
private fun clamped(v: Double?, default: Double, lo: Double, hi: Double): Double = valueOr(v, default).coerceIn(lo, hi)

/**
 * One sounding note: source (oscillator or sample), then the optional filter, waveshaper, bit-crusher and
 * sample-and-hold stages, then envelope, gain and pan, mixed into the dry bus and the two send buses.
 * Instances live in a pool and are restarted with [start]; nothing here allocates after construction.
 */
internal class Voice(private val sampleRate: Int) {
    private val oscillator = Oscillator(sampleRate)
    private val player = SamplePlayer(sampleRate)
    private val envelope = Envelope(sampleRate)
    private val lowPass = SvFilter(sampleRate, FilterKind.LOW_PASS)
    private val highPass = SvFilter(sampleRate, FilterKind.HIGH_PASS)
    private val bandPass = SvFilter(sampleRate, FilterKind.BAND_PASS)

    private var isSynth = true
    private var sourceLevel = 1f
    private var useLowPass = false
    private var useHighPass = false
    private var useBandPass = false
    private var shapeAmount = 0f
    private var crushLevels = 0f
    private var coarseFactor = 1
    private var coarseCounter = 0
    private var held = 0f
    private var level = 1f
    private var panLeft = 0f
    private var panRight = 0f
    private var delayLeft = 0f
    private var delayRight = 0f
    private var reverbLeft = 0f
    private var reverbRight = 0f

    /** True while the voice still produces sound, including a forced fade-out. */
    var isActive = false
        private set

    /** True once the voice has been told to fade out (cut, steal or stop). */
    val isFading: Boolean get() = envelope.isFading

    /** Start sequence number, so the pool can find the oldest voice. */
    var order = 0L
        private set

    var hasCut = false
        private set
    var cutGroup = 0
        private set

    /**
     * Resolves [params] (clamping everything) and starts sounding. Returns false and stays idle when the event
     * cannot sound: an empty sample, an empty `begin..end` region, or zero `speed`.
     */
    fun start(params: VoiceParams, order: Long, seed: Int): Boolean {
        isActive = false
        val duration = if (params.durationSec > 0.0) params.durationSec else 0.0 // NaN becomes 0 too
        val clip = clamped(params.clip, Double.NaN, 0.0, MAX_CLIP)
        val gateSeconds: Double
        val defaultAttack: Double
        val defaultDecay: Double
        val defaultSustain: Double
        val defaultRelease: Double

        when (val source = params.source) {
            is VoiceSource.Synth -> {
                val note = clamped(params.note, DEFAULT_NOTE, MIN_NOTE, MAX_NOTE)
                oscillator.start(source.waveform, 440.0 * 2.0.pow((note - 69.0) / 12.0), seed)
                isSynth = true
                sourceLevel = source.waveform.baseLevel
                gateSeconds = duration * (if (clip.isNaN()) 1.0 else clip)
                defaultAttack = 0.002
                defaultDecay = 0.1
                defaultSustain = 0.8
                defaultRelease = 0.05
            }
            is VoiceSource.Sample -> {
                val data = source.data
                val begin = clamped(params.begin, 0.0, 0.0, 1.0)
                val end = clamped(params.end, 1.0, 0.0, 1.0)
                val speed = clamped(params.speed, 1.0, -MAX_SPEED, MAX_SPEED)
                if (end <= begin || speed == 0.0 || data.samples.isEmpty()) return false
                val note = clamped(params.note, Double.NaN, MIN_NOTE, MAX_NOTE)
                val root = source.rootNote
                val pitchRatio = when {
                    root != null -> 2.0.pow(((if (note.isNaN()) DEFAULT_NOTE else note) - root) / 12.0)
                    note.isNaN() -> 1.0
                    else -> 2.0.pow((note - DEFAULT_NOTE) / 12.0)
                }
                player.start(data.samples, begin, end, speed * data.sampleRate / sampleRate * pitchRatio)
                isSynth = false
                gateSeconds = if (clip.isNaN()) Double.POSITIVE_INFINITY else duration * clip
                defaultAttack = 0.001
                defaultDecay = 0.0
                defaultSustain = 1.0
                defaultRelease = 0.01
            }
        }

        envelope.start(
            clamped(params.attack, defaultAttack, 0.0, MAX_ENVELOPE_SECONDS),
            clamped(params.decay, defaultDecay, 0.0, MAX_ENVELOPE_SECONDS),
            clamped(params.sustain, defaultSustain, 0.0, 1.0),
            clamped(params.release, defaultRelease, 0.0, MAX_ENVELOPE_SECONDS),
            gateSeconds,
        )

        // SvFilter.configure owns the cutoff (20 Hz..0.45 x sample rate) and Q (0.1..30) clamps.
        useLowPass = params.lpf.isSet()
        if (useLowPass) lowPass.configure(params.lpf!!, valueOr(params.lpq, DEFAULT_FILTER_Q))
        useHighPass = params.hpf.isSet()
        if (useHighPass) highPass.configure(params.hpf!!, valueOr(params.hpq, DEFAULT_FILTER_Q))
        useBandPass = params.bpf.isSet()
        if (useBandPass) bandPass.configure(params.bpf!!, valueOr(params.bpq, DEFAULT_BAND_Q))

        val shape = clamped(params.shape, 0.0, 0.0, MAX_SHAPE)
        shapeAmount = (2.0 * shape / (1.0 - shape)).toFloat()
        crushLevels = if (params.crush.isSet()) 2.0.pow(clamped(params.crush, 16.0, 1.0, 16.0) - 1.0).toFloat() else 0f
        coarseFactor = floor(clamped(params.coarse, 1.0, 1.0, MAX_COARSE)).toInt()
        coarseCounter = 0
        held = 0f

        level = (clamped(params.gain, 1.0, 0.0, MAX_GAIN) *
            clamped(params.velocity, 1.0, 0.0, 1.0) *
            clamped(params.postgain, 1.0, 0.0, MAX_GAIN)).toFloat()
        val pan = clamped(params.pan, 0.5, 0.0, 1.0) * PI / 2
        panLeft = cos(pan).toFloat()
        panRight = sin(pan).toFloat()
        val delaySend = clamped(params.delay, 0.0, 0.0, 1.0).toFloat()
        delayLeft = panLeft * delaySend
        delayRight = panRight * delaySend
        val reverbSend = clamped(params.room, 0.0, 0.0, 1.0).toFloat()
        reverbLeft = panLeft * reverbSend
        reverbRight = panRight * reverbSend

        hasCut = params.cut.isSet()
        cutGroup = if (hasCut) floor(clamped(params.cut, 0.0, -MAX_CUT_GROUP, MAX_CUT_GROUP)).toInt() else 0
        this.order = order
        isActive = true
        return true
    }

    /** Fades this voice out over [ms] milliseconds. */
    fun fade(ms: Int) = envelope.fade(ms * sampleRate / 1000)

    /** Adds the next [n] frames of this voice (at most the buffer size) into the dry and send buses. */
    fun render(n: Int, buffers: VoiceBuffers) {
        val mono = buffers.mono
        val produced: Int
        if (isSynth) {
            oscillator.fill(mono, n)
            for (i in 0 until n) mono[i] *= sourceLevel
            produced = n
        } else {
            produced = player.fill(mono, n)
        }
        if (produced == 0) {
            isActive = false
            return
        }

        if (useLowPass) lowPass.process(mono, produced)
        if (useHighPass) highPass.process(mono, produced)
        if (useBandPass) bandPass.process(mono, produced)
        if (shapeAmount > 0f) shape(mono, produced)
        if (crushLevels > 0f) crush(mono, produced)
        if (coarseFactor > 1) holdSamples(mono, produced)
        for (i in 0 until produced) mono[i] *= envelope.next() * level

        buffers.dry.addMono(mono, produced, panLeft, panRight)
        if (delayLeft > 0f || delayRight > 0f) buffers.delaySend.addMono(mono, produced, delayLeft, delayRight)
        if (reverbLeft > 0f || reverbRight > 0f) buffers.reverbSend.addMono(mono, produced, reverbLeft, reverbRight)
        if (produced < n || envelope.isDone) isActive = false
    }

    private fun shape(buf: FloatArray, n: Int) {
        val k = shapeAmount
        for (i in 0 until n) {
            val x = buf[i]
            buf[i] = x * (1f + k) / (1f + k * abs(x))
        }
    }

    private fun crush(buf: FloatArray, n: Int) {
        val levels = crushLevels
        for (i in 0 until n) buf[i] = round(buf[i] * levels) / levels
    }

    private fun holdSamples(buf: FloatArray, n: Int) {
        for (i in 0 until n) {
            if (coarseCounter == 0) held = buf[i]
            buf[i] = held
            if (++coarseCounter >= coarseFactor) coarseCounter = 0
        }
    }
}
