package com.octopusman.cosic.monetization

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.AdSize
import com.google.android.gms.ads.AdView

/** Anchored adaptive banner as wide as its container. Only compose it while ads are enabled. */
@Composable
fun BannerAd(adUnitId: String, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val context = LocalContext.current
        val adSize = remember(maxWidth) { AdSize.getLargeAnchoredAdaptiveBannerAdSize(context, maxWidth.value.toInt()) }
        val adView = remember(adSize, adUnitId) {
            AdView(context).apply {
                this.adUnitId = adUnitId
                setAdSize(adSize)
            }
        }
        LaunchedEffect(adView) { adView.loadAd(AdRequest.Builder().build()) }

        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(adView, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> adView.resume()
                    Lifecycle.Event.ON_PAUSE -> adView.pause()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                lifecycle.removeObserver(observer)
                adView.destroy()
            }
        }

        // A fixed height keeps the layout from jumping when the ad arrives.
        val height = with(LocalDensity.current) { adSize.getHeightInPixels(context).toDp() }
        AndroidView(factory = { adView }, modifier = Modifier.fillMaxWidth().height(height))
    }
}
