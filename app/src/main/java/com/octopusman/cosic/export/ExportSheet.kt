package com.octopusman.cosic.export

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged
import cosic.SoundLibrary

/** The sample rates the export offers; the second is the default of Strudel's Export tab. */
internal val EXPORT_SAMPLE_RATES = listOf(44_100, 48_000)
internal const val DEFAULT_EXPORT_SAMPLE_RATE = 48_000

/** A file name for the document picker: [name] without characters a file system does not take, and with the `.wav` the picker expects. */
internal fun exportFileName(name: String, fallback: String): String {
    val clean = name.trim().replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ').ifEmpty { fallback }
    return if (clean.endsWith(".wav", ignoreCase = true)) clean else "$clean.wav"
}

/** The cycles the two text fields say, or null when they are not numbers, the start is negative or the end is not after it. */
internal fun parseCycleRange(start: String, end: String): Pair<Double, Double>? {
    val from = start.trim().toDoubleOrNull() ?: return null
    val to = end.trim().toDoubleOrNull() ?: return null
    return if (from.isFinite() && to.isFinite() && from >= 0.0 && to > from) from to to else null
}

/**
 * The export of the program as a WAV file, as a sheet (Strudel's Export tab): the file name, the first and the last cycle (the last one is not rendered; they
 * default to 0 and 4) and the sample rate. Export asks for the place of the file with the system's document picker and then renders in [ExportWorker], showing how
 * far it is with a Cancel button; what plays in the editor is not touched. [code] is the program as it is in the editor now, [defaultName] the name the file
 * has when the field is empty (the project's), and [library] the sounds of the app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(code: String, defaultName: String, library: SoundLibrary, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val worker = remember(library) { ExportWorker(scope, library) }
    DisposableEffect(worker) { onDispose { worker.close() } }
    val state by worker.state.collectAsState()
    val exporting = state is ExportState.Running

    var name by remember { mutableStateOf(defaultName) }
    var startText by remember { mutableStateOf("0") }
    var endText by remember { mutableStateOf("4") }
    var sampleRate by remember { mutableIntStateOf(DEFAULT_EXPORT_SAMPLE_RATE) }
    val range = parseCycleRange(startText, endText)

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val (from, to) = range ?: return@rememberLauncherForActivityResult
        if (uri != null) worker.start(context.contentResolver, uri, code, from, to, sampleRate)
    }

    ModalBottomSheet(
        onDismissRequest = { if (!exporting) onDismiss() },
        sheetState = rememberModalBottomSheetState(
            skipPartiallyExpanded = true,
            confirmValueChange = { !exporting },
        ),
        modifier = Modifier.tagged("export_sheet", stringResource(R.string.export_title)),
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.export_title), style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.export_file_name)) },
                singleLine = true,
                enabled = !exporting,
                modifier = Modifier.fillMaxWidth().tagged("export_name", stringResource(R.string.export_file_name)),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text(stringResource(R.string.export_start_cycle)) },
                    singleLine = true,
                    isError = range == null,
                    enabled = !exporting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).tagged("export_start", stringResource(R.string.export_start_cycle)),
                )
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text(stringResource(R.string.export_end_cycle)) },
                    singleLine = true,
                    isError = range == null,
                    enabled = !exporting,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f).tagged("export_end", stringResource(R.string.export_end_cycle)),
                )
            }
            if (range == null) {
                Text(stringResource(R.string.export_range_invalid), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.export_sample_rate), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (rate in EXPORT_SAMPLE_RATES) {
                    FilterChip(
                        selected = sampleRate == rate,
                        onClick = { sampleRate = rate },
                        enabled = !exporting,
                        label = { Text(stringResource(R.string.export_rate_hz, rate)) },
                        modifier = Modifier.tagged("export_rate_$rate", stringResource(R.string.export_rate_hz, rate)),
                    )
                }
            }
            when (val shown = state) {
                is ExportState.Running -> {
                    LinearProgressIndicator(progress = { shown.fraction }, modifier = Modifier.fillMaxWidth().tagged("export_progress", stringResource(R.string.export_progress, (shown.fraction * 100).toInt())))
                    Text(stringResource(R.string.export_progress, (shown.fraction * 100).toInt()), style = MaterialTheme.typography.bodySmall)
                }
                is ExportState.Done -> Text(stringResource(R.string.export_done, shown.seconds), style = MaterialTheme.typography.bodyMedium)
                is ExportState.Failed -> Text(
                    stringResource(R.string.export_failed, shown.reason),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.tagged("export_error", shown.reason),
                )
                ExportState.Idle -> Unit
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                if (exporting) {
                    OutlinedButton(onClick = { worker.cancel() }, modifier = Modifier.tagged("export_cancel", stringResource(R.string.export_cancel))) {
                        Text(stringResource(R.string.export_cancel))
                    }
                } else {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.tagged("export_close", stringResource(R.string.export_close))) {
                        Text(stringResource(R.string.export_close))
                    }
                    Button(
                        onClick = {
                            worker.reset()
                            picker.launch(exportFileName(name, defaultName.ifEmpty { "cosic" }))
                        },
                        enabled = range != null,
                        modifier = Modifier.tagged("export_start_button", stringResource(R.string.export_action)),
                    ) {
                        Text(stringResource(R.string.export_action))
                    }
                }
            }
        }
    }
}
