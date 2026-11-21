package cosic

import cosic.audio.AudioSettings
import cosic.audio.BackgroundIrExecutor
import cosic.audio.DRUM_NAMES
import cosic.audio.ChainDemand
import cosic.audio.DrumKit
import cosic.audio.IrExecutor
import cosic.audio.Mixer
import cosic.audio.PoolPreparer
import cosic.audio.VoiceParams
import cosic.audio.sources.SynthRegistry
import cosic.io.ClockBridge
import cosic.io.ConsoleLog
import cosic.io.InputState
import cosic.io.Metadata
import cosic.io.OutputBus
import cosic.io.Prebake
import cosic.io.ScopeTaps
import cosic.pattern.CosicError
import cosic.pattern.Hap
import cosic.pattern.Pattern
import cosic.pattern.Rational
import cosic.dough.DoughOutput
import cosic.player.IdleTracker
import cosic.player.Scheduler
import cosic.player.SoundLookup
import cosic.player.SoundResolver
import cosic.player.dryRun
import cosic.player.lookAhead
import cosic.player.errorMessage
import cosic.player.voiceParams
import cosic.script.DEFAULT_CPS
import cosic.script.ReplSession
import cosic.script.SessionHost
import cosic.script.onBigStack
import cosic.visual.VisualSession
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

/** Evaluating a program waits at most this long for the library to finish installing the default sounds. */
private const val LIBRARY_READY_MILLIS = 15_000L
private const val SNAPSHOT_INTERVAL_FRAMES = 256

/** What a pattern event may be highlighted for after it has ended. */
private const val HIGHLIGHT_KEEP_SECONDS = 1

/** Work handed from other threads to the audio thread. */
private sealed interface Command {
    class SetProgram(val pattern: Pattern, val cps: Double, val warnings: List<String>) : Command

    /** A program set the tempo while it ran. */
    class SetCps(val cps: Double) : Command

    data object Play : Command

    /** Starts at [cycle] with no start delay: the first frame rendered next is the first frame of that cycle. */
    class PlayFrom(val cycle: Rational) : Command

    data object Stop : Command

    /** Stops querying the program but leaves the voices and the effect tails to ring out (the end of an offline render). */
    data object Halt : Command

    class Preview(val sound: String, val index: Int, val note: Double?) : Command

    /** A program changed the polyphony or the multi-channel orbits (`setMaxPolyphony`, `setMultiChannelOrbits`): the mixer takes them from the settings. */
    data object ApplyAudioSettings : Command

    /** A live input just made events the program turns into sounds (`midikeys`); [done] is told whether any were played. */
    class Trigger(val latencySeconds: Double, val match: (Hap) -> Boolean, val done: ((Boolean) -> Unit)?) : Command
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
class CosicEngine internal constructor(val sampleRate: Int, private val library: SoundLibrary, irExecutor: IrExecutor) {
    /** An engine that builds the impulse responses of its reverbs on a background thread (offline rendering builds them on the spot instead). */
    constructor(sampleRate: Int, library: SoundLibrary) : this(sampleRate, library, BackgroundIrExecutor.shared)

    /**
     * What programs change about the sound engine itself (`setMaxPolyphony`, `setMultiChannelOrbits`, `setGainCurve`, the default values): the functions
     * of the program write it ([EvalEffects.audio]), the sound resolver and the voices read it, and the mixer takes the polyphony and the channels from it
     * on the audio thread. It lives from one evaluation to the next, as superdough's module variables do, and [resetSession] clears it.
     */
    internal val audioSettings = AudioSettings().also { it.onChange = { commands.add(Command.ApplyAudioSettings) } }

    internal val drums = DrumKit(sampleRate)
    private val sounds = SoundResolver(sampleRate, library, drums, audioSettings)

    /** The sound of the voices with an `analyze` id, for `scope`, `fscope` and `spectrum`: the voices add to it, the visuals read it (`VisualSession`). */
    val scopes = ScopeTaps()
    private val mixer = Mixer(sampleRate, irExecutor, scopes)

    /** Grows the pools of the mixer ahead of the queued events on a background thread; offline renders (inline executor) do it on the spot. */
    private val preparer = PoolPreparer(mixer, background = irExecutor !== IrExecutor.INLINE)

