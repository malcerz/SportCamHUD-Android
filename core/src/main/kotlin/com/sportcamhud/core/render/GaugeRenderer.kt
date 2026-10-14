package com.sportcamhud.core.render

import com.sportcamhud.core.layout.IndicatorConfig
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin
import java.util.Locale

/** Speedometer-style gauge (port of `indicators/gauge.py`, needle/tick geometry kept). */
object GaugeRenderer {

    data class Scale(val min: Double, val max: Double, val step: Double, val majors: Int, val subTicks: Int, val total: Int)

    /** `_gauge_ticks`: rounds the scale up to "nice" major steps. */
    fun scale(displayMin: Double, rawMax: Double, ticks: Int): Scale {
        var dMax = if (rawMax > 0) ceil(rawMax / 10.0) * 10 else 100.0
        if (dMax <= displayMin) dMax = displayMin + 10.0
        val span0 = dMax - displayMin
        val step = when {
            span0 <= 15 -> if (span0 <= 5) 1.0 else 5.0
            span0 <= 60 -> 10.0
            span0 <= 140 -> 20.0
            span0 <= 300 -> 50.0
            else -> 100.0
        }
        val majors = max(1, ceil(span0 / step).toInt())
        dMax = displayMin + majors * step
        val sub = if (ticks > 0) max(1, ticks) else 10
        return Scale(displayMin, dMax, step, majors, sub, majors * sub)
    }

    fun draw(ctx: RenderContext, cfg: IndicatorConfig, value: Double?, formatted: String?) {
        val c = ctx.canvas
        val radius = ctx.s(cfg.double("size", 10.8), ctx.minDim)
        val cx = ctx.px(cfg)
        val cy = ctx.py(cfg)
        val fs = ctx.fontSize(cfg)
        val startDeg = cfg.double("start_angle", 180.0)
        val sweepDeg = cfg.double("sweep_angle", 180.0)

        val displayMin = cfg.doubleOrNull("min_val")?.let { floor(it / 10.0) * 10 } ?: 0.0
        val rawMax = cfg.doubleOrNull("max_val") ?: 100.0
        val sc = scale(displayMin, rawMax, cfg.int("ticks", 0))

        // Tick geometry: thickness 1..10 maps to 0.6..2.4 % of min_dim (dispatcher contract).
        val legacy = cfg.double("thickness", 3.0)
        fun factor(raw: Double) = max(1f, ctx.s(0.6 + (raw - 1) * 0.2, ctx.minDim))
        val majLen = factor(cfg.double("major_tick_length", legacy))
        val majThick = factor(cfg.double("major_tick_thickness", legacy))
        val minLen = factor(cfg.double("minor_tick_length", legacy))
        val minThick = factor(cfg.double("minor_tick_thickness", legacy))
        val midLen = (majLen + minLen) / 2f
        val midThick = (majThick + minThick) / 2f

        val opacity = cfg.double("opacity", 1.0).coerceIn(0.0, 1.0).toFloat()
        val rotation = cfg.rotation
        c.save()
        if (rotation % 360 != 0) c.rotate(-rotation.toFloat(), cx, cy)
        if (opacity < 1f) c.beginLayer(opacity)

        val outlineEnabled = cfg.bool("outline_enabled", false)
        val outlineW = cfg.float("outline_width", 1f)
        val outlineColor = Colors.withAlpha(
            Colors.parse(cfg.string("outline_color", "#000000")) ?: Colors.BLACK,
            (pct(cfg.double("outline_opacity", 0.8)) * 255).roundToInt(),
        )

        fun drawDial(dx: Float, dy: Float, override: Int?) {
            for (i in 0..sc.total) {
                val a = Math.toRadians(startDeg + sweepDeg * i / sc.total)
                val ca = cos(a).toFloat()
                val sa = sin(a).toFloat()
                val len: Float
                val w: Float
                if (i % sc.subTicks == 0) {
                    len = majLen * 1.4f
                    w = max(3f, (majThick * 0.8f).toInt().toFloat())
                    val v = sc.min + (sc.max - sc.min) * i / sc.total
                    val tr = radius - len - radius * 0.16f
                    val style = if (outlineEnabled && outlineW > 0) {
                        TextStyle(fs, override ?: Colors.argb(240, 255, 255, 255), minOf(fs * 0.28f, outlineW), override ?: outlineColor)
                    } else {
                        TextStyle(fs, override ?: Colors.argb(240, 255, 255, 255), 1f, override ?: Colors.BLACK)
                    }
                    c.text("%.0f".format(Locale.ROOT, v), cx + ca * tr + dx, cy + sa * tr + dy, style, HAlign.CENTER, VAlign.MIDDLE)
                } else if (sc.subTicks % 2 == 0 && i % (sc.subTicks / 2) == 0) {
                    len = midLen * 0.9f
                    w = max(2f, (midThick * 0.5f).toInt().toFloat())
                } else {
                    len = minLen * 0.5f
                    w = max(1f, (minThick * 0.3f).toInt().toFloat())
                }
                val rOut = radius
                val rIn = radius - len
                if (outlineEnabled && outlineW > 0 && override == null) {
                    c.line(cx + ca * (rIn - outlineW) + dx, cy + sa * (rIn - outlineW) + dy,
                        cx + ca * (rOut + outlineW) + dx, cy + sa * (rOut + outlineW) + dy, outlineColor, w + 2 * outlineW)
                }
                c.line(cx + ca * rIn + dx, cy + sa * rIn + dy, cx + ca * rOut + dx, cy + sa * rOut + dy,
                    override ?: Colors.argb(255, 240, 240, 240), w)
            }
        }

        val shadow = Shadow.from(cfg)
        if (shadow != null) shadow.offsets().forEach { (dx, dy, a) -> drawDial(dx, dy, Colors.withAlpha(shadow.color, a)) }
        drawDial(0f, 0f, null)

        // Needle: triangle from the hub to the tip.
        if (value != null) {
            val frac = if (sc.max > sc.min) ((value - sc.min) / (sc.max - sc.min)).coerceIn(0.0, 1.0) else 0.0
            val ang = Math.toRadians(startDeg + sweepDeg * frac)
            val ca = cos(ang).toFloat()
            val sa = sin(ang).toFloat()
            val rOut = radius * cfg.float("needle_length", 1.1f)
            val rIn = max(1f, radius * 0.05f)
            val needleW = needleWidthPx(cfg.double("needle_width", 4.0), ctx.minDim)
            val hw = needleW / 2f
            val pdx = -sa
            val pdy = ca
            val bx = cx + ca * rIn
            val by = cy + sa * rIn
            val xs = floatArrayOf(bx + pdx * hw, bx - pdx * hw, cx + ca * rOut)
            val ys = floatArrayOf(by + pdy * hw, by - pdy * hw, cy + sa * rOut)
            if (shadow != null) {
                shadow.offsets().forEach { (dx, dy, a) ->
                    c.fillPolygon(FloatArray(3) { xs[it] + dx }, FloatArray(3) { ys[it] + dy }, 3, Colors.withAlpha(shadow.color, a))
                }
            }
            if (outlineEnabled && outlineW > 0) {
                c.polyline(xs, ys, 3, outlineColor, outlineW * 2, closed = true)
            }
            val needleColor = Colors.parse(cfg.string("needle_color", "#DC3232")) ?: Colors.argb(255, 220, 50, 50)
            c.fillPolygon(xs, ys, 3, needleColor)
        }

        if (cfg.bool("show_marker", false) && cfg.int("marker_size", 0) > 0) {
            val r = cfg.int("marker_size", 0).toFloat()
            c.fillCircle(cx, cy, r, Colors.parse(cfg.string("marker_color", "#333333")) ?: Colors.argb(255, 51, 51, 51))
            c.strokeCircle(cx, cy, r, Colors.argb(255, 120, 120, 120), 1f)
        }

        if (cfg.bool("show_value", true)) {
            val txt = formatted ?: value?.let { "%.1f".format(Locale.ROOT, it) } ?: "--"
            if (txt.isNotEmpty()) {
                val size = radius * 2.4f
                val ox = cfg.float("text_offset_x", 0f) * size
                val oy = cfg.float("text_offset_y", 0f) * size
                val color = Colors.parse(cfg.string("text_color", "#FFFFFF")) ?: Colors.WHITE
                c.text(txt, cx + ox, cy + radius * 0.15f + oy, TextStyle(fs, color, max(1f, ctx.outline)), HAlign.CENTER, VAlign.TOP)
            }
        }

        if (opacity < 1f) c.endLayer()
        c.restore()
    }

