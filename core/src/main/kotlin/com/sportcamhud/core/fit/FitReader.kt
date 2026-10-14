package com.sportcamhud.core.fit

import com.garmin.fit.ActivityMesg
import com.garmin.fit.Decode
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.FitRuntimeException
import com.garmin.fit.Mesg
import com.garmin.fit.MesgNum
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.sportcamhud.core.telemetry.ActiveTimeMapper
import com.sportcamhud.core.telemetry.Derive
import com.sportcamhud.core.telemetry.FieldInfo
import com.sportcamhud.core.telemetry.Fields
import com.sportcamhud.core.telemetry.GpsPoint
import com.sportcamhud.core.telemetry.Series
import com.sportcamhud.core.telemetry.TelemetryDataset
import java.io.ByteArrayInputStream
import java.io.InputStream

class FitParseException(message: String) : Exception(message)

/** Summary shown when a FIT file is picked (and used for activity validation). */
data class FitSummary(
    val start: Long,
    val end: Long,
    val sport: String?,
    val totalDistanceM: Double?,
    val recordCount: Int,
    val hasGps: Boolean,
    val availableFields: Set<String>,
)

/**
 * Reads a Garmin .FIT activity with the official Garmin FIT SDK and turns it into a
 * [TelemetryDataset] (replacement for `telemetry_fit.parse_fit` + `sync_fit_to_video`).
 */
object FitReader {
    private const val FIT_EPOCH_OFFSET_MS = 631_065_600_000L // 1989-12-31T00:00:00Z
    private const val SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0

    /** Record fields mapped onto canonical streams; everything else becomes `fit_<name>`. */
    private val SKIPPED_RECORD_FIELDS = setOf(
        "timestamp", "position_lat", "position_long", "speed", "enhanced_speed",
        "altitude", "enhanced_altitude", "distance", "heart_rate", "cadence", "power",
        "temperature", "compressed_speed_distance", "time128", "fractional_cadence",
        "left_right_balance", "cycle_length", "accumulated_power", "gps_accuracy",
        "compressed_accumulated_power", "cycles", "total_cycles", "event_timestamp",
        "event_timestamp_12", "device_index", "activity_type", "ball_speed",
    )

    fun isFitBytes(bytes: ByteArray): Boolean =
        bytes.size >= 12 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte() &&
            bytes[10] == 'I'.code.toByte() && bytes[11] == 'T'.code.toByte()

    fun read(input: InputStream): TelemetryDataset = read(input.readBytes())

    fun read(bytes: ByteArray): TelemetryDataset = build(decode(bytes))

    fun summary(bytes: ByteArray): FitSummary {
        val raw = decode(bytes)
        val ds = build(raw)
        return FitSummary(
            start = raw.recordTimes.first(),
            end = raw.recordTimes.last(),
            sport = raw.sport,
            totalDistanceM = raw.sessionDistance ?: ds[Fields.DISTANCE]?.values?.lastOrNull(),
            recordCount = raw.recordTimes.size,
            hasGps = ds.track.isNotEmpty(),
            availableFields = ds.fields.filterValues { !it.isEmpty() }.keys,
        )
    }

    // -----------------------------------------------------------------------

    private class Raw {
        val recordTimes = ArrayList<Long>()
        val streams = LinkedHashMap<String, MutableList<Pair<Long, Double>>>()
        val units = HashMap<String, String>()
        val track = ArrayList<GpsPoint>()
        val timerEvents = ArrayList<Pair<Long, Boolean>>()
        var timezoneOffsetSec: Int? = null
        var sport: String? = null
        var sessionDistance: Double? = null

        fun add(key: String, t: Long, v: Double?) {
            if (v == null || !v.isFinite()) return
            streams.getOrPut(key) { ArrayList() }.add(t to v)
        }
    }

    private fun fitTimeToMs(fitSeconds: Long): Long = fitSeconds * 1000L + FIT_EPOCH_OFFSET_MS

    private fun decode(bytes: ByteArray): Raw {
        if (!isFitBytes(bytes)) throw FitParseException("To nie jest plik FIT.")
        val raw = Raw()
        val decode = Decode()
        val listener = com.garmin.fit.MesgListener { mesg -> onMesg(raw, mesg) }
        try {
            decode.read(ByteArrayInputStream(bytes), listener)
        } catch (e: FitRuntimeException) {
            // Truncated or CRC-damaged files (e.g. a recording cut by a dead battery)
            // still carry usable records; keep what was decoded so far.
            if (raw.recordTimes.isEmpty()) throw FitParseException("Uszkodzony plik FIT: ${e.message}")
        }
        if (raw.recordTimes.isEmpty()) throw FitParseException("Plik FIT nie zawiera rekordów aktywności.")
        return raw
    }

