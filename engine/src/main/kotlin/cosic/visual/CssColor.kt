package cosic.visual

/**
 * CSS colours as a canvas reads them: what the app's [PaintCanvas] does with the strings a program gives `color`, `active`,
 * `inactive`, `playheadColor` and the like. [parse] answers an ARGB int, or null for a string a canvas would not accept (a canvas keeps
 * the style it had then).
 *
 * Understood: `#rgb`, `#rgba`, `#rrggbb`, `#rrggbbaa`; the named colours of packages/draw/color.mjs (the 147 of CSS 3, which are the colours the
 * visuals of Strudel know by name) with `rebeccapurple` of CSS 4 and `transparent`; `rgb()`, `rgba()`, `hsl()` and `hsla()` in the comma form and in the
 * space form with `/` before the alpha, with percentages for the channels and for the alpha. Case and the whitespace around the string do not matter.
 */
object CssColor {
    /** The ARGB int of [text], or null when it is no colour. */
    fun parse(text: String): Int? {
        val t = text.trim().lowercase()
        if (t.isEmpty()) return null
        if (t[0] == '#') return hex(t)
        NAMED[t]?.let { return OPAQUE or it }
        if (t == "transparent") return 0
        val open = t.indexOf('(')
        if (open <= 0 || !t.endsWith(")")) return null
        val args = t.substring(open + 1, t.length - 1)
        return when (t.substring(0, open)) {
            "rgb", "rgba" -> rgb(args)
            "hsl", "hsla" -> hsl(args)
            else -> null
        }
    }

    private const val OPAQUE = 0xFF000000.toInt()

    private fun hex(t: String): Int? {
        val digits = t.substring(1)
        if (digits.any { Character.digit(it, 16) < 0 }) return null
        fun nibble(i: Int): Int = Character.digit(digits[i], 16) * 17
        fun byte(i: Int): Int = Character.digit(digits[i], 16) * 16 + Character.digit(digits[i + 1], 16)
        return when (digits.length) {
            3 -> argb(255, nibble(0), nibble(1), nibble(2))
            4 -> argb(nibble(3), nibble(0), nibble(1), nibble(2))
            6 -> argb(255, byte(0), byte(2), byte(4))
            8 -> argb(byte(6), byte(0), byte(2), byte(4))
            else -> null
        }
    }

    private fun argb(a: Int, r: Int, g: Int, b: Int): Int = (a shl 24) or (r shl 16) or (g shl 8) or b

    /** The three colour arguments and the alpha argument (null when there is none) of a functional colour, or null when the shape is wrong. */
    private fun arguments(args: String): Pair<List<String>, String?>? {
        val slash = args.indexOf('/')
        val colour = (if (slash >= 0) args.substring(0, slash) else args).split(',', ' ', '\t').filter { it.isNotEmpty() }
        val alpha = if (slash >= 0) args.substring(slash + 1).trim().ifEmpty { return null } else null
        return when {
            slash >= 0 -> if (colour.size == 3) colour to alpha else null
            colour.size == 3 -> colour to null
            colour.size == 4 -> colour.subList(0, 3) to colour[3]
            else -> null
        }
    }

    private fun number(s: String): Double? = s.toDoubleOrNull()?.takeIf { it.isFinite() && !s.endsWith("f") && !s.endsWith("d") }

    /** A number, or a percentage of [whole]. */
    private fun scaled(s: String, whole: Double): Double? =
        if (s.endsWith("%")) number(s.dropLast(1))?.let { it / 100.0 * whole } else number(s)

    private fun byteOf(x: Double): Int = Math.floor(x.coerceIn(0.0, 255.0) + 0.5).toInt()

    private fun alphaOf(s: String?): Int? {
        if (s == null) return 255
        val a = scaled(s, 1.0) ?: return null
        return byteOf(a.coerceIn(0.0, 1.0) * 255.0)
    }

