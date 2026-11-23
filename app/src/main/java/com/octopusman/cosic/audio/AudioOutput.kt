package com.octopusman.cosic.audio

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTimestamp
import android.media.AudioTrack
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import cosic.CosicEngine

private const val TAG = "CosicAudio"
private const val DEFAULT_SAMPLE_RATE = 48_000
private const val BLOCK_FRAMES = 256
private const val BYTES_PER_FRAME = 2 * Float.SIZE_BYTES
private const val KEEP_ALIVE_MS = 1_500L
private const val IDLE_BEFORE_EXIT_MS = 1_000L
private const val LOG_INTERVAL_MS = 5_000L
private const val CLOCK_OBSERVE_INTERVAL_MS = 200L
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

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)

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
        val noisy = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // A thread that already ended must not stop an engine that a newer one is playing.
                if (synchronized(lock) { thread === self }) engine.stop()
            }
        }
        var noisyRegistered = false
        var track: AudioTrack? = null
        try {
            if (audioManager.requestAudioFocus(focus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.w(TAG, "audio focus denied")
                engine.stop()
                return
            }
            // Unplugging headphones (or dropping a Bluetooth sink) must not move the music to the speaker: stop, as when the focus is lost.
            ContextCompat.registerReceiver(appContext, noisy, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY), ContextCompat.RECEIVER_NOT_EXPORTED)
            noisyRegistered = true
            track = openTrack() ?: return
            pump(track)
        } finally {
            if (noisyRegistered) appContext.unregisterReceiver(noisy)
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

    /** Renders and writes blocks until this thread has been idle long enough to end, or rendering or writing fails. */
    private fun pump(track: AudioTrack) {
        val lowLatency = track.performanceMode == AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
        Log.i(TAG, "start sr=${engine.sampleRate} bufferFrames=${track.bufferSizeInFrames} lowLatency=$lowLatency")
        val engineFrameAtStart = engine.snapshot.renderedFrames
        synchronized(lock) { session = Session(track, engineFrameAtStart) }
        // MIDI, OSC, MQTT and serial are sent at the system time of the frame that is heard: until the track reports its first time stamp the clock
        // works from the moment it was started, then it follows the stamps (the engine's ClockBridge smooths them)
        engine.clock.beginSession(System.nanoTime(), engineFrameAtStart, engine.sampleRate)
        track.play()

        val buffer = FloatArray(BLOCK_FRAMES * 2)
        // the audio thread's own; playheadFrame() has another for the UI thread
        val clockStamp = AudioTimestamp()
        var idleSince = 0L
        var lastLog = SystemClock.elapsedRealtime()
        var lastClockStamp = 0L
        while (true) {
            val snapshot = engine.snapshot
            val now = SystemClock.elapsedRealtime()
            if (!snapshot.idle) idleSince = 0L else if (idleSince == 0L) idleSince = now
            if (idleSince != 0L && now - idleSince >= IDLE_BEFORE_EXIT_MS && leave(force = false)) return
            if (now - lastLog >= LOG_INTERVAL_MS) {
                lastLog = now
                Log.i(TAG, "frames=${snapshot.renderedFrames} underruns=${track.underrunCount} voices=${snapshot.activeVoices}")
            }
            if (now - lastClockStamp >= CLOCK_OBSERVE_INTERVAL_MS) {
                lastClockStamp = now
                if (track.getTimestamp(clockStamp)) {
                    engine.clock.observe(clockStamp.framePosition, clockStamp.nanoTime, engine.sampleRate, engineFrameAtStart)
                }
            }

            try {
                engine.render(buffer, BLOCK_FRAMES)
                val written = track.write(buffer, 0, buffer.size, AudioTrack.WRITE_BLOCKING)
                if (written >= 0) continue
                Log.e(TAG, "write failed: $written")
            } catch (e: Throwable) {
                // An engine bug or an out-of-memory error here would otherwise take the whole app down.
                Log.e(TAG, "audio failed", e)
            }
            // Let the UI see a stopped engine instead of staying in "playing" with no sound.
            engine.stop()
            return
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