    private fun onMesg(raw: Raw, mesg: Mesg) {
        when (mesg.num) {
            MesgNum.RECORD -> onRecord(raw, RecordMesg(mesg))
            MesgNum.EVENT -> {
                val ev = EventMesg(mesg)
                val ts = ev.timestamp?.timestamp ?: return
                if (ev.event == Event.TIMER) {
                    when (ev.eventType) {
                        EventType.START -> raw.timerEvents.add(fitTimeToMs(ts) to true)
                        EventType.STOP, EventType.STOP_ALL, EventType.STOP_DISABLE, EventType.STOP_DISABLE_ALL ->
                            raw.timerEvents.add(fitTimeToMs(ts) to false)
                        else -> Unit
                    }
                }
            }
            MesgNum.ACTIVITY -> {
                val act = ActivityMesg(mesg)
                val ts = act.timestamp?.timestamp
                val local = act.localTimestamp
                if (ts != null && local != null) {
                    val off = (local - ts).toInt()
                    if (off in -14 * 3600..14 * 3600) raw.timezoneOffsetSec = off
                }
            }
            MesgNum.SESSION -> {
                val s = SessionMesg(mesg)
                if (raw.sport == null) raw.sport = s.sport?.name?.lowercase()
                s.totalDistance?.let { raw.sessionDistance = (raw.sessionDistance ?: 0.0) + it }
            }
            MesgNum.DEVICE_INFO -> {
                val d = DeviceInfoMesg(mesg)
                val ts = d.timestamp?.timestamp ?: return
                // Device index 0 is the recording device (the "creator").
                if ((d.deviceIndex ?: 0).toInt() == 0) {
                    d.batteryLevel?.let { raw.add(Fields.BATTERY, fitTimeToMs(ts), it.toDouble()) }
                }
            }
        }
    }

    private fun onRecord(raw: Raw, r: RecordMesg) {
        val ts = r.timestamp?.timestamp ?: return
        val t = fitTimeToMs(ts)
        raw.recordTimes.add(t)

        val lat = r.positionLat
        val lon = r.positionLong
        if (lat != null && lon != null && !(lat == 0 && lon == 0)) {
            raw.track.add(GpsPoint(t, lat * SEMICIRCLE_TO_DEG, lon * SEMICIRCLE_TO_DEG))
        }
        (r.enhancedSpeed ?: r.speed)?.let { raw.add(Fields.SPEED, t, it * 3.6) }
        (r.enhancedAltitude ?: r.altitude)?.let { raw.add(Fields.ALT, t, it.toDouble()) }
        r.distance?.let { raw.add(Fields.DISTANCE, t, it.toDouble()) }
        r.heartRate?.let { raw.add(Fields.HEART_RATE, t, it.toDouble()) }
        r.cadence?.let { raw.add(Fields.CADENCE, t, it.toDouble()) }
        r.power?.let { raw.add(Fields.POWER, t, it.toDouble()) }
        r.temperature?.let { raw.add(Fields.TEMPERATURE, t, it.toDouble()) }

        // Every other numeric field (incl. developer fields such as CIQ power meters)
        // becomes an extra stream, like the dynamic fit_* indicators of the Windows app.
        for (f in r.fields) {
            val name = f.name ?: continue
            if (name in SKIPPED_RECORD_FIELDS || name.startsWith("unknown")) continue
            val v = runCatching { f.doubleValue }.getOrNull() ?: continue
            val key = "fit_" + sanitize(name)
            raw.add(key, t, v)
            f.units?.takeIf { it.isNotBlank() }?.let { raw.units[key] = it }
        }
        for (f in r.developerFields) {
            val name = f.name ?: continue
            val v = runCatching { f.doubleValue }.getOrNull() ?: continue
            val key = "fit_dev_" + sanitize(name)
            raw.add(key, t, v)
            f.units?.takeIf { it.isNotBlank() }?.let { raw.units[key] = it }
        }
    }

    private fun sanitize(name: String) = name.lowercase().replace(Regex("[^a-z0-9_]+"), "_").trim('_')

    private fun build(raw: Raw): TelemetryDataset {
        val fields = LinkedHashMap<String, Series>()
        val info = HashMap(Fields.INFO)
        for ((key, samples) in raw.streams) fields[key] = Series.of(samples)

        val track = raw.track.sortedBy { it.time }

        // Distance: normalise FIT resets, or compute from GPS when absent.
        val dist = fields[Fields.DISTANCE]?.let { Derive.normalizeDistance(it) }
            ?: Derive.distanceFromTrack(track).takeIf { !it.isEmpty() }
        if (dist != null) fields[Fields.DISTANCE] = dist

        // Speed and altitude are smoothed like the Windows app (moving average, strength 3).
        fields[Fields.SPEED]?.let { fields[Fields.SPEED] = it.movingAverage(3) }
        if (fields[Fields.SPEED] == null && dist != null && dist.size > 2) {
            val sp = ArrayList<Pair<Long, Double>>()
            for (i in 1 until dist.size) {
                val dt = (dist.times[i] - dist.times[i - 1]) / 1000.0
                if (dt > 0) sp.add(dist.times[i] to (dist.values[i] - dist.values[i - 1]) / dt * 3.6)
            }
            fields[Fields.SPEED] = Series.of(sp).movingAverage(5)
        }
        fields[Fields.ALT]?.let { fields[Fields.ALT] = it.movingAverage(3) }

        val heading = Derive.heading(track, fields[Fields.SPEED])
        if (!heading.isEmpty()) fields[Fields.HEADING] = heading
        val slope = Derive.slope(fields[Fields.DISTANCE], fields[Fields.ALT])
        if (!slope.isEmpty()) fields[Fields.SLOPE] = slope

        for ((key, unit) in raw.units) {
            val label = key.removePrefix("fit_dev_").removePrefix("fit_").replace('_', ' ')
            info[key] = FieldInfo(unit, label, 1)
        }
        for (key in fields.keys) if (key !in info) {
            info[key] = FieldInfo("", key.removePrefix("fit_dev_").removePrefix("fit_").replace('_', ' '), 1)
        }

        val times = raw.recordTimes.toLongArray().also { it.sort() }
        return TelemetryDataset(
            fields = fields.filterValues { !it.isEmpty() },
            track = track,
            activeTime = ActiveTimeMapper.build(raw.timerEvents, times),
            timezoneOffsetSec = raw.timezoneOffsetSec,
            fieldInfo = info,
            sourceName = "fit",
        )
    }
}
