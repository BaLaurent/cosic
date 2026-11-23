package com.octopusman.cosic.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octopusman.cosic.BuildConfig
import com.octopusman.cosic.R
import com.octopusman.cosic.midi.MidiDevicesSheet
import com.octopusman.cosic.midi.MidiService
import com.octopusman.cosic.monetization.AdsController
import com.octopusman.cosic.monetization.EntitlementRepository
import com.octopusman.cosic.monetization.PurchaseUiState
import com.octopusman.cosic.monetization.UnavailableReason
import com.octopusman.cosic.osc.OscSettings
import com.octopusman.cosic.osc.OscSettingsSection
import com.octopusman.cosic.ui.BackTopBar
import com.octopusman.cosic.ui.PRIVACY_POLICY_URL
import com.octopusman.cosic.ui.findActivity
import com.octopusman.cosic.ui.openLink
import com.octopusman.cosic.ui.tagged
import cosic.io.Prebake

/** Purchase and privacy controls, what the editor and its outputs do, the credits and the app version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    entitlements: EntitlementRepository,
    ads: AdsController,
    editorSettings: EditorSettings,
    oscSettings: OscSettings,
    midi: MidiService,
    onBack: () -> Unit,
    onOpenCredits: () -> Unit,
) {
    val context = LocalContext.current
    val activity = context.findActivity()
    val purchase by entitlements.purchaseState.collectAsStateWithLifecycle()
    val privacyOptionsRequired by ads.privacyOptionsRequired.collectAsStateWithLifecycle()
    var showMidiSheet by rememberSaveable { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar(stringResource(R.string.settings_title), onBack) }) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            when (val state = purchase) {
                PurchaseUiState.Loading -> PurchaseRow(
                    headline = stringResource(R.string.settings_store_loading),
                    icon = Icons.Filled.Sync,
                )
                is PurchaseUiState.Available -> PurchaseRow(
                    headline = stringResource(R.string.settings_remove_ads_price, state.price),
                    icon = Icons.Filled.ShoppingCart,
                    onClick = { entitlements.launchPurchase(activity) },
                )
                PurchaseUiState.Pending -> PurchaseRow(
                    headline = stringResource(R.string.settings_purchase_pending),
                    icon = Icons.Filled.Schedule,
                )
                PurchaseUiState.Owned -> PurchaseRow(
                    headline = stringResource(R.string.settings_ads_removed),
                    icon = Icons.Filled.Check,
                )
                is PurchaseUiState.Unavailable -> PurchaseRow(
                    headline = stringResource(R.string.settings_store_unavailable),
                    icon = Icons.Filled.CloudOff,
                    supporting = when (state.reason) {
                        UnavailableReason.STORE_UNREACHABLE -> null
                        UnavailableReason.PURCHASES_UNREADABLE -> stringResource(R.string.settings_purchases_unreadable)
                        UnavailableReason.PRODUCT_MISSING -> stringResource(R.string.settings_product_unavailable)
                        UnavailableReason.PURCHASE_FAILED -> stringResource(R.string.settings_purchase_failed)
                    },
                )
            }

            SettingsRow(
                headline = stringResource(R.string.settings_restore_purchase),
                icon = Icons.Filled.Restore,
                modifier = Modifier
                    .clickable(onClick = entitlements::refresh)
                    .tagged("restore_purchase", stringResource(R.string.settings_restore_purchase)),
            )
            if (privacyOptionsRequired) {
                SettingsRow(
                    headline = stringResource(R.string.settings_privacy_options),
                    icon = Icons.Filled.PrivacyTip,
                    modifier = Modifier
                        .clickable { ads.showPrivacyOptions(activity) }
                        .tagged("privacy_options", stringResource(R.string.settings_privacy_options)),
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SectionHeader(stringResource(R.string.settings_editor_title))
            EditorToggles(editorSettings)
            PrebakeEditor(editorSettings)

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            OscSettingsSection(oscSettings)
            SettingsRow(
                headline = stringResource(R.string.settings_midi_devices),
                icon = Icons.Filled.Usb,
                supporting = stringResource(R.string.settings_midi_devices_summary),
                modifier = Modifier
                    .clickable {
                        // the sheet lists what the service sees: it must be watching the devices (the editor's engine does that, but it may not exist yet)
                        midi.start()
                        showMidiSheet = true
                    }
                    .tagged("midi_devices", stringResource(R.string.settings_midi_devices)),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            SettingsRow(
                headline = stringResource(R.string.settings_privacy_policy),
                icon = Icons.Filled.Policy,
                modifier = Modifier
                    .clickable { context.openLink(PRIVACY_POLICY_URL) }
                    .tagged("privacy_policy", stringResource(R.string.settings_privacy_policy)),
            )
            SettingsRow(
                headline = stringResource(R.string.settings_credits),
                icon = Icons.Filled.Description,
                modifier = Modifier
                    .clickable(onClick = onOpenCredits)
                    .tagged("credits", stringResource(R.string.settings_credits)),
            )
            if (BuildConfig.DEBUG) SimulateAdsRemovedRow(entitlements)

            Text(
                text = stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp).tagged("app_version", stringResource(R.string.settings_version, BuildConfig.VERSION_NAME)),
            )
        }
    }

    if (showMidiSheet) MidiDevicesSheet(midi, onDismiss = { showMidiSheet = false })
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** The switches of the code editor: what it marks, suggests and shows, and whether a web link carries the prebake script. */
@Composable
private fun EditorToggles(settings: EditorSettings) {
    val highlight by settings.highlight.collectAsStateWithLifecycle()
    val flash by settings.flash.collectAsStateWithLifecycle()
    val autocomplete by settings.autocomplete.collectAsStateWithLifecycle()
    val docs by settings.docs.collectAsStateWithLifecycle()
    val sharePrebake by settings.includePrebakeInShare.collectAsStateWithLifecycle()
    SwitchRow("setting_highlight", R.string.settings_highlight, R.string.settings_highlight_summary, highlight, settings::setHighlight)
    SwitchRow("setting_flash", R.string.settings_flash, R.string.settings_flash_summary, flash, settings::setFlash)
    SwitchRow("setting_autocomplete", R.string.settings_autocomplete, R.string.settings_autocomplete_summary, autocomplete, settings::setAutocomplete)
    SwitchRow("setting_docs", R.string.settings_docs, R.string.settings_docs_summary, docs, settings::setDocs)
    SwitchRow("setting_share_prebake", R.string.settings_share_prebake, R.string.settings_share_prebake_summary, sharePrebake, settings::setIncludePrebakeInShare)
}

