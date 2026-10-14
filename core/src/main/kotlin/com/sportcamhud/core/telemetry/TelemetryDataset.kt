package com.sportcamhud.core.telemetry

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One GPS fix (epoch ms UTC, WGS84 degrees). */
data class GpsPoint(val time: Long, val lat: Double, val lon: Double)

/** Display metadata for a field (unit and Polish label, as in the Windows app). */
data class FieldInfo(val unit: String, val label: String, val decimals: Int)

/**
 * Canonical field keys. Units: speed km/h, distance m, alt m, slope %, heading deg,
 * temperature °C, battery %. FIT-only extras are stored as `fit_<name>`.
 */
object Fields {
    const val SPEED = "speed"
    const val DISTANCE = "distance"
    const val ALT = "alt"
    const val HEART_RATE = "heart_rate"
    const val CADENCE = "cadence"
    const val POWER = "power"
    const val TEMPERATURE = "atemp"
    const val HEADING = "heading"
    const val SLOPE = "slope"
    const val BATTERY = "garmin_battery_percent"

    val INFO: Map<String, FieldInfo> = mapOf(
        SPEED to FieldInfo("km/h", "Prędkość", 1),
        DISTANCE to FieldInfo("km", "Dystans", 2),
        ALT to FieldInfo("m", "Alt", 0),
        HEART_RATE to FieldInfo("BPM", "HR", 0),
        CADENCE to FieldInfo("RPM", "Cad", 0),
        POWER to FieldInfo("W", "Moc", 0),
        TEMPERATURE to FieldInfo("°C", "ATemp", 0),
        HEADING to FieldInfo("°", "Kurs", 0),
        SLOPE to FieldInfo("%", "Nachylenie", 1),
        BATTERY to FieldInfo("%", "Bat", 0),
    )
}

/**
 * Pause-aware activity clock, built from FIT timer start/stop events
 * (the ActiveTimeMapper of the Windows app).
 */
class ActiveTimeMapper(private val segments: List<LongRange>) {
    val start: Long? get() = segments.firstOrNull()?.first

    /** Active (timer running) seconds from the activity start until [t]. */
    fun activeSecondsAt(t: Long): Double {
        var total = 0L
        for (seg in segments) {
            if (t <= seg.first) break
            total += min(t, seg.last) - seg.first
        }
        return total / 1000.0
    }

    fun shifted(offsetMs: Long) = ActiveTimeMapper(segments.map { (it.first + offsetMs)..(it.last + offsetMs) })

    companion object {
        /**
         * @param timerEvents pairs of (time, running) from FIT `event` messages with event=timer.
         * Falls back to splitting the record stream at gaps longer than [pauseGapMs].
         */
        fun build(timerEvents: List<Pair<Long, Boolean>>, recordTimes: LongArray, pauseGapMs: Long = 30_000): ActiveTimeMapper? {
            if (recordTimes.isEmpty()) return null
            val segs = ArrayList<LongRange>()
            if (timerEvents.isNotEmpty()) {
                var runningSince: Long? = null
                for ((t, running) in timerEvents.sortedBy { it.first }) {
                    if (running && runningSince == null) runningSince = t
                    if (!running && runningSince != null) {
                        if (t > runningSince) segs.add(runningSince..t)
                        runningSince = null
                    }
                }
                runningSince?.let { if (recordTimes.last() > it) segs.add(it..recordTimes.last()) }
            }
            if (segs.isEmpty()) {
                var segStart = recordTimes[0]
                for (i in 1 until recordTimes.size) {
                    if (recordTimes[i] - recordTimes[i - 1] > pauseGapMs) {
                        segs.add(segStart..recordTimes[i - 1])
                        segStart = recordTimes[i]
                    }
                }
                segs.add(segStart..recordTimes.last())
            }
            return ActiveTimeMapper(segs)
        }
    }
}

