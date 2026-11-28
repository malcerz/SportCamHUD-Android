package com.sportcamhud.core

import com.sportcamhud.core.layout.HudLayout
import com.sportcamhud.core.layout.IndicatorConfig
import com.sportcamhud.core.render.IndicatorPlacement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class IndicatorPlacementTest {
    private val windows get() = HudLayout.fromJson(File("../app/src/main/assets/def_layout.json").readText())

    @Test
    fun `Windows default map uses TOP LEFT x and y and remains fully visible`() {
        val cfg = windows.indicators.getValue("track_map")
        assertEquals("topleft", cfg.string("map_anchor", ""))
        val rect = IndicatorPlacement.mapBounds(cfg, 1920, 1080)
        assertEquals(22.08f, rect.left, 0.1f)
        assertEquals(157.464f, rect.top, 0.1f)
        assertEquals(288f, rect.width, 0.1f)
        assertEquals(288f, rect.height, 0.1f)
        assertTrue(rect.right < 1920)
        assertTrue(rect.bottom < 1080)
    }

    @Test
    fun `Legacy map without explicit anchor resolves to Windows v2 geometry`() {
        val cfg = IndicatorConfig("track_map", JsonObject(mapOf(
            "form" to JsonPrimitive("map"),
            "x" to JsonPrimitive(12.0),
            "y" to JsonPrimitive(30.0),
            "size" to JsonPrimitive(18.0),
        )))
        val rect = IndicatorPlacement.mapBounds(cfg, 1920, 1080)
        assertEquals(57.6f, rect.left, 0.1f)
        assertEquals(151.2f, rect.top, 0.1f)
        assertEquals(345.6f, rect.width, 0.1f)
    }

    @Test
    fun `Tapping on map selects map and finger drag preserves its top-left contract`() {
        val layout = windows
        val rect = IndicatorPlacement.mapBounds(layout.indicators.getValue("track_map"), 1920, 1080)
        val selected = IndicatorPlacement.hitTest(
            layout, 1920, 1080,
            rect.centerX, rect.centerY, touchPad = 25f,
        )
        assertEquals("track_map", selected)
        val (x, y) = IndicatorPlacement.clampPosition(layout.indicators.getValue("track_map"),
            1920, 1080, 98.0, 92.0)
        assertEquals(85.0, x, 0.0001)
        assertEquals(73.333333, y, 0.001)
        assertNotNull(layout.indicators.getValue("track_map"))
    }
}
