package com.sportcamhud.core.render

import com.sportcamhud.core.layout.HudLayout
import com.sportcamhud.core.layout.IndicatorConfig
import com.sportcamhud.core.layout.IndicatorRegistry
import com.sportcamhud.core.telemetry.Fields
import com.sportcamhud.core.telemetry.FrameData
import com.sportcamhud.core.telemetry.TelemetryDataset
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Draws the complete HUD for one frame (port of `compose_overlay`): binds every enabled
 * indicator to its telemetry field and dispatches to the per-form renderer.
 */
class HudCompositor(private val caches: RenderCaches = RenderCaches()) {

    /** Streams that only GoPro GPMF metadata provides; hidden on Android. */
    private val gpmfOnly = setOf("iso", "exposure", "camera_temp", "lean")

    fun invalidate() = caches.clear()

    /**
     * @param canvas platform canvas; may be scaled so that [width] x [height] (the video
     * resolution) maps onto the view, keeping preview and export identical.
     */
    fun render(canvas: HudCanvas, width: Int, height: Int, layout: HudLayout, frame: FrameData?, tiles: TileSource? = null) {
        val ctx = RenderContext(canvas, width, height, layout, frame, tiles, caches)
        // Maps first so that other indicators stay readable on top of them.
        val ordered = layout.indicators.values.filter { it.enabled }.sortedBy { if (it.form == "map" || it.form == "static_map") 0 else 1 }
        for (cfg in ordered) {
            try {
                renderOne(ctx, cfg, frame)
            } catch (e: Exception) {
                // One broken indicator must never break the whole frame (or an export).
            }
        }
    }

    data class Bound(val field: String?, val value: Double?, val unit: String, val label: String, val decimals: Int)

    fun bind(cfg: IndicatorConfig, frame: FrameData?): Bound? {
        val ds = frame?.dataset
        val field = IndicatorRegistry.fieldFor(cfg)
        if (field != null) {
            if (field in gpmfOnly) return null
            if (ds != null && !ds.has(field)) return null
        }
        val info = field?.let { ds?.info(it) ?: Fields.INFO[it] }
        val raw = field?.let { frame?.values?.get(it) }
        val value = raw?.let { convert(field, it) }
        val unit = cfg.string("unit", info?.unit ?: "")
        val decimals = if ("decimals" in cfg) cfg.int("decimals", 0) else info?.decimals ?: 1
        return Bound(field, value, unit, cfg.label, decimals)
    }

    private fun convert(field: String, v: Double): Double =
        if (field == Fields.DISTANCE || field.contains("distance")) v / 1000.0 else v

    private fun renderOne(ctx: RenderContext, cfg: IndicatorConfig, frame: FrameData?) {
        when (cfg.form) {
            "time_display" -> TimeDisplayRenderer.draw(ctx, cfg, frame)
            "map", "static_map" -> MapRenderer.draw(ctx, cfg, frame)
            "lean" -> Unit // needs GoPro gyroscope data (GPMF)
            else -> {
                val b = bind(cfg, frame) ?: return
                val withUnit = cfg.bool("show_units", true)
                when (cfg.form) {
                    "gauge" -> {
                        if (cfg.string("gauge_style", "") == "compass") {
                            CompassRenderer.draw(ctx, cfg, b.value)
                        } else {
                            val txt = ValueFormat.format(b.value, if ("decimals" in cfg) b.decimals else 0, b.unit, cfg.bool("show_units", false))
                            GaugeRenderer.draw(ctx, cfg, b.value, txt)
                        }
                    }
                    "compass" -> CompassRenderer.draw(ctx, cfg, b.value)
                    "bar", "segment_bar" -> {
                        val range = range(ctx, cfg, b.field, frame?.dataset)
                        BarRenderer.draw(ctx, cfg, b.value, ValueFormat.format(b.value, b.decimals, b.unit, withUnit), b.unit, b.label, range)
                    }
                    "chart" -> {
                        val field = b.field ?: return
                        ChartRenderer.draw(ctx, cfg, field, frame, b.value, ValueFormat.format(b.value, b.decimals, b.unit, withUnit), b.label) { convert(field, it) }
                    }
                    else -> {
                        val v = ValueFormat.format(b.value, b.decimals, b.unit, withUnit)
                        val text = if (b.label.isNotEmpty()) "${b.label}: $v" else v
                        TextRenderer.draw(ctx, cfg, text)
                    }
                }
            }
        }
    }

    /** Bar scale: explicit min/max, or (auto_range) the activity range of the field. */
    private fun range(ctx: RenderContext, cfg: IndicatorConfig, field: String?, ds: TelemetryDataset?): Pair<Double, Double> {
        val explicit = cfg.double("min_val", 0.0) to cfg.double("max_val", 100.0)
        if (!cfg.bool("auto_range", false) || field == null || ds == null) return explicit
        val key = "${cfg.key}|$field"
        ctx.caches.ranges[key]?.let { return it }
        val s = ds[field] ?: return explicit
        val lo = convert(field, s.minValue())
        val hi = convert(field, s.maxValue())
        val r = when (field) {
            Fields.DISTANCE -> 0.0 to niceCeil(hi)
            Fields.ALT -> floor(lo / 10) * 10 to ceil(hi / 10) * 10
            Fields.BATTERY -> 0.0 to 100.0
            else -> floor(lo) to ceil(hi)
        }.let { if (it.second <= it.first) it.first to it.first + 1 else it }
        ctx.caches.ranges[key] = r
        return r
    }

    private fun niceCeil(v: Double): Double = when {
        v <= 1 -> ceil(v * 10) / 10
        v <= 20 -> ceil(v)
        v <= 100 -> ceil(v / 5) * 5
        else -> ceil(v / 10) * 10
    }
}