/**
 * All telemetry of a project after parsing and post-processing, on the absolute UTC timeline.
 */
class TelemetryDataset(
    val fields: Map<String, Series>,
    val track: List<GpsPoint>,
    val activeTime: ActiveTimeMapper?,
    /** Local-time offset of the recording device (from the FIT activity message), seconds. */
    val timezoneOffsetSec: Int?,
    val fieldInfo: Map<String, FieldInfo> = Fields.INFO,
    val sourceName: String = "fit",
) {
    val start: Long? = fields.values.filter { !it.isEmpty() }.minOfOrNull { it.start } ?: track.firstOrNull()?.time
    val end: Long? = fields.values.filter { !it.isEmpty() }.maxOfOrNull { it.end } ?: track.lastOrNull()?.time

    operator fun get(field: String): Series? = fields[field]?.takeIf { !it.isEmpty() }
    fun has(field: String) = get(field) != null
    fun info(field: String): FieldInfo = fieldInfo[field] ?: FieldInfo("", field, 1)

    /** Applies a clock correction (SmartSync / manual offset) to every stream. */
    fun shifted(offsetMs: Long): TelemetryDataset = if (offsetMs == 0L) this else TelemetryDataset(
        fields.mapValues { it.value.shifted(offsetMs) },
        track.map { it.copy(time = it.time + offsetMs) },
        activeTime?.shifted(offsetMs),
        timezoneOffsetSec,
        fieldInfo,
        sourceName,
    )

    /** Value of [field] at [t] using the interpolation policy of the Windows app. */
    fun valueAt(field: String, t: Long): Double? {
        val s = get(field) ?: return null
        return when (interpolationPolicy(field)) {
            Interp.LINEAR -> s.linear(t)
            Interp.ANGULAR -> s.angular(t)
            Interp.STEP -> s.step(t)
        }
    }

    /** Index of the track point nearest in time to [t] (or -1). */
    fun trackIndexAt(t: Long): Int {
        if (track.isEmpty()) return -1
        var lo = 0
        var hi = track.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (track[mid].time <= t) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Interpolated GPS position at [t], or null outside the track. */
    fun positionAt(t: Long): Pair<Double, Double>? {
        if (track.isEmpty() || t < track.first().time - 2000 || t > track.last().time + 2000) return null
        val i = trackIndexAt(t)
        if (i >= track.size - 1 || t <= track[i].time) return track[i].lat to track[i].lon
        val a = track[i]
        val b = track[i + 1]
        val span = b.time - a.time
        if (span <= 0 || span > Series.DEFAULT_MAX_GAP_MS) return a.lat to a.lon
        val f = (t - a.time).toDouble() / span
        return (a.lat + (b.lat - a.lat) * f) to (a.lon + (b.lon - a.lon) * f)
    }

    enum class Interp { LINEAR, STEP, ANGULAR }

    companion object {
        fun interpolationPolicy(field: String): Interp = when {
            field == Fields.HEADING -> Interp.ANGULAR
            field == Fields.HEART_RATE || field == Fields.CADENCE || field == Fields.BATTERY ||
                field.contains("battery") -> Interp.STEP
            else -> Interp.LINEAR
        }
    }
}

// ---------------------------------------------------------------------------
// Derived streams (ports of telemetry_heading.py / telemetry_slope.py /
// telemetry_resolver.normalize_fit_recorded_distance).
// ---------------------------------------------------------------------------

object Derive {
    private const val EARTH_R_M = 6_371_000.0

    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dp = Math.toRadians(lat2 - lat1)
        val dl = Math.toRadians(lon2 - lon1)
        val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
        return 2 * EARTH_R_M * atan2(sqrt(a), sqrt(1 - a))
    }

    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
    }

    /**
     * Course over ground from consecutive GPS fixes at least [minStepM] apart.
     * While nearly stationary (speed < [minSpeedKmh]) the last heading is held.
     */
    fun heading(track: List<GpsPoint>, speed: Series?, minStepM: Double = 4.0, minSpeedKmh: Double = 2.0): Series {
        if (track.size < 2) return Series.EMPTY
        val out = ArrayList<Pair<Long, Double>>(track.size)
        var anchor = track[0]
        var last: Double? = null
        for (i in 1 until track.size) {
            val p = track[i]
            val d = haversineM(anchor.lat, anchor.lon, p.lat, p.lon)
            val moving = speed?.linear(p.time)?.let { it >= minSpeedKmh } ?: true
            if (d >= minStepM && moving) {
                last = bearingDeg(anchor.lat, anchor.lon, p.lat, p.lon)
                anchor = p
            }
            last?.let { out.add(p.time to it) }
        }
        if (out.isEmpty()) return Series.EMPTY
        return smoothAngles(Series.of(out), 5)
    }

    private fun smoothAngles(s: Series, window: Int): Series {
        if (s.size < 3) return s
        val half = window / 2
        val out = DoubleArray(s.size)
        for (i in 0 until s.size) {
            var sx = 0.0
            var sy = 0.0
            for (j in max(0, i - half)..min(s.size - 1, i + half)) {
                sx += cos(Math.toRadians(s.values[j]))
                sy += sin(Math.toRadians(s.values[j]))
            }
            out[i] = (Math.toDegrees(atan2(sy, sx)) + 360.0) % 360.0
        }
        return Series(s.times.copyOf(), out)
    }

    /**
     * Road gradient in percent: altitude change over a trailing distance window
     * of [windowM] metres, clamped to ±[clampPct] and lightly smoothed.
     */
    fun slope(distance: Series?, alt: Series?, windowM: Double = 25.0, clampPct: Double = 30.0): Series {
        if (distance == null || alt == null || distance.size < 2) return Series.EMPTY
        val out = ArrayList<Pair<Long, Double>>(distance.size)
        var j = 0
        for (i in 0 until distance.size) {
            val d = distance.values[i]
            while (j < i && d - distance.values[j] > windowM) j++
            val back = if (j > 0) j - 1 else 0
            val dd = d - distance.values[back]
            if (dd < windowM * 0.5) continue
            val a1 = alt.linear(distance.times[i]) ?: continue
            val a0 = alt.linear(distance.times[back]) ?: continue
            val pct = ((a1 - a0) / dd * 100.0).coerceIn(-clampPct, clampPct)
            out.add(distance.times[i] to pct)
        }
        return Series.of(out).movingAverage(5)
    }

    /**
     * FIT recorded distance can restart at zero (multi-session files, device resets).
     * Makes the stream monotonic by carrying the previous total across resets.
     */
    fun normalizeDistance(raw: Series, resetThresholdM: Double = 50.0): Series {
        if (raw.size < 2) return raw
        val out = DoubleArray(raw.size)
        var carry = 0.0
        var prev = raw.values[0]
        out[0] = prev
        for (i in 1 until raw.size) {
            val cur = raw.values[i]
            if (cur + resetThresholdM < prev) carry += prev
            out[i] = cur + carry
            prev = cur
        }
        // Never let jitter make the cumulative distance go backwards.
        for (i in 1 until out.size) if (out[i] < out[i - 1]) out[i] = out[i - 1]
        return Series(raw.times.copyOf(), out)
    }

    /** Cumulative distance computed from the GPS track when the device did not record one. */
    fun distanceFromTrack(track: List<GpsPoint>): Series {
        if (track.size < 2) return Series.EMPTY
        var total = 0.0
        val out = ArrayList<Pair<Long, Double>>(track.size)
        out.add(track[0].time to 0.0)
        for (i in 1 until track.size) {
            val step = haversineM(track[i - 1].lat, track[i - 1].lon, track[i].lat, track[i].lon)
            if (step < 500) total += step
            out.add(track[i].time to total)
        }
        return Series.of(out)
    }
}
