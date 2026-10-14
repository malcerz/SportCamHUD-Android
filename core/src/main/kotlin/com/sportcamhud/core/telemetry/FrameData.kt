package com.sportcamhud.core.telemetry

import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Everything the HUD needs for one video frame (port of `prepare_overlay_frame_data`).
 */
class FrameData(
    /** Absolute time of the frame (epoch ms UTC). */
    val time: Long,
    val values: Map<String, Double?>,
    val dataset: TelemetryDataset?,
    val dateText: String,
    val timeText: String,
    val elapsedSeconds: Double,
    val avgSpeedKmh: Double,
    val videoStart: Long,
    val videoEnd: Long,
    /** Position in the video (ms); camera (GPMF) streams are keyed by it. */
    val videoPositionMs: Long = time - videoStart,
    val camera: CameraTelemetry? = null,
) {
    /** True when the field has data from the activity file or from the camera. */
    fun has(field: String): Boolean = dataset?.has(field) == true || camera?.has(field) == true

    fun info(field: String): FieldInfo? = CameraTelemetry.INFO[field]?.takeIf { camera?.has(field) == true }
        ?: dataset?.info(field)
}

/** Maps video presentation time onto the telemetry timeline. */
class TelemetryTimeline(
    /** Telemetry already shifted by SmartSync + manual offset. */
    val dataset: TelemetryDataset?,
    /** Absolute time (epoch ms) of video position 0 after synchronisation. */
    val videoStart: Long,
    val videoDurationMs: Long,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** GoPro telemetry of this video (never shifted by sync offsets). */
    val camera: CameraTelemetry? = null,
) {
    private val dateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm:ss")

    fun frameAt(videoPositionMs: Long): FrameData {
        val t = videoStart + videoPositionMs
        val ds = dataset
        val values = HashMap<String, Double?>()
        if (ds != null) for (key in ds.fields.keys) values[key] = ds.valueAt(key, t)
        camera?.let { cam -> for (key in cam.fields.keys) values[key] = cam.valueAt(key, videoPositionMs) }

        val displayZone = ds?.timezoneOffsetSec?.let { ZoneOffset.ofTotalSeconds(it) } ?: zone
        val local = Instant.ofEpochMilli(t).atZone(displayZone)

        // Elapsed: active (timer running) time since the activity start; video time otherwise.
        val elapsed = ds?.activeTime?.activeSecondsAt(t)?.takeIf { it >= 0 } ?: (videoPositionMs / 1000.0)
        val avg = averageSpeed(ds, t, elapsed)
        return FrameData(
            time = t,
            values = values,
            dataset = ds,
            dateText = dateFmt.format(local),
            timeText = timeFmt.format(local),
            elapsedSeconds = elapsed,
            avgSpeedKmh = avg,
            videoStart = videoStart,
            videoEnd = videoStart + videoDurationMs,
            videoPositionMs = videoPositionMs,
            camera = camera,
        )
    }

    private fun averageSpeed(ds: TelemetryDataset?, t: Long, elapsedS: Double): Double {
        if (ds == null || elapsedS < 1.0) return 0.0
        val dist = ds[Fields.DISTANCE] ?: return 0.0
        val d0 = dist.values.first()
        val d = dist.linear(t) ?: return 0.0
        return ((d - d0) / elapsedS * 3.6).coerceAtLeast(0.0)
    }
}
