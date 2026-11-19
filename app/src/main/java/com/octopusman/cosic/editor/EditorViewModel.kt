package com.octopusman.cosic.editor

import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.getValue
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
import com.octopusman.cosic.library.Project
import com.octopusman.cosic.library.ProjectRepository
import cosic.CosicEngine
import cosic.EvalReport
import cosic.SourceRange
import java.io.IOException
import kotlin.math.floor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "CosicEditor"
private const val SAVE_DEBOUNCE_MS = 1_000L

// After a play request the engine reports "stopped" until its audio thread has taken the request
// up. A stop reported after that thread has rendered a few blocks means the engine stopped by
// itself, for example when another app took the audio. If it never renders at all (no audio
// focus, no output device), the start is given up after a timeout.
private const val SETTLE_FRAMES = 1_024L
private const val START_TIMEOUT_MS = 3_000L

/** A project as it should be on disk, with the edit it stands for. */
private class PendingSave(val version: Long, val project: Project)

/**
 * State and actions of the editor screen for one project. The project is saved one second after the
 * last edit, and at once when the editor is left or stopped.
 */
class EditorViewModel(
    projectId: String,
    private val projects: ProjectRepository,
    private val engine: CosicEngine,
    private val audioOutput: AudioOutput,
) : ViewModel() {
    private val loaded: Project? = projects.load(projectId)
    private val undoStack = UndoStack(SystemClock::uptimeMillis)

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

    /** The steps sounding right now, in the coordinates of the current text. */
    var activeRanges by mutableStateOf<List<SourceRange>>(emptyList())
        private set

    /** The engine's view of the music while it plays, null otherwise. */
    var playback by mutableStateOf<PlaybackInfo?>(null)
        private set

    var canUndo by mutableStateOf(false)
        private set

    var canRedo by mutableStateOf(false)
        private set

    // The running evaluation, if any. Stopping cancels it, so a play request that has not been
    // answered yet can never start playback after the user has already stopped or left.
    private var evalJob: Job? = null
    private var playRequestedAt = 0L
    private var playRequestedFrames = 0L

    // Maps highlights, and the error position, from the evaluated text to the text being edited.
    private var highlightMapper = HighlightMapper("")
    private var errorMapper = HighlightMapper("")

    // Each edit gets a version; a save is skipped when a newer edit has been written already, so a
    // slow background save can never overwrite what leaving the editor wrote.
    private var editVersion = 0L
    private var savedVersion = 0L
    private val saveLock = Any()
    private var saveJob: Job? = null

    fun onTextChange(value: TextFieldValue) {
        val before = textValue
        textValue = value
        if (value.text == before.text) return // only the cursor or selection moved
        undoStack.recordEdit(before, value)
        refreshHistory()
        markEdited()
    }

    /** Inserts a symbol from the symbol bar at the cursor, as an ordinary edit. */
    fun insertSymbol(symbol: String) = onTextChange(textValue.withSymbol(symbol))

    /** Inserts a reference example at the cursor, on a line of its own, as an ordinary edit. */
    fun insertSnippet(snippet: String) = onTextChange(textValue.withSnippet(snippet))

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

    /** Evaluates the text; on success it plays, or swaps in the new pattern when already playing. */
    fun evaluateAndPlay() {
        if (missing || evalJob?.isActive == true) return
        val code = textValue.text
        evalJob = viewModelScope.launch {
            // withContext rethrows cancellation instead of returning when stop() cancelled this
            // job meanwhile, so a late result is discarded and never reaches play().
            when (val report = withContext(Dispatchers.Default) { engine.evaluate(code) }) {
                is EvalReport.Ok -> {
                    highlightMapper = HighlightMapper(code)
                    evalStatus = EvalStatus.Ok(report.warnings)
                    if (!playing) {
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

    fun stop() {
        evalJob?.cancel()
        engine.stop()
        playing = false
        activeRanges = emptyList()
        playback = null
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
        val sounding = if (playhead == null) {
            emptyList()
        } else {
            snapshot.highlights
                .filter { it.startFrame <= playhead && playhead < it.endFrame }
                .flatMap { it.ranges }
                .distinct()
        }
        val mapped = highlightMapper.map(sounding, textValue.text)
        if (mapped != activeRanges) activeRanges = mapped

        val cycle = if (playhead == null) 0 else floor(snapshot.cycleAt(playhead)).toInt().coerceAtLeast(0)
        val info = PlaybackInfo(snapshot.cps, cycle, snapshot.runtimeError, snapshot.warnings)
        if (info != playback) playback = info
    }

    override fun onCleared() {
        saveNow()
        if (playing) engine.stop()
    }

    private fun restore(value: TextFieldValue) {
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
            initializer { EditorViewModel(projectId, app.projects, app.engine, app.audioOutput) }
        }
    }
}
