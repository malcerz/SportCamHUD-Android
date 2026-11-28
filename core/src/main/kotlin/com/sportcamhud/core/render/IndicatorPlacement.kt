package com.sportcamhud.core.render

import com.sportcamhud.core.layout.HudLayout
import com.sportcamhud.core.layout.IndicatorConfig
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** View-independent HUD placement, measured in actual video pixels. */
data class IndicatorBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    fun expanded(pad: Float) = IndicatorBounds(left - pad, top - pad, right + pad, bottom + pad)
    fun contains(x: Float, y: Float) = x in left..right && y in top..bottom
}

/**
 * Windows treats map x/y as the TOP LEFT corner for map_anchor=topleft.
 * Earlier map presets used centre anchors, normalised by _effective_indicator_cfg
 * in src/indicators/compositor.py without rewriting the saved JSON.
 */
object IndicatorPlacement {
    fun mapBounds(cfg: IndicatorConfig, videoWidth: Int, videoHeight: Int): IndicatorBounds {
        val w = videoWidth.toFloat()
        val h = videoHeight.toFloat()
        val sizePercent = cfg.double("size", 18.0).coerceAtLeast(0.1)
        val pxSize = max(1f, (sizePercent / 100.0 * w).toFloat())
        val topLeft = cfg.string("map_anchor", "").equals("topleft", ignoreCase = true) ||
            cfg.int("map_geometry_version", 1) >= 2
        // Legacy Windows v1 mapping: subtract half-size only when x/y exceed half-size.
        val leftPct = if (topLeft) cfg.x else max(0.0, cfg.x - if (cfg.x <= sizePercent / 2) 0.0 else sizePercent / 2)
        val halfHeightPercent = sizePercent * (16.0 / 9.0) / 2.0
        val topPct = if (topLeft) cfg.y else max(0.0, cfg.y - if (cfg.y <= halfHeightPercent) 0.0 else halfHeightPercent)
        val left = (leftPct / 100.0 * w).toFloat()
        val top = (topPct / 100.0 * h).toFloat()
        return IndicatorBounds(left, top, left + pxSize, top + pxSize)
    }

    /** Approximate on-screen footprint used ONLY by the touch editor. Renderers keep their geometry. */
    fun touchBounds(cfg: IndicatorConfig, w: Int, h: Int): IndicatorBounds {
        if (cfg.form == "map" || cfg.form == "static_map") return mapBounds(cfg, w, h)
        val cx = (cfg.x / 100.0 * w).toFloat()
        val cy = (cfg.y / 100.0 * h).toFloat()
        val size = cfg.double("size", 10.0).coerceIn(0.1, 100.0).toFloat()
        val fs = cfg.double("font_size", 2.0).coerceAtLeast(0.6).toFloat() / 100f * min(w, h)
        val bw: Float
        val bh: Float
        when (cfg.form) {
            "time_display" -> {
                // Time widget is left/top anchored, unlike gauges.
                return IndicatorBounds(cx, cy, cx + 0.22f * w, cy + 0.15f * h)
            }
            "chart" -> { bw = w * size / 100f; bh = max(bw * 0.4f, fs * 3f) }
            "bar", "segment_bar" -> {
                if (cfg.string("orientation", "horizontal").equals("vertical", true)) {
                    bw = max(w * 0.07f, fs * 5); bh = h * size / 100f
                } else {
                    bw = w * size / 100f; bh = max(h * 0.09f, fs * 3)
                }
            }
            "gauge", "compass", "lean" -> {
                bw = max(w * 0.06f, min(w, h) * size / 50f)
                bh = bw
            }
            else -> {
                // Text x/y positions are left anchored in Windows.
                return IndicatorBounds(cx, cy - fs, cx + max(w * 0.10f, fs * 7), cy + fs * 2)
            }
        }
        return IndicatorBounds(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
    }

    /**
     * Pick a visible widget underneath the finger. If bounds overlap, prefer
     * whichever centre is closer relative to the widget size.
     */
    fun hitTest(layout: HudLayout, w: Int, h: Int, x: Float, y: Float, touchPad: Float): String? =
        layout.indicators.values.asSequence().filter { it.enabled }
            .map { cfg -> cfg to touchBounds(cfg, w, h) }
            .filter { (_, b) -> b.expanded(touchPad).contains(x, y) }
            .minByOrNull { (_, b) ->
                hypot((x - b.centerX) / max(b.width, touchPad),
                      (y - b.centerY) / max(b.height, touchPad))
            }?.first?.key

    /** Restrict user dragging of top-left anchored maps to the video frame. */
    fun clampPosition(cfg: IndicatorConfig, w: Int, h: Int, xPct: Double, yPct: Double): Pair<Double, Double> {
        if ((cfg.form == "map" || cfg.form == "static_map") &&
            cfg.string("map_anchor", "").equals("topleft", ignoreCase = true)) {
            val widthPercent = cfg.double("size", 18.0)
            val heightPercent = widthPercent * w / h.toDouble()
            return xPct.coerceIn(0.0, max(0.0, 100.0 - widthPercent)) to
                yPct.coerceIn(0.0, max(0.0, 100.0 - heightPercent))
        }
        return xPct.coerceIn(0.0, 100.0) to yPct.coerceIn(0.0, 100.0)
    }
}
