package com.sportcamhud.core.render

import com.sportcamhud.core.layout.IndicatorConfig
import com.sportcamhud.core.telemetry.Fields
import com.sportcamhud.core.telemetry.FrameData
import com.sportcamhud.core.telemetry.Imu
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/**
 * "Przechył" (lean) indicator, port of `indicators/lean.py`: a bike-like beam rotated around
 * its bottom-centre pivot by the roll angle, with a 0° reference line, 10° ticks and a readout.
 */
object LeanRenderer {

    /** Raw roll for the configured source, before calibration; null when there is no data. */
    fun rawValue(cfg: IndicatorConfig, frame: FrameData?): Double? {
        frame ?: return null
        val smoothing = cfg.double("lean_smoothing_s", 0.0)
        return when (cfg.string("source", "gyro").lowercase()) {
            "grade" -> frame.values[Fields.SLOPE]?.let { Imu.gradeToAngleDeg(it) }
            "raw_gyro_z" -> frame.camera?.takeIf { it.gyro != null }
                ?.gyroRateSeries("z", smoothing)?.linear(frame.videoPositionMs, 1000)
            else -> frame.camera?.takeIf { it.hasImu }
                ?.leanSeries(cfg.string("axis", "y"), smoothing)?.linear(frame.videoPositionMs, 1000)
        }
    }

    /** Calibration -> invert -> sensitivity -> clamp (`lean_visual_angle`). */
    fun visualAngle(roll: Double, cfg: IndicatorConfig): Double {
        val calib = if ("calibration" in cfg) cfg.double("calibration", 0.0) else cfg.double("zero_offset", 0.0)
        val sign = if (cfg.bool("invert_axis", false)) -1.0 else 1.0
        val maxAngle = abs(cfg.double("max_angle", 30.0))
        return ((roll + calib) * sign * cfg.double("sensitivity", 1.0)).coerceIn(-maxAngle, maxAngle)
    }

    /** True when the configured source has any data in this project. */
    fun available(cfg: IndicatorConfig, frame: FrameData?): Boolean = when (cfg.string("source", "gyro").lowercase()) {
        "grade" -> frame?.dataset?.has(Fields.SLOPE) == true
        "raw_gyro_z" -> frame?.camera?.gyro != null
        else -> frame?.camera?.hasImu == true
    }

    fun draw(ctx: RenderContext, cfg: IndicatorConfig, frame: FrameData?) {
        if (!available(cfg, frame)) return
        val c = ctx.canvas
        val raw = rawValue(cfg, frame)
        val angle = raw?.let { visualAngle(it, cfg) } ?: 0.0
        val maxAngle = abs(cfg.double("max_angle", 30.0))
        val k = ctx.scale1080.coerceAtLeast(0.25f)
        val pad = 8f * k
        val g = max(32f * k, ctx.s(cfg.double("size", 14.0), ctx.minDim))
        val fs = max(8f, ctx.s(cfg.double("font_size", 2.0), ctx.minDim))

        val title = cfg.string("title_text", cfg.label.ifEmpty { "Przechył" }).trim()
            .let { if (cfg.bool("uppercase_title", false)) it.uppercase() else it }
        val showLabel = cfg.bool("show_label", true) && title.isNotEmpty()
        val decimals = cfg.int("decimals", 0).coerceIn(0, 2)
        val valueText = if (cfg.bool("show_value", true) && raw != null) "%+.${decimals}f°".format(Locale.ROOT, angle) else ""
        val titleFs = max(8f, cfg.double("title_font_scale", 1.0).toFloat() * fs)
        val valueFs = max(8f, cfg.double("value_font_scale", 0.9).toFloat() * fs)
        val titleH = if (showLabel) c.capHeight(titleFs) * 1.3f else 0f
        val valueH = if (valueText.isNotEmpty()) c.capHeight(valueFs) * 1.3f else 0f
        val titleGap = if (showLabel) 5f * k else 0f
        val valueGap = if (valueText.isNotEmpty()) 4f * k else 0f

        val totalW = max(g + 2 * pad, 2 * pad + 40f * k)
        val totalH = pad + titleH + titleGap + g + valueGap + valueH + pad
        val cx = ctx.px(cfg)
        val top = ctx.py(cfg) - totalH / 2
        val centerY = top + pad + titleH + titleGap + g / 2
        val white = Colors.WHITE
        val style = { size: Float -> TextStyle(size, white, ctx.outline) }

        if (showLabel) c.text(title, cx, top + pad, style(titleFs), HAlign.CENTER, VAlign.TOP)

        if (cfg.bool("show_reference", true)) {
            val ref = Colors.withAlpha(Colors.parse(cfg.string("track_color", "#FFFFFF")) ?: white, (255 * 0.55).toInt())
            c.line(cx - totalW / 2 + pad, centerY, cx + totalW / 2 - pad, centerY, ref, max(1f, 1.4f * k))
        }
        if (cfg.bool("show_ticks", true)) {
            val tickColor = Colors.withAlpha(
                Colors.parse(cfg.string("tick_color", cfg.string("track_color", "#FFFFFF"))) ?: white, (255 * 0.35).toInt(),
            )
            val range = minOf(maxAngle, 90.0)
            var t = -range
            while (t <= range + 1e-6) {
                val frac = (t / max(1.0, maxAngle)).coerceIn(-1.0, 1.0).toFloat()
                val x = cx + frac * (g / 2 - 4 * k)
                val tl = if (abs(abs(t) - range) < 1e-6) 4 * k else 3 * k
                c.line(x, centerY - tl, x, centerY + tl, tickColor, max(1f, k))
                t += 10.0
            }
        }

        if (cfg.string("graphic", "bike").lowercase() != "none") {
            val marker = Colors.parse(cfg.string("marker_color", "#FFFFFF")) ?: white
            val w = max(24f * k, g)
            val h = max(12f * k, g * 0.35f)
            val gw = w + 4 * k
            val gh = h + 4 * k
            val left = cx - gw / 2
            val gTop = centerY - gh / 2
            val px = left + cfg.double("pivot_x", 0.5).coerceIn(0.0, 1.0).toFloat() * gw
            val py = gTop + cfg.double("pivot_y", 1.0).coerceIn(0.0, 1.0).toFloat() * gh
            c.save()
            c.rotate(-angle.toFloat(), px, py)
            val gcx = cx
            val gcy = centerY
            val r = max(3f * k, g * 0.12f)
            c.fillCircle(gcx - w / 2 + r, gcy, r, marker)
            c.fillCircle(gcx + w / 2 - r, gcy, r, marker)
            c.fillRect(gcx - w / 2 + r + 2 * k, gcy - 2 * k, gcx + w / 2 - r - 2 * k, gcy + 2 * k, marker, 2 * k)
            c.fillCircle(gcx, gcy, max(2f * k, g * 0.05f), white)
            c.restore()
        }

        if (valueText.isNotEmpty()) {
            c.text(valueText, cx, centerY + g / 2 + valueGap, style(valueFs), HAlign.CENTER, VAlign.TOP)
        }
    }
}