    /** What the program, the engine and the patterns being played write to the console: the last 40 lines. */
    val console = ConsoleLog()

    /** Where the haps with hooks (`.midi()`, `.osc()`, `.log()`...) go, and the devices those hooks send to; the app plugs its devices in here. */
    val outputs = OutputBus(console)

    /** What the person playing does to the device: pointer, keys, motion sensors, sliders. The app sets it; patterns read it. */
    val inputs = InputState()

    /** Turns the engine's frames into the system's time (for MIDI and OSC): the app feeds it the time stamps of its audio track. */
    val clock = ClockBridge().also { outputs.clock = it }

    init {
        // a note played on a MIDI keyboard (`midikeys`) is made to sound at once, not at the next chunk the scheduler queries
        outputs.midiHub.attach(trigger = ::trigger, isPlaying = { snapshot.playing })
    }

    /** Dough, the second audio engine (Task 4.12): the haps of a pattern that `.dough()` marked play with it, and it adds its sound to what the mixer made. */
    internal val dough = DoughOutput(sampleRate, library.sampleStore).also { outputs.dough = it }

    /** Where programs fetch remote JSON from (`midimaps('github:...')`); the app implements it. */
    @Volatile
    var remote: RemoteJson? = null

    /**
     * Reads the metadata comments of a program (`// @title ...`) for [ProgramInfo.metadata]: [Metadata.parse] unless the host sets another
     * reader; none is read while it is null.
     */
    @Volatile
    var metadataParser: ((String) -> Map<String, Any>)? = Metadata::parse

    /**
     * The prebake script of the REPL's settings: code that runs once in every session, before its first program, to define what all programs
     * use (the default one defines `ratchet`). Setting it makes the next evaluation run it again; null runs nothing. A script that fails is
     * reported to the console (`[prebake] error: ...`) and the program is evaluated anyway.
     */
    @Volatile
    var prebake: String? = Prebake.DEFAULT
        set(value) {
            field = value
            prebaked = false
        }

    /** Whether the prebake script has run in the current session. */
    @Volatile
    private var prebaked = false

    /** What the last program that [evaluate] accepted is: its sliders, widgets, visuals, metadata and the inputs it uses. */
    @Volatile
    var program: ProgramInfo? = null
        private set

    /** The pattern and tempo of the last program that [evaluate] accepted, for [soundsLoading]. */
    private var lastPattern: Pair<Pattern, Double>? = null

    private val effects = EngineEffects(inputs, console, outputs, audioSettings) { remote }
    private val scheduler = Scheduler(sampleRate, sounds, outputs, scopes)
    private val chainDemand = ChainDemand()

    private val idleTracker = IdleTracker(sampleRate)
    private val commands = ConcurrentLinkedQueue<Command>()
    private val startDelayFrames = Math.round(PLAY_START_DELAY_SECONDS * sampleRate)

    /** What callers of [play] and [stop] asked for last, so that `[cyclist] start` and `[cyclist] stop` are logged once per change, as the cyclist does. */
    @Volatile
    private var playRequested = false

    /** What a program can reach outside of the language; a `setcps` reaches the scheduler at once, even when the program fails later. */
    private val host = SessionHost(library, { cps -> commands.add(Command.SetCps(cps)) }, effects)

    /** The language session: the tempo and the patterns labelled by earlier programs live from one evaluation to the next. */
    private var session = ReplSession(host)

    // Audio thread only from here on.
    private var renderedFrames = 0L
    private var startedVoice = false
    private var voicesWereSounding = false
    private var waitingPreview: Command.Preview? = null
    private var previewGivesUpAt = 0L
    private var publishedAt = 0L
    private var chainsReported = 0

