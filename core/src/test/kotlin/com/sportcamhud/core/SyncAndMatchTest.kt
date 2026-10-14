package com.sportcamhud.core

import com.sportcamhud.core.garmin.ActivityCandidate
import com.sportcamhud.core.garmin.ActivityMatcher
import com.sportcamhud.core.telemetry.Series
import com.sportcamhud.core.telemetry.TimeSync
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SyncAndMatchTest {
    private val h = 3_600_000L
    private val fitStart = 1_790_000_000_000L
    private val fitEnd = fitStart + 2 * h

    @Test
    fun `smart sync - direct, timezone and fallback`() {
        assertEquals(TimeSync.Method.ABSOLUTE_TIME, TimeSync.smartOffset(fitStart, fitEnd, fitStart + h).method)
        // Camera stores local time (UTC+2) labelled as UTC.
        val tz = TimeSync.smartOffset(fitStart, fitStart + h, fitStart + 2 * h + 30 * 60_000)
        assertEquals(TimeSync.Method.TIMEZONE_FALLBACK, tz.method)
        assertEquals(2 * h, tz.offsetMs)
        val far = TimeSync.smartOffset(fitStart, fitEnd, fitStart + 400 * h + 123)
        assertEquals(TimeSync.Method.ALIGN_START, far.method)
        assertEquals(400 * h + 123, far.offsetMs)
    }

    @Test
    fun `matcher prefers the overlapping activity`() {
        val vStart = fitStart + 10 * 60_000
        val vEnd = vStart + 20 * 60_000
        val good = ActivityCandidate("1", "garmin", "Ride", "cycling", fitStart, fitStart + 40 * 60_000, 2400.0)
        val other = ActivityCandidate("2", "garmin", "Run", "running", fitStart + 5 * h, fitStart + 6 * h, 3600.0)
        val (decision, ranked) = ActivityMatcher.rank(listOf(other, good), vStart, vEnd)
        assertEquals("1", ranked.first().candidate.activityId)
        assertEquals(ActivityMatcher.Decision.NEEDS_SELECTION, decision) // 0.55 + 0.30*0.83 + 0.15*0.5 = 0.875
        val exact = ActivityCandidate("3", "garmin", "Ride", "cycling", vStart, vEnd, 1200.0)
        assertEquals(ActivityMatcher.Decision.AUTO_ACCEPT, ActivityMatcher.rank(listOf(exact, other), vStart, vEnd).first)
    }

    @Test
    fun `series interpolation holds across pauses`() {
        val s = Series.of(listOf(0L to 0.0, 1000L to 10.0, 60_000L to 20.0))
        assertEquals(5.0, s.linear(500)!!, 1e-9)
        assertEquals(10.0, s.linear(30_000)!!, 1e-9) // gap > 10 s -> hold
        assertNull(s.linear(70_000))
        val a = Series.of(listOf(0L to 350.0, 1000L to 10.0))
        assertEquals(0.0, a.angular(500)!!, 1e-9)
    }
}
