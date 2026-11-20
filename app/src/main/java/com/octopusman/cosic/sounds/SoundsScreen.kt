package com.octopusman.cosic.sounds

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octopusman.cosic.R
import com.octopusman.cosic.audio.AudioOutput
import com.octopusman.cosic.audio.BankKind
import com.octopusman.cosic.audio.BankSummary
import com.octopusman.cosic.audio.SampleRepository
import com.octopusman.cosic.audio.playPreview
import com.octopusman.cosic.ui.BackTopBar
import com.octopusman.cosic.ui.ConfirmDialog
import com.octopusman.cosic.ui.copyWithConfirmation
import com.octopusman.cosic.ui.tagged
import cosic.CosicEngine
import kotlinx.coroutines.launch

/**
 * Everything the engine can play: synth waveforms, the drum kit, the bundled instruments and
 * percussion, and the folders the user imported. Tap a row to copy its name, the arrow to hear it.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun SoundsScreen(
    engine: CosicEngine,
    audioOutput: AudioOutput,
    sampleRepository: SampleRepository,
    onBack: () -> Unit,
) {
    val banks by sampleRepository.banks.collectAsStateWithLifecycle()
    val instruments = remember(banks) { banks.filter { it.kind == BankKind.INSTRUMENT } }
    val percussion = remember(banks) { banks.filter { it.kind == BankKind.PERCUSSION } }
    val imported = remember(banks) { banks.filter { it.kind == BankKind.IMPORTED } }

    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var removing by remember { mutableStateOf<BankSummary?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        if (uri != null) sampleRepository.importTree(uri)
    }

    fun copy(name: String) {
        scope.launch { clipboard.copyWithConfirmation(name, snackbar, context.getString(R.string.sounds_copied, name)) }
    }

    fun preview(name: String, index: Int, pitched: Boolean) = engine.playPreview(audioOutput, name, index, pitched)

    Scaffold(
        topBar = { BackTopBar(stringResource(R.string.sounds_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            sectionHeader("synths", R.string.sounds_synths)
            items(CosicEngine.SYNTHS, key = { "synth-$it" }) { name ->
                SoundRow(name, onCopy = { copy(name) }, onPreview = { preview(name, 0, pitched = true) })
            }

            sectionHeader("drums", R.string.sounds_drums)
            items(CosicEngine.DRUMS, key = { "drum-$it" }) { name ->
                SoundRow(
                    name = name,
                    onCopy = { copy(name) },
                    onPreview = { preview(name, 0, pitched = false) },
                    supporting = {
                        Column {
                            Text(stringResource(R.string.sounds_drum_variants), style = MaterialTheme.typography.bodySmall)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (variant in 0 until CosicEngine.DRUM_VARIANTS) {
                                    val label = "$name:$variant"
                                    AssistChip(
                                        onClick = { preview(name, variant, pitched = false) },
                                        label = { Text(label, fontFamily = FontFamily.Monospace) },
                                        modifier = Modifier.tagged("preview_${name}_$variant", stringResource(R.string.sounds_preview, label)),
                                    )
                                }
                            }
                        }
                    },
                )
            }

            bankSection("instruments", R.string.sounds_instruments, instruments) { bank ->
                BankRow(bank, onCopy = { copy(bank.name) }, onPreview = { preview(bank.name, 0, bank.pitched) })
            }
            bankSection("percussion", R.string.sounds_percussion, percussion) { bank ->
                BankRow(bank, onCopy = { copy(bank.name) }, onPreview = { preview(bank.name, 0, bank.pitched) })
            }

            sectionHeader("imported", R.string.sounds_imported)
            item(key = "import-folder") {
                OutlinedButton(
                    onClick = { folderPicker.launch(null) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        .tagged("import_folder", stringResource(R.string.sounds_import_folder)),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.sounds_import_folder))
                }
            }
            if (imported.isEmpty()) {
                item(key = "imported-empty") {
                    Text(
                        text = stringResource(R.string.sounds_imported_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(imported, key = { "imported-${it.name}" }) { bank ->
                BankRow(
                    bank = bank,
                    onCopy = { copy(bank.name) },
                    onPreview = { preview(bank.name, 0, bank.pitched) },
                    extraAction = {
                        TextButton(
                            onClick = { removing = bank },
                            modifier = Modifier.tagged("remove_${bank.name}", stringResource(R.string.sounds_remove_bank, bank.name)),
                        ) { Text(stringResource(R.string.action_remove)) }
                    },
                )
            }
        }
    }

    removing?.let { bank ->
        ConfirmDialog(
            title = stringResource(R.string.sounds_remove_title),
            message = stringResource(R.string.sounds_remove_message),
            confirmLabel = stringResource(R.string.action_remove),
            onConfirm = {
                // A folder can hold several banks; they all go with it.
                bank.treeUri?.let(sampleRepository::removeImported)
                removing = null
            },
            onDismiss = { removing = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.sectionHeader(key: String, titleRes: Int) {
    stickyHeader(key = "header-$key") {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.background)
                .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        )
    }
}

/** A section that is left out entirely while it has no banks. */
private fun LazyListScope.bankSection(
    key: String,
    titleRes: Int,
    banks: List<BankSummary>,
    row: @Composable (BankSummary) -> Unit,
) {
    if (banks.isEmpty()) return
    sectionHeader(key, titleRes)
    items(banks, key = { "$key-${it.name}" }) { bank -> row(bank) }
}

@Composable
private fun BankRow(
    bank: BankSummary,
    onCopy: () -> Unit,
    onPreview: () -> Unit,
    extraAction: (@Composable () -> Unit)? = null,
) {
    SoundRow(
        name = bank.name,
        onCopy = onCopy,
        onPreview = onPreview,
        supporting = { Text(pluralStringResource(R.plurals.sounds_sample_count, bank.count, bank.count)) },
        extraAction = extraAction,
    )
}

@Composable
private fun SoundRow(
    name: String,
    onCopy: () -> Unit,
    onPreview: () -> Unit,
    supporting: (@Composable () -> Unit)? = null,
    extraAction: (@Composable () -> Unit)? = null,
) {
    ListItem(
        headlineContent = { Text(name, fontFamily = FontFamily.Monospace) },
        supportingContent = supporting,
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                extraAction?.invoke()
                IconButton(
                    onClick = onPreview,
                    modifier = Modifier.tagged("preview_$name", stringResource(R.string.sounds_preview, name)),
                ) { Icon(Icons.Filled.PlayArrow, contentDescription = null) }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onCopy),
    )
}
