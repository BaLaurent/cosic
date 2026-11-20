package cosic

import cosic.audio.DRUM_NAMES
import cosic.audio.DrumKit
import cosic.audio.Mixer
import cosic.audio.SYNTH_NAMES
import cosic.audio.VoiceParams
import cosic.pattern.ControlMap
import cosic.pattern.CosicError
import cosic.pattern.Pattern
import cosic.player.IdleTracker
import cosic.player.Scheduler
import cosic.player.SoundLookup
import cosic.player.SoundResolver
import cosic.player.dryRun
import cosic.player.errorMessage
import cosic.script.DEFAULT_CPS
import cosic.script.evaluateProgram
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.concurrent.thread
import kotlin.math.floor
import kotlin.math.max

/** [CosicEngine.play] starts cycle 0 this long after the frames rendered so far, so the first events are not late. */
internal const val PLAY_START_DELAY_SECONDS = 0.05

private const val STOP_FADE_MS = 20
private const val PREVIEW_SECONDS = 0.5

/** A preview waits this long for a sample that is still being decoded before it gives up. */
private const val PREVIEW_WAIT_SECONDS = 1
private const val SNAPSHOT_INTERVAL_FRAMES = 256

/** What a pattern event may be highlighted for after it has ended. */
private const val HIGHLIGHT_KEEP_SECONDS = 1

/** Work handed from other threads to the audio thread. */
private sealed interface Command {
    class SetProgram(val pattern: Pattern<ControlMap>, val cps: Double, val warnings: List<String>) : Command

    data object Play : Command

    data object Stop : Command

    class Preview(val sound: String, val index: Int, val note: Double?) : Command
}

/**
 * The sound engine: evaluates programs, schedules their events on a sample-accurate clock and mixes them into
 * stereo float PCM. It has no audio device of its own; whoever owns one calls [render] from its audio thread.
 *
 * Threading: [evaluate], [play], [stop] and [preview] may be called from any thread but the audio thread. They only
 * queue work, which [render] takes up first, so the audio thread owns all engine state and never waits on a lock.
 * What the engine reports back is [snapshot] and the return value of [evaluate].
 *
 * Events with no sound name (`note("c e g")` on its own) play a triangle. An event whose sound is unknown, or
 * whose sample is not decoded yet, is skipped; an unknown name is also reported as a warning.
 */
class CosicEngine(val sampleRate: Int, library: SoundLibrary) {
    internal val drums = DrumKit(sampleRate)
    private val sounds = SoundResolver(sampleRate, library, drums)
    private val mixer = Mixer(sampleRate)
    private val scheduler = Scheduler(sampleRate, sounds)
    private val idleTracker = IdleTracker(sampleRate)
    private val commands = ConcurrentLinkedQueue<Command>()
    private val startDelayFrames = Math.round(PLAY_START_DELAY_SECONDS * sampleRate)

    // Audio thread only from here on.
    private var renderedFrames = 0L
    private var startedVoice = false
    private var voicesWereSounding = false
    private var waitingPreview: Command.Preview? = null
    private var previewGivesUpAt = 0L
    private var publishedAt = 0L

    init {
        // Rendering a drum sound takes milliseconds, which the audio thread must not spend on one it meets first.
        thread(name = "cosic-drum-warm-up", isDaemon = true, priority = Thread.MIN_PRIORITY, block = drums::warmUp)
    }

    /** The engine's state as of a moment ago; republished at most every 256 rendered frames. */
    @Volatile
    var snapshot: EngineSnapshot =
        EngineSnapshot(sampleRate, false, true, DEFAULT_CPS, 0L, 0.0, 0L, 0, emptyList(), null, emptyList())
        private set

    /**
     * Parses and runs [code], and if that works, makes it the program from the next scheduling chunk on. The program
     * is also played through for the next two cycles without making a sound, so that errors in what it would play are
     * reported here, and the samples it needs start loading. A failure changes nothing.
     */
    @Synchronized
    fun evaluate(code: String): EvalReport = try {
        val program = evaluateProgram(code)
        val warnings = dryRun(program.pattern, currentCycle(), sounds)
        commands.add(Command.SetProgram(program.pattern, program.cps, warnings))
        EvalReport.Ok(program.cps, warnings)
    } catch (e: CosicError) {
        failed(code, e.message, e.range)
    } catch (e: Exception) {
        failed(code, "Error while playing: ${errorMessage(e)}", null)
    }

    /** Starts the program at cycle 0 if it is not playing yet. */
    fun play() {
        commands.add(Command.Play)
    }

    /** Stops the program: voices and effect tails fade out over 20 ms, and the delay and reverb are emptied after it. */
    fun stop() {
        commands.add(Command.Stop)
    }

    /**
     * Plays one sample or note of [sound] at the next block, for trying sounds out; [index] is the sample number. A
     * sample that is still being decoded is waited for, up to a second. A newer preview replaces one still waiting.
     */
    fun preview(sound: String, index: Int = 0, note: Double? = null) {
        sounds.resolve(sound, index.toDouble(), note) // loads or renders the sound here, off the audio thread
        commands.add(Command.Preview(sound, index, note))
    }

