package cosic.visual

/**
 * A [PaintCanvas] that only writes down what it is told, in the form the oracle `tools/strudel-ref/visual-oracle.mjs` records the calls of Strudel's canvas context:
 * `["set", property, value]` and `["call", method, [arguments]]`. As there, a `lineTo` that starts a path is recorded as a `moveTo` (a canvas treats it so),
 * and `measureText(text)` is `10 * text.length`.
 */
class RecordingCanvas(override val width: Double, override val height: Double) : PaintCanvas {
    val calls = ArrayList<List<Any?>>()
    private var hasPoint = false

    private fun set(property: String, value: Any) {
        calls.add(listOf("set", property, value))
    }

    private fun call(method: String, vararg args: Any) {
        calls.add(listOf("call", method, args.toList()))
    }

    override var fillStyle: String = ""
        set(value) {
            field = value
            set("fillStyle", value)
        }
    override var strokeStyle: String = ""
        set(value) {
            field = value
            set("strokeStyle", value)
        }
    override var globalAlpha: Double = 1.0
        set(value) {
            field = value
            set("globalAlpha", value)
        }
    override var lineWidth: Double = 1.0
        set(value) {
            field = value
            set("lineWidth", value)
        }
    override var lineCap: String = "butt"
        set(value) {
            field = value
            set("lineCap", value)
        }
    override var font: String = ""
        set(value) {
            field = value
            set("font", value)
        }
    override var textBaseline: String = "alphabetic"
        set(value) {
            field = value
            set("textBaseline", value)
        }

    override fun clearRect(x: Double, y: Double, w: Double, h: Double) = call("clearRect", x, y, w, h)

    override fun fillRect(x: Double, y: Double, w: Double, h: Double) = call("fillRect", x, y, w, h)

    override fun strokeRect(x: Double, y: Double, w: Double, h: Double) = call("strokeRect", x, y, w, h)

    override fun fillText(text: String, x: Double, y: Double, maxWidth: Double?) {
        if (maxWidth == null) call("fillText", text, x, y) else call("fillText", text, x, y, maxWidth)
    }

    override fun measureText(text: String): Double = 10.0 * text.length

    override fun beginPath() {
        hasPoint = false
        call("beginPath")
    }

    override fun moveTo(x: Double, y: Double) {
        hasPoint = true
        call("moveTo", x, y)
    }

    override fun lineTo(x: Double, y: Double) {
        if (!hasPoint) {
            hasPoint = true
            call("moveTo", x, y)
        } else {
            call("lineTo", x, y)
        }
    }

    override fun arc(x: Double, y: Double, r: Double, start: Double, end: Double) {
        hasPoint = true
        call("arc", x, y, r, start, end)
    }

    override fun closePath() = call("closePath")

    override fun stroke() = call("stroke")

    override fun fill() = call("fill")

    override fun scrollLeft(px: Double) = call("scrollLeft", px)

    /** The calls as short text, one per line: `fillRect(201, 51, 98, 48)`, `fillStyle = #fff`. For messages and for tests written by hand. */
    fun text(): List<String> = calls.map { entry ->
        if (entry[0] == "set") "${entry[1]} = ${entry[2].let { if (it is Double) number(it) else it }}"
        else "${entry[1]}(${(entry[2] as List<*>).joinToString(", ") { if (it is Double) number(it) else it.toString() }})"
    }

    private fun number(d: Double): String = cosic.pattern.jsNumberToString(d)
}
