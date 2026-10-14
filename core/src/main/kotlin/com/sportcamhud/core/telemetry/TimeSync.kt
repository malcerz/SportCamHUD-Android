package com.sportcamhud.core.telemetry

import kotlin.math.abs

/**
 * SmartSync: aligns the telemetry clock with the video clock
 * (port of `_compute_smart_time_offset` and `_align_offset_by_track` from the Windows app).
 */
object TimeSync {
    enum class Method { NO_VIDEO_START, GPS_TRACK, ABSOLUTE_TIME, TIMEZONE_FALLBACK, ALIGN_START }

    data class Result(val offsetMs: Long, val method: Method, val timezoneHours: Int = 0)

    private const val MARGIN_MS = 5 * 60_000L

    /**
     * @param recordsStart first telemetry timestamp (epoch ms)
     * @param recordsEnd last telemetry timestamp (epoch ms)
     * @param videoStart video creation time as stored in the MP4 (epoch ms, often local time tagged UTC)
     * @return offset to ADD to telemetry timestamps.
     */
    fun smartOffset(
        recordsStart: Long,
        recordsEnd: Long,
        videoStart: Long?,
        fitTrack: List<GpsPoint> = emptyList(),
        cameraTrack: List<GpsPoint> = emptyList(),
    ): Result {
        if (videoStart == null) return Result(0, Method.NO_VIDEO_START)

        // 0. GPS track alignment: the camera's own GPS is ground truth when both tracks exist.
        trackOffset(fitTrack, cameraTrack)?.let { return Result(it, Method.GPS_TRACK) }

        // 1. Direct match: the video starts inside the activity.
        if (videoStart in (recordsStart - MARGIN_MS)..(recordsEnd + MARGIN_MS)) {
            return Result(0, Method.ABSOLUTE_TIME)
        }
        // 2. Whole-hour timezone difference (camera clock in local time, FIT in UTC).
        for (tz in -14..14) {
            if (tz == 0) continue
            val shift = tz * 3_600_000L
            if (videoStart in (recordsStart - shift - MARGIN_MS)..(recordsEnd - shift + MARGIN_MS)) {
                return Result(-shift, Method.TIMEZONE_FALLBACK, tz)
            }
        }
        // 3. Independent clocks: align the activity start with the video start.
        return Result(videoStart - recordsStart, Method.ALIGN_START)
    }

    /**
     * Small FIT-to-video correction from GPS positions at common UTC times. Both tracks are
     * on the absolute clock (camera track = GPS-derived video start + video position).
     * Candidates are searched around zero (±2 min coarse, then ±5 s fine); a refinement is
     * accepted only when it clearly beats zero. Returns ms to add to FIT times, or null.
     */
    fun trackOffset(fitTrack: List<GpsPoint>, cameraTrack: List<GpsPoint>): Long? {
        if (fitTrack.size < 10 || cameraTrack.size < 10) return null
        val fit = fitTrack.sortedBy { it.time }
        val step = maxOf(1, cameraTrack.size / 120)
        val cam = cameraTrack.sortedBy { it.time }.filterIndexed { i, _ -> i % step == 0 }
        if (cam.size < 5) return null

        fun positionAt(t: Long): Pair<Double, Double>? {
            if (t < fit.first().time || t > fit.last().time) return null
            var lo = 0
            var hi = fit.size - 1
            while (lo < hi) {
                val mid = (lo + hi + 1) ushr 1
                if (fit[mid].time <= t) lo = mid else hi = mid - 1
            }
            if (lo >= fit.size - 1) return fit.last().lat to fit.last().lon
            val a = fit[lo]
            val b = fit[lo + 1]
            val span = b.time - a.time
            if (span <= 0) return a.lat to a.lon
            val f = (t - a.time).toDouble() / span
            return (a.lat + (b.lat - a.lat) * f) to (a.lon + (b.lon - a.lon) * f)
        }

        val refStart = maxOf(cam.first().time, fit.first().time)
        val refEnd = minOf(cam.last().time, fit.last().time)
        if (refEnd <= refStart) return null
        val ref = cam.filter { it.time in refStart..refEnd }
        if (ref.size < 5) return null

        data class Score(val coverage: Double, val median: Double, val p90: Double)

        fun score(offsetMs: Long): Score? {
            val errors = ref.mapNotNull { g ->
                positionAt(g.time - offsetMs)?.let { Derive.haversineM(g.lat, g.lon, it.first, it.second) }
            }.sorted()
            val coverage = errors.size.toDouble() / ref.size
            if (errors.size < 5 || coverage < 0.25) return null
            return Score(coverage, errors[errors.size / 2], errors[maxOf(0, (errors.size * 0.9).toInt() - 1)])
        }

        fun rank(o: Long, s: Score) = s.median + 0.25 * s.p90 + abs(o) * 1e-9

        val base = score(0) ?: return null
        val coarse = (-24..24).map { it * 5_000L }.mapNotNull { o -> score(o)?.let { o to it } }
        if (coarse.isEmpty()) return null
        val bestCoarse = coarse.minBy { rank(it.first, it.second) }.first
        val fine = ((-50..50).map { it * 100L } + (-50..50).map { bestCoarse + it * 100L })
            .distinct().mapNotNull { o -> score(o)?.let { o to it } }
        val candidates = fine.ifEmpty { coarse }
        val refinements = candidates.filter { (o, s) -> o != 0L && s.coverage >= 0.75 && s.median <= base.median * 0.80 }
        val (best, metrics) = refinements.minByOrNull { rank(it.first, it.second) } ?: (0L to base)
        if (metrics.median > 100.0 || metrics.p90 > 250.0) return null
        return best
    }
}
