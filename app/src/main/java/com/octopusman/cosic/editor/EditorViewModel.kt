package com.octopusman.cosic.editor

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.octopusman.cosic.CosicApp
import com.octopusman.cosic.audio.AudioOutput
import com.octopusman.cosic.audio.BankSummary
import com.octopusman.cosic.audio.SampleRepository
import com.octopusman.cosic.audio.SoundsStatus
import com.octopusman.cosic.audio.playPreview
import com.octopusman.cosic.input.InputSwitches
import com.octopusman.cosic.library.Project
import com.octopusman.cosic.library.ProjectRepository
import com.octopusman.cosic.reference.InsertText
import com.octopusman.cosic.settings.EditorSettings
import com.octopusman.cosic.settings.ProgramEditorSettings
import com.octopusman.cosic.widgets.SliderEdits
import cosic.CosicEngine
import cosic.EvalReport
import cosic.ProgramInfo
import cosic.SliderConfig
import cosic.SourceRange
import cosic.io.ConsoleLog
import cosic.io.InputState
import cosic.io.ShareLink
import cosic.io.SliderBank
import cosic.visual.VisualSession
import java.io.IOException
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CosicEditor"
private const val SAVE_DEBOUNCE_MS = 1_000L

/** How long the code flashes when it is evaluated (`flash.mjs` of the web editor). */
const val FLASH_MS = 200L

// After a play request the engine reports "stopped" until its audio thread has taken the request
// up. A stop reported after that thread has rendered a few blocks means the engine stopped by
// itself, for example when another app took the audio. If it never renders at all (no audio
// focus, no output device), the start is given up after a timeout.
private const val SETTLE_FRAMES = 1_024L
private const val START_TIMEOUT_MS = 3_000L

// The samples a program names are fetched when it is evaluated (the engine plays the next two cycles without sound to find
// them). The first start waits for them this long, so that the first bar is not missing the hits whose files are still coming.
private const val SAMPLES_WAIT_MS = 5_000L

/** A project as it should be on disk, with the edit it stands for. */
private class PendingSave(val version: Long, val project: Project)

/**
 * State and actions of the editor screen for one project. The project is saved one second after the
 * last edit, and at once when the editor is left or stopped.
 *
 * Besides the text it holds what the last evaluated program says about itself ([program], [visuals], [sliders]), what sounds right now
 * ([activeHighlights], [nowCycle]) and the switches of the phone's inputs that follow what the program reads ([inputSwitches]).
 */
