package com.sportcamhud.core

import com.sportcamhud.core.layout.HudLayout
import com.sportcamhud.core.layout.IndicatorRegistry
import com.sportcamhud.core.telemetry.Fields
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Tests the actual Windows def_layout.json bundled by Android.
 * Unsupported display settings must survive JSON round trips unchanged.
 */
class WindowsLayoutCompatibilityTest {
    private val source get() = File("../app/src/main/assets/def_layout.json").readText(Charsets.UTF_8)

    @Test
    fun `Windows default layout imports with no surprise indicators`() {
        val layout = HudLayout.fromJson(source)
        assertEquals(32, layout.indicators.size)
        assertEquals(17, layout.indicators.values.count { it.enabled })
        assertEquals("map", layout.indicators.getValue("track_map").form)
        assertEquals("gauge", layout.indicators.getValue("compass").form)
        assertTrue("integrations" in layout.extra)
        assertTrue("smoothing" in layout.extra)
    }

    @Test
    fun `Windows layout round trips without losing indicator or global settings`() {
        val before = HudLayout.fromJson(source)
        val after = HudLayout.fromJson(before.toJson())
        assertEquals(before.indicators.keys, after.indicators.keys)
        assertEquals(before.global, after.global)
        assertEquals(before.extra, after.extra)
        for ((key, cfg) in before.indicators) {
            assertEquals(cfg.props, after.indicators.getValue(key).props, key)
        }
    }

    @Test
    fun `Windows field names resolve to Android dataset keys`() {
        val layout = HudLayout.fromJson(source)
        assertEquals(Fields.SPEED, IndicatorRegistry.fieldFor(layout.indicators.getValue("speed_text")))
        assertEquals(Fields.BATTERY, IndicatorRegistry.fieldFor(layout.indicators.getValue("fit_garmin_battery_percent_text")))
        assertTrue("fit_curvpower" in IndicatorRegistry.fieldCandidates(layout.indicators.getValue("fit_curVpower_text")))
        assertTrue("fit_dev_curvpower" in IndicatorRegistry.fieldCandidates(layout.indicators.getValue("fit_curVpower_text")))
        assertEquals("camera_temp", IndicatorRegistry.fieldFor(layout.indicators.getValue("temp_text")))
        assertEquals(Fields.HEADING, IndicatorRegistry.fieldFor(layout.indicators.getValue("compass")))
    }
}
