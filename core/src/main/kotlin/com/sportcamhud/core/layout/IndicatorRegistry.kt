package com.sportcamhud.core.layout

import com.sportcamhud.core.telemetry.Fields

/** Port of `indicators/registry.py`: default forms, chart colours, key -> field binding. */
object IndicatorRegistry {
    private val DEFAULT_FORM_RULES = listOf(
        Triple("passing_speedabs", "gauge", 12.0),
        Triple("passing_speed", "gauge", 12.0),
        Triple("speed", "gauge", 12.0),
        Triple("cad", "chart", 30.0),
        Triple("heart_rate", "chart", 30.0),
        Triple("hr", "chart", 30.0),
        Triple("power", "chart", 30.0),
        Triple("curvpower", "chart", 30.0),
        Triple("alt", "bar", 15.0),
        Triple("distance", "bar", 15.0),
        Triple("dist", "bar", 15.0),
        Triple("battery", "bar", 12.0),
        Triple("solar", "bar", 12.0),
        Triple("gopro_battery", "bar", 12.0),
        Triple("slope", "bar", 20.0),
        Triple("heading", "compass", 7.0),
        Triple("lean", "lean", 14.0),
    )

    private val CHART_COLOR_RULES = listOf(
        "speed" to 0xFFFF3232.toInt(),
        "cad" to 0xFFFF3232.toInt(),
        "alt" to 0xFF32C832.toInt(),
        "dist" to 0xFF3296FF.toInt(),
        "power" to 0xFFFFC832.toInt(),
        "hr" to 0xFFFF3296.toInt(),
        "heart" to 0xFFFF3296.toInt(),
        "battery" to 0xFF32FF32.toInt(),
    )

    fun formForKey(key: String): Pair<String, Map<String, Any?>> {
        val k = key.lowercase()
        for ((pattern, form, size) in DEFAULT_FORM_RULES) {
            if (pattern in k) {
                val o = mutableMapOf<String, Any?>("form" to form, "size" to size)
                when (pattern) {
                    "battery", "solar", "gopro_battery" -> { o["bar_style"] = "segments"; o["segments"] = 20 }
                    "slope" -> o["bar_style"] = "slope"
                    "heading" -> o["gauge_style"] = "compass"
                }
                return form to o
            }
        }
        return "text" to emptyMap()
    }

    fun chartColor(key: String): Int {
        val k = key.lowercase()
        return CHART_COLOR_RULES.firstOrNull { it.first in k }?.second ?: 0xFFC8C8C8.toInt()
    }

    /** Built-in indicator keys -> canonical telemetry field. */
    private val BUILTIN_FIELDS = mapOf(
        "speed_visual" to Fields.SPEED,
        "speed_text" to Fields.SPEED,
        "dist_visual" to Fields.DISTANCE,
        "dist_text" to Fields.DISTANCE,
        "alt_visual" to Fields.ALT,
        "alt_text" to Fields.ALT,
        "power_text" to Fields.POWER,
        "atemp_text" to Fields.TEMPERATURE,
        "hr_text" to Fields.HEART_RATE,
        "cad_text" to Fields.CADENCE,
        "battery_text" to Fields.BATTERY,
        "compass" to Fields.HEADING,
        "slope_text" to Fields.SLOPE,
        "iso_text" to "iso",
        "exposure_text" to "exposure",
        "temp_text" to "camera_temp",
        "lean_indicator" to "lean",
    )

    /** Windows FIT indicator keys look like `fit_<field>_text`; map them to dataset keys. */
    fun fieldFor(cfg: IndicatorConfig): String? {
        cfg.string("field", "").takeIf { it.isNotBlank() }?.let { return it }
        BUILTIN_FIELDS[cfg.key]?.let { return it }
        val k = cfg.key
        if (k.startsWith("fit_")) {
            val base = k.removeSuffix("_text").removeSuffix("_visual").removePrefix("fit_")
            return when (base) {
                "speed", "enhanced_speed" -> Fields.SPEED
                "distance" -> Fields.DISTANCE
                "altitude", "enhanced_altitude" -> Fields.ALT
                "heart_rate" -> Fields.HEART_RATE
                "cadence" -> Fields.CADENCE
                "power" -> Fields.POWER
                "temperature" -> Fields.TEMPERATURE
                else -> "fit_$base"
            }
        }
        return null
    }
}
