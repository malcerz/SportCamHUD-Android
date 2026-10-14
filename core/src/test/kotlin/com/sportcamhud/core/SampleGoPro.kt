package com.sportcamhud.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Synthetic GoPro MP4: a dummy video track plus a `gpmd` metadata track holding one GPMF
 * payload per second (GPS9 or GPS5+GPSU at 10 Hz, ISOE/SHUT at 30 Hz, ACCL/GYRO at 200 Hz
 * with TMPC). The GPS follows the same route as [SampleFit], starting [fitSecond] seconds
 * into the activity.
 */
object SampleGoPro {
    const val SECONDS = 60
    const val FIT_SECOND = 120
    const val ROLL_AMPLITUDE_DEG = 15.0
    const val ROLL_PERIOD_S = 10.0
    const val ISO = 400
    const val SHUTTER_DENOMINATOR = 500
    const val TEMP_C = 45.5

    fun rollDeg(tS: Double) = ROLL_AMPLITUDE_DEG * sin(2 * PI * tS / ROLL_PERIOD_S)

    fun lat(fitS: Double): Double = 50.0614 + 0.01 * sin(fitS / SampleFit.SECONDS * 2 * PI)
    fun lon(fitS: Double): Double = 19.9366 + 0.015 * (1 - cos(fitS / SampleFit.SECONDS * 2 * PI))

    // ---- GPMF KLV writer -------------------------------------------------------------
    fun klv(key: String, type: Char, structSize: Int, repeat: Int, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(key.toByteArray(Charsets.ISO_8859_1))
        out.write(type.code)
        out.write(structSize)
        out.write(repeat shr 8)
        out.write(repeat and 0xFF)
        out.write(data)
        repeat((4 - data.size % 4) % 4) { out.write(0) }
        return out.toByteArray()
    }

    fun nest(key: String, vararg children: ByteArray): ByteArray {
        val body = children.fold(ByteArray(0)) { a, b -> a + b }
        return klv(key, '\u0000', 4, body.size / 4, body)
    }

    private fun str(key: String, s: String) = klv(key, 'c', 1, s.length, s.toByteArray(Charsets.ISO_8859_1))
    private fun ints(vararg v: Int) = ByteBuffer.allocate(v.size * 4).apply { v.forEach { putInt(it) } }.array()
    private fun shorts(vararg v: Int) = ByteBuffer.allocate(v.size * 2).apply { v.forEach { putShort(it.toShort()) } }.array()
    private fun floats(vararg v: Float) = ByteBuffer.allocate(v.size * 4).apply { v.forEach { putFloat(it) } }.array()

    /** One second of GPMF starting at video second [sec]. */
    fun payload(sec: Int, gps9: Boolean, utcStartMs: Long): ByteArray {
        val gpsN = 10
        val gpsStream = if (gps9) {
            val samples = ByteBuffer.allocate(gpsN * 32)
            for (i in 0 until gpsN) {
                val t = sec + i / gpsN.toDouble()
                val fitS = FIT_SECOND + t
                val utc = utcStartMs + (t * 1000).toLong()
                val days = ((utc - 946_684_800_000L) / 86_400_000L).toInt()
                val msOfDay = ((utc - 946_684_800_000L) % 86_400_000L).toInt()
                val speed = 7.0 + 2.0 * sin(fitS / 60.0)
                samples.putInt((lat(fitS) * 1e7).toInt())
                samples.putInt((lon(fitS) * 1e7).toInt())
                samples.putInt((230.5 * 1000).toInt())
                samples.putInt((speed * 1000).toInt())
                samples.putInt((speed * 100).toInt())
                samples.putInt(days)
                samples.putInt(msOfDay) // seconds scaled by 1000
                samples.putShort(150) // DOP * 100
                samples.putShort(3) // 3D fix
            }
            nest(
                "STRM",
                str("STNM", "GPS (Lat., Long., Alt., 2D, 3D, days, secs, DOP, fix)"),
                klv("SCAL", 'l', 4, 9, ints(10_000_000, 10_000_000, 1000, 1000, 100, 1, 1000, 100, 1)),
                str("TYPE", "lllllllSS"),
                klv("GPS9", '?', 32, gpsN, samples.array()),
            )
        } else {
            val gpsu = java.time.format.DateTimeFormatter.ofPattern("yyMMddHHmmss.SSS")
                .format(java.time.Instant.ofEpochMilli(utcStartMs + sec * 1000L).atOffset(java.time.ZoneOffset.UTC))
            val samples = ByteBuffer.allocate(gpsN * 20)
            for (i in 0 until gpsN) {
                val fitS = FIT_SECOND + sec + i / gpsN.toDouble()
                val speed = 7.0 + 2.0 * sin(fitS / 60.0)
                samples.putInt((lat(fitS) * 1e7).toInt())
                samples.putInt((lon(fitS) * 1e7).toInt())
                samples.putInt(230_500)
                samples.putInt((speed * 1000).toInt())
                samples.putInt((speed * 100).toInt())
            }
            nest(
                "STRM",
                klv("GPSU", 'U', 16, 1, gpsu.toByteArray(Charsets.ISO_8859_1)),
                klv("GPSF", 'L', 4, 1, ints(3)),
                klv("GPSP", 'S', 2, 1, shorts(150)),
                klv("SCAL", 'l', 4, 5, ints(10_000_000, 10_000_000, 1000, 1000, 100)),
                klv("GPS5", 'l', 20, gpsN, samples.array()),
            )
        }

        val imuN = 200
        val accl = ByteBuffer.allocate(imuN * 6)
        val gyro = ByteBuffer.allocate(imuN * 6)
        for (i in 0 until imuN) {
            val t = sec + i / imuN.toDouble()
            val r = Math.toRadians(rollDeg(t))
            val rate = Math.toRadians(ROLL_AMPLITUDE_DEG) * 2 * PI / ROLL_PERIOD_S * cos(2 * PI * t / ROLL_PERIOD_S)
            accl.putShort((9.81 * sin(r) * 418).toInt().toShort()); accl.putShort(0); accl.putShort((-9.81 * cos(r) * 418).toInt().toShort())
            gyro.putShort(0); gyro.putShort((rate * 939).toInt().toShort()); gyro.putShort(0)
        }
        val acclStream = nest(
            "STRM",
            str("STNM", "Accelerometer"),
            klv("TMPC", 'f', 4, 1, floats(TEMP_C.toFloat())),
            klv("SCAL", 's', 2, 1, shorts(418)),
            klv("ACCL", 's', 6, imuN, accl.array()),
        )
        val gyroStream = nest(
            "STRM",
            str("STNM", "Gyroscope"),
            klv("TMPC", 'f', 4, 1, floats(TEMP_C.toFloat() + 1)),
            klv("SCAL", 's', 2, 1, shorts(939)),
            klv("GYRO", 's', 6, imuN, gyro.array()),
        )
        val isoStream = nest("STRM", str("STNM", "Sensor ISO"), klv("ISOE", 'S', 2, 30, shorts(*IntArray(30) { ISO })))
        val shutStream = nest("STRM", str("STNM", "Exposure time"), klv("SHUT", 'f', 4, 30, floats(*FloatArray(30) { 1f / SHUTTER_DENOMINATOR })))
        return nest("DEVC", klv("DVID", 'L', 4, 1, ints(1)), str("DVNM", "HERO11 Black"), acclStream, gyroStream, gpsStream, isoStream, shutStream)
    }