    /** Audio thread only. Renders [frames] frames of interleaved stereo into [out], which must hold `frames * 2` samples. */
    fun render(out: FloatArray, frames: Int) {
        require(frames >= 0 && out.size >= frames * 2) { "a buffer of ${out.size} samples cannot hold $frames stereo frames" }
        startedVoice = false
        var changed = drainCommands()
        startWaitingPreview()
        scheduler.schedule(renderedFrames + frames)
        renderSegments(out, frames)
        renderedFrames += frames
        scheduler.highlights.expire(renderedFrames - HIGHLIGHT_KEEP_SECONDS * sampleRate)

        val sounding = mixer.isSounding
        val wasIdle = idleTracker.idle
        val voices = startedVoice || voicesWereSounding || sounding || waitingPreview != null
        voicesWereSounding = sounding
        if (idleTracker.blockRendered(renderedFrames, scheduler.playing, voices)) mixer.clearBuses()
        changed = changed || idleTracker.idle != wasIdle
        if (changed || renderedFrames - publishedAt >= SNAPSHOT_INTERVAL_FRAMES) publish()
    }

    private fun drainCommands(): Boolean {
        var any = false
        while (true) {
            val command = commands.poll() ?: return any
            any = true
            when (command) {
                is Command.SetProgram -> scheduler.setProgram(command.pattern, command.cps, command.warnings)
                Command.Play -> if (!scheduler.playing) scheduler.play(renderedFrames + startDelayFrames)
                Command.Stop -> {
                    scheduler.stop()
                    mixer.stopAll(STOP_FADE_MS)
                    waitingPreview = null
                }
                is Command.Preview -> {
                    waitingPreview = command
                    previewGivesUpAt = renderedFrames + PREVIEW_WAIT_SECONDS * sampleRate
                }
            }
        }
    }

    /** Starts the waiting preview as soon as its sound is ready, giving a sample that is still being decoded its time. */
    private fun startWaitingPreview() {
        val preview = waitingPreview ?: return
        when (val lookup = sounds.resolve(preview.sound, preview.index.toDouble(), preview.note)) {
            is SoundLookup.Ready -> {
                waitingPreview = null
                start(VoiceParams(lookup.source, PREVIEW_SECONDS, note = preview.note))
            }
            SoundLookup.Loading -> if (renderedFrames >= previewGivesUpAt) waitingPreview = null
            is SoundLookup.Unknown -> waitingPreview = null
        }
    }

    /** Renders the block in pieces, starting every due event exactly on its frame. */
    private fun renderSegments(out: FloatArray, frames: Int) {
        var position = 0
        while (position < frames) {
            val frame = renderedFrames + position
            while (true) {
                val event = scheduler.takeDue(frame) ?: break
                start(event.voice)
            }
            val next = minOf(frames.toLong(), scheduler.nextEventFrame - renderedFrames).toInt()
            mixer.render(out, next - position, position)
            position = next
        }
    }

    private fun start(voice: VoiceParams) {
        if (mixer.startVoice(voice)) startedVoice = true
    }

    private fun publish() {
        publishedAt = renderedFrames
        snapshot = EngineSnapshot(
            sampleRate, scheduler.playing, idleTracker.idle, scheduler.cps,
            scheduler.anchor.frame, scheduler.anchor.cycle.toDouble(), renderedFrames, mixer.activeVoices,
            scheduler.highlights.snapshot(), scheduler.runtimeError, scheduler.warnings,
        )
    }

    /** The cycle being heard, or 0 when stopped. */
    private fun currentCycle(): Long {
        val now = snapshot
        return if (now.playing) max(0L, floor(now.cycleAt(now.renderedFrames)).toLong()) else 0L
    }

    private fun failed(code: String, message: String, range: SourceRange?): EvalReport.Failed {
        if (range == null) return EvalReport.Failed(message, null, 0, 0)
        val start = range.start.coerceIn(0, code.length)
        val line = 1 + code.take(start).count { it == '\n' }
        val column = start - code.lastIndexOf('\n', start - 1)
        return EvalReport.Failed(message, range, line, column)
    }

    companion object {
        val SYNTHS: List<String> = SYNTH_NAMES
        val DRUMS: List<String> = DRUM_NAMES

        /**
         * The synthesised drum machines in display order, each with its sound names in display order:
         * `s("bd").bank("cosic808")` plays `cosic808_bd`. Every one has [DRUM_VARIANTS] variants, like the [DRUMS].
         */
        val DRUM_MACHINES: Map<String, List<String>> = cosic.audio.DRUM_MACHINES

        /** How many variants each of [DRUMS] has: `bd:0` to `bd:3`. */
        const val DRUM_VARIANTS: Int = cosic.audio.DRUM_VARIANTS
    }
}
