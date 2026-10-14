package com.sportcamhud.core.layout

import com.sportcamhud.core.telemetry.Fields
import kotlinx.serialization.json.JsonObject

/** Built-in layout of the Windows app (`layout_manager.default_layout`) plus FIT extras. */
object DefaultLayout {
    private fun ind(key: String, vararg p: Pair<String, Any?>): IndicatorConfig =
        IndicatorConfig(key, JsonObject(p.associate { it.first to IndicatorConfig.toJson(it.second) } + ("font" to IndicatorConfig.toJson(""))))

    private fun textInd(key: String, label: String, x: Double, min: Int, max: Int) = ind(
        key, "enabled" to true, "label" to label, "x" to x, "y" to 8.0, "rotation" to 0, "form" to "text",
        "font_size" to 1.8, "size" to 10.0, "thickness" to 0.1, "min_val" to min, "max_val" to max, "ticks" to 0,
    )

    fun create(): HudLayout {
        val list = listOf(
            ind(
                "time_display", "enabled" to true, "label" to "Czas", "x" to 2.0, "y" to 3.0,
                "rotation" to 0, "form" to "time_display", "size" to 1.0, "icon" to "clock",
                "show_date" to true, "show_time" to true, "show_elapsed" to true,
                "show_avg_speed" to true, "show_date_label" to true, "date_label" to "Data",
                "show_time_label" to true, "time_label" to "Godzina",
                "show_elapsed_label" to true, "elapsed_label" to "Czas",
                "show_avg_speed_label" to true, "avg_speed_label" to "Średnia prędkość",
                "font_size" to 1.8, "date_font_size" to 1.2, "time_font_size" to 1.9,
                "elapsed_font_size" to 1.5, "avg_speed_font_size" to 1.5,
            ),
            ind(
                "speed_visual", "enabled" to true, "label" to "", "x" to 50.0, "y" to 78.0, "rotation" to 0, "form" to "gauge",
                "font_size" to 1.25, "size" to 10.8, "thickness" to 0.7, "min_val" to 0, "max_val" to 60, "ticks" to 6,
                "source" to "fit",
            ),
            ind(
                "speed_text", "enabled" to true, "label" to "", "x" to 50.0, "y" to 85.5, "rotation" to 0, "form" to "text",
                "font_size" to 4.2, "size" to 10.0, "thickness" to 0.1, "min_val" to 0, "max_val" to 100, "ticks" to 0,
                "source" to "fit", "align" to "center",
            ),
            ind(
                "dist_visual", "enabled" to true, "label" to "", "x" to 50.0, "y" to 92.5, "rotation" to 0, "form" to "bar",
                "font_size" to 1.25, "size" to 20.0, "thickness" to 0.4, "min_val" to 0, "max_val" to 10, "ticks" to 5,
                "show_range_labels" to true, "range_label_offset_x" to 0.0, "range_label_offset_y" to 0.0,
                "range_label_spread_x" to 0.0, "value_offset_x" to 0.0, "value_offset_y" to 0.0, "source" to "fit",
                "auto_range" to true,
            ),
            ind(
                "dist_text", "enabled" to true, "label" to "", "x" to 50.0, "y" to 95.5, "rotation" to 0, "form" to "text",
                "font_size" to 1.7, "size" to 10.0, "thickness" to 0.1, "min_val" to 0, "max_val" to 100, "ticks" to 0,
                "source" to "fit", "align" to "center",
            ),
            ind(
                "alt_visual", "enabled" to true, "label" to "Alt", "x" to 4.0, "y" to 80.0, "rotation" to 90, "form" to "bar",
                "font_size" to 1.25, "size" to 20.0, "thickness" to 0.6, "min_val" to 0, "max_val" to 100, "ticks" to 5,
                "show_range_labels" to true, "range_label_offset_x" to 0.0, "range_label_offset_y" to 0.0,
                "range_label_spread_x" to 0.0, "value_offset_x" to 0.0, "value_offset_y" to 0.0, "source" to "fit",
                "auto_range" to true,
            ),
            ind(
                "alt_text", "enabled" to true, "label" to "", "x" to 2.5, "y" to 80.0, "rotation" to 0, "form" to "text",
                "font_size" to 1.7, "size" to 10.0, "thickness" to 0.1, "min_val" to 0, "max_val" to 1000, "ticks" to 0,
                "source" to "fit",
            ),
            // GoPro-only (GPMF) streams: kept for preset compatibility, hidden without data.
            textInd("iso_text", "ISO", 90.0, 0, 12800),
            textInd("exposure_text", "Exp", 82.0, 0, 10000),
            textInd("temp_text", "Temp", 74.0, 0, 100),
            textInd("power_text", "Moc", 18.5, 0, 1000),
            textInd("atemp_text", "ATemp", 26.5, -20, 60),
            textInd("hr_text", "HR", 34.5, 0, 250),
            textInd("cad_text", "Cad", 41.0, 0, 200),
            textInd("battery_text", "Bat", 49.0, 0, 100),
            ind(
                "track_map", "enabled" to false, "label" to "Mapa", "x" to 12.0, "y" to 30.0, "rotation" to 0, "form" to "map",
                "font_size" to 1.2, "size" to 18.0, "thickness" to 1, "zoom" to 16,
                "gps_source" to "auto", "source" to "fit", "map_style" to "light_all", "map_shape" to "square",
                "map_orientation" to "north_up", "map_rotation_smoothing_s" to 0.0,
                "min_val" to 0, "max_val" to 1, "ticks" to 0, "marker_size" to 7, "marker_color" to "#FFFFFF",
            ),
            ind(
                "compass", "enabled" to false, "label" to "", "x" to 90.0, "y" to 80.0, "rotation" to 0, "form" to "compass",
                "gauge_style" to "compass", "font_size" to 1.3, "size" to 7.0, "field" to Fields.HEADING,
                "compass_show_cardinals" to true, "compass_show_heading" to true,
                "compass_tick_degrees" to 15, "compass_major_tick_degrees" to 45,
            ),
            ind(
                "lean_indicator", "enabled" to true, "label" to "Przechył", "x" to 90.0, "y" to 55.0, "rotation" to 0,
                "form" to "lean", "font_size" to 1.6, "size" to 9.0, "source" to "gyro", "axis" to "y",
                "calibration" to 0.0, "invert_axis" to false, "sensitivity" to 1.0, "max_angle" to 30.0,
                "lean_smoothing_s" to 0.0, "graphic" to "bike", "show_reference" to true, "show_ticks" to true,
                "track_color" to "#FFFFFF", "tick_color" to "#F6F6F6", "marker_color" to "#FFFFFF",
                "show_value" to true, "show_label" to true, "decimals" to 0,
            ),
            ind(
                "slope_text", "enabled" to false, "label" to "Nachylenie", "x" to 96.0, "y" to 60.0, "rotation" to 0,
                "form" to "bar", "bar_style" to "slope", "font_size" to 1.25, "size" to 15.0, "thickness" to 0.6,
                "min_val" to -15, "max_val" to 15, "ticks" to 6, "field" to Fields.SLOPE,
            ),
        )
        return HudLayout(LinkedHashMap(list.associateBy { it.key }))
    }

    /**
     * Creates an indicator for an arbitrary telemetry field (the "Dodaj wskaźnik" action),
     * using the default form rules of `indicators/registry.py`.
     */
    fun newIndicatorFor(field: String, label: String, existing: Set<String>): IndicatorConfig {
        val (form, overrides) = IndicatorRegistry.formForKey(field)
        var key = "${field}_$form"
        var n = 2
        while (key in existing) key = "${field}_${form}_${n++}"
        val base = mutableMapOf<String, Any?>(
            "enabled" to true, "label" to label, "x" to 50.0, "y" to 50.0, "rotation" to 0,
            "form" to form, "font_size" to 1.6, "size" to 10.0, "thickness" to 1, "ticks" to 5,
            "field" to field, "source" to "fit", "font" to "", "auto_range" to true,
        )
        base.putAll(overrides)
        return IndicatorConfig(key, JsonObject(base.mapValues { IndicatorConfig.toJson(it.value) }))
    }
}
