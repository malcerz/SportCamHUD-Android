package com.sportcamhud.core.render

import com.sportcamhud.core.layout.IndicatorConfig
import com.sportcamhud.core.layout.IndicatorRegistry
import com.sportcamhud.core.telemetry.FrameData
import com.sportcamhud.core.telemetry.Series
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import java.util.Locale

/** History chart (`indicators/chart.py` + `chart_utils.generate_history_chart`). */
object ChartRenderer {

    private class History(val t0: Long, val t1: Long, val times: LongArray, val values: DoubleArray, val lo: Double, val hi: Double)

    fun draw(ctx: RenderContext, cfg: IndicatorConfig, field: String, frame: FrameData?, value: Double?,
             formatted: String, label: String, convert: (Double) -> Double) {
        val c = ctx.canvas
        frame ?: return
        val series = frame.dataset?.get(field) ?: frame.camera?.fields?.get(field)?.let { cam ->
            // Camera streams are on the video clock; charts work on the absolute one.
            val key = "camera|$field|${frame.videoStart}"
            (ctx.caches.charts[key] as? com.sportcamhud.core.telemetry.Series)
                ?: cam.shifted(frame.videoStart).also { ctx.caches.charts[key] = it }
        } ?: return
        val fs = ctx.fontSize(cfg)
        val chartW = ctx.s(cfg.double("size", 30.0), ctx.width)
        var chartH = max(40f, chartW * 0.4f)
        val marginTop = if (label.isNotEmpty() || formatted.isNotEmpty()) fs + 8 + ctx.outline else 0f
        val cx = ctx.px(cfg)
        val cy = ctx.py(cfg)
        val maxFull = 2 * min(cy, ctx.height - cy)
        chartH = min(chartH, max(20f, maxFull - marginTop - 4))

        val scope = cfg.string("chart_time_scope", "activity")
        val now = frame.time
        val (from, to) = when (scope) {
            "video" -> frame.videoStart to frame.videoEnd
            "window" -> (now - (cfg.double("chart_window_s", 60.0) * 1000).toLong()) to now
            else -> series.start to series.end
        }
        val buckets = max(16, (chartW / 2).toInt())
        val cacheKey = "${cfg.key}|$field|$scope|$from|$to|$buckets"
        @Suppress("UNCHECKED_CAST")
        val hist = (ctx.caches.charts[cacheKey] as? History) ?: build(series.slice(from, to), from, to, buckets, convert, cfg).also {
            if (scope != "window") ctx.caches.charts[cacheKey] = it
        }

        val c0 = cfg.rotation % 360
        c.save()
        if (c0 != 0) c.rotate(-c0.toFloat(), cx, cy)
        val totalH = chartH + marginTop + 4
        val left = cx - chartW / 2
        val top = cy - totalH / 2
        val lineColor = Colors.parse(cfg.string("chart_color", "")) ?: IndicatorRegistry.chartColor(cfg.key + field)
        val textColor = Colors.parse(cfg.string("text_color", "#FFFFFF")) ?: Colors.WHITE
        val axisFs = max(8f, if ("label_font_size" in cfg) ctx.s(cfg.double("label_font_size", 1.0), ctx.minDim) else fs * 0.8f)
        val stroke = max(1f, ctx.outline)

        // Header: "Label: value".
        val header = listOf(label, formatted).filter { it.isNotEmpty() }.joinToString(": ")
        if (header.isNotEmpty()) c.text(header, left, top, TextStyle(fs, textColor, stroke), HAlign.LEFT, VAlign.TOP)

        val showY = cfg.bool("show_y_axis_values", true)
        val showX = cfg.bool("show_x_axis_values", true)
        val dec = cfg.int("decimals", if (cfg.key.contains("speed") || field.contains("speed")) 1 else 0)
        val yLabels = if (showY) listOf(BarRenderer.fmt(hist.hi, dec), BarRenderer.fmt(hist.lo, dec)) else emptyList()
        val yLabelW = yLabels.maxOfOrNull { c.textWidth(it, axisFs) } ?: 0f
        val plotL = left + if (showY) yLabelW + 6 + stroke else 0f
        val plotR = left + chartW
        val plotT = top + marginTop
        val plotB = top + marginTop + chartH - if (showX) axisFs + 6 + stroke else 0f
        if (plotB - plotT < 8 || plotR - plotL < 8) { c.restore(); return }

        if (cfg.bool("show_grid", true)) {
            val grid = Colors.withAlpha(Colors.parse(cfg.string("grid_color", "#444444")) ?: Colors.argb(255, 68, 68, 68), 60)
            for (i in 0..4) {
                val gy = plotT + (plotB - plotT) * i / 4
                c.line(plotL, gy, plotR, gy, grid, 1f)
            }
            c.line(plotL, plotT, plotL, plotB, grid, 1f)
        }

        val span = max(1L, hist.t1 - hist.t0).toDouble()
        val range = if (hist.hi > hist.lo) hist.hi - hist.lo else 1.0
        fun px(t: Long) = plotL + ((t - hist.t0) / span).toFloat() * (plotR - plotL)
        fun py(v: Double) = plotB - ((v - hist.lo) / range).toFloat() * (plotB - plotT)
        val n = hist.times.size
        if (n >= 2) {
            val xs = FloatArray(n) { px(hist.times[it]) }
            val ys = FloatArray(n) { py(hist.values[it]) }
            val fillAlpha = cfg.int("fill_alpha", 40)
            if (fillAlpha > 0) {
                val fx = FloatArray(n + 2)
                val fy = FloatArray(n + 2)
                for (i in 0 until n) { fx[i] = xs[i]; fy[i] = ys[i] }
                fx[n] = xs[n - 1]; fy[n] = plotB
                fx[n + 1] = xs[0]; fy[n + 1] = plotB
                val fillBase = Colors.parse(cfg.string("fill_color", "")) ?: lineColor
                c.fillPolygon(fx, fy, n + 2, Colors.withAlpha(fillBase, fillAlpha))
            }
            c.polyline(xs, ys, n, lineColor, max(1f, cfg.float("line_width", cfg.float("thickness", 2f)) * max(1f, ctx.scale1080)))
        }

        if (showY) {
            val st = TextStyle(axisFs, textColor, stroke)
            c.text(yLabels[0], plotL - 4 - stroke, plotT, st, HAlign.RIGHT, VAlign.TOP)
            c.text(yLabels[1], plotL - 4 - stroke, plotB, st, HAlign.RIGHT, VAlign.BOTTOM)
        }
        if (showX) {
            val st = TextStyle(axisFs, textColor, stroke)
            val y = plotB + 4 + stroke
            if (scope == "window") {
                c.text("-${(span / 1000).toInt()} s", plotL, y, st, HAlign.LEFT, VAlign.TOP)
                c.text("0 s", plotR, y, st, HAlign.RIGHT, VAlign.TOP)
            } else {
                c.text("0:00", plotL, y, st, HAlign.LEFT, VAlign.TOP)
                c.text(duration(span / 1000), plotR, y, st, HAlign.RIGHT, VAlign.TOP)
            }
        }

        // Cursor at the current time.
        if (now in hist.t0..hist.t1 && value != null) {
            val x = px(now)
            val y = py(value.coerceIn(hist.lo, hist.hi))
            val r = max(3f, 4f * ctx.scale1080)
            c.fillCircle(x, y, r + max(1f, ctx.scale1080), Colors.BLACK)
            c.fillCircle(x, y, r, Colors.WHITE)
        }
        c.restore()
    }

