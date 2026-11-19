package com.octopusman.cosic.monetization

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EntitlementResolveTest {
    private fun purchase(state: PurchaseStatus, vararg productIds: String = arrayOf("remove_ads")) =
        PurchaseInfo(productIds.toList(), state, acknowledged = true, token = "token")

    @Test
    fun purchasedRemoveAdsRemovesAds() {
        assertTrue(resolveAdsRemoved(listOf(purchase(PurchaseStatus.PURCHASED)), cached = false))
    }

    @Test
    fun pendingRemoveAdsDoesNotRemoveAds() {
        assertFalse(resolveAdsRemoved(listOf(purchase(PurchaseStatus.PENDING)), cached = false))
    }

    @Test
    fun refundedPurchaseIsAbsentAndRevokesACachedEntitlement() {
        assertFalse(resolveAdsRemoved(emptyList(), cached = true))
    }

    @Test
    fun purchasesOfOtherProductsAreIgnored() {
        assertFalse(resolveAdsRemoved(listOf(purchase(PurchaseStatus.PURCHASED, "something_else")), cached = true))
    }

    @Test
    fun failedQueryKeepsTheCachedValue() {
        assertTrue(resolveAdsRemoved(null, cached = true))
        assertFalse(resolveAdsRemoved(null, cached = false))
    }
}
