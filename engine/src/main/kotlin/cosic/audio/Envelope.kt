package cosic.audio

private const val ATTACK = 0
private const val DECAY = 1
private const val SUSTAIN = 2
private const val RELEASE = 3
private const val DONE = 4

/** Gate lengths past this many frames are treated as unlimited. */
private const val UNLIMITED_FRAMES = 4.0e18

/**
 * Linear ADSR with a gate. Level rises 0 -> 1 over the attack, falls to the sustain level over the decay, holds
 * until the gate closes, then falls linearly to 0 over the release, starting from wherever the level is (so a gate
 * shorter than the attack still releases smoothly). [fade] forces a fast release for voice stealing and stopping.
 * One value per frame via [next]; never allocates.
 */
internal class Envelope(private val sampleRate: Int) {
    private var phase = DONE
    private var level = 0.0
    private var sustain = 1.0
    private var attackStep = 0.0
    private var decayStep = 0.0
    private var releaseFrames = 0.0
    private var releaseStep = 0.0
    private var gateFrames = Long.MAX_VALUE
    private var elapsed = 0L

    /** True once a [fade] has been requested; a fading voice is on its way out and no longer counts as active. */
    var isFading = false
        private set

    /** True when the release has finished (or nothing was started). */
    val isDone: Boolean get() = phase == DONE

    /** Times are in seconds; a zero time makes that stage instantaneous. [gateSeconds] may be infinite. */
    fun start(attack: Double, decay: Double, sustain: Double, release: Double, gateSeconds: Double) {
        this.sustain = sustain
        attackStep = stepFor(1.0, attack)
        decayStep = stepFor(1.0 - sustain, decay)
        releaseFrames = release * sampleRate
        val frames = gateSeconds * sampleRate
        gateFrames = when {
            frames >= UNLIMITED_FRAMES -> Long.MAX_VALUE
            frames > 0.0 -> Math.round(frames)
            else -> 0L
        }
        elapsed = 0L
        isFading = false
        level = 0.0
        phase = ATTACK
        if (attackStep.isInfinite()) {
            level = 1.0
            phase = DECAY
            if (decayStep.isInfinite()) {
                level = sustain
                phase = SUSTAIN
            }
        }
    }

    /** Forces the level to zero within [frames] frames from now; never lengthens a release already in progress. */
    fun fade(frames: Int) {
        if (phase == DONE) return
        isFading = true
        val step = if (frames >= 1) level / frames else Double.POSITIVE_INFINITY
        if (phase == RELEASE) {
            if (step > releaseStep) releaseStep = step
        } else {
            phase = RELEASE
            releaseStep = step
        }
    }

    /** The level for the next frame, 0 once the envelope is done. */
    fun next(): Float {
        if (phase == DONE) return 0f
        if (phase != RELEASE && elapsed >= gateFrames) beginRelease()
        elapsed++
        when (phase) {
            ATTACK -> {
                level += attackStep
                if (level >= 1.0) {
                    level = 1.0
                    phase = DECAY
                }
            }
            DECAY -> {
                level -= decayStep
                if (level <= sustain) {
                    level = sustain
                    phase = SUSTAIN
                }
            }
            RELEASE -> {
                level -= releaseStep
                if (level <= 0.0) {
                    level = 0.0
                    phase = DONE
                }
            }
        }
        return level.toFloat()
    }

    private fun beginRelease() {
        phase = RELEASE
        releaseStep = if (releaseFrames >= 1.0) level / releaseFrames else Double.POSITIVE_INFINITY
    }

    /** Level change per frame to cover [delta] in [seconds]; infinite when the stage is shorter than one frame. */
    private fun stepFor(delta: Double, seconds: Double): Double {
        val frames = seconds * sampleRate
        return if (frames >= 1.0) delta / frames else Double.POSITIVE_INFINITY
    }
}
