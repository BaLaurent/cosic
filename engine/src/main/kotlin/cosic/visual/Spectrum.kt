package cosic.visual

/*
 * The spectrum, port of `drawSpectrum` of packages/webaudio/spectrum.mjs: a picture that scrolls to the left, one column per frame, each bin of the analyser
 * a short dash at the height of its pitch (on a log scale) as bright as it is loud.
 */

/**
 * Scrolls the picture by `speed` and draws the column of this frame in [color]. [db] is the level of each bin in decibels, or null while no sound with the
 * analyser's id has played, which leaves the picture as it is. The surface keeps the picture between frames (Strudel keeps it as image data).
 */
internal fun drawSpectrum(ctx: PaintCanvas, db: FloatArray?, o: VisualOptions, color: String) {
    val thickness = o.num("thickness", 3.0)
    val speed = o.num("speed", 1.0)
    val min = o.num("min", -80.0)
    val max = o.num("max", 0.0)
    ctx.lineWidth = thickness
    ctx.strokeStyle = color
    if (db == null) return
    val scrollSize = speed
    ctx.fillStyle = color
    val bufferSize = db.size
    val height = ctx.height
    ctx.scrollLeft(scrollSize)
    val q = ctx.width - speed
    val logSize = StrictMath.log(bufferSize.toDouble())
    for (i in 0 until bufferSize) {
        val normalized = jsClamp((db[i].toDouble() - min) / (max - min), 0.0, 1.0)
        ctx.globalAlpha = normalized
        val next = (StrictMath.log(i + 1.0) / logSize) * height
        val size = 2.0
        ctx.fillRect(q, height - next, scrollSize, size)
    }
}

/** What the draw loop of a spectrum remembers between frames: the colour (`latestColor[id]`). */
internal class SpectrumMemory {
    var latestColor: String? = null
}
