package com.sportcamhud.core.telemetry

import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2

/** Three-axis IMU samples on the video clock (ms), axes in the camera's own GPMF order. */
class ImuSamples(val times: LongArray, val axes: Array<DoubleArray>) {
    val size get() = times.size
}

/**
 * Telemetry recorded by the camera itself (GoPro GPMF). Unlike FIT data it is locked to the
 * video, so every time here is a video position in milliseconds and is never shifted by
 * SmartSync or the manual offset.
 */
class CameraTelemetry(
    /** UTC time of video position 0 derived from GPS (null without a GPS fix). */
    val utcStartMs: Long?,
    val durationMs: Long,
    val gps: List<GpsSample>,
    val iso: Series?,
    /** Shutter speed as the denominator of 1/x s (as shown by the Windows app). */
    val exposure: Series?,
    val cameraTemp: Series?,
    /** Accelerometer, m/s². */
    val accel: ImuSamples?,
    /** Gyroscope, rad/s. */
    val gyro: ImuSamples?,
    val deviceName: String? = null,
) {
    data class GpsSample(val time: Long, val lat: Double, val lon: Double, val alt: Double, val speed2dKmh: Double?, val speed3dKmh: Double?)

    val hasImu: Boolean get() = (accel?.size ?: 0) > 0 || (gyro?.size ?: 0) > 0
    val hasGps: Boolean get() = gps.size >= 2

    /** Camera fields available for indicators, keyed like [Fields]. */
    val fields: Map<String, Series> by lazy {
        buildMap {
            iso?.let { put(ISO, it) }
            exposure?.let { put(EXPOSURE, it) }
            cameraTemp?.let { put(CAMERA_TEMP, it) }
            if (hasImu) put(LEAN, leanSeries("y", 0.0))
        }
    }

    fun has(field: String) = fields[field]?.isEmpty() == false

    /** ISO, exposure and temperature hold their value (step), lean is interpolated. */
    fun valueAt(field: String, videoPosMs: Long): Double? {
        val s = fields[field] ?: return null
        if (s.isEmpty() || videoPosMs < s.start - 2000 || videoPosMs > s.end + 2000) return null
        return if (field == LEAN) s.linear(videoPosMs, 1000) else s.step(videoPosMs)
    }

    private val leanCache = HashMap<String, Series>()

    /** Roll angle timeline for [axis] (x/y/z), smoothed over [smoothingS] seconds. */
    @Synchronized
    fun leanSeries(axis: String, smoothingS: Double): Series = leanCache.getOrPut("$axis|$smoothingS") {
        val roll = Imu.rollTimeline(accel, gyro, axis)
        if (smoothingS > 0) Imu.smooth(roll, smoothingS) else roll
    }

    /** Gyroscope angular rate on [axis] in deg/s (the "raw_gyro_z" lean source). */
    @Synchronized
    fun gyroRateSeries(axis: String, smoothingS: Double): Series = leanCache.getOrPut("rate|$axis|$smoothingS") {
        val g = gyro ?: return@getOrPut Series.EMPTY
        val idx = Imu.axisIndex(axis)
        val s = Series(g.times.copyOf(), DoubleArray(g.size) { Math.toDegrees(g.axes[idx][it]) })
        if (smoothingS > 0) Imu.smooth(s, smoothingS) else s
    }

    /** Video-relative GPS track (for SmartSync). */
    fun relativeTrack(): List<GpsPoint> = gps.map { GpsPoint(it.time, it.lat, it.lon) }

    /**
     * GoPro GPS as a regular telemetry source (speed, altitude, distance, heading, slope and
     * map) on the absolute timeline, for videos without a FIT file.
     */
    fun toDataset(videoStartUtc: Long): TelemetryDataset? {
        if (!hasGps) return null
        val track = gps.map { GpsPoint(videoStartUtc + it.time, it.lat, it.lon) }
        val fields = HashMap<String, Series>()
        val speed = gps.mapNotNull { g -> (g.speed3dKmh ?: g.speed2dKmh)?.let { videoStartUtc + g.time to it } }
        if (speed.isNotEmpty()) fields[Fields.SPEED] = Series.of(speed).movingAverage(5)
        fields[Fields.ALT] = Series.of(gps.map { videoStartUtc + it.time to it.alt }).movingAverage(5)
        val dist = Derive.distanceFromTrack(track)
        if (!dist.isEmpty()) fields[Fields.DISTANCE] = dist
        Derive.heading(track, fields[Fields.SPEED]).takeIf { !it.isEmpty() }?.let { fields[Fields.HEADING] = it }
        Derive.slope(fields[Fields.DISTANCE], fields[Fields.ALT]).takeIf { !it.isEmpty() }?.let { fields[Fields.SLOPE] = it }
        return TelemetryDataset(
            fields = fields.filterValues { !it.isEmpty() },
            track = track,
            activeTime = null,
            timezoneOffsetSec = null,
            sourceName = "gopro",
        )
    }

    companion object {
        const val ISO = "iso"
        const val EXPOSURE = "exposure"
        const val CAMERA_TEMP = "camera_temp"
        const val LEAN = "lean"

        val INFO = mapOf(
            ISO to FieldInfo("", "ISO", 0),
            EXPOSURE to FieldInfo("", "Exp", 0),
            CAMERA_TEMP to FieldInfo("°C", "Temp", 0),
            LEAN to FieldInfo("°", "Przechył", 0),
        )
    }
}

