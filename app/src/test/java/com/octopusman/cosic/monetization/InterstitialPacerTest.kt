package com.octopusman.cosic.monetization

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InterstitialPacerTest {
    private var clock = 5_000L
    private val pacer = InterstitialPacer(minIntervalMs = 180_000L) { clock }

    @Test
    fun noInterstitialWhilePlaybackWasRunning() {
        clock += 10 * 180_000L
        assertFalse(pacer.shouldShow(wasPlaying = true))
    }

    @Test
    fun noInterstitialBeforeTheIntervalSinceStart() {
        clock += 179_999L
        assertFalse(pacer.shouldShow(wasPlaying = false))
    }

    @Test
    fun interstitialOnceTheIntervalSinceStartHasPassed() {
        clock += 180_000L
        assertTrue(pacer.shouldShow(wasPlaying = false))
    }

    @Test
    fun intervalRestartsAfterAnInterstitialWasShown() {
        clock += 200_000L
        assertTrue(pacer.shouldShow(wasPlaying = false))

        pacer.markShown()
        clock += 179_999L
        assertFalse(pacer.shouldShow(wasPlaying = false))

        clock += 1L
        assertTrue(pacer.shouldShow(wasPlaying = false))
    }
}