    private fun rgb(args: String): Int? {
        val (c, a) = arguments(args) ?: return null
        val r = scaled(c[0], 255.0) ?: return null
        val g = scaled(c[1], 255.0) ?: return null
        val b = scaled(c[2], 255.0) ?: return null
        return argb(alphaOf(a) ?: return null, byteOf(r), byteOf(g), byteOf(b))
    }

    private fun hue(s: String): Double? {
        val units = listOf("deg" to 1.0, "grad" to 0.9, "rad" to 180.0 / Math.PI, "turn" to 360.0)
        for ((suffix, factor) in units) if (s.endsWith(suffix)) return number(s.dropLast(suffix.length))?.times(factor)
        return number(s)
    }

    private fun hsl(args: String): Int? {
        val (c, a) = arguments(args) ?: return null
        val h = hue(c[0]) ?: return null
        val s = scaled(c[1], 1.0)?.coerceIn(0.0, 1.0) ?: return null
        val l = scaled(c[2], 1.0)?.coerceIn(0.0, 1.0) ?: return null
        val hh = ((h % 360.0) + 360.0) % 360.0 / 360.0
        val q = if (l < 0.5) l * (1 + s) else l + s - l * s
        val p = 2 * l - q
        fun channel(t0: Double): Int {
            var t = t0
            if (t < 0) t += 1.0
            if (t > 1) t -= 1.0
            val v = when {
                t < 1.0 / 6 -> p + (q - p) * 6 * t
                t < 0.5 -> q
                t < 2.0 / 3 -> p + (q - p) * (2.0 / 3 - t) * 6
                else -> p
            }
            return byteOf(v * 255.0)
        }
        return argb(alphaOf(a) ?: return null, channel(hh + 1.0 / 3), channel(hh), channel(hh - 1.0 / 3))
    }

