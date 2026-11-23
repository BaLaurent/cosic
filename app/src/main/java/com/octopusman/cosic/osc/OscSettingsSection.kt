package com.octopusman.cosic.osc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octopusman.cosic.R
import com.octopusman.cosic.ui.tagged

/** The rows of the Settings screen for the OSC output: host, port and "send all sound to OSC" (with Strudel's warning while it is on). */
@Composable
fun OscSettingsSection(settings: OscSettings, modifier: Modifier = Modifier) {
    val storedHost by settings.host.collectAsStateWithLifecycle()
    val storedPort by settings.port.collectAsStateWithLifecycle()
    val routeAll by settings.routeAll.collectAsStateWithLifecycle()
    var host by remember(storedHost) { mutableStateOf(storedHost) }
    var port by remember(storedPort) { mutableStateOf(storedPort.toString()) }
    val portValid = port.toIntOrNull()?.let { it in 1..65535 } == true
    val title = stringResource(R.string.osc_title)
    val routeLabel = stringResource(R.string.osc_route_all)

    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.osc_summary), style = MaterialTheme.typography.bodyMedium)
        OutlinedTextField(
            value = host,
            onValueChange = {
                host = it
                if (it.isNotBlank()) settings.setHost(it)
            },
            label = { Text(stringResource(R.string.osc_host)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("osc_host"),
        )
        OutlinedTextField(
            value = port,
            onValueChange = {
                port = it.filter(Char::isDigit).take(5)
                port.toIntOrNull()?.let(settings::setPort)
            },
            label = { Text(stringResource(R.string.osc_port)) },
            isError = !portValid,
            supportingText = if (portValid) null else ({ Text(stringResource(R.string.osc_port_invalid)) }),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth().testTag("osc_port"),
        )
        ListItem(
            headlineContent = { Text(routeLabel) },
            supportingContent = {
                Text(stringResource(if (routeAll) R.string.osc_route_all_warning else R.string.osc_route_all_summary))
            },
            trailingContent = { Switch(checked = routeAll, onCheckedChange = null) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier
                .toggleable(value = routeAll, role = Role.Switch, onValueChange = settings::setRouteAll)
                .tagged("osc_route_all", routeLabel),
        )
    }
}