    init {
        // Rendering a drum sound takes milliseconds, and so does building the tables of a wave or the loop of a noise, which the audio thread must not spend on one it meets first.
        thread(name = "cosic-sound-warm-up", isDaemon = true, priority = Thread.MIN_PRIORITY) {
            drums.warmUp()
            SynthRegistry.warmUp(sampleRate)
        }
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
        library.awaitReady(LIBRARY_READY_MILLIS) // the sounds a program names must be registered when its first events are looked up
        onBigStack {
            runPrebake()
            effects.beginEvaluation()
            val program = session.evaluate(code)
            val warnings = host.quietly { dryRun(program.pattern, currentCycle(), sounds, program.cps, outputs) }
            commands.add(Command.SetProgram(program.pattern, program.cps, warnings))
            // the visuals follow the program that plays, and read the sound of their analysers from the engine
            (effects.visuals as? VisualSession)?.attach(program.pattern, scopes) { audioSettings.current.number("fft") }
            lastPattern = program.pattern to program.cps
            val info = ProgramInfo(
                program.miniLocations, program.sliders, program.widgets, effects.visuals, metadataOf(code), effects.inputsUsed(),
            )
            this.program = info
            // the web REPL ends every successful evaluation with `setVersionDefaultsFrom(code)` (useReplContext.jsx): the live default values go back to the base ones
            audioSettings.setVersionDefaults(info.metadata["version"])
            console.log("[eval] code updated")
            EvalReport.Ok(program.cps, warnings, info)
        }
    } catch (e: CosicError) {
        failed(code, e.message, e.range)
    } catch (e: Exception) {
        failed(code, "Error while playing: ${errorMessage(e)}", null)
    }

    /**
     * Forgets what the programs so far set, the tempo and what they labelled, as when another project is opened: the next
     * program starts in a new session at the default tempo.
     */
    @Synchronized
    fun resetSession() {
        session = ReplSession(host)
        sounds.kabel.reset()
        audioSettings.reset()
        prebaked = false
        host.cps = DEFAULT_CPS
    }

    /** Starts the program at cycle 0 if it is not playing yet. */
    fun play() {
        if (!playRequested) console.log("[cyclist] start")
        playRequested = true
        commands.add(Command.Play)
    }

    /**
     * Starts the program at [cycle] (a position in the pattern, not a time), with no start delay: the first frame rendered next is the
     * first frame of that cycle, and the events that began before it are not played. A program that is playing starts over there. This is
     * how a stretch of the program is rendered offline.
     */
    fun playFrom(cycle: Double) {
        require(cycle >= 0.0 && cycle.isFinite()) { "cycle must be zero or a positive number" }
        if (!playRequested) console.log("[cyclist] start")
        playRequested = true
        commands.add(Command.PlayFrom(Rational.of(cycle)))
    }

    /**
     * How many of the events the last accepted program plays from cycle [beginCycle] to [endCycle] have a sound that is still being decoded; looking asks the library
     * for those, so calling this again after a moment finds fewer. The offline export waits for 0 before it renders. Not while the engine is rendering.
     */
    @Synchronized
    internal fun soundsLoading(beginCycle: Double, endCycle: Double): Int {
        val (pattern, cps) = lastPattern ?: return 0
        return onBigStack {
            host.quietly { lookAhead(pattern, Rational.of(beginCycle), Rational.of(endCycle), sounds, cps, outputs).loading }
        }
    }

    /** Stops scheduling events but lets the voices and the delay and reverb tails sound out, which [stop] would fade away. */
    internal fun halt() {
        commands.add(Command.Halt)
    }

    /** Stops the program: voices and effect tails fade out over 20 ms, and the delay and reverb are emptied after it. */
    fun stop() {
        if (playRequested) console.log("[cyclist] stop")
        playRequested = false
        commands.add(Command.Stop)
    }

    /**
     * Makes the program play what a live input has just made (`_triggerKeyboard` of midi.mjs): [match] picks the haps of the pattern at the
     * instant [latencySeconds] from now that the input made (the keyboard's notes), and those are routed like scheduled ones, sounding as soon as the
     * next block. [done] is told on the audio thread whether any hap was played. Does nothing while the program is not playing.
     */
    internal fun trigger(latencySeconds: Double, match: (Hap) -> Boolean, done: ((Boolean) -> Unit)? = null) {
        commands.add(Command.Trigger(latencySeconds, match, done))
    }

