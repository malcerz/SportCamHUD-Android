package com.sportcamhud.core

import com.sportcamhud.core.fit.FitParseException
import com.sportcamhud.core.fit.FitReader
import com.sportcamhud.core.telemetry.Fields
import com.sportcamhud.core.telemetry.TelemetryTimeline
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.time.ZoneOffset

class FitReaderTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun `reads records, units and derived streams`() {
        val bytes = SampleFit.write(File(dir, "ride.fit")).readBytes()
        val ds = FitReader.read(bytes)
        val start = SampleFit.START.toEpochMilli()

        for (f in listOf(Fields.SPEED, Fields.DISTANCE, Fields.ALT, Fields.HEART_RATE, Fields.CADENCE,
            Fields.POWER, Fields.TEMPERATURE, Fields.HEADING, Fields.SLOPE, Fields.BATTERY)) {
            assertTrue(ds.has(f), "missing $f")
        }
        // 7 m/s + 2 sin(t/60) -> km/h
        val sp = ds.valueAt(Fields.SPEED, start + 90_000)!!
        assertEquals((7.0 + 2.0 * Math.sin(90 / 60.0)) * 3.6, sp, 0.5)
        assertEquals(SampleFit.SECONDS + 1 - 60, ds.track.size)
        assertEquals(50.0614, ds.track[0].lat, 1e-4)
        assertEquals(7200, ds.timezoneOffsetSec)
        assertEquals(87.0, ds.valueAt(Fields.BATTERY, start + 10_000))

        // Pause 600..660 s excluded from the active clock.
        val active = ds.activeTime!!
        assertEquals(599.0, active.activeSecondsAt(start + 599_000), 0.01)
        assertEquals(599.0 + 40.0, active.activeSecondsAt(start + 700_000), 0.01)

        // Heading must be a valid bearing; slope stays within the clamp.
        assertTrue(ds[Fields.HEADING]!!.values.all { it in 0.0..360.0 })
        assertTrue(ds[Fields.SLOPE]!!.values.all { it in -30.0..30.0 })

        val summary = FitReader.summary(bytes)
        assertEquals("cycling", summary.sport)
        assertTrue(summary.hasGps)
    }

    @Test
    fun `distance resets are normalised to a monotonic total`() {
        val ds = FitReader.read(SampleFit.write(File(dir, "reset.fit"), distanceResetAt = 300).readBytes())
        val d = ds[Fields.DISTANCE]!!
        for (i in 1 until d.size) assertTrue(d.values[i] >= d.values[i - 1])
        assertTrue(d.values.last() > 7000.0)
    }

    @Test
    fun `timeline gives local time, active elapsed and average speed`() {
        val ds = FitReader.read(SampleFit.write(File(dir, "t.fit")).readBytes())
        val start = SampleFit.START.toEpochMilli()
        val tl = TelemetryTimeline(ds, start + 60_000, 600_000, ZoneOffset.UTC)
        val f = tl.frameAt(30_000)
        assertEquals("2026-10-02", f.dateText)
        assertEquals("08:28:17", f.timeText) // 06:28:17Z in CEST
        assertEquals(90.0, f.elapsedSeconds, 0.01)
        assertTrue(f.avgSpeedKmh in 20.0..35.0, "avg ${f.avgSpeedKmh}")
        assertNotNull(f.values[Fields.SPEED])
    }

    @Test
    fun `rejects non-FIT data`() {
        assertThrows<FitParseException> { FitReader.read(ByteArray(64) { 1 }) }
    }
}
