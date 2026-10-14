package com.sportcamhud.core.render

import com.sportcamhud.core.layout.HudLayout
import com.sportcamhud.core.layout.IndicatorConfig
import com.sportcamhud.core.telemetry.FrameData
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Source of map tiles; returns null while a tile is not (yet) available. */
interface TileSource {
    fun tile(style: String, z: Int, x: Int, y: Int): HudImage?
}

/** Per-frame drawing context shared by all renderers. Sizes follow the Windows `s()` helper. */
class RenderContext(
    val canvas: HudCanvas,
    /** Logical canvas size (the video resolution); the platform canvas may be scaled. */
    val width: Int,
    val height: Int,
    val layout: HudLayout,
    val frame: FrameData?,
    val tiles: TileSource? = null,
    val caches: RenderCaches = RenderCaches(),
) {
    val minDim: Int = min(width, height)

    /** Global text outline: `text_outline * min_dim / 1000` (3 -> ~3 px at 1080p). */
    val outline: Float = max(0, (layout.textOutline * minDim / 1000.0).roundToInt()).toFloat()

    /** Percentage of [base] in pixels, at least 1 (`helpers.s`). */
    fun s(value: Double, base: Int): Float = max(1, (value / 100.0 * base).roundToInt()).toFloat()

    fun px(cfg: IndicatorConfig): Float = s(cfg.x, width)
    fun py(cfg: IndicatorConfig): Float = s(cfg.y, height)

    /** Indicator font size: `max(8, s(font_size, min_dim))`. */
    fun fontSize(cfg: IndicatorConfig): Float {
        val fsVal = if ("font_size" in cfg) cfg.double("font_size", 2.0) else cfg.double("size", 2.0)
        return max(8f, s(fsVal, minDim))
    }

    /** Scale factor relative to the 1080p reference the Windows renderers were tuned for. */
    val scale1080: Float get() = minDim / 1080f
}

/** Caches that survive between frames (chart histories, value ranges). */
class RenderCaches {
    val charts = HashMap<String, Any>()
    val ranges = HashMap<String, Pair<Double, Double>>()
    fun clear() {
        charts.clear()
        ranges.clear()
    }
}
