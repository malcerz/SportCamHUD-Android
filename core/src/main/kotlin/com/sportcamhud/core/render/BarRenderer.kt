package com.sportcamhud.core.render

import com.sportcamhud.core.layout.IndicatorConfig
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Bar indicators (port of `indicators/bar.py`): the horizontal "ruler", the vertical
 * ruler used for slope, and the segmented battery bar.
 */
object BarRenderer {

    fun draw(ctx: RenderContext, cfg: IndicatorConfig, value: Double?, formatted: String?, unit: String,
             label: String, range: Pair<Double, Double>) {
        val style = cfg.string("bar_style", cfg.string("style", "ruler")).trim().lowercase()
        val segments = cfg.form == "segment_bar" || style in setOf("segment", "segments", "segmented", "segment_bar")
        val vertical = style in setOf("slope", "grade", "vertical_slope") ||
            cfg.string("orientation", "horizontal").lowercase() == "vertical"
        val cx = ctx.px(cfg)
        val cy = ctx.py(cfg)
        val c = ctx.canvas
        c.save()
        if (cfg.rotation % 360 != 0) c.rotate(-cfg.rotation.toFloat(), cx, cy)
        val opacity = cfg.double("opacity", 1.0).coerceIn(0.0, 1.0).toFloat()
        if (opacity < 1f) c.beginLayer(opacity)
        when {
            segments -> drawSegments(ctx, cfg, value, formatted, unit, label, range, cx, cy)
            vertical -> drawVerticalRuler(ctx, cfg, value, formatted, unit, label, range, cx, cy)
            else -> drawRuler(ctx, cfg, value, formatted, unit, label, range, cx, cy)
        }
        if (opacity < 1f) c.endLayer()
        c.restore()
    }

    /** `_nice_step`: 1, 2, 5 x 10^n giving roughly [target] divisions. */
    fun niceStep(range: Double, target: Int = 8): Double {
        if (range <= 0) return 1.0
        val rough = range / max(1, target)
        val mag = 10.0.pow(floor(log10(rough)))
        val norm = rough / mag
        val nice = when {
            norm < 1.5 -> 1.0
            norm < 3.0 -> 2.0
            norm < 7.0 -> 5.0
            else -> 10.0
        }
        return nice * mag
    }

    private data class TickPlan(val step: Double?, val majors: Int, val minorPerMajor: Int)

    private fun tickPlan(cfg: IndicatorConfig, lo: Double, hi: Double): TickPlan {
        val rng = abs(hi - lo)
        val mode = cfg.string("major_tick_mode", "").trim().lowercase()
        val majorStep = cfg.double("major_step", 0.0)
        val minor = max(1, cfg.int("minor_ticks", 5))
        val ticks = cfg.int("ticks", 0)
        val count = max(1, cfg.int("major_ticks", if (ticks > 0) ticks else 8))
        return when {
            mode == "auto" && rng > 0 -> niceStep(rng).let { TickPlan(it, max(1, (rng / it).roundToInt()), minor) }
            (mode == "step" || mode.isEmpty()) && majorStep > 0 && rng > 0 ->
                TickPlan(majorStep, max(1, (rng / majorStep).roundToInt()), minor)
            else -> TickPlan(null, count, minor)
        }
    }

    fun fmt(v: Double, decimals: Int): String =
        if (decimals <= 0) "%.0f".format(java.util.Locale.ROOT, v).let { if (it == "-0") "0" else it } else "%.${decimals}f".format(java.util.Locale.ROOT, v)

    private fun rangeDecimals(cfg: IndicatorConfig, lo: Double, hi: Double): Int {
        if ("range_decimals" in cfg) return cfg.int("range_decimals", 0)
        val d = cfg.int("decimals", 0)
        if (d > 0) return d
        val span = abs(hi - lo)
        return if (span in 0.0..2.0 && span > 0 && (lo != floor(lo) || hi != floor(hi))) 1 else 0
    }