    /**
     * Plays one sample or note of [sound] at the next block, for trying sounds out; [index] is the sample number. A
     * sample that is still being decoded is waited for, up to a second. A newer preview replaces one still waiting.
     */
    fun preview(sound: String, index: Int = 0, note: Double? = null) {
        sounds.resolve(sound, index.toDouble(), note, PREVIEW_SECONDS, DEFAULT_CPS) // loads or renders the sound here, off the audio thread
        commands.add(Command.Preview(sound, index, note))
    }

    /** Audio thread only. Renders [frames] frames of interleaved stereo into [out], which must hold `frames * 2` samples. */
    fun render(out: FloatArray, frames: Int) {
        require(frames >= 0 && out.size >= frames * 2) { "a buffer of ${out.size} samples cannot hold $frames stereo frames" }
        startedVoice = false
        var changed = drainCommands()
        startWaitingPreview()
        scheduler.schedule(renderedFrames + frames)
        // the chains and vocoders the queued events will need are made ahead of them, by the preparer's thread, not by this one when they start
        chainDemand.clear()
        scheduler.addQueuedDemand(chainDemand)
        preparer.request(chainDemand)
        renderSegments(out, frames)
        scopes.endBlock(frames)
        reportChainsMadeHere()
        renderedFrames += frames
        scheduler.highlights.expire(renderedFrames - HIGHLIGHT_KEEP_SECONDS * sampleRate)

        val sounding = mixer.isSounding || dough.isSounding
        val wasIdle = idleTracker.idle
        val voices = startedVoice || voicesWereSounding || sounding || waitingPreview != null
        voicesWereSounding = sounding
        if (idleTracker.blockRendered(renderedFrames, scheduler.playing, voices)) mixer.clearBuses()
        changed = changed || idleTracker.idle != wasIdle
        if (changed || renderedFrames - publishedAt >= SNAPSHOT_INTERVAL_FRAMES) publish()
    }

    /** The pools were too late for a voice, which then got its chain from the audio thread (a glitch risk, not a lost effect): said once per occasion. */
    private fun reportChainsMadeHere() {
        val made = mixer.chainsMadeOnTheSpot
        if (made == chainsReported) return
        console.log("[audio] ${made - chainsReported} effect chain(s) had to be made while rendering: the pool was not ready in time", "warning")
        chainsReported = made
    }

    private fun drainCommands(): Boolean {
        var any = false
        while (true) {
            val command = commands.poll() ?: return any
            any = true
            when (command) {
                is Command.SetProgram -> scheduler.setProgram(command.pattern, command.cps, command.warnings)
                is Command.SetCps -> scheduler.setCps(command.cps)
                Command.ApplyAudioSettings -> applyAudioSettings()
                Command.Play -> if (!scheduler.playing) scheduler.play(renderedFrames + startDelayFrames)
                is Command.PlayFrom -> {
                    // a program that is playing starts over: what was queued for the old timeline goes, voices that sound keep sounding
                    scheduler.stop()
                    scheduler.play(renderedFrames, command.cycle)
                }
                Command.Stop -> {
                    scheduler.stop()
                    mixer.stopAll(STOP_FADE_MS)
                    dough.stop(STOP_FADE_MS * sampleRate / 1000)
                    waitingPreview = null
                }
                Command.Halt -> scheduler.stop()
                is Command.Preview -> {
                    waitingPreview = command
                    previewGivesUpAt = renderedFrames + PREVIEW_WAIT_SECONDS * sampleRate
                }
                is Command.Trigger -> {
                    val latencyFrames = Math.round(command.latencySeconds * sampleRate)
                    val played = scheduler.trigger(
                        renderedFrames, latencyFrames, scheduler.anchor.cycleAt(renderedFrames), scheduler.cps, command.match,
                    )
                    command.done?.invoke(played)
                }
            }
        }
    }

