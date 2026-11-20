package com.octopusman.cosic.midi

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged

private enum class ConnectState { CONNECTING, CONNECTED, FAILED }

/**
 * The MIDI devices of the phone, for a program's `midi()`, `midin()` and `midikeys()`: the outputs and inputs in the order the program counts
 * them, and a scan for Bluetooth MIDI devices that connects them. Self-contained: it asks for the Bluetooth permissions itself, and USB devices
 * need nothing (a denied permission leaves them working). Shown as a sheet over whatever is on screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MidiDevicesSheet(service: MidiService, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val names by service.devices.collectAsStateWithLifecycle()
    val scanState by service.scanner.state.collectAsStateWithLifecycle()
    val found by service.scanner.found.collectAsStateWithLifecycle()
    val connections = remember { mutableStateMapOf<String, ConnectState>() }

    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        // whatever was granted, the scanner looks at it again and says what is missing
        service.scanner.start()
    }
    // a scan that is left running when the sheet closes would go on until its own end
    DisposableEffect(service) { onDispose { service.scanner.stop() } }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        LazyColumn(
            modifier = Modifier.tagged("midi_devices_sheet", stringResource(R.string.midi_devices_title)),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            item { Text(stringResource(R.string.midi_devices_title), style = MaterialTheme.typography.titleLarge) }
            if (!service.supported) {
                item { Text(stringResource(R.string.midi_not_supported), modifier = Modifier.padding(vertical = 12.dp)) }
            } else {
                item { SectionTitle(stringResource(R.string.midi_outputs_title)) }
                if (names.outputs.isEmpty()) {
                    item { Hint(stringResource(R.string.midi_no_outputs)) }
                } else {
                    items(names.outputs.withIndex().toList()) { (index, name) -> PortRow(index, name) }
                }
                item { SectionTitle(stringResource(R.string.midi_inputs_title)) }
                if (names.inputs.isEmpty()) {
                    item { Hint(stringResource(R.string.midi_no_inputs)) }
                } else {
                    items(names.inputs.withIndex().toList()) { (index, name) -> PortRow(index, name) }
                }
                item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
                item { SectionTitle(stringResource(R.string.midi_bluetooth_title)) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (scanState == BleScanState.SCANNING) {
                            OutlinedButton(onClick = { service.scanner.stop() }) { Text(stringResource(R.string.midi_stop_scan)) }
                            Text(stringResource(R.string.midi_scanning), style = MaterialTheme.typography.bodyMedium)
                        } else {
                            Button(
                                onClick = {
                                    if (service.scanner.hasPermissions()) {
                                        service.scanner.start()
                                    } else {
                                        permissions.launch(bluetoothPermissions(android.os.Build.VERSION.SDK_INT).toTypedArray())
                                    }
                                },
                                modifier = Modifier.tagged("midi_scan", stringResource(R.string.midi_scan_bluetooth)),
                            ) { Text(stringResource(R.string.midi_scan_bluetooth)) }
                        }
                    }
                }
                when (scanState) {
                    BleScanState.NO_PERMISSION -> item { Hint(stringResource(R.string.midi_bluetooth_permission_denied)) }
                    BleScanState.BLUETOOTH_OFF -> item { Hint(stringResource(R.string.midi_bluetooth_off)) }
                    BleScanState.NO_BLUETOOTH -> item { Hint(stringResource(R.string.midi_bluetooth_unavailable)) }
                    BleScanState.SCANNING, BleScanState.IDLE -> Unit
                }
                items(found, key = { it.address }) { device ->
                    val state = connections[device.address]
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(device.name, style = MaterialTheme.typography.bodyLarge)
                            if (state == ConnectState.FAILED) {
                                Text(
                                    stringResource(R.string.midi_connect_failed, device.name),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            } else {
                                Text(device.address, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        when (state) {
                            ConnectState.CONNECTING -> Text(stringResource(R.string.midi_connecting))
                            ConnectState.CONNECTED -> Text(stringResource(R.string.midi_connected))
                            ConnectState.FAILED, null -> TextButton(
                                onClick = {
                                    connections[device.address] = ConnectState.CONNECTING
                                    service.scanner.connect(device) { ok ->
                                        connections[device.address] = if (ok) ConnectState.CONNECTED else ConnectState.FAILED
                                    }
                                },
                            ) { Text(stringResource(R.string.midi_connect)) }
                        }
                    }
                }
            }
            item {
                TextButton(onClick = onDismiss, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp)) {
                    Text(stringResource(R.string.midi_close))
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** One port of the list; a program refers to it by its number or by (a part of) its name. */
@Composable
private fun PortRow(index: Int, name: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("$index", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.bodyLarge)
    }
}
