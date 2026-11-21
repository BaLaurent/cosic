package com.octopusman.cosic

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.octopusman.cosic.nav.CosicNavHost
import com.octopusman.cosic.share.sharedTextOf
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
        app.reattachDevices()
        gatherConsentWhenAdsAreWanted()
        setContent { CosicTheme { CosicNavHost(app) } }
        // A link that started the app is imported once: an activity that is made again (a rotation) already did.
        if (savedInstanceState == null) takeSharedText(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeSharedText(intent)
    }

    override fun onResume() {
        super.onResume()
        app.entitlements.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Closing the app (not a rotation) lets go of the devices that hold things open.
        if (isFinishing) app.releaseDevices()
    }

    // The keys and the sticks of game controllers are what the signals of a program read (`keyDown`, `gamepad`). Both only observe or take events that belong
    // to a controller while a program uses one, so typing and touching work as they do.
    // (the base method of ComponentActivity is restricted to its library group, which is what lint objects to when it is overridden)
    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (app.engineCreated) app.keyTracker.observe(event)
        return app.gamepadHub.onKeyEvent(event) || super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean =
        app.gamepadHub.onGenericMotionEvent(ev) || super.dispatchGenericMotionEvent(ev)

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // the key-up events of the keys that are held now go to another window
        if (!hasFocus && app.engineCreated) app.keyTracker.releaseAll()
    }

    /** Hands over the pattern a link or another app brought, as text; the navigation host imports it and opens the project. */
    private fun takeSharedText(intent: Intent) {
        val text = sharedTextOf(intent) ?: return
        app.offerSharedText(text)
        // taken: starting this activity over from the same intent (the system may) must not import it a second time
        setIntent(Intent(this, MainActivity::class.java))
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
