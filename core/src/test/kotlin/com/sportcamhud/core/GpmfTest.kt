package com.sportcamhud.core

import com.sportcamhud.core.fit.FitReader
import com.sportcamhud.core.gpmf.FileByteSource
import com.sportcamhud.core.gpmf.GpmfParser
import com.sportcamhud.core.gpmf.Mp4MetaTrack
import com.sportcamhud.core.layout.DefaultLayout
import com.sportcamhud.core.render.HudCompositor
import com.sportcamhud.core.telemetry.CameraTelemetry
import com.sportcamhud.core.telemetry.Fields
import com.sportcamhud.core.telemetry.TelemetryTimeline
import com.sportcamhud.core.telemetry.TimeSync
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.File
import java.time.ZoneOffset
import javax.imageio.ImageIO
import kotlin.math.abs

class GpmfTest {
    @TempDir
    lateinit var dir: File

    private val utcStart = SampleFit.START.toEpochMilli() + SampleGoPro.FIT_SECOND * 1000L

    private fun read(gps9: Boolean = true): CameraTelemetry {
        val f = SampleGoPro.write(File(dir, "GX01.MP4"), utcStart, gps9)
        return FileByteSource(f).use { GpmfParser.read(it) }!!
    }

    @Test
    fun `finds the gpmd track and its timing`() {
        val f = SampleGoPro.write(File(dir, "a.MP4"), utcStart)
        val samples = FileByteSource(f).use { Mp4MetaTrack.samples(it) }!!
        assertEquals(SampleGoPro.SECONDS, samples.size)
        assertEquals(0.0, samples[0].startMs)
        assertEquals(59_000.0, samples.last().startMs)
        assertEquals(1000.0, samples[3].durationMs)
    }

    @Test
    fun `GPS9 gives position, speed and the true UTC start`() {
        val cam = read(gps9 = true)
        assertEquals("HERO11 Black", cam.deviceName)
        assertEquals(utcStart.toDouble(), cam.utcStartMs!!.toDouble(), 5.0)
        assertEquals(SampleGoPro.SECONDS * 10, cam.gps.size)
        val g = cam.gps[150] // 15 s into the video
        assertEquals(SampleGoPro.lat(SampleGoPro.FIT_SECOND + 15.0), g.lat, 1e-6)
        assertEquals(SampleGoPro.lon(SampleGoPro.FIT_SECOND + 15.0), g.lon, 1e-6)
        assertEquals(230.5, g.alt, 1e-6)
        val expectedKmh = (7.0 + 2.0 * Math.sin((SampleGoPro.FIT_SECOND + 15.0) / 60.0)) * 3.6
        assertEquals(expectedKmh, g.speed3dKmh!!, 0.05)
    }

    @Test
    fun `GPS5 with GPSU works for older cameras`() {
        val cam = read(gps9 = false)
        assertEquals(utcStart.toDouble(), cam.utcStartMs!!.toDouble(), 5.0)
        assertEquals(SampleGoPro.SECONDS * 10, cam.gps.size)
        assertEquals(SampleGoPro.lat(SampleGoPro.FIT_SECOND + 30.0), cam.gps[300].lat, 1e-6)
    }

    @Test
    fun `ISO, shutter and camera temperature`() {
        val cam = read()
        assertEquals(SampleGoPro.ISO.toDouble(), cam.valueAt(CameraTelemetry.ISO, 12_345))
        assertEquals(SampleGoPro.SHUTTER_DENOMINATOR.toDouble(), cam.valueAt(CameraTelemetry.EXPOSURE, 12_345))
        assertEquals(SampleGoPro.TEMP_C, cam.valueAt(CameraTelemetry.CAMERA_TEMP, 12_345)!!, 0.01)
        assertNull(cam.valueAt(CameraTelemetry.ISO, 120_000), "no value far past the end of the video")
    }

    @Test
    fun `lean follows the simulated roll`() {
        val cam = read()
        assertTrue(cam.hasImu)
        val lean = cam.leanSeries("y", 0.0)
        for (t in listOf(2.5, 7.5, 12.0, 31.3)) {
            val got = lean.linear((t * 1000).toLong(), 1000)!!
            assertTrue(abs(got - SampleGoPro.rollDeg(t)) < 1.5, "t=$t got=$got want=${SampleGoPro.rollDeg(t)}")
        }
        // Smoothing keeps the angle but flattens it slightly.
        val smooth = cam.leanSeries("y", 1.0).linear(2500, 1000)!!
        assertTrue(smooth in 10.0..15.5, "smoothed=$smooth")
    }

    @Test
    fun `GoPro GPS alone is a telemetry source`() {
        val cam = read()
        val ds = cam.toDataset(cam.utcStartMs!!)!!
        assertEquals("gopro", ds.sourceName)
        for (f in listOf(Fields.SPEED, Fields.DISTANCE, Fields.ALT, Fields.HEADING)) assertTrue(ds.has(f), f)
        val tl = TelemetryTimeline(ds, cam.utcStartMs!!, 60_000, ZoneOffset.UTC, camera = cam)
        val frame = tl.frameAt(30_000)
        assertNotNull(frame.values[Fields.SPEED])
        assertEquals(SampleGoPro.ISO.toDouble(), frame.values[CameraTelemetry.ISO])
        assertTrue(frame.has(CameraTelemetry.LEAN))
    }

    @Test
    fun `SmartSync aligns the FIT track to the camera GPS`() {
        val cam = read()
        val fit = FitReader.read(SampleFit.write(File(dir, "r.fit")).readBytes())
        val camTrack = cam.relativeTrack().map { it.copy(time = it.time + cam.utcStartMs!!) }
        // FIT clock exactly right: no correction.
        val ok = TimeSync.smartOffset(fit.start!!, fit.end!!, cam.utcStartMs, fit.track, camTrack)
        assertEquals(TimeSync.Method.GPS_TRACK, ok.method)
        assertEquals(0L, ok.offsetMs)
        // FIT clock 4 s late: the track match finds +4 s.
        val late = fit.shifted(-4000)
        val r = TimeSync.smartOffset(late.start!!, late.end!!, cam.utcStartMs, late.track, camTrack)
        assertEquals(TimeSync.Method.GPS_TRACK, r.method)
        assertEquals(4000.0, r.offsetMs.toDouble(), 300.0)
    }

    @Test
    fun `renders GoPro indicators`() {
        val cam = read()
        val fit = FitReader.read(SampleFit.write(File(dir, "r2.fit")).readBytes())
        val tl = TelemetryTimeline(fit, cam.utcStartMs!!, 60_000, ZoneOffset.UTC, camera = cam)
        val frame = tl.frameAt(2_500)
        val comp = HudCompositor()
        val layout = DefaultLayout.create()
        val iso = comp.bind(layout.indicators.getValue("iso_text"), frame)!!
        assertEquals(SampleGoPro.ISO.toDouble(), iso.value)

        val w = 1920
        val h = 1080
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB)
        val g = img.createGraphics()
        g.color = Color(70, 100, 130); g.fillRect(0, 0, w, h); g.dispose()
        comp.render(AwtHudCanvas(img), w, h, layout, frame)
        val outDir = File(System.getProperty("hud.previewDir") ?: "build/hud-preview").apply { mkdirs() }
        ImageIO.write(img, "png", File(outDir, "hud_gopro_1080p.png"))

        // Without camera data the GoPro-only indicators are hidden, not drawn empty.
        val plain = TelemetryTimeline(fit, cam.utcStartMs!!, 60_000, ZoneOffset.UTC).frameAt(2_500)
        assertNull(comp.bind(layout.indicators.getValue("iso_text"), plain))
    }
}
