package cosic.player

private const val GRACE_SECONDS = 2.0

/** The delay bus holds this much audio, so an echo can be on its way round for this long without being heard. */
private const val LONGEST_ECHO_SECONDS = 4.0
private const val ECHO_MARGIN_SECONDS = 0.1

/** Output below this peak (-80 dB) counts as silence. */
private const val AUDIBLE_PEAK = 1e-4f

/**
 * Decides when the engine has gone quiet and the audio thread may stop rendering. The engine is idle when nothing
 * plays, no voice has been heard for two seconds, and, if any voice ever fed the delay or reverb, the output has also
 * stayed silent for longer than the longest echo, so the buses are certainly empty and clearing them cuts no audible
 * tail. A delay of 4 s with feedback keeps the engine awake as long as its echoes are audible: that is what the
 * user asked for. Audio thread only.
 */
internal class IdleTracker(sampleRate: Int) {
    private val graceFrames = Math.round(GRACE_SECONDS * sampleRate)
    private val echoFrames = Math.round((LONGEST_ECHO_SECONDS + ECHO_MARGIN_SECONDS) * sampleRate)
    private var lastBusy = Long.MIN_VALUE / 2
    private var lastAudible = Long.MIN_VALUE / 2
    private var effectsUsed = false

    var idle = true
        private set

    /** A voice that feeds the delay or reverb has started. */
    fun effectsUsed() {
        effectsUsed = true
    }

    /**
     * Records a rendered block that ended at frame [end]. [busy] says whether anything was or could be sounding
     * during it, [peak] is the largest absolute output sample. Returns true on the block that makes the engine idle,
     * which is when the caller should clear the effect buses.
     */
    fun blockRendered(end: Long, busy: Boolean, peak: Float): Boolean {
        if (busy) lastBusy = end
        if (peak >= AUDIBLE_PEAK) lastAudible = end
        val wasIdle = idle
        idle = end - lastBusy >= graceFrames && (!effectsUsed || end - lastAudible >= echoFrames)
        if (!idle || wasIdle) return false
        effectsUsed = false
        return true
    }
}
