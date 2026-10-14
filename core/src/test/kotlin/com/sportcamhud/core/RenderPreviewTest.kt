package com.sportcamhud.core

import com.sportcamhud.core.fit.FitReader
import com.sportcamhud.core.layout.DefaultLayout
import com.sportcamhud.core.layout.HudLayout
import com.sportcamhud.core.render.HudCompositor
import com.sportcamhud.core.render.TileSource
import com.sportcamhud.core.telemetry.Fields
import com.sportcamhud.core.telemetry.TelemetryTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.GradientPaint
import java.awt.image.BufferedImage
import java.io.File
import java.time.ZoneOffset
import javax.imageio.ImageIO

/** Renders HUD frames to PNG (build/hud-preview) so the look can be checked by eye. */
class RenderPreviewTest {
    @TempDir
    lateinit var dir: File

    private fun background(w: Int, h: Int): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.paint = GradientPaint(0f, 0f, Color(70, 110, 150), 0f, h.toFloat(), Color(60, 85, 50))
        g.fillRect(0, 0, w, h)
        g.dispose()
        return img
    }

    private fun out(name: String): File {
        val d = File(System.getProperty("hud.previewDir") ?: "build/hud-preview").apply { mkdirs() }
        return File(d, name)
    }

    @Test
    fun `renders the default layout plus extra indicators`() {
        val ds = FitReader.read(SampleFit.write(File(dir, "r.fit")).readBytes())
        var layout = DefaultLayout.create()
        layout = layout.replace(layout.indicators.getValue("compass").with("enabled", true))
        layout = layout.replace(layout.indicators.getValue("slope_text").with("enabled", true))
        layout = layout.replace(layout.indicators.getValue("track_map").withAll(mapOf("enabled" to true, "zoom" to 14)))
        val hr = DefaultLayout.newIndicatorFor(Fields.HEART_RATE, "HR", layout.indicators.keys)
            .withAll(mapOf("x" to 84.0, "y" to 30.0, "size" to 22.0))
        layout = layout.replace(hr)
        val bat = DefaultLayout.newIndicatorFor(Fields.BATTERY, "Bateria", layout.indicators.keys)
            .withAll(mapOf("x" to 88.0, "y" to 50.0))
        layout = layout.replace(bat)

        val start = SampleFit.START.toEpochMilli()
        val tl = TelemetryTimeline(ds, start + 120_000, 600_000, ZoneOffset.UTC)
        val comp = HudCompositor()
        val tiles = object : TileSource {
            // Checkerboard stand-in for map tiles (no network in tests).
            val img = AwtHudCanvas.Img(BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB).also {
                val g = it.createGraphics()
                g.color = Color(225, 225, 220); g.fillRect(0, 0, 256, 256)
                g.color = Color(200, 205, 200); g.fillRect(0, 0, 128, 128); g.fillRect(128, 128, 128, 128)
                g.dispose()
            })
            override fun tile(style: String, z: Int, x: Int, y: Int) = img
        }
        for ((name, w, h) in listOf(Triple("hud_1080p.png", 1920, 1080), Triple("hud_preview_small.png", 960, 540))) {
            val img = background(w, h)
            val canvas = AwtHudCanvas(img)
            comp.render(canvas, w, h, layout, tl.frameAt(200_000), tiles)
            ImageIO.write(img, "png", out(name))
        }
        assertTrue(out("hud_1080p.png").length() > 10_000)
    }

    @Test
    fun `layout json round trip keeps windows keys`() {
        val json = DefaultLayout.create().toJson()
        val back = HudLayout.fromJson(json)
        assertEquals(DefaultLayout.create().indicators.keys, back.indicators.keys)
        val windowsPreset = """{"version":6,"global":{"text_outline":4},"indicators":{"speed_visual":{"form":"gauge","x":40,"y":70,"size":12,"max_val":80},"fit_power_text":{"form":"chart","x":20,"y":30}}}"""
        val merged = HudLayout.fromJsonMergedWithDefaults(windowsPreset)
        assertEquals(4, merged.textOutline)
        assertEquals(80.0, merged.indicators.getValue("speed_visual").double("max_val", 0.0))
        assertEquals(10.8 to 12.0, 10.8 to merged.indicators.getValue("speed_visual").double("size", 0.0))
        assertTrue("fit_power_text" in merged.indicators)
        assertTrue("time_display" in merged.indicators)
    }
}