    private fun duration(seconds: Double): String {
        val s = seconds.toInt()
        return if (s >= 3600) "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(Locale.ROOT, s / 60, s % 60)
    }

    private fun build(s: Series, from: Long, to: Long, buckets: Int, convert: (Double) -> Double, cfg: IndicatorConfig): History {
        if (s.isEmpty() || to <= from) return History(from, max(to, from + 1), LongArray(0), DoubleArray(0), 0.0, 1.0)
        val step = (to - from).toDouble() / buckets
        val tOut = ArrayList<Long>(buckets)
        val vOut = ArrayList<Double>(buckets)
        var i = 0
        for (b in 0 until buckets) {
            val bEnd = from + ((b + 1) * step).toLong()
            var sum = 0.0
            var cnt = 0
            while (i < s.size && s.times[i] <= bEnd) {
                if (s.times[i] >= from) { sum += s.values[i]; cnt++ }
                i++
            }
            if (cnt > 0) {
                tOut += from + ((b + 0.5) * step).toLong()
                vOut += convert(sum / cnt)
            }
        }
        val values = vOut.toDoubleArray()
        var lo = cfg.doubleOrNull("chart_min") ?: values.minOrNull() ?: 0.0
        var hi = cfg.doubleOrNull("chart_max") ?: values.maxOrNull() ?: 1.0
        if (hi - lo < 1e-6) { lo -= 1; hi += 1 }
        val pad = (hi - lo) * 0.05
        lo = floor(lo - pad)
        hi = ceil(hi + pad)
        return History(from, to, tOut.toLongArray(), values, lo, hi)
    }
}
