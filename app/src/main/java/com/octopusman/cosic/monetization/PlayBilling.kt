package com.octopusman.cosic.monetization

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.queryProductDetails
import com.android.billingclient.api.queryPurchasesAsync
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** How the purchase flow ended, as far as [EntitlementRepository] needs to tell outcomes apart. */
internal sealed interface BillingUpdate {
    data object PurchasesChanged : BillingUpdate

    data object Canceled : BillingUpdate

    data object AlreadyOwned : BillingUpdate

    data class Failed(val message: String) : BillingUpdate
}

/** The `remove_ads` offer as shown to the user, plus what is needed to launch its purchase flow. */
internal class Offer(val price: String, val details: ProductDetails, val offerToken: String)

/** Wraps [BillingClient]; the only place that touches Play Billing types. */
internal class PlayBilling(context: Context, private val onUpdate: (BillingUpdate) -> Unit) {
    private val client = BillingClient.newBuilder(context)
        .setListener { result, _ -> onUpdate(result.toUpdate()) }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .build()

    private val connectLock = Mutex()

    /** Connects to the store; false when it cannot be reached (for example no Play Store installed). */
    suspend fun connect(): Boolean = connectLock.withLock {
        client.isReady || suspendCancellableCoroutine { continuation ->
            client.startConnection(object : BillingClientStateListener {
                override fun onBillingSetupFinished(result: BillingResult) {
                    if (continuation.isActive) continuation.resume(result.responseCode == BillingResponseCode.OK)
                }

                // Reconnecting after a drop is handled by enableAutoServiceReconnection().
                override fun onBillingServiceDisconnected() = Unit
            })
        }
    }

    /** The purchases the user currently owns, or null when the store did not answer. */
    suspend fun queryOwned(): List<PurchaseInfo>? {
        val result = client.queryPurchasesAsync(
            QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build(),
        )
        if (result.billingResult.responseCode != BillingResponseCode.OK) return null
        return result.purchasesList.map { it.toInfo() }
    }

    /** The `remove_ads` offer, or null when the product is missing or the store did not answer. */
    suspend fun offer(): Offer? {
        val product = QueryProductDetailsParams.Product.newBuilder()
            .setProductId(REMOVE_ADS_PRODUCT_ID)
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
        val result = client.queryProductDetails(
            QueryProductDetailsParams.newBuilder().setProductList(listOf(product)).build(),
        )
        if (result.billingResult.responseCode != BillingResponseCode.OK) return null
        val details = result.productDetailsList?.firstOrNull() ?: return null
        val offer = details.oneTimePurchaseOfferDetailsList?.firstOrNull() ?: return null
        val token = offer.offerToken ?: return null
        return Offer(offer.formattedPrice, details, token)
    }

    /** Opens the purchase sheet; the outcome arrives through [onUpdate]. */
    fun launch(activity: Activity, offer: Offer) {
        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(
                listOf(
                    BillingFlowParams.ProductDetailsParams.newBuilder()
                        .setProductDetails(offer.details)
                        .setOfferToken(offer.offerToken)
                        .build(),
                ),
            )
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode != BillingResponseCode.OK) onUpdate(result.toUpdate())
    }

    suspend fun acknowledge(token: String): Boolean {
        val result = client.acknowledgePurchase(AcknowledgePurchaseParams.newBuilder().setPurchaseToken(token).build())
        return result.responseCode == BillingResponseCode.OK
    }
}

private fun BillingResult.toUpdate(): BillingUpdate = when (responseCode) {
    BillingResponseCode.OK -> BillingUpdate.PurchasesChanged
    BillingResponseCode.USER_CANCELED -> BillingUpdate.Canceled
    BillingResponseCode.ITEM_ALREADY_OWNED -> BillingUpdate.AlreadyOwned
    else -> BillingUpdate.Failed(debugMessage)
}

private fun Purchase.toInfo() = PurchaseInfo(
    productIds = products,
    state = when (purchaseState) {
        Purchase.PurchaseState.PURCHASED -> PurchaseStatus.PURCHASED
        Purchase.PurchaseState.PENDING -> PurchaseStatus.PENDING
        else -> PurchaseStatus.OTHER
    },
    acknowledged = isAcknowledged,
    token = purchaseToken,
)
