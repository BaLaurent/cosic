package com.octopusman.cosic.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import cosic.CosicEngine

private const val TAG = "CosicAudio"
private const val DEFAULT_SAMPLE_RATE = 48_000
private const val BLOCK_FRAMES = 256
private const val BYTES_PER_FRAME = 2 * Float.SIZE_BYTES
private const val KEEP_ALIVE_MS = 1_500L
private const val IDLE_BEFORE_EXIT_MS = 1_000L
private const val LOG_INTERVAL_MS = 5_000L
private const val NANOS_PER_SECOND = 1_000_000_000L

private val ATTRIBUTES = AudioAttributes.Builder()
    .setUsage(AudioAttributes.USAGE_MEDIA)
    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
    .build()

/**
 * Feeds [engine] to an [AudioTrack] from a dedicated thread. [ensureRunning] starts that thread on
 * demand; it ends on its own once the engine has been idle for a while.
 */
class AudioOutput(private val engine: CosicEngine, context: Context) {
    /** The live track and the engine frame that was rendered first into it. */
    private class Session(val track: AudioTrack, val engineFrameAtStart: Long)

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    // The audio thread's registration and its session, guarded by [lock]. The thread deregisters
    // itself under the same lock as it decides to end, so ensureRunning() either keeps it alive or
    // starts a replacement.
    private val lock = Any()
    private var thread: Thread? = null
    private var session: Session? = null
    private val timestamp = AudioTimestamp()

    @Volatile
    private var keepAliveUntil = 0L

    /** Makes sure audio is being rendered, and keeps it that way for at least the next moments. */
    fun ensureRunning() {
        synchronized(lock) {
            keepAliveUntil = SystemClock.elapsedRealtime() + KEEP_ALIVE_MS
            if (thread == null) thread = Thread(::audioLoop, "cosic-audio").also { it.start() }
        }
    }

    /** The engine frame being heard right now, or null while no timestamp is available. */
    fun playheadFrame(): Long? = synchronized(lock) {
        val current = session ?: return null
        if (!current.track.getTimestamp(timestamp)) return null
        current.engineFrameAtStart + timestamp.framePosition +
            (System.nanoTime() - timestamp.nanoTime) * engine.sampleRate / NANOS_PER_SECOND
    }

    private fun audioLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val self = Thread.currentThread()
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener { change ->
                // A thread that already ended must not stop an engine that a newer one is playing.
                val lost = change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                if (lost && synchronized(lock) { thread === self }) engine.stop()
            }
            .build()
        var track: AudioTrack? = null
        try {
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "audio focus denied")
                engine.stop()
                return
            }
            track = openTrack() ?: return
            pump(track)
        } finally {
            leave(force = true)
            track?.release()
            audioManager.abandonAudioFocusRequest(focus)
            Log.i(TAG, "stop")
        }
    }

    private fun openTrack(): AudioTrack? {
        val sampleRate = engine.sampleRate
        val format = AudioFormat.Builder()
            .setSampleRate(sampleRate)
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()
        val minBytes = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT)
        val bufferBytes = maxOf(2 * minBytes, 1024 * BYTES_PER_FRAME)

        fun build(lowLatency: Boolean): AudioTrack = AudioTrack.Builder()
            .setAudioAttributes(ATTRIBUTES)
            .setAudioFormat(format)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .apply { if (lowLatency) setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY) }
            .build()

        return try {
            build(lowLatency = true)
        } catch (_: RuntimeException) {
            try {
                build(lowLatency = false)
            } catch (e: RuntimeException) {
                Log.e(TAG, "cannot create an AudioTrack", e)
                null
            }
        }
    }

    /** Renders and writes blocks until this thread has been idle long enough to end. */
    private fun pump(track: AudioTrack) {
        val lowLatency = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        Log.i(TAG, "start sr=${engine.sampleRate} bufferFrames=${track.bufferSizeInFrames} lowLatency=$lowLatency")
        synchronized(lock) { session = Session(track, engine.snapshot.renderedFrames) }
        track.play()

        val buffer = FloatArray(BLOCK_FRAMES * 2)
        var idleSince = 0L
        var lastLog = SystemClock.elapsedRealtime()
        while (true) {
            val snapshot = engine.snapshot
            val now = SystemClock.elapsedRealtime()
            if (!snapshot.idle) idleSince = 0L else if (idleSince == 0L) idleSince = now
            if (idleSince != 0L && now - idleSince >= IDLE_BEFORE_EXIT_MS && leave(force = false)) return
            if (now - lastLog >= LOG_INTERVAL_MS) {
                lastLog = now
                Log.i(TAG, "frames=${snapshot.renderedFrames} underruns=${track.underrunCount} voices=${snapshot.activeVoices}")
            }

            engine.render(buffer, BLOCK_FRAMES)
            val written = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
            if (written < 0) {
                Log.e(TAG, "write failed: $written")
                return
            }
        }
    }

    /**
     * Ends the calling thread's registration. Unless [force]d, it declines (false) when
     * [ensureRunning] asked for more time. Calling it again after a successful leave is harmless.
     */
    private fun leave(force: Boolean): Boolean = synchronized(lock) {
        if (thread !== Thread.currentThread()) return true
        if (!force && SystemClock.elapsedRealtime() <= keepAliveUntil) return false
        thread = null
        session = null
        true
    }

    companion object {
        /** The device's native output rate, which the engine should be created with. */
        fun nativeSampleRate(context: Context): Int =
            context.getSystemService(AudioManager::class.java)
                .getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: DEFAULT_SAMPLE_RATE
    }
}
