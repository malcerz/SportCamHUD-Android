package com.sportcamhud.core.render

/** Horizontal text anchor. */
enum class HAlign { LEFT, CENTER, RIGHT }

/** Vertical text anchor: TOP = top of capital letters, MIDDLE = optical centre, BASELINE. */
enum class VAlign { TOP, MIDDLE, BASELINE, BOTTOM }

data class TextStyle(
    val sizePx: Float,
    val color: Int = Colors.WHITE,
    val strokeWidth: Float = 0f,
    val strokeColor: Int = Colors.BLACK,
    val bold: Boolean = false,
)

/** A decoded bitmap (map tile, icon) owned by the platform canvas implementation. */
interface HudImage {
    val width: Int
    val height: Int
}

/**
 * Minimal drawing surface used by every indicator renderer. Implemented with
 * android.graphics.Canvas on the device and java.awt.Graphics2D in unit tests,
 * so preview, export and tests share one rendering code path.
 */
interface HudCanvas {
    val width: Int
    val height: Int

    fun save()
    fun restore()
    fun translate(dx: Float, dy: Float)
    fun rotate(degrees: Float, px: Float, py: Float)

    /** Starts an offscreen group composited with [alpha] at [endLayer]. */
    fun beginLayer(alpha: Float)
    fun endLayer()

    fun clipRect(l: Float, t: Float, r: Float, b: Float)
    fun clipCircle(cx: Float, cy: Float, radius: Float)

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, roundCap: Boolean = false)
    fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float, closed: Boolean = false)
    fun fillPolygon(xs: FloatArray, ys: FloatArray, count: Int, color: Int)
    fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int)
    fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float)
    fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int, cornerRadius: Float = 0f)
    fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, cornerRadius: Float = 0f)

    /** Width of [text] in pixels at [sizePx]. */
    fun textWidth(text: String, sizePx: Float, bold: Boolean = false): Float

    /** Ascent of capital letters/digits (positive) at [sizePx]. */
    fun capHeight(sizePx: Float): Float

    /** Draws text with an optional outline stroke (the black "stroke" of the Windows HUD). */
    fun text(text: String, x: Float, y: Float, style: TextStyle, hAlign: HAlign = HAlign.LEFT, vAlign: VAlign = VAlign.TOP)

    fun image(image: HudImage, l: Float, t: Float, r: Float, b: Float, alpha: Float = 1f)
}

object Colors {
    const val WHITE = -0x1
    const val BLACK = -0x1000000
    const val TRANSPARENT = 0

    fun argb(a: Int, r: Int, g: Int, b: Int): Int =
        (a.coerceIn(0, 255) shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)
    fun alpha(color: Int) = (color ushr 24) and 0xFF
    fun red(color: Int) = (color shr 16) and 0xFF
    fun green(color: Int) = (color shr 8) and 0xFF
    fun blue(color: Int) = color and 0xFF

    /** Parses `#RRGGBB`, `#AARRGGBB`, `RRGGBB` or `(r, g, b)`; null when invalid. */
    fun parse(text: String?, alpha: Int = 255): Int? {
        if (text.isNullOrBlank()) return null
        val s = text.trim()
        if (s.startsWith("(") || s.startsWith("[")) {
            val parts = s.trim('(', ')', '[', ']').split(',').mapNotNull { it.trim().toIntOrNull() }
            if (parts.size >= 3) return argb(parts.getOrElse(3) { alpha }, parts[0], parts[1], parts[2])
            return null
        }
        val hex = s.removePrefix("#")
        return when (hex.length) {
            6 -> hex.toLongOrNull(16)?.let { (alpha shl 24) or it.toInt() }
            8 -> hex.toLongOrNull(16)?.toInt()
            3 -> hex.map { "$it$it" }.joinToString("").toLongOrNull(16)?.let { (alpha shl 24) or it.toInt() }
            else -> null
        }
    }

    fun toHex(color: Int): String = "#%06X".format(java.util.Locale.ROOT, color and 0xFFFFFF)

    fun lerp(a: Int, b: Int, t: Float): Int {
        val f = t.coerceIn(0f, 1f)
        fun ch(x: Int, y: Int) = (x + (y - x) * f).toInt()
        return argb(ch(alpha(a), alpha(b)), ch(red(a), red(b)), ch(green(a), green(b)), ch(blue(a), blue(b)))
    }
}
