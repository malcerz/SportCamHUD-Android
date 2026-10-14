package com.sportcamhud.core.render

import com.sportcamhud.core.layout.IndicatorConfig
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** Compass dial (port of `_render_compass_indicator`): north-up needle or rotating rose. */
object CompassRenderer {
    fun mode(cfg: IndicatorConfig): String {
        val raw = cfg.string("compass_rotation_mode", "").ifBlank { cfg.string("compass_motion_mode", "needle") }
        return if (raw.trim().lowercase() == "rose") "rose" else "needle"
    }

    fun draw(ctx: RenderContext, cfg: IndicatorConfig, heading: Double?) {
        val c = ctx.canvas
        val radius = ctx.s(cfg.double("size", 7.0), ctx.minDim)
        val ringR = radius * 0.95f
        val cx = ctx.px(cfg)
        val cy = ctx.py(cfg)
        val fs = ctx.fontSize(cfg)
        val k = max(1f, radius / 100f)

        val ringColor = Colors.parse(cfg.string("compass_ring_color", "#B8C7D9")) ?: Colors.argb(255, 184, 199, 217)
        val tickColor = Colors.parse(cfg.string("compass_tick_color", "#DDE7F2")) ?: Colors.argb(255, 221, 231, 242)
        val cardinalColor = Colors.parse(cfg.string("compass_cardinal_color", "#FFFFFF")) ?: Colors.WHITE
        val needleColor = Colors.parse(cfg.string("compass_needle_color", "#FFD42A")) ?: Colors.argb(255, 255, 212, 42)
        val headingColor = Colors.parse(cfg.string("compass_heading_color", "#FFFFFF")) ?: Colors.WHITE
        val ringW = cfg.float("compass_ring_width", 1.5f) * k
        val tickW = cfg.float("compass_tick_width", 1.0f) * k
        val majorW = max(tickW, tickW * 1.6f)
        val tickDeg = max(1, cfg.int("compass_tick_degrees", 15))
        val majorDeg = max(tickDeg, cfg.int("compass_major_tick_degrees", 45))
        val majorLen = max(4f, radius * 0.10f)
        val minorLen = max(2f, radius * 0.055f)

        val h = heading?.takeIf { it.isFinite() }?.let { ((it % 360) + 360) % 360 }
        val rose = mode(cfg) == "rose"
        val opacity = cfg.double("opacity", 1.0).coerceIn(0.0, 1.0).toFloat()

        c.save()
        if (cfg.rotation % 360 != 0) c.rotate(-cfg.rotation.toFloat(), cx, cy)
        if (opacity < 1f) c.beginLayer(opacity)

        fun dial(dx: Float, dy: Float, override: Int?) {
            c.save()
            if (rose && h != null) c.rotate(-h.toFloat(), cx, cy)
            c.strokeCircle(cx + dx, cy + dy, ringR, override ?: Colors.withAlpha(ringColor, 235), ringW)
            var d = 0
            while (d < 360) {
                val a = Math.toRadians(d - 90.0)
                val major = d % majorDeg == 0
                val outer = ringR - ringW
                val inner = outer - if (major) majorLen else minorLen
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                c.line(cx + ca * inner + dx, cy + sa * inner + dy, cx + ca * outer + dx, cy + sa * outer + dy,
                    override ?: Colors.withAlpha(tickColor, 245), if (major) majorW else tickW)
                d += tickDeg
            }
            if (cfg.bool("compass_show_cardinals", true)) {
                val lr = ringR - radius * 0.22f
                for ((deg, txt) in listOf(0 to "N", 90 to "E", 180 to "S", 270 to "W")) {
                    val a = Math.toRadians(deg - 90.0)
                    val tx = cx + cos(a).toFloat() * lr + dx
                    val ty = cy + sin(a).toFloat() * lr + dy
                    val style = TextStyle(fs, override ?: cardinalColor, max(1f, ctx.outline / 2f), override ?: Colors.argb(230, 0, 0, 0))
                    if (rose && h != null) {
                        // Keep letters upright while the rose turns.
                        c.save()
                        c.rotate(h.toFloat(), tx, ty)
                        c.text(txt, tx, ty, style, HAlign.CENTER, VAlign.MIDDLE)
                        c.restore()
                    } else {
                        c.text(txt, tx, ty, style, HAlign.CENTER, VAlign.MIDDLE)
                    }
                }
            }
            c.restore()
        }

        fun needle(dx: Float, dy: Float, color: Int) {
            val angDeg = if (rose) 0.0 else (h ?: 0.0)
            val a = Math.toRadians(angDeg - 90.0)
            val ca = cos(a).toFloat()
            val sa = sin(a).toFloat()
            val tip = ringR * cfg.float("compass_needle_length", 0.62f)
            val base = max(2f, ringR * 0.08f)
            val hw = max(1.5f, cfg.float("compass_needle_width", 3f)) * k
            val bx = cx - ca * base
            val by = cy - sa * base
            val xs = floatArrayOf(bx - sa * hw + dx, bx + sa * hw + dx, cx + ca * tip + dx)
            val ys = floatArrayOf(by + ca * hw + dy, by - ca * hw + dy, cy + sa * tip + dy)
            c.fillPolygon(xs, ys, 3, color)
            c.fillCircle(cx + dx, cy + dy, max(2f, ringR * 0.06f), color)
        }

        val shadow = Shadow.from(cfg)
        if (shadow != null) shadow.offsets().forEach { (dx, dy, a) -> dial(dx, dy, Colors.withAlpha(shadow.color, a)) }
        dial(0f, 0f, null)
        if (h != null) {
            if (shadow != null) shadow.offsets().forEach { (dx, dy, a) -> needle(dx, dy, Colors.withAlpha(shadow.color, a)) }
            needle(0f, 0f, needleColor)
            c.strokeCircle(cx, cy, max(2f, ringR * 0.06f), ringColor, max(1f, k))
        }

        if (cfg.bool("compass_show_heading", true)) {
            val txt = if (h == null) "--" else {
                val v = h.roundToInt() % 360
                if (cfg.string("compass_heading_format", "03d") == "d") "$v°" else "%03d°".format(java.util.Locale.ROOT, v)
            }
            c.text(txt, cx, cy + ringR + max(4f, radius * 0.08f), TextStyle(fs, headingColor, max(1f, ctx.outline)), HAlign.CENTER, VAlign.TOP)
        }
        if (opacity < 1f) c.endLayer()
        c.restore()
    }
}
