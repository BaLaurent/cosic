package cosic.player

private const val GRACE_SECONDS = 2.0

/**
 * Decides when the engine has gone quiet and the audio thread may stop rendering: it is not playing, no voice is
 * sounding, and at least two seconds have passed since the last voice ended. Audio thread only.
 */
internal class IdleTracker(sampleRate: Int) {
    private val graceFrames = Math.round(GRACE_SECONDS * sampleRate)
    private var lastVoice = Long.MIN_VALUE / 2

    var idle = true
        private set

    /**
     * Records a rendered block that ended at frame [end]. [playing] says whether the program is playing, [voices]
     * whether any voice was, or was about to be, sounding during the block. Returns true on the block that makes the
     * engine idle, which is when the caller should empty the effect buses.
     */
    fun blockRendered(end: Long, playing: Boolean, voices: Boolean): Boolean {
        if (voices) lastVoice = end
        val wasIdle = idle
        idle = !playing && end - lastVoice >= graceFrames
        return idle && !wasIdle
    }
}
