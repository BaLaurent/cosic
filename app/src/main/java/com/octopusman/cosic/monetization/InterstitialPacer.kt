package com.octopusman.cosic.monetization

/**
 * Decides when leaving a project may show an interstitial: never right after playback, and at most
 * once per [minIntervalMs]. The first interval is counted from construction, i.e. from app start.
 * Used from the main thread only.
 */
class InterstitialPacer(private val minIntervalMs: Long, private val now: () -> Long) {
    private var lastShown = now()

    fun sinceLastShownMs(): Long = now() - lastShown

    fun shouldShow(wasPlaying: Boolean): Boolean = !wasPlaying && sinceLastShownMs() >= minIntervalMs

    fun markShown() {
        lastShown = now()
    }
}