class EditorViewModel(
    projectId: String,
    private val projects: ProjectRepository,
    private val engine: CosicEngine,
    private val audioOutput: AudioOutput,
    private val sampleRepository: SampleRepository,
    private val settings: EditorSettings,
    programSettings: ProgramEditorSettings,
    private val inputSwitches: InputSwitches,
) : ViewModel() {
    private val loaded: Project? = projects.load(projectId)
    private val undoStack = UndoStack(SystemClock::uptimeMillis)

    init {
        // The tempo a program sets lasts from one evaluation to the next; a different project starts at the default.
        engine.resetSession()
        programSettings.reset()
    }

    /** True when the project is gone, for example because its file was unreadable. */
    val missing = loaded == null

    var name by mutableStateOf(loaded?.name.orEmpty())
        private set

    var textValue by mutableStateOf(TextFieldValue(loaded?.code.orEmpty()))
        private set

    var evalStatus by mutableStateOf<EvalStatus>(EvalStatus.Idle)
        private set

    var playing by mutableStateOf(false)
        private set

    /** The steps sounding right now, in the coordinates of the current text, with the colour and style their events asked for. */
    var activeHighlights by mutableStateOf<List<ActiveHighlight>>(emptyList())
        private set

    /** Where those steps are, for the editor that only marks them. */
    val activeRanges: List<SourceRange> get() = activeHighlights.map { it.range }

    /** The engine's view of the music while it plays, null otherwise. */
    var playback by mutableStateOf<PlaybackInfo?>(null)
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    /**
     * What the last program that evaluated says about itself: where its mini-notation is, its sliders and widgets, its visuals, the metadata of its comments and the inputs
     * it reads. A program that failed to evaluate changes nothing here.
     */
    var program by mutableStateOf<ProgramInfo?>(null)
        private set

    /** The visuals the last program made (pianoroll, scope, spiral...); null when it made none. The frame loop that draws them takes it. */
    var visuals by mutableStateOf<VisualSession?>(null)
        private set

    /**
     * The sliders of the last program that have a number to move, with where that number is in the text now: the edits since the evaluation moved them, and a slider
     * whose number was edited away is gone.
     */
    var sliders by mutableStateOf<List<LiveSlider>>(emptyList())
        private set

    /** The values of the sliders, where the playing pattern reads them: a slider card sets its own, and the sound follows with no new evaluation. */
    val sliderBank: SliderBank get() = engine.inputs.sliders

    /** The pointer, keys, sensors and sliders of the engine, for the root of the screen that follows the pointer. */
    val inputs: InputState get() = engine.inputs

    /** What programs and the engine write to the console. */
    val console: ConsoleLog get() = engine.console

    /** The prebake script the engine runs before a session's first program, null when there is none. */
    val prebake: String? get() = engine.prebake

    /** The title the program's comments declare (`// @title ...`), null when it has none. */
    val title: String?
        get() = (program?.metadata?.get("title") as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /** Until this time ([SystemClock.uptimeMillis]) the code flashes: set when the code is evaluated, as the web editor flashes it, unless the setting is off. */
    var flashUntilMs by mutableLongStateOf(0L)
        private set

    // The running evaluation, if any. Stopping cancels it, so a play request that has not been
    // answered yet can never start playback after the user has already stopped or left.
    private var evalJob: Job? = null
    private var playRequestedAt = 0L
    private var playRequestedFrames = 0L

    // Maps highlights, and the error position, from the evaluated text to the text being edited.
    private var highlightMapper = HighlightMapper("")
    private var errorMapper = HighlightMapper("")

    // The text a slider drag began from: the whole drag is one step of the undo history.
    private var sliderDragStart: TextFieldValue? = null

    // Each edit gets a version; a save is skipped when a newer edit has been written already, so a
    // slow background save can never overwrite what leaving the editor wrote.
    private var editVersion = 0L
    private var savedVersion = 0L
    private val saveLock = Any()
    private var saveJob: Job? = null

    /** The sample banks the reference lists next to the built-in sounds. */
    val banks: StateFlow<List<BankSummary>> = sampleRepository.banks

    /** How loading the sounds goes: the Sounds tab says so. */
    val soundsStatus: StateFlow<SoundsStatus> = sampleRepository.status

    private var previewJob: Job? = null

    /**
     * Plays sample [index] of a sound once from the reference, without touching the music that is playing. A sound that has to be
     * downloaded first plays when it is there; a newer preview replaces one that is still waiting.
     */
    fun previewSound(name: String, index: Int, pitched: Boolean) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch(Dispatchers.Default) { engine.playPreview(sampleRepository, audioOutput, name, index, pitched) }
    }

    /** Tries again to load the default sounds that could not be loaded. */
    fun retrySounds() = sampleRepository.retryDefaults()

    /** Adds a folder of samples picked on the Sounds tab; its sounds are listed once it is indexed. */
    fun importFolder(uri: Uri) = sampleRepository.importTree(uri)

    /** Takes an imported folder, and every sound made from it, away again. */
    fun removeFolder(uri: Uri) = sampleRepository.removeImported(uri)

    fun onTextChange(value: TextFieldValue) {
        val before = textValue
        textValue = value
        if (value.text == before.text) return // only the cursor or selection moved
        sliders = SliderTracking.afterEdit(sliders, before.text, value.text)
        undoStack.recordEdit(before, value)
        refreshHistory()
        markEdited()
    }

    /** Inserts a symbol from the symbol bar at the cursor, as an ordinary edit. */
    fun insertSymbol(symbol: String) = onTextChange(textValue.withSymbol(symbol))

    /** Inserts a reference example at the cursor, on a line of its own, as an ordinary edit. */
    fun insertSnippet(snippet: String) = onTextChange(textValue.withSnippet(snippet))

    /** Puts a reference entry (a call, a sound or scale name) at the cursor, as an ordinary edit. */
    fun insertEntry(entry: InsertText) = onTextChange(textValue.withEntry(entry))

    /**
     * Moves the cursor to the next (or, for a negative [direction], the previous) `$` label, as Alt-W and Alt-Q do in the web editor. A move is no edit; a text without a
     * label stays as it is.
     */
    fun jumpToLabel(direction: Int) {
        val at = LabelJump.jump(textValue.text, textValue.selection.start, direction) ?: return
        textValue = textValue.copy(selection = TextRange(at))
    }

    /** The address that opens this code in the web editor (the `#...` link of its Share button): with the prebake script in front when the setting says so. */
    fun webLink(): String =
        ShareLink.forSharing(textValue.text, if (settings.includePrebakeInShare.value) engine.prebake else null)

    fun undo() {
        undoStack.undo(textValue)?.let(::restore)
    }

    fun redo() {
        undoStack.redo(textValue)?.let(::restore)
    }

    fun rename(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == name) return
        name = trimmed
        markEdited()
        saveNow()
    }

    /** A drag of a slider begins: what it writes into the code until [onSliderDragEnd] is one step of the undo history. */
    fun onSliderDragStart() {
        if (sliderDragStart == null) sliderDragStart = textValue
    }

    /**
     * The slider [spec] moved to the number written [newText]: its literal in the text is replaced by it, at once and without a new evaluation (the card has set the value
     * where the pattern reads it). Nothing happens when the number is not in the code any more.
     */
    fun onSliderRewrite(spec: SliderConfig, newText: String) {
        val live = sliders.firstOrNull { it.spec.id == spec.id } ?: return
        val before = textValue
        val length = SliderEdits.literalLength(before.text, live.start) ?: return
        val (next, range) = SliderEdits.applyAt(before.text, live.start, length, newText)
        val shift = range.end - range.start - length
        fun moved(offset: Int) = when {
            offset <= live.start -> offset
            offset >= live.start + length -> offset + shift
            else -> range.end // inside the number: after the new one
        }
        val after = TextFieldValue(next, TextRange(moved(before.selection.start), moved(before.selection.end)))
        textValue = after
        sliders = SliderTracking.afterEdit(sliders, before.text, next)
        // a change that is no part of a drag is an edit of its own
        if (sliderDragStart == null) undoStack.recordEdit(before, after)
        refreshHistory()
        markEdited()
    }

    /** The drag ended: if it changed the code, that is one step of the undo history. */
    fun onSliderDragEnd() {
        val start = sliderDragStart ?: return
        sliderDragStart = null
        if (start.text != textValue.text) {
            undoStack.recordEdit(start, textValue)
            refreshHistory()
        }
    }

    /** Evaluates the text; on success it plays, or swaps in the new pattern when already playing. */
    fun evaluateAndPlay() {
        if (missing || evalJob?.isActive == true) return
        val code = textValue.text
        if (settings.flash.value) flashUntilMs = SystemClock.uptimeMillis() + FLASH_MS
        evalJob = viewModelScope.launch {
            // withContext rethrows cancellation instead of returning when stop() cancelled this
            // job meanwhile, so a late result is discarded and never reaches play(). Evaluating can wait
            // for the network (samples() and the default sounds), which is what the IO threads are for.
            when (val report = withContext(Dispatchers.IO) { engine.evaluate(code) }) {
                is EvalReport.Ok -> {
                    highlightMapper = HighlightMapper(code)
                    evalStatus = EvalStatus.Ok(report.warnings)
                    takeProgram(report.program, code)
                    if (!playing) {
                        sampleRepository.awaitDownloads(SAMPLES_WAIT_MS)
                        engine.play()
                        playing = true
                        playRequestedAt = SystemClock.uptimeMillis()
                        playRequestedFrames = engine.snapshot.renderedFrames
                    }
                    audioOutput.ensureRunning()
                }
                // A failed update leaves whatever is playing playing.
                is EvalReport.Failed -> {
                    errorMapper = HighlightMapper(code)
                    evalStatus = EvalStatus.Error(report.message, report.range, report.line, report.column)
                }
            }
        }
    }

    /** The program that was just evaluated from [evaluated] becomes the one the screen shows: its visuals, its sliders and the inputs it reads. */
    private fun takeProgram(info: ProgramInfo, evaluated: String) {
        program = info
        visuals = info.visuals as? VisualSession
        sliders = SliderTracking.place(evaluated, textValue.text, info.sliders)
        inputSwitches.use(info.inputsUsed)
    }

    fun stop() {
        evalJob?.cancel()
        engine.stop()
        playing = false
        activeHighlights = emptyList()
        playback = null
        // nothing needs the sensors, controllers or MIDI inputs of a program that does not play
        inputSwitches.releaseAll()
    }

    /** Leaves the editor: stops the music and saves. Returns whether music was playing. */
    fun onLeave(): Boolean {
        val wasPlaying = playing
        stop()
        saveNow()
        return wasPlaying
    }

    /** The app went to the background, where nothing may keep playing. */
    fun onBackground() {
        stop()
        saveNow()
    }

    /** Selects the code the current error points at, if it still exists in the edited text. */
    fun selectError() {
        val error = evalStatus as? EvalStatus.Error ?: return
        val range = error.range ?: return
        val mapped = errorMapper.map(listOf(range), textValue.text).firstOrNull() ?: return
        textValue = TextFieldValue(textValue.text, TextRange(mapped.start, mapped.end))
    }

    /**
     * The cycle that is being heard (the playhead of the audio device, not what the engine has rendered ahead), or null while the engine has not started or the device has
     * not said where it is. The visuals draw at this cycle.
     */
    fun nowCycle(): Double? {
        val frame = audioOutput.playheadFrame() ?: return null
        return engine.snapshot.cycleAt(frame).coerceAtLeast(0.0)
    }

    /** Whether the engine plays right now (not what was asked for: the engine reports it once its audio thread has taken the request up). */
    fun isEnginePlaying(): Boolean = engine.snapshot.playing

    /** Called on every frame while playing: refreshes the highlights and the status line from the engine. */
    fun onFrame() {
        if (!playing) return
        val snapshot = engine.snapshot
        val settled = snapshot.renderedFrames - playRequestedFrames >= SETTLE_FRAMES ||
            SystemClock.uptimeMillis() - playRequestedAt > START_TIMEOUT_MS
        if (!snapshot.playing && settled) {
            stop()
            return
        }
        val playhead = audioOutput.playheadFrame()
        val sounding = if (playhead == null || !settings.highlight.value) emptyList() else activeHighlights(snapshot.highlights, playhead)
        val mapped = highlightMapper.mapActive(sounding, textValue.text)
        if (mapped != activeHighlights) activeHighlights = mapped

        val cycle = if (playhead == null) 0 else floor(snapshot.cycleAt(playhead)).toInt().coerceAtLeast(0)
        val info = PlaybackInfo(snapshot.cps, cycle, snapshot.runtimeError, snapshot.warnings)
        if (info != playback) playback = info
    }

    override fun onCleared() {
        saveNow()
        if (playing) engine.stop()
        inputSwitches.releaseAll()
    }

    private fun restore(value: TextFieldValue) {
        sliders = SliderTracking.afterEdit(sliders, textValue.text, value.text)
        textValue = value
        refreshHistory()
        markEdited()
    }

    private fun refreshHistory() {
        canUndo = undoStack.canUndo
        canRedo = undoStack.canRedo
    }

    private fun markEdited() {
        editVersion++
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            delay(SAVE_DEBOUNCE_MS)
            val pending = capture()
            withContext(Dispatchers.IO) { pending?.let(::write) }
        }
    }

    private fun saveNow() {
        saveJob?.cancel()
        capture()?.let(::write)
    }

    private fun capture(): PendingSave? {
        val base = loaded ?: return null
        val project = base.copy(name = name, code = textValue.text, updatedAt = System.currentTimeMillis())
        return PendingSave(editVersion, project)
    }

    private fun write(pending: PendingSave) = synchronized(saveLock) {
        if (pending.version <= savedVersion) return@synchronized
        try {
            projects.save(pending.project)
            savedVersion = pending.version
        } catch (e: IOException) {
            // Still unsaved: the next edit, or leaving the editor, tries again.
            Log.w(TAG, "cannot save project ${pending.project.id}", e)
        }
    }

    companion object {
        fun factory(app: CosicApp, projectId: String) = viewModelFactory {
            initializer {
                EditorViewModel(
                    projectId = projectId,
                    projects = app.projects,
                    engine = app.engine,
                    audioOutput = app.audioOutput,
                    sampleRepository = app.sampleRepository,
                    settings = app.editorSettings,
                    programSettings = app.programSettings,
                    inputSwitches = InputSwitches(
                        motion = { on -> if (on) app.sensorHub.start() else app.sensorHub.stop() },
                        gamepad = app.gamepadHub::setEnabled,
                        midiIn = app.midi::setListening,
                        releaseKeys = app.keyTracker::releaseAll,
                    ),
                )
            }
        }
    }
}
