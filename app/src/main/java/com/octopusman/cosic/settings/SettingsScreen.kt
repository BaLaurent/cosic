package com.octopusman.cosic.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.octopusman.cosic.BuildConfig
import com.octopusman.cosic.R
import com.octopusman.cosic.monetization.AdsController
import com.octopusman.cosic.monetization.EntitlementRepository
import com.octopusman.cosic.monetization.PurchaseUiState
import com.octopusman.cosic.monetization.UnavailableReason
import com.octopusman.cosic.ui.BackTopBar
import com.octopusman.cosic.ui.findActivity
import com.octopusman.cosic.ui.tagged

/** Purchase and privacy controls, the credits and the app version. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    entitlements: EntitlementRepository,
    ads: AdsController,
    onBack: () -> Unit,
    onOpenCredits: () -> Unit,
) {
    val activity = LocalContext.current.findActivity()
    val purchase by entitlements.purchaseState.collectAsStateWithLifecycle()
    val privacyOptionsRequired by ads.privacyOptionsRequired.collectAsStateWithLifecycle()

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