    /** Starts the waiting preview as soon as its sound is ready, giving a sample that is still being decoded its time. */
    private fun startWaitingPreview() {
        val preview = waitingPreview ?: return
        when (val lookup = sounds.resolve(preview.sound, preview.index.toDouble(), preview.note, PREVIEW_SECONDS, scheduler.cps)) {
            is SoundLookup.Ready -> {
                waitingPreview = null
                start(voiceParams(preview.note?.let { mapOf("note" to it) } ?: emptyMap(), lookup.source, PREVIEW_SECONDS, scheduler.cps, 0.0, settings = audioSettings))
            }
            SoundLookup.Loading -> if (renderedFrames >= previewGivesUpAt) waitingPreview = null
            is SoundLookup.Unknown -> waitingPreview = null
            is SoundLookup.Failed -> waitingPreview = null
            SoundLookup.Silent -> waitingPreview = null
        }
    }

    /** Hands the polyphony and the channel mode the settings now have to the mixer. Audio thread only. */
    private fun applyAudioSettings() {
        val settings = audioSettings.current
        mixer.maxPolyphony = settings.voiceLimit
        mixer.multiChannelOrbits = settings.multiChannelOrbits
    }

    /** The polyphony the mixer works with now, as the last block took it from [audioSettings]. Read it between renders; for tests. */
    internal val mixerPolyphony: Int get() = mixer.maxPolyphony

    /** Whether the mixer gives orbits channels of their own now, as the last block took it from [audioSettings]. Read it between renders; for tests. */
    internal val mixerMultiChannelOrbits: Boolean get() = mixer.multiChannelOrbits

    /** Renders the block in pieces, starting every due event exactly on its frame. */
    private fun renderSegments(out: FloatArray, frames: Int) {
        var position = 0
        while (position < frames) {
            val frame = renderedFrames + position
            while (true) {
                val event = scheduler.takeDue(frame) ?: break
                start(event.voice)
            }
            while (true) {
                val event = scheduler.takeDueDough(frame) ?: break
                dough.start(event.event)
            }
            val next = minOf(frames.toLong(), scheduler.nextEventFrame - renderedFrames).toInt()
            mixer.render(out, next - position, position)
            dough.render(out, next - position, position)
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

    /** The metadata of [code] when there is a [metadataParser] and it can read it. */
    private fun metadataOf(code: String): Map<String, Any> =
        try {
            metadataParser?.invoke(code) ?: emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

    /** Runs the prebake script when the session has not yet (see [prebake]); what it labels or sets stays out of the program that follows. */
    private fun runPrebake() {
        if (prebaked) return
        prebaked = true
        val script = prebake ?: return
        try {
            session.evaluate(script, shouldHush = false)
        } catch (e: CosicError) {
            console.error("prebake", e.message)
        } catch (e: Exception) {
            console.error("prebake", errorMessage(e))
        }
    }

    private fun failed(code: String, message: String, range: SourceRange?): EvalReport.Failed {
        console.log("[eval] error: $message", "error")
        if (range == null) return EvalReport.Failed(message, null, 0, 0)
        val start = range.start.coerceIn(0, code.length)
        val line = 1 + code.take(start).count { it == '\n' }
        val column = start - code.lastIndexOf('\n', start - 1)
        return EvalReport.Failed(message, range, line, column)
    }

    companion object {
        /** The names of the engine's own synths and noises in display order (`SynthRegistry`), for the sound lists of the app: `s("sawtooth")`, `s("supersaw")`, `s("white")`. */
        val SYNTHS: List<String> = SynthRegistry.names
        val DRUMS: List<String> = DRUM_NAMES

        /**
         * The synthesised drum machines in display order, each with its sound names in display order:
         * `s("bd").bank("cosic808")` plays `cosic808_bd`. Every one has [DRUM_VARIANTS] variants, like the [DRUMS].
         */
        val DRUM_MACHINES: Map<String, List<String>> = cosic.audio.DRUM_MACHINES

        /** How many variants each of [DRUMS] has: `bd:0` to `bd:3`. */
        const val DRUM_VARIANTS: Int = cosic.audio.DRUM_VARIANTS

        /**
         * Every name the engine plays itself (the synths with their short names, the kit's drums, the machines' `<machine>_<drum>`): what a
         * library made for this engine takes as its `builtins`.
         */
        val BUILTIN_SOUNDS: Set<String> =
            (SYNTHS + SynthRegistry.aliases.keys + DRUMS + DRUM_MACHINES.flatMap { (machine, names) -> names.map { "${machine}_$it" } }).toSet()
    }
}