    /** `colorMap` of packages/draw/color.mjs, and `rebeccapurple`. */
    private val NAMED: Map<String, Int> = mapOf(
        "aliceblue" to 0xF0F8FF, "antiquewhite" to 0xFAEBD7, "aqua" to 0x00FFFF, "aquamarine" to 0x7FFFD4, "azure" to 0xF0FFFF, "beige" to 0xF5F5DC,
        "bisque" to 0xFFE4C4, "black" to 0x000000, "blanchedalmond" to 0xFFEBCD, "blue" to 0x0000FF, "blueviolet" to 0x8A2BE2, "brown" to 0xA52A2A,
        "burlywood" to 0xDEB887, "cadetblue" to 0x5F9EA0, "chartreuse" to 0x7FFF00, "chocolate" to 0xD2691E, "coral" to 0xFF7F50,
        "cornflowerblue" to 0x6495ED, "cornsilk" to 0xFFF8DC, "crimson" to 0xDC143C, "cyan" to 0x00FFFF, "darkblue" to 0x00008B,
        "darkcyan" to 0x008B8B, "darkgoldenrod" to 0xB8860B, "darkgray" to 0xA9A9A9, "darkgreen" to 0x006400, "darkgrey" to 0xA9A9A9,
        "darkkhaki" to 0xBDB76B, "darkmagenta" to 0x8B008B, "darkolivegreen" to 0x556B2F, "darkorange" to 0xFF8C00, "darkorchid" to 0x9932CC,
        "darkred" to 0x8B0000, "darksalmon" to 0xE9967A, "darkseagreen" to 0x8FBC8F, "darkslateblue" to 0x483D8B, "darkslategray" to 0x2F4F4F,
        "darkslategrey" to 0x2F4F4F, "darkturquoise" to 0x00CED1, "darkviolet" to 0x9400D3, "deeppink" to 0xFF1493, "deepskyblue" to 0x00BFFF,
        "dimgray" to 0x696969, "dimgrey" to 0x696969, "dodgerblue" to 0x1E90FF, "firebrick" to 0xB22222, "floralwhite" to 0xFFFAF0,
        "forestgreen" to 0x228B22, "fuchsia" to 0xFF00FF, "gainsboro" to 0xDCDCDC, "ghostwhite" to 0xF8F8FF, "gold" to 0xFFD700,
        "goldenrod" to 0xDAA520, "gray" to 0x808080, "green" to 0x008000, "greenyellow" to 0xADFF2F, "grey" to 0x808080, "honeydew" to 0xF0FFF0,
        "hotpink" to 0xFF69B4, "indianred" to 0xCD5C5C, "indigo" to 0x4B0082, "ivory" to 0xFFFFF0, "khaki" to 0xF0E68C, "lavender" to 0xE6E6FA,
        "lavenderblush" to 0xFFF0F5, "lawngreen" to 0x7CFC00, "lemonchiffon" to 0xFFFACD, "lightblue" to 0xADD8E6, "lightcoral" to 0xF08080,
        "lightcyan" to 0xE0FFFF, "lightgoldenrodyellow" to 0xFAFAD2, "lightgray" to 0xD3D3D3, "lightgreen" to 0x90EE90, "lightgrey" to 0xD3D3D3,
        "lightpink" to 0xFFB6C1, "lightsalmon" to 0xFFA07A, "lightseagreen" to 0x20B2AA, "lightskyblue" to 0x87CEFA, "lightslategray" to 0x778899,
        "lightslategrey" to 0x778899, "lightsteelblue" to 0xB0C4DE, "lightyellow" to 0xFFFFE0, "lime" to 0x00FF00, "limegreen" to 0x32CD32,
        "linen" to 0xFAF0E6, "magenta" to 0xFF00FF, "maroon" to 0x800000, "mediumaquamarine" to 0x66CDAA, "mediumblue" to 0x0000CD,
        "mediumorchid" to 0xBA55D3, "mediumpurple" to 0x9370DB, "mediumseagreen" to 0x3CB371, "mediumslateblue" to 0x7B68EE,
        "mediumspringgreen" to 0x00FA9A, "mediumturquoise" to 0x48D1CC, "mediumvioletred" to 0xC71585, "midnightblue" to 0x191970,
        "mintcream" to 0xF5FFFA, "mistyrose" to 0xFFE4E1, "moccasin" to 0xFFE4B5, "navajowhite" to 0xFFDEAD, "navy" to 0x000080,
        "oldlace" to 0xFDF5E6, "olive" to 0x808000, "olivedrab" to 0x6B8E23, "orange" to 0xFFA500, "orangered" to 0xFF4500, "orchid" to 0xDA70D6,
        "palegoldenrod" to 0xEEE8AA, "palegreen" to 0x98FB98, "paleturquoise" to 0xAFEEEE, "palevioletred" to 0xDB7093, "papayawhip" to 0xFFEFD5,
        "peachpuff" to 0xFFDAB9, "peru" to 0xCD853F, "pink" to 0xFFC0CB, "plum" to 0xDDA0DD, "powderblue" to 0xB0E0E6, "purple" to 0x800080,
        "red" to 0xFF0000, "rosybrown" to 0xBC8F8F, "royalblue" to 0x4169E1, "saddlebrown" to 0x8B4513, "salmon" to 0xFA8072,
        "sandybrown" to 0xF4A460, "seagreen" to 0x2E8B57, "seashell" to 0xFFF5EE, "sienna" to 0xA0522D, "silver" to 0xC0C0C0, "skyblue" to 0x87CEEB,
        "slateblue" to 0x6A5ACD, "slategray" to 0x708090, "slategrey" to 0x708090, "snow" to 0xFFFAFA, "springgreen" to 0x00FF7F,
        "steelblue" to 0x4682B4, "tan" to 0xD2B48C, "teal" to 0x008080, "thistle" to 0xD8BFD8, "tomato" to 0xFF6347, "turquoise" to 0x40E0D0,
        "violet" to 0xEE82EE, "wheat" to 0xF5DEB3, "white" to 0xFFFFFF, "whitesmoke" to 0xF5F5F5, "yellow" to 0xFFFF00, "yellowgreen" to 0x9ACD32,
        "rebeccapurple" to 0x663399,
    )
}