    /** `resolve_gauge_needle_width_px` (keeps needle/diameter ratio across resolutions). */
    fun needleWidthPx(logical: Double, minDim: Int, pixelProfile: Boolean = false): Float {
        val base = max(1.0, logical) * (if (pixelProfile) 1.8 else 1.5)
        return max(2, (base * max(0.1, minDim / 540.0)).roundToInt()).toFloat()
    }

    internal fun pct(v: Double): Double = if (v > 1.0) v / 100.0 else v.coerceIn(0.0, 1.0)
}

/** Optional drop shadow / trailing shadow shared by gauge and compass. */
internal class Shadow(val color: Int, private val distance: Float, private val length: Float, private val fade: Float,
                      private val opacity: Float, private val angleDeg: Float) {
    /** (dx, dy, alpha) passes, farthest first. */
    fun offsets(): List<Triple<Float, Float, Int>> {
        val rad = Math.toRadians(angleDeg.toDouble())
        val ca = cos(rad).toFloat()
        val sa = sin(rad).toFloat()
        if (length <= 0f) return listOf(Triple(distance * ca, distance * sa, (opacity * 255).roundToInt()))
        val steps = length.toInt().coerceIn(1, 30)
        return (steps downTo 0).map { k ->
            val f = k / steps.toFloat()
            val w = opacity * max(0f, 1f - fade * f)
            val reach = distance + f * length
            Triple(reach * ca, reach * sa, (w * 255).roundToInt())
        }
    }

    companion object {
        fun from(cfg: IndicatorConfig): Shadow? {
            if (!cfg.bool("shadow_enabled", false)) return null
            val op = GaugeRenderer.pct(cfg.double("shadow_opacity", 0.6)).toFloat()
            if (op <= 0f) return null
            return Shadow(
                Colors.parse(cfg.string("shadow_color", "#000000")) ?: Colors.BLACK,
                cfg.float("shadow_distance", 2f),
                cfg.float("shadow_length", 0f),
                GaugeRenderer.pct(cfg.double("shadow_fade", 1.0)).toFloat(),
                op,
                cfg.float("shadow_angle", 45f),
            )
        }
    }
}
