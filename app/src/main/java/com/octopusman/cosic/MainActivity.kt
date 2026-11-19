package com.octopusman.cosic

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.octopusman.cosic.nav.CosicNavHost
import com.octopusman.cosic.ui.CosicTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val app get() = application as CosicApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The app is dark whatever the system theme, so the system bars always get light icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        gatherConsentWhenAdsAreWanted()
        setContent { CosicTheme { CosicNavHost(app) } }
    }

    override fun onResume() {
        super.onResume()
        app.entitlements.refresh()
    }

    // Ads, and with them the consent flow, are only started for a user who has not removed them:
    // right away when the first value says so, and again whenever ads come back (for example after
    // a refund).
    private fun gatherConsentWhenAdsAreWanted() {
        lifecycleScope.launch {
            var removed = true
            app.entitlements.adsRemoved.collect { nowRemoved ->
                if (removed && !nowRemoved) app.ads.gatherConsent(this@MainActivity)
                removed = nowRemoved
            }
        }
    }
}
