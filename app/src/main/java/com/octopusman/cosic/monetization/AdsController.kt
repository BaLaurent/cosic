package com.octopusman.cosic.monetization

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.ads.AdError
import com.google.android.gms.ads.AdRequest
import com.google.android.gms.ads.FullScreenContentCallback
import com.google.android.gms.ads.LoadAdError
import com.google.android.gms.ads.MobileAds
import com.google.android.gms.ads.interstitial.InterstitialAd
import com.google.android.gms.ads.interstitial.InterstitialAdLoadCallback
import com.google.android.ump.ConsentDebugSettings
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform
import com.octopusman.cosic.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

private const val TAG = "CosicAds"
private const val RETRY_LOAD_MS = 60_000L

/**
 * Consent, ads SDK start-up and the interstitial. Nothing here touches UMP or the ads SDK until
 * [gatherConsent] is called, which the app only does while ads are not removed.
 * [scope] must run on the main thread, which is also where all calls are expected to come from.
 */
class AdsController(
    context: Context,
    private val adsRemoved: StateFlow<Boolean>,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val pacer = InterstitialPacer(BuildConfig.INTERSTITIAL_MIN_INTERVAL_MS, SystemClock::elapsedRealtime)

    private val canRequestAds = MutableStateFlow(false)
    private val initialized = MutableStateFlow(false)
    private val _privacyOptionsRequired = MutableStateFlow(false)
    private var initializing = false
    private var loadingInterstitial = false
    private var interstitial: InterstitialAd? = null

    val adsEnabled: StateFlow<Boolean> =
        combine(adsRemoved, canRequestAds, initialized) { removed, consented, ready -> !removed && consented && ready }
            .stateIn(scope, SharingStarted.Eagerly, false)

    val privacyOptionsRequired: StateFlow<Boolean> = _privacyOptionsRequired.asStateFlow()

    /** Asks for consent where the law requires it, then starts the ads SDK. */
    fun gatherConsent(activity: Activity) {
        val consent = UserMessagingPlatform.getConsentInformation(activity)
        // Consent stored by an earlier session lets ads start without waiting for the network.
        if (consent.canRequestAds()) syncConsent(consent)
        consent.requestConsentInfoUpdate(
            activity,
            requestParameters(activity),
            { UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { syncConsent(consent) } },
            { syncConsent(consent) },
        )
    }

    fun showPrivacyOptions(activity: Activity) {
        UserMessagingPlatform.showPrivacyOptionsForm(activity) {
            syncConsent(UserMessagingPlatform.getConsentInformation(activity))
        }
    }

    /** Shows the interstitial when leaving a project, unless playback was running or one was shown recently. */
    fun maybeShowInterstitial(activity: Activity, wasPlaying: Boolean) {
        if (!adsEnabled.value) return
        val ad = interstitial
        if (ad == null || !pacer.shouldShow(wasPlaying)) {
            Log.i(TAG, "interstitial skipped wasPlaying=$wasPlaying loaded=${ad != null} sinceLastMs=${pacer.sinceLastShownMs()}")
            return
        }
        interstitial = null
        ad.fullScreenContentCallback = object : FullScreenContentCallback() {
            override fun onAdShowedFullScreenContent() {
                Log.i(TAG, "interstitial shown")
                pacer.markShown()
            }

            override fun onAdDismissedFullScreenContent() = loadInterstitial()

            override fun onAdFailedToShowFullScreenContent(error: AdError) {
                Log.w(TAG, "interstitial failed to show: ${error.message}")
                loadInterstitial()
            }
        }
        ad.show(activity)
    }

    private fun requestParameters(activity: Activity): ConsentRequestParameters {
        val parameters = ConsentRequestParameters.Builder()
        if (BuildConfig.DEBUG) {
            // Pretend to be outside the EEA, so Google's test app id needs no configured consent message.
            parameters.setConsentDebugSettings(
                ConsentDebugSettings.Builder(activity)
                    .setDebugGeography(ConsentDebugSettings.DebugGeography.DEBUG_GEOGRAPHY_OTHER)
                    .build(),
            )
        }
        return parameters.build()
    }

    private fun syncConsent(consent: ConsentInformation) {
        val consented = consent.canRequestAds()
        Log.i(TAG, "consent canRequestAds=$consented")
        canRequestAds.value = consented
        _privacyOptionsRequired.value =
            consent.privacyOptionsRequirementStatus == ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
        if (consented) startAds()
    }

    // The ads SDK starts once per process; a repeat call (ads are wanted again after an entitlement
    // change) only makes sure an interstitial is on its way.
    private fun startAds() {
        if (initialized.value) return loadInterstitial()
        if (initializing) return
        initializing = true
        scope.launch(Dispatchers.IO) {
            MobileAds.initialize(appContext) {
                scope.launch {
                    Log.i(TAG, "ads initialized")
                    initialized.value = true
                    loadInterstitial()
                }
            }
        }
    }

    private fun loadInterstitial() {
        if (adsRemoved.value || interstitial != null || loadingInterstitial) return
        loadingInterstitial = true
        InterstitialAd.load(
            appContext,
            BuildConfig.ADMOB_INTERSTITIAL_ID,
            AdRequest.Builder().build(),
            object : InterstitialAdLoadCallback() {
                override fun onAdLoaded(ad: InterstitialAd) {
                    Log.i(TAG, "interstitial loaded")
                    loadingInterstitial = false
                    interstitial = ad
                }

                override fun onAdFailedToLoad(error: LoadAdError) {
                    Log.w(TAG, "interstitial load failed: ${error.message}")
                    loadingInterstitial = false
                    scope.launch {
                        delay(RETRY_LOAD_MS)
                        loadInterstitial()
                    }
                }
            },
        )
    }
}