@Composable
private fun SwitchRow(tag: String, @StringRes label: Int, @StringRes summary: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    val text = stringResource(label)
    ListItem(
        headlineContent = { Text(text) },
        supportingContent = { Text(stringResource(summary)) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .tagged(tag, text),
    )
}

/**
 * The prebake script: code that runs before the first pattern of a session. What is typed is kept at once and runs from the next evaluation on; Reset brings
 * back the script the app starts with. The field follows the stored script until the first edit (the preferences may be read a moment after the screen opens).
 */
@Composable
private fun PrebakeEditor(settings: EditorSettings) {
    val stored by settings.prebake.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf(stored) }
    var edited by remember { mutableStateOf(false) }
    LaunchedEffect(stored) { if (!edited) text = stored }
    val title = stringResource(R.string.settings_prebake_title)
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(
            stringResource(R.string.settings_prebake_summary),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = text,
            onValueChange = {
                edited = true
                text = it
                settings.setPrebake(it)
            },
            minLines = 6,
            maxLines = 12,
            textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp),
            keyboardOptions = KeyboardOptions(
                capitalization = KeyboardCapitalization.None,
                autoCorrectEnabled = false,
                keyboardType = KeyboardType.Ascii,
            ),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("prebake_editor"),
        )
        TextButton(
            onClick = {
                edited = false
                text = Prebake.DEFAULT
                settings.resetPrebake()
            },
            enabled = text != Prebake.DEFAULT,
            modifier = Modifier.tagged("prebake_reset", stringResource(R.string.settings_prebake_reset)),
        ) { Text(stringResource(R.string.settings_prebake_reset)) }
    }
}

/** The purchase row; it is a button only while the product can be bought. */
@Composable
private fun PurchaseRow(headline: String, icon: ImageVector, supporting: String? = null, onClick: (() -> Unit)? = null) {
    val tagged = Modifier.tagged("purchase_row", headline)
    SettingsRow(headline, icon, if (onClick == null) tagged else tagged.clickable(onClick = onClick), supporting)
}

@Composable
private fun SimulateAdsRemovedRow(entitlements: EntitlementRepository) {
    val simulated by entitlements.simulateAdsRemoved.collectAsStateWithLifecycle()
    val label = stringResource(R.string.settings_simulate_ads_removed)
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = { Text(stringResource(R.string.settings_simulate_ads_removed_summary)) },
        trailingContent = { Switch(checked = simulated, onCheckedChange = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier
            .toggleable(value = simulated, role = Role.Switch, onValueChange = entitlements::setSimulateAdsRemoved)
            .tagged("simulate_ads_removed", label),
    )
}

@Composable
private fun SettingsRow(
    headline: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    ListItem(
        headlineContent = { Text(headline) },
        supportingContent = supporting?.let { { Text(it) } },
        leadingContent = { Icon(icon, contentDescription = null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier,
    )
}
