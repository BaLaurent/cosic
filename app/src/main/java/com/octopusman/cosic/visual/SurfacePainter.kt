package com.octopusman.cosic.visual

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import cosic.visual.VisualSession
import cosic.visual.VisualSurface

/**
 * A bitmap that shows one [surface] of a [session]: [refresh] paints the session's current picture into it when the surface changed since the last time. The canvas is
 * [width] x [height] logical px at [density] pixels per px. Call everything from the thread that ticks the session.
 */
internal class SurfacePainter(
    private val session: VisualSession,
    private val surface: VisualSurface,
    private var width: Double,
    private var height: Double,
    private val density: Float,
) {
    private var canvas = BitmapPaintCanvas(width, height, density)
    private var seen = -1L

    /** The picture, always the same object for the same size. */
    var image: ImageBitmap = canvas.bitmap.asImageBitmap()
        private set

    /** Makes the canvas [w] x [h] logical px (the background follows the size of the editor); a new size starts from an empty picture that is painted in full. */
    fun resize(w: Double, h: Double) {
        if (w == width && h == height) return
        width = w
        height = h
        canvas = BitmapPaintCanvas(w, h, density)
        image = canvas.bitmap.asImageBitmap()
        seen = -1L
    }

    /** Paints when [VisualSession.versionOf] says the surface changed. Returns whether it painted. */
    fun refresh(): Boolean {
        val version = session.versionOf(surface)
        if (version == seen) return false
        session.paint(surface, canvas)
        seen = version
        return true
    }
}