/**
 * IMU roll estimation (port of telemetry_imu.py): complementary filter of gyroscope
 * integration and the accelerometer gravity vector, precomputed once per video so that
 * preview, seek and export all see the same angle.
 */
object Imu {
    const val ALPHA = 0.98
    const val MAX_GAP_MS = 500L

    fun axisIndex(axis: String) = when (axis.trim().lowercase()) { "x" -> 0; "y" -> 1; else -> 2 }

    private fun upAxis(accel: ImuSamples): Int {
        val sums = DoubleArray(3) { a -> accel.axes[a].sumOf { abs(it) } }
        return (0..2).maxBy { sums[it] }
    }

    fun accelRollDeg(v: DoubleArray, rollIdx: Int, upIdx: Int): Double {
        val perps = (0..2).filter { it != rollIdx }
        return if (upIdx in perps) {
            val lateral = perps.first { it != upIdx }
            Math.toDegrees(atan2(v[lateral], -v[upIdx]))
        } else {
            Math.toDegrees(atan2(v[perps[1]], -v[perps[0]]))
        }
    }

    fun rollTimeline(accel: ImuSamples?, gyro: ImuSamples?, axis: String): Series {
        val r = axisIndex(axis)
        val a = accel?.takeIf { it.size > 0 }
        val g = gyro?.takeIf { it.size > 0 }
        if (a == null && g == null) return Series.EMPTY
        val up = a?.let { upAxis(it) } ?: 2
        fun aVec(i: Int) = DoubleArray(3) { a!!.axes[it][i] }
        if (g == null) {
            return Series(a!!.times.copyOf(), DoubleArray(a.size) { accelRollDeg(aVec(it), r, up) })
        }
        val out = DoubleArray(g.size)
        var roll = if (a != null) accelRollDeg(aVec(0), r, up) else 0.0
        var ai = 0
        for (i in 0 until g.size) {
            if (i > 0) {
                val dt = g.times[i] - g.times[i - 1]
                if (dt in 1..MAX_GAP_MS) {
                    roll += Math.toDegrees(g.axes[r][i]) * dt / 1000.0
                    if (a != null) {
                        while (ai + 1 < a.size && abs(a.times[ai + 1] - g.times[i]) <= abs(a.times[ai] - g.times[i])) ai++
                        roll = ALPHA * roll + (1 - ALPHA) * accelRollDeg(aVec(ai), r, up)
                    }
                }
            }
            out[i] = roll
        }
        return Series(g.times.copyOf(), out)
    }

    /** Centred time-window average (port of smooth_roll_samples). */
    fun smooth(s: Series, windowS: Double): Series {
        if (s.isEmpty() || windowS <= 0) return s
        val half = (windowS * 500).toLong()
        val cum = DoubleArray(s.size + 1)
        for (i in 0 until s.size) cum[i + 1] = cum[i] + s.values[i]
        var lo = 0
        var hi = 0
        val out = DoubleArray(s.size)
        for (i in 0 until s.size) {
            while (s.times[lo] < s.times[i] - half) lo++
            while (hi < s.size && s.times[hi] <= s.times[i] + half) hi++
            out[i] = (cum[hi] - cum[lo]) / (hi - lo)
        }
        return Series(s.times.copyOf(), out)
    }

    /** FIT grade % -> terrain angle in degrees (the "grade" lean source). */
    fun gradeToAngleDeg(gradePct: Double) = Math.toDegrees(atan(gradePct / 100.0))
}