    private fun title(cfg: IndicatorConfig, label: String, unit: String): String {
        val upper = cfg.bool("uppercase_title", true)
        var t = cfg.string("title_text", "").ifBlank { label }.trim()
        if (upper) t = t.uppercase()
        val u = if (upper) unit.uppercase() else unit
        if (cfg.bool("title_with_unit", true) && u.isNotBlank() && u.trim() != "%") t = if (t.isNotEmpty()) "$t | $u" else u
        return t
    }

    private fun drawRuler(ctx: RenderContext, cfg: IndicatorConfig, value: Double?, formatted: String?, unit: String,
                          label: String, range: Pair<Double, Double>, cx: Float, cy: Float) {
        val c = ctx.canvas
        val scale = ctx.scale1080
        val (lo, hi) = range
        val sizePx = ctx.s(cfg.double("size", 20.0), ctx.width)
        val width = max(80 * scale, sizePx)
        val fs = ctx.fontSize(cfg)
        val titleFs = max(4f, cfg.float("title_font_scale", 1f) * fs)
        val rangeFs = max(4f, cfg.float("range_font_scale", 0.82f) * fs)
        val valueFs = max(4f, cfg.float("value_font_scale", 1f) * fs)
        val stroke = max(1f, ctx.outline)

        val showTitle = cfg.bool("show_label", true)
        val showRange = cfg.bool("show_range_labels", true)
        val showMid = cfg.bool("show_mid_label", true)
        val valueText = formatted ?: value?.let { "${fmt(it, cfg.int("decimals", 0))} $unit".trim() } ?: "--"
        val showValue = cfg.bool("show_value", valueText.isNotEmpty())
        val ttl = if (showTitle) title(cfg, label, unit) else ""
        val rDec = rangeDecimals(cfg, lo, hi)

        val trackColor = Colors.withAlpha(Colors.parse(cfg.string("track_color", "#F4F4F4")) ?: Colors.WHITE, cfg.int("track_alpha", 235))
        val tickColor = Colors.withAlpha(Colors.parse(cfg.string("tick_color", "#F6F6F6")) ?: Colors.WHITE, cfg.int("tick_alpha", 240))
        val markerColor = Colors.parse(cfg.string("marker_color", cfg.string("dot_color", "#159FA5"))) ?: Colors.argb(255, 21, 159, 165)
        val markerBorder = Colors.parse(cfg.string("marker_border_color", "#D8D8D8")) ?: Colors.argb(255, 216, 216, 216)
        val textColor = Colors.parse(cfg.string("text_color", "#F4F4F4")) ?: Colors.WHITE
        val dimText = Colors.parse(cfg.string("range_color", cfg.string("text_color", "#E0E0E0"))) ?: Colors.argb(255, 224, 224, 224)

        val thickness = rulerThickness(ctx, cfg)
        val lineW = max(1f, (max(1f, thickness) * 0.35f * scale))
        val tickW = max(1f, cfg.float("tick_width", 1.4f) * scale)
        val majorLen = max(8 * scale, cfg.float("major_tick_length", 17f) * scale)
        val minorLen = max(4 * scale, cfg.float("minor_tick_length", 10f) * scale)
        val markerR = max(3 * scale, cfg.float("marker_size", 7f) * scale)
        val markerBorderW = max(1f, cfg.float("marker_border_width", 1.5f) * scale)

        val titleH = if (ttl.isNotEmpty()) c.capHeight(titleFs) + 2 * stroke else 0f
        val rangeH = if (showRange) c.capHeight(rangeFs) + 2 * stroke else 0f
        val valueH = if (showValue) c.capHeight(valueFs) + 2 * stroke else 0f
        val titleGap = if (titleH > 0) 5 * scale else 0f
        val padTop = 4 * scale
        val valueGap = if (valueH > 0) 4 * scale else 0f
        val trackRel = padTop + titleH + titleGap + valueH + valueGap + majorLen + markerR
        val bottomGap = 6 * scale
        val height = trackRel + markerR + bottomGap + rangeH + 5 * scale

        // The raster is centred on (x, y) like every non-text indicator.
        val left = cx - width / 2
        val top = cy - height / 2
        val x1 = left
        val x2 = left + width
        val trackY = top + trackRel

        if (ttl.isNotEmpty()) {
            c.text(ttl, cx, top + padTop, TextStyle(titleFs, textColor, stroke, Colors.argb(230, 0, 0, 0)), HAlign.CENTER, VAlign.TOP)
        }
        shadowLine(c, x1, trackY, x2, trackY, trackColor, lineW)

        val plan = tickPlan(cfg, lo, hi)
        if (plan.step != null && hi != lo) {
            val minorStep = plan.step / plan.minorPerMajor
            val kMin = floor(lo / minorStep - 1e-7).toInt()
            val kMax = ceil(hi / minorStep + 1e-7).toInt()
            for (k in kMin..kMax) {
                val v = k * minorStep
                if (v < lo - 1e-7 || v > hi + 1e-7) continue
                val x = x1 + width * ((v - lo) / (hi - lo)).toFloat()
                val major = abs((v / plan.step).roundToInt() * plan.step - v) < 1e-6
                shadowLine(c, x, trackY - if (major) majorLen else minorLen, x, trackY + max(1f, lineW / 2), tickColor,
                    if (major) max(tickW, 2f) else tickW)
            }
        } else {
            val total = plan.majors * plan.minorPerMajor
            for (i in 0..total) {
                val x = x1 + width * i / total
                val major = i % plan.minorPerMajor == 0
                shadowLine(c, x, trackY - if (major) majorLen else minorLen, x, trackY + max(1f, lineW / 2), tickColor,
                    if (major) max(tickW, 2f) else tickW)
            }
        }

        if (showRange) {
            val units = cfg.bool("range_units", true)
            fun rt(v: Double) = fmt(v, rDec).let { if (units) "$it $unit".trim() else it }
            val y = trackY + markerR + bottomGap
            val rs = TextStyle(rangeFs, dimText, stroke, Colors.argb(230, 0, 0, 0))
            val rox = cfg.float("range_label_offset_x", 0f) * ctx.width / 100f
            val roy = cfg.float("range_label_offset_y", 0f) * ctx.height / 100f
            val spread = cfg.float("range_label_spread_x", 0f) * ctx.width / 100f
            c.text(rt(lo), x1 + rox - spread, y + roy, rs, HAlign.LEFT, VAlign.TOP)
            if (showMid) c.text(rt((lo + hi) / 2), cx + rox, y + roy, rs, HAlign.CENTER, VAlign.TOP)
            c.text(rt(hi), x2 + rox + spread, y + roy, rs, HAlign.RIGHT, VAlign.TOP)
        }

        if (value != null) {
            val frac = if (hi != lo) ((value - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat() else 0f
            val mx = x1 + frac * width
            val sr = markerR + markerBorderW
            c.fillCircle(mx + 2 * scale, trackY + 2 * scale, sr, Colors.argb(130, 0, 0, 0))
            c.fillCircle(mx, trackY, sr, markerBorder)
            c.fillCircle(mx, trackY, markerR, markerColor)
            if (showValue && valueText.isNotEmpty()) {
                val vox = cfg.float("value_offset_x", 0f) * ctx.width / 100f
                val voy = cfg.float("value_offset_y", 0f) * ctx.height / 100f
                val vy = top + padTop + titleH + titleGap
                val tw = c.textWidth(valueText, valueFs)
                val vx = (mx + vox).coerceIn(x1 - 12 * scale + tw / 2, x2 + 12 * scale - tw / 2)
                c.text(valueText, vx, vy + voy, TextStyle(valueFs, textColor, stroke, Colors.argb(230, 0, 0, 0)), HAlign.CENTER, VAlign.TOP)
            }
        }
    }

    /** Vertical ruler (slope / grade): ticks on the left, value beside the marker. */
    private fun drawVerticalRuler(ctx: RenderContext, cfg: IndicatorConfig, value: Double?, formatted: String?, unit: String,
                                  label: String, range: Pair<Double, Double>, cx: Float, cy: Float) {
        val c = ctx.canvas
        val scale = ctx.scale1080
        val (lo, hi) = range
        val heightPx = max(80 * scale, ctx.s(cfg.double("size", 15.0), ctx.height))
        val fs = ctx.fontSize(cfg)
        val stroke = max(1f, ctx.outline)
        val textColor = Colors.parse(cfg.string("text_color", "#F4F4F4")) ?: Colors.WHITE
        val dimText = Colors.parse(cfg.string("range_color", "#E0E0E0")) ?: Colors.argb(255, 224, 224, 224)
        val trackColor = Colors.withAlpha(Colors.parse(cfg.string("track_color", "#F4F4F4")) ?: Colors.WHITE, 235)
        val tickColor = Colors.withAlpha(Colors.parse(cfg.string("tick_color", "#F6F6F6")) ?: Colors.WHITE, 240)
        val markerColor = Colors.parse(cfg.string("marker_color", "#159FA5")) ?: Colors.argb(255, 21, 159, 165)
        val lineW = max(1f, max(1f, rulerThickness(ctx, cfg)) * 0.35f * scale)
        val majorLen = max(8 * scale, cfg.float("major_tick_length", 17f) * scale)
        val minorLen = max(4 * scale, cfg.float("minor_tick_length", 10f) * scale)
        val markerR = max(3 * scale, cfg.float("marker_size", 7f) * scale)

        val top = cy - heightPx / 2
        val bottom = cy + heightPx / 2
        val ttl = if (cfg.bool("show_label", true)) title(cfg, label, unit) else ""
        if (ttl.isNotEmpty()) {
            c.text(ttl, cx, top - 6 * scale, TextStyle(fs, textColor, stroke, Colors.argb(230, 0, 0, 0)), HAlign.CENTER, VAlign.BOTTOM)
        }
        shadowLine(c, cx, top, cx, bottom, trackColor, lineW)
        val plan = tickPlan(cfg, lo, hi)
        val total = plan.majors * plan.minorPerMajor
        for (i in 0..total) {
            val y = bottom - heightPx * i / total
            val major = i % plan.minorPerMajor == 0
            shadowLine(c, cx - if (major) majorLen else minorLen, y, cx, y, tickColor, if (major) 2f * max(1f, scale) else max(1f, scale))
        }
        // Zero line for symmetric slope scales.
        if (lo < 0 && hi > 0) {
            val y0 = bottom - heightPx * ((0 - lo) / (hi - lo)).toFloat()
            shadowLine(c, cx - majorLen * 1.3f, y0, cx + majorLen * 0.5f, y0, tickColor, 2f * max(1f, scale))
        }
        if (cfg.bool("show_range_labels", true)) {
            val rs = TextStyle(fs * 0.82f, dimText, stroke, Colors.argb(230, 0, 0, 0))
            val dec = rangeDecimals(cfg, lo, hi)
            c.text("${fmt(hi, dec)}$unit", cx - majorLen - 4 * scale, top, rs, HAlign.RIGHT, VAlign.MIDDLE)
            c.text("${fmt(lo, dec)}$unit", cx - majorLen - 4 * scale, bottom, rs, HAlign.RIGHT, VAlign.MIDDLE)
        }
        if (value != null) {
            val frac = if (hi != lo) ((value - lo) / (hi - lo)).coerceIn(0.0, 1.0).toFloat() else 0f
            val my = bottom - frac * heightPx
            c.fillCircle(cx + 2 * scale, my + 2 * scale, markerR + 1.5f * scale, Colors.argb(130, 0, 0, 0))
            c.fillCircle(cx, my, markerR + 1.5f * scale, Colors.argb(255, 216, 216, 216))
            c.fillCircle(cx, my, markerR, markerColor)
            val txt = formatted ?: "${fmt(value, max(0, cfg.int("decimals", 1)))} $unit".trim()
            c.text(txt, cx + markerR + 8 * scale, my, TextStyle(fs, textColor, stroke, Colors.argb(230, 0, 0, 0)), HAlign.LEFT, VAlign.MIDDLE)
        }
    }

    /** Segmented bar (battery): coloured by thresholds/gradient, inactive segments dimmed. */
    private fun drawSegments(ctx: RenderContext, cfg: IndicatorConfig, value: Double?, formatted: String?, unit: String,
                             label: String, range: Pair<Double, Double>, cx: Float, cy: Float) {
        val c = ctx.canvas
        val scale = ctx.scale1080
        val (lo, hi) = range
        val n = cfg.int("segments", 20).coerceIn(2, 100)
        val width = max(60 * scale, ctx.s(cfg.double("size", 12.0), ctx.width))
        val segH = max(6 * scale, cfg.float("segment_height", width / n * 2.2f))
        val gap = max(1f, width / n * 0.18f)
        val segW = (width - gap * (n - 1)) / n
        val fs = ctx.fontSize(cfg)
        val stroke = max(1f, ctx.outline)
        val textColor = Colors.parse(cfg.string("text_color", "#FFFFFF")) ?: Colors.WHITE
        val inactiveAlpha = (GaugeRenderer.pct(cfg.double("inactive_opacity", 0.25)) * 255).roundToInt()
        val stops = gradientStops(cfg)

        val left = cx - width / 2
        val top = cy - segH / 2
        val frac = value?.let { if (hi != lo) ((it - lo) / (hi - lo)).coerceIn(0.0, 1.0) else 0.0 } ?: 0.0
        val active = (frac * n).roundToInt()
        val batteryLike = "bat" in cfg.key.lowercase() || unit == "%"
        for (i in 0 until n) {
            val x = left + i * (segW + gap)
            val pos = i / (n - 1f)
            // Battery: red at the empty end, green when full.
            val col = gradient(stops, if (batteryLike) 1f - pos else pos)
            c.fillRect(x, top, x + segW, top + segH, if (i < active) col else Colors.withAlpha(col, inactiveAlpha), segW * 0.2f)
        }
        val ttl = if (cfg.bool("show_label", true)) label.uppercase() else ""
        if (ttl.isNotEmpty()) c.text(ttl, left, top - 4 * scale, TextStyle(fs, textColor, stroke), HAlign.LEFT, VAlign.BOTTOM)
        if (cfg.bool("show_value", true)) {
            val txt = formatted ?: value?.let { "${fmt(it, 0)}$unit" } ?: "--"
            c.text(txt, left + width, top - 4 * scale, TextStyle(fs, textColor, stroke), HAlign.RIGHT, VAlign.BOTTOM)
        }
    }

    private fun gradientStops(cfg: IndicatorConfig): List<Int> {
        val raw = cfg.props["gradient"]
        val list = (raw as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
            Colors.parse((it as? kotlinx.serialization.json.JsonPrimitive)?.content)
        }
        return list?.takeIf { it.size >= 2 } ?: listOf("#00FF00", "#FFFF00", "#FF0000").mapNotNull { Colors.parse(it) }
    }

    private fun gradient(stops: List<Int>, pos: Float): Int {
        val p = pos.coerceIn(0f, 1f) * (stops.size - 1)
        val i = p.toInt().coerceAtMost(stops.size - 2)
        return Colors.lerp(stops[i], stops[i + 1], p - i)
    }

    /** Dispatcher thickness rules: ruler values < 1 are a fraction of the 0.6 %-of-min_dim baseline. */
    private fun rulerThickness(ctx: RenderContext, cfg: IndicatorConfig): Float {
        val raw = cfg.double("thickness", 1.0)
        return if (raw < 1) max(0.25f, ctx.s(0.6, ctx.minDim) * raw.toFloat())
        else max(1f, ctx.s(0.6 + (raw - 1) * 0.2, ctx.minDim))
    }

    private fun shadowLine(c: HudCanvas, x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) {
        c.line(x1 + 1, y1 + 1, x2 + 1, y2 + 1, Colors.argb(140, 0, 0, 0), width + 1)
        c.line(x1, y1, x2, y2, color, width)
    }
}
