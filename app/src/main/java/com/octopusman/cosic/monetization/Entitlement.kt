package com.octopusman.cosic.monetization

/** Play Console product id of the one-time purchase that removes ads. */
internal const val REMOVE_ADS_PRODUCT_ID = "remove_ads"

internal enum class PurchaseStatus { PURCHASED, PENDING, OTHER }

internal data class PurchaseInfo(
    val productIds: List<String>,
    val state: PurchaseStatus,
    val acknowledged: Boolean,
    val token: String,
)

/**
 * Whether ads are removed, given the result of the latest purchase query.
 * A failed query (`null`) keeps the [cached] value; a successful one is authoritative, so a refunded
 * purchase (no longer listed) revokes the entitlement and a pending one grants nothing yet.
 */
internal fun resolveAdsRemoved(purchases: List<PurchaseInfo>?, cached: Boolean): Boolean =
    purchases?.any { REMOVE_ADS_PRODUCT_ID in it.productIds && it.state == PurchaseStatus.PURCHASED } ?: cached

sealed interface PurchaseUiState {
    data object Loading : PurchaseUiState

    data class Available(val price: String) : PurchaseUiState

    data object Pending : PurchaseUiState

    data object Owned : PurchaseUiState

    data class Unavailable(val message: String) : PurchaseUiState
}
