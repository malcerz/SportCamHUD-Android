package com.sportcamhud.core.render

import com.sportcamhud.core.layout.IndicatorConfig
import com.sportcamhud.core.telemetry.FrameData
import kotlin.math.max
import kotlin.math.roundToInt
import java.util.Locale

/** Plain text indicator (`indicators/text.py`): "Label: value unit", anchored top-left. */
object TextRenderer {
    fun draw(ctx: RenderContext, cfg: IndicatorConfig, text: String) {
        if (text.isEmpty()) return
        val c = ctx.canvas
        val fs = ctx.fontSize(cfg)
        val color = Colors.parse(cfg.string("text_color", "#FFFFFF")) ?: Colors.WHITE
        val ox = cfg.float("text_offset_x", 0f) * ctx.width
        val oy = cfg.float("text_offset_y", 0f) * ctx.height
        val w = c.textWidth(text, fs)
        val h = c.capHeight(fs)
        val center = cfg.string("align", "left") == "center"
        val x = ctx.px(cfg) + ox - if (center) w / 2 else 0f
        val y = ctx.py(cfg) + oy
        val style = TextStyle(fs, color, ctx.outline)
        val rot = cfg.rotation % 360
        if (rot != 0) {
            c.save()
            c.rotate(-rot.toFloat(), x + w / 2, y + h / 2)
            c.text(text, x, y, style, HAlign.LEFT, VAlign.TOP)
            c.restore()
        } else {
            c.text(text, x, y, style, HAlign.LEFT, VAlign.TOP)
        }
    }
}

/** Date / time / elapsed / average-speed block (`indicators/time_display.py`). */
object TimeDisplayRenderer {
    fun draw(ctx: RenderContext, cfg: IndicatorConfig, frame: FrameData?) {
        val c = ctx.canvas
        val master = cfg.double("size", 1.0).coerceIn(0.2, 5.0).toFloat()
        val globalFs = max(14f, ctx.s(cfg.double("font_size", 2.5), ctx.minDim))

        val elapsed = frame?.elapsedSeconds?.toInt() ?: 0
        val elapsedStr = if (elapsed >= 3600) "%02d:%02d:%02d".format(Locale.ROOT, elapsed / 3600, elapsed % 3600 / 60, elapsed % 60)
        else "%02d:%02d".format(Locale.ROOT, elapsed / 60, elapsed % 60)
        val lines = listOf(
            Triple("date", frame?.dateText ?: "----------", Colors.argb(255, 210, 210, 210) to "Data"),
            Triple("time", frame?.timeText ?: "--:--:--", Colors.WHITE to "Godzina"),
            Triple("elapsed", elapsedStr, Colors.WHITE to "Czas"),
            Triple("avg_speed", "%.1f km/h".format(Locale.ROOT, frame?.avgSpeedKmh ?: 0.0), Colors.WHITE to "Średnia prędkość"),
        )
        data class Line(val text: String, val fs: Float, val color: Int)
        val rendered = ArrayList<Line>()
        for ((prefix, value, defaults) in lines) {
            if (!cfg.bool("show_$prefix", true)) continue
            val showLabel = cfg.bool("show_${prefix}_label", true)
            val lbl = if (showLabel) cfg.string("${prefix}_label", defaults.second) else ""
            val text = if (showLabel && lbl.isNotEmpty()) "$lbl: $value" else value
            val fs = max(1f, (if ("${prefix}_font_size" in cfg) ctx.s(cfg.double("${prefix}_font_size", 2.0), ctx.minDim) else globalFs) * master)
            val color = Colors.parse(cfg.string("${prefix}_color", "")) ?: defaults.first
            rendered += Line(text, fs, color)
        }
        if (rendered.isEmpty()) return

        val x = ctx.px(cfg)
        val y = ctx.py(cfg)
        val showIcon = cfg.string("icon", "none").let { it != "none" && it.isNotBlank() }
        val iconSize = if (showIcon) max(12f, globalFs * master * 0.9f) else 0f
        val iconGap = if (showIcon) max(2f, globalFs * master * 0.18f) else 0f
        val totalH = rendered.sumOf { (it.fs * 1.25f).toDouble() }.toFloat()

        c.save()
        val rot = cfg.rotation % 360
        if (rot != 0) c.rotate(-rot.toFloat(), x, y)
        var ly = y
        for (line in rendered) {
            c.text(line.text, x + iconSize + iconGap, ly, TextStyle(line.fs, line.color, ctx.outline), HAlign.LEFT, VAlign.TOP)
            ly += line.fs * 1.25f
        }
        if (showIcon) drawClock(c, x + iconSize / 2, y + (totalH - line0Pad(rendered.first().fs)) / 2, iconSize / 2, ctx.outline)
        c.restore()
    }

    private fun line0Pad(fs: Float) = fs * 0.25f

    /** Vector clock icon (the Windows app ships it as a PNG). */
    fun drawClock(c: HudCanvas, cx: Float, cy: Float, r: Float, outline: Float) {
        val w = max(1.5f, r * 0.16f)
        c.strokeCircle(cx, cy, r - w / 2, Colors.BLACK, w + outline * 1.5f)
        c.strokeCircle(cx, cy, r - w / 2, Colors.WHITE, w)
        c.line(cx, cy, cx, cy - r * 0.55f, Colors.WHITE, w, roundCap = true)
        c.line(cx, cy, cx + r * 0.4f, cy + r * 0.15f, Colors.WHITE, w, roundCap = true)
    }
}

/** Number formatting shared by indicators. */
object ValueFormat {
    fun format(value: Double?, decimals: Int, unit: String, withUnit: Boolean = true): String {
        val num = value?.let { if (decimals <= 0) it.roundToInt().toString() else "%.${decimals}f".format(Locale.ROOT, it) } ?: "--"
        return if (withUnit && unit.isNotEmpty()) "$num $unit" else num
    }
}
