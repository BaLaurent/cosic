package com.octopusman.cosic.monetization

import android.view.View
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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

/**
 * Anchored adaptive banner as wide as its container. Only compose it while ads are enabled.
 * While [paused] (the banner is hidden) the AdView is paused and made INVISIBLE: pause() alone does
 * not stop its automatic refresh (measured on the emulator), INVISIBLE does, so a hidden banner makes
 * no ad requests and counts no impressions. GONE would also stop it, but the collapsed AdView then
 * stays 0x0 after it is shown again. It resumes when [paused] goes back to false.
 */
@Composable
fun BannerAd(adUnitId: String, paused: Boolean = false, modifier: Modifier = Modifier) {
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
        val currentPaused by rememberUpdatedState(paused)
        DisposableEffect(adView, lifecycle) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> if (!currentPaused) adView.resume()
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

        LaunchedEffect(adView, paused) {
            if (paused) adView.pause() else if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) adView.resume()
        }

        // A fixed height keeps the layout from jumping when the ad arrives.
        val height = with(LocalDensity.current) { adSize.getHeightInPixels(context).toDp() }
        AndroidView(factory = { adView }, update = { it.visibility = if (paused) View.INVISIBLE else View.VISIBLE }, modifier = Modifier.fillMaxWidth().height(height))
    }
}
