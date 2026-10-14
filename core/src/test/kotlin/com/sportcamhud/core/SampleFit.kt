package com.sportcamhud.core

import com.garmin.fit.ActivityMesg
import com.garmin.fit.DateTime
import com.garmin.fit.DeviceInfoMesg
import com.garmin.fit.Event
import com.garmin.fit.EventMesg
import com.garmin.fit.EventType
import com.garmin.fit.File as FitFileType
import com.garmin.fit.FileEncoder
import com.garmin.fit.FileIdMesg
import com.garmin.fit.Manufacturer
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import java.io.File
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin

/** Builds a synthetic 20-minute ride with the official FIT SDK encoder. */
object SampleFit {
    val START: Instant = Instant.parse("2026-10-02T06:26:47Z")
    const val SECONDS = 1200
    const val PAUSE_FROM = 600
    const val PAUSE_TO = 660

    private fun dt(sec: Int) = DateTime(java.util.Date.from(START.plusSeconds(sec.toLong())))
    private fun semi(deg: Double) = (deg * 2147483648.0 / 180.0).toInt()

    fun write(target: File, distanceResetAt: Int? = null): File {
        val enc = FileEncoder(target, com.garmin.fit.Fit.ProtocolVersion.V2_0)
        enc.write(FileIdMesg().apply {
            type = FitFileType.ACTIVITY
            manufacturer = Manufacturer.GARMIN
            product = 3121
            serialNumber = 123456L
            timeCreated = dt(0)
        })
        enc.write(DeviceInfoMesg().apply { timestamp = dt(0); deviceIndex = 0; batteryLevel = 87 })
        enc.write(EventMesg().apply { timestamp = dt(0); event = Event.TIMER; eventType = EventType.START })
        var dist = 0.0
        var distOffset = 0.0
        for (s in 0..SECONDS) {
            if (s in PAUSE_FROM until PAUSE_TO) continue
            if (s == PAUSE_FROM - 1 + 1) Unit
            val speed = 7.0 + 2.0 * sin(s / 60.0) // m/s
            if (s > 0) dist += speed
            if (distanceResetAt != null && s == distanceResetAt) distOffset = dist
            val ang = s / SECONDS.toDouble() * 2 * Math.PI
            val lat = 50.0614 + 0.01 * sin(ang)
            val lon = 19.9366 + 0.015 * (1 - cos(ang))
            enc.write(RecordMesg().apply {
                timestamp = dt(s)
                positionLat = semi(lat)
                positionLong = semi(lon)
                this.speed = speed.toFloat()
                enhancedSpeed = speed.toFloat()
                distance = (dist - distOffset).toFloat()
                altitude = (220.0 + 30 * sin(s / 200.0)).toFloat()
                enhancedAltitude = altitude
                heartRate = (120 + (s / 20) % 40).toShort()
                cadence = (85 + (s % 7)).toShort()
                power = 180 + (s % 50)
                temperature = 14
            })
            if (s == PAUSE_FROM - 1) enc.write(EventMesg().apply { timestamp = dt(s); event = Event.TIMER; eventType = EventType.STOP_ALL })
            if (s == PAUSE_TO - 1) Unit
        }
        enc.write(EventMesg().apply { timestamp = dt(PAUSE_TO); event = Event.TIMER; eventType = EventType.START })
        enc.write(DeviceInfoMesg().apply { timestamp = dt(SECONDS); deviceIndex = 0; batteryLevel = 85 })
        enc.write(EventMesg().apply { timestamp = dt(SECONDS); event = Event.TIMER; eventType = EventType.STOP_ALL })
        enc.write(SessionMesg().apply {
            timestamp = dt(SECONDS); startTime = dt(0); sport = Sport.CYCLING; totalDistance = dist.toFloat()
            totalElapsedTime = SECONDS.toFloat(); totalTimerTime = (SECONDS - (PAUSE_TO - PAUSE_FROM)).toFloat()
        })
        enc.write(ActivityMesg().apply {
            timestamp = dt(SECONDS); numSessions = 1; totalTimerTime = (SECONDS - 60).toFloat()
            localTimestamp = dt(SECONDS).timestamp + 7200 // CEST
        })
        enc.close()
        return target
    }
}