    // ---- MP4 writer -------------------------------------------------------------------
    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val content = body.fold(ByteArray(0)) { a, b -> a + b }
        return ByteBuffer.allocate(8 + content.size).putInt(8 + content.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(content).array()
    }

    private fun full(type: String, body: ByteArray) = box(type, ByteArray(4), body)

    private fun trak(format: String, timescale: Int, sizes: IntArray, offsets: LongArray, deltas: IntArray): ByteArray {
        val mdhd = full("mdhd", ByteBuffer.allocate(20).putInt(0).putInt(0).putInt(timescale).putInt(deltas.sum()).putInt(0).array())
        val hdlr = full("hdlr", ByteBuffer.allocate(20).putInt(0).put((if (format == "gpmd") "meta" else "vide").toByteArray()).array())
        val stsd = full("stsd", ByteBuffer.allocate(4 + 16).putInt(1).putInt(16).put(format.toByteArray()).put(ByteArray(8)).array())
        val stts = full("stts", ByteBuffer.allocate(4 + deltas.size * 8).putInt(deltas.size).apply { deltas.forEach { putInt(1); putInt(it) } }.array())
        val stsc = full("stsc", ByteBuffer.allocate(4 + 12).putInt(1).putInt(1).putInt(1).putInt(1).array())
        val stsz = full("stsz", ByteBuffer.allocate(8 + sizes.size * 4).putInt(0).putInt(sizes.size).apply { sizes.forEach { putInt(it) } }.array())
        val stco = full("stco", ByteBuffer.allocate(4 + offsets.size * 4).putInt(offsets.size).apply { offsets.forEach { putInt(it.toInt()) } }.array())
        return box("trak", box("mdia", mdhd, hdlr, box("minf", box("stbl", stsd, stts, stsc, stsz, stco))))
    }

    fun write(target: File, utcStartMs: Long, gps9: Boolean = true): File {
        val ftyp = box("ftyp", "mp41".toByteArray(), ByteArray(4), "mp41".toByteArray())
        val payloads = List(SECONDS) { payload(it, gps9, utcStartMs) }
        val video = ByteArray(64) { 0x11 }
        val mdatBody = video + payloads.fold(ByteArray(0)) { a, b -> a + b }
        val mdatStart = ftyp.size + 8L
        val offsets = LongArray(payloads.size)
        var off = mdatStart + video.size
        payloads.forEachIndexed { i, p -> offsets[i] = off; off += p.size }
        val videoTrak = trak("avc1", 90_000, intArrayOf(video.size), longArrayOf(mdatStart), intArrayOf(SECONDS * 90_000))
        val metaTrak = trak("gpmd", 1000, IntArray(payloads.size) { payloads[it].size }, offsets, IntArray(payloads.size) { 1000 })
        val moov = box("moov", videoTrak, metaTrak)
        target.writeBytes(ftyp + box("mdat", mdatBody) + moov)
        return target
    }
}
