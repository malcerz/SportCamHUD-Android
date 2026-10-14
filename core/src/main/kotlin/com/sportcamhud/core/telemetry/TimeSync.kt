package com.sportcamhud.core.telemetry

/**
 * SmartSync: aligns the telemetry clock with the video clock
 * (port of `_compute_smart_time_offset` from the Windows app, without the GoPro GPS
 * cross-correlation step, which needs GPMF data).
 */
object TimeSync {
    enum class Method { NO_VIDEO_START, ABSOLUTE_TIME, TIMEZONE_FALLBACK, ALIGN_START }

    data class Result(val offsetMs: Long, val method: Method, val timezoneHours: Int = 0)

    private const val MARGIN_MS = 5 * 60_000L

    /**
     * @param recordsStart first telemetry timestamp (epoch ms)
     * @param recordsEnd last telemetry timestamp (epoch ms)
     * @param videoStart video creation time as stored in the MP4 (epoch ms, often local time tagged UTC)
     * @return offset to ADD to telemetry timestamps.
     */
    fun smartOffset(recordsStart: Long, recordsEnd: Long, videoStart: Long?): Result {
        if (videoStart == null) return Result(0, Method.NO_VIDEO_START)

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
}
