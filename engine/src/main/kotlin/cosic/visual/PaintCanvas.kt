package cosic.visual

/**
 * The part of the HTML canvas 2D context that Strudel's visuals draw with, in the units of that context: [width] and [height]
 * are the size of the surface in logical pixels (dp in the app; `ctx.canvas.width` at a pixel ratio of 1), and a call does what
 * the call of the same name does on a canvas. The renderers of this package ([drawPianoroll], [drawSpiral], ...) are written against it, so
 * they run unchanged on the bitmap of the app and on the recording surface of the tests.
 *
 * The style properties take CSS strings. An implementation parses what it is given ([CssColor]) and **ignores** a value it cannot
 * parse, keeping the previous one, as a canvas does; [globalAlpha] multiplies the alpha of the colour. A surface is private to one
 * visual or to one widget: unlike Strudel's shared background canvas, its state does not leak into other visuals.
 */
interface PaintCanvas {
    val width: Double
    val height: Double

    var fillStyle: String
    var strokeStyle: String
    var globalAlpha: Double
    var lineWidth: Double

    /** `butt`, `round` or `square`. */
    var lineCap: String

    /** A CSS font such as `12px monospace`: the size and the family are what an implementation needs. */
    var font: String

    /** `top` is the only baseline the renderers set; the default is the alphabetic one. */
    var textBaseline: String

    fun clearRect(x: Double, y: Double, w: Double, h: Double)

    fun fillRect(x: Double, y: Double, w: Double, h: Double)

    fun strokeRect(x: Double, y: Double, w: Double, h: Double)

    /** Draws [text] with its left edge at [x]; text wider than [maxWidth] is squeezed horizontally to fit, and nothing is drawn when [maxWidth] is zero or less. */
    fun fillText(text: String, x: Double, y: Double, maxWidth: Double? = null)

    /** The width the text takes in the current [font]. */
    fun measureText(text: String): Double

    fun beginPath()

    fun moveTo(x: Double, y: Double)

    fun lineTo(x: Double, y: Double)

    /** A clockwise arc of the circle at ([x], [y]), from the angle [start] to [end] in radians. */
    fun arc(x: Double, y: Double, r: Double, start: Double, end: Double)

    fun closePath()

    fun stroke()

    fun fill()

    /** Moves everything drawn so far [px] to the left; what is uncovered on the right is transparent. The spectrum scrolls this way. */
    fun scrollLeft(px: Double)
}
