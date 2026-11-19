package com.octopusman.cosic.monetization

import android.app.Activity
import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.octopusman.cosic.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "CosicBilling"
private const val RETRY_AFTER_FAILURE_MS = 5_000L

private val ADS_REMOVED_CACHED = booleanPreferencesKey("ads_removed_cached")
private val DEBUG_SIMULATE_ADS_REMOVED = booleanPreferencesKey("debug_simulate_ads_removed")

private fun effectiveAdsRemoved(entitled: Boolean, simulated: Boolean) = entitled || (BuildConfig.DEBUG && simulated)

/**
 * Whether the user bought `remove_ads`, and the state of that purchase for the Settings screen.
 * [scope] must run on the main thread, which is also where all calls are expected to come from.
 */
class EntitlementRepository(
    context: Context,
    private val prefs: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val billing = PlayBilling(context, ::onBillingUpdate)
    private val refreshLock = Mutex()
    private var offer: Offer? = null

    // Read synchronously: the first value of [adsRemoved] must already be right, so that a paying
    // user never starts the consent flow or the ads SDK, even while the store is still being asked.
    private val persisted = runBlocking { prefs.data.first() }
    private val entitled = MutableStateFlow(persisted[ADS_REMOVED_CACHED] ?: false)
    private val simulated = MutableStateFlow(persisted[DEBUG_SIMULATE_ADS_REMOVED] ?: false)
    private val _purchaseState = MutableStateFlow<PurchaseUiState>(PurchaseUiState.Loading)

    val adsRemoved: StateFlow<Boolean> = combine(entitled, simulated, ::effectiveAdsRemoved)
        .stateIn(scope, SharingStarted.Eagerly, effectiveAdsRemoved(entitled.value, simulated.value))

    val purchaseState: StateFlow<PurchaseUiState> = _purchaseState.asStateFlow()

    /** Debug builds only: behave as if ads were removed. */
    val simulateAdsRemoved: StateFlow<Boolean> = simulated.asStateFlow()

    fun setSimulateAdsRemoved(enabled: Boolean) {
        simulated.value = enabled
        scope.launch { prefs.edit { it[DEBUG_SIMULATE_ADS_REMOVED] = enabled } }
    }

    /** Re-reads purchases from the store: at app start, on resume and from "Restore purchase". */
    fun refresh() {
        scope.launch { refreshLock.withLock { sync() } }
    }

    fun launchPurchase(activity: Activity) {
        val offer = offer ?: return
        _purchaseState.value = PurchaseUiState.Loading
        billing.launch(activity, offer)
    }

    private suspend fun sync() {
        if (!billing.connect()) return unavailable(UnavailableReason.STORE_UNREACHABLE)
        val purchases = billing.queryOwned() ?: return unavailable(UnavailableReason.PURCHASES_UNREADABLE)

        setEntitled(resolveAdsRemoved(purchases, entitled.value))
        val removeAds = purchases.filter { REMOVE_ADS_PRODUCT_ID in it.productIds }
        for (purchase in removeAds) {
            if (purchase.state == PurchaseStatus.PURCHASED && !purchase.acknowledged && !billing.acknowledge(purchase.token)) {
                Log.w(TAG, "acknowledge failed, retrying on the next refresh")
            }
        }
        _purchaseState.value = when {
            removeAds.any { it.state == PurchaseStatus.PURCHASED } -> PurchaseUiState.Owned
            removeAds.any { it.state == PurchaseStatus.PENDING } -> PurchaseUiState.Pending
            else -> {
                offer = billing.offer()
                offer?.let { PurchaseUiState.Available(it.price) } ?: PurchaseUiState.Unavailable(UnavailableReason.PRODUCT_MISSING)
            }
        }
    }

    private suspend fun setEntitled(value: Boolean) {
        if (entitled.value == value) return
        entitled.value = value
        prefs.edit { it[ADS_REMOVED_CACHED] = value }
    }

    // A paying user offline keeps seeing "owned": the cached entitlement still applies.
    private fun unavailable(reason: UnavailableReason) {
        Log.w(TAG, "store unavailable: $reason")
        _purchaseState.value = if (entitled.value) PurchaseUiState.Owned else PurchaseUiState.Unavailable(reason)
    }

    private fun onBillingUpdate(update: BillingUpdate) {
        when (update) {
            BillingUpdate.PurchasesChanged, BillingUpdate.AlreadyOwned -> refresh()
            BillingUpdate.Canceled -> offer?.let { _purchaseState.value = PurchaseUiState.Available(it.price) } ?: refresh()
            is BillingUpdate.Failed -> {
                Log.w(TAG, "purchase failed: ${update.message}")
                _purchaseState.value = PurchaseUiState.Unavailable(UnavailableReason.PURCHASE_FAILED)
                scope.launch {
                    delay(RETRY_AFTER_FAILURE_MS)
                    refresh()
                }
            }
        }
    }
}
