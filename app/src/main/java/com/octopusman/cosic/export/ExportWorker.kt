package com.octopusman.cosic.export

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import cosic.SoundLibrary
import cosic.render.OfflineRenderer
import java.io.IOException
import java.util.concurrent.CancellationException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible

/** What the export of [ExportWorker] is doing. */
sealed interface ExportState {
    /** Nothing has been started, or the last export was cancelled. */
    data object Idle : ExportState

    /** Rendering: [fraction] (0 to 1) of the cycles are done; 1 while the file is being written. */
    data class Running(val fraction: Float) : ExportState

    /** The file is written. */
    data class Done(val seconds: Double) : ExportState

    /** The export failed, with the reason (the message of the engine without its own "Export failed" in front). */
    data class Failed(val reason: String) : ExportState
}

private const val FAILED_PREFIX = "Export failed: "

/**
 * Renders the program of the editor to a 16-bit stereo WAV file (Strudel's Export tab): cycles [startCycle] to [endCycle] at [sampleRate], with
 * [OfflineRenderer], which makes an engine of its own, so what is playing in the editor is untouched. It shares the [library] of the app, so the sounds that
 * are loaded for the editor are not loaded twice. The work runs on [Dispatchers.Default] in [scope]; [state] says how far it is.
 */
class ExportWorker(private val scope: CoroutineScope, private val library: SoundLibrary) {
    private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle)
    private val cancelRequested = AtomicBoolean(false)
    private var job: Job? = null

    val state: StateFlow<ExportState> get() = mutableState

    /** Whether an export is going on. */
    val running: Boolean get() = mutableState.value is ExportState.Running

    /**
     * Renders [code] and writes the file to [output], a document the user chose; it is deleted again when the export fails or is cancelled. Does nothing while another
     * export is running.
     */
    fun start(
        resolver: ContentResolver,
        output: Uri,
        code: String,
        startCycle: Double,
        endCycle: Double,
        sampleRate: Int,
    ) {
        if (running) return
        cancelRequested.set(false)
        mutableState.value = ExportState.Running(0f)
        job = scope.launch(Dispatchers.Default) {
            try {
                val samples = runInterruptible {
                    OfflineRenderer.render(
                        code, startCycle, endCycle, sampleRate, library,
                        progress = { mutableState.value = ExportState.Running(it.toFloat().coerceIn(0f, 1f)) },
                        cancelled = { cancelRequested.get() },
                    )
                }
                if (cancelRequested.get()) throw CancellationException("export cancelled")
                val stream = resolver.openOutputStream(output, "wt") ?: throw IOException("cannot write to the chosen file")
                stream.use { OfflineRenderer.writeWav(it, samples, sampleRate) }
                mutableState.value = ExportState.Done(samples.size / 2.0 / sampleRate)
            } catch (e: CancellationException) {
                discard(resolver, output)
                mutableState.value = ExportState.Idle
            } catch (e: Exception) {
                discard(resolver, output)
                mutableState.value = ExportState.Failed((e.message ?: e.toString()).removePrefix(FAILED_PREFIX))
            }
        }
    }

    /** Stops the export that is running; the file it made is deleted. */
    fun cancel() {
        cancelRequested.set(true)
    }

    /** Stops what is running and forgets its result: for when nobody looks at the export any more. */
    fun close() {
        cancelRequested.set(true)
        job?.cancel()
    }

    /** Forgets a finished or failed export, so that the next one starts from a clean state. */
    fun reset() {
        if (!running) mutableState.value = ExportState.Idle
    }

    private fun discard(resolver: ContentResolver, output: Uri) {
        try {
            DocumentsContract.deleteDocument(resolver, output)
        } catch (_: Exception) {
            // the provider may refuse; an empty file is left, which is harmless
        }
    }
}
