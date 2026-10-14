package com.sportcamhud.core.gpmf

import com.sportcamhud.core.telemetry.CameraTelemetry
import com.sportcamhud.core.telemetry.ImuSamples
import java.nio.ByteBuffer
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** One GPMF KLV entry (port of `parse_gpmf_tree` from telemetry_gpmf_new.py). */
class Klv(
    val key: String,
    val type: Char,
    val structSize: Int,
    val repeat: Int,
    val data: ByteArray,
    val children: List<Klv>?,
) {
    val text: String get() = String(data, Charsets.ISO_8859_1).trimEnd('\u0000', ' ')

    /** Every numeric element of the payload, in order (struct members flattened). */
    fun numbers(): DoubleArray {
        val ts = typeSize(type) ?: return DoubleArray(0)
        val bb = ByteBuffer.wrap(data)
        return DoubleArray(data.size / ts) { i ->
            val p = i * ts
            when (type) {
                'b' -> data[p].toDouble()
                'B' -> (data[p].toInt() and 0xFF).toDouble()
                's' -> bb.getShort(p).toDouble()
                'S' -> (bb.getShort(p).toInt() and 0xFFFF).toDouble()
                'l' -> bb.getInt(p).toDouble()
                'L' -> (bb.getInt(p).toLong() and 0xFFFFFFFFL).toDouble()
                'f' -> bb.getFloat(p).toDouble()
                'd' -> bb.getDouble(p)
                'j', 'J' -> bb.getLong(p).toDouble()
                'Q' -> bb.getLong(p).toULong().toDouble()
                'q' -> bb.getInt(p) / 65536.0
                else -> 0.0
            }
        }
    }

    /** Number of values per sample (e.g. 3 for an ACCL x/y/z triple). */
    val elementsPerSample: Int get() = typeSize(type)?.let { (structSize / it).coerceAtLeast(1) } ?: 1

    companion object {
        fun typeSize(t: Char): Int? = when (t) {
            'b', 'B', 'c', 'U' -> 1
            's', 'S' -> 2
            'l', 'L', 'f', 'q', 'F' -> 4
            'd', 'j', 'J', 'Q' -> 8
            else -> null
        }

        fun parse(buf: ByteArray, from: Int = 0, to: Int = buf.size): List<Klv> {
            val out = ArrayList<Klv>()
            var off = from
            val bb = ByteBuffer.wrap(buf)
            while (off + 8 <= to) {
                val key = String(buf, off, 4, Charsets.ISO_8859_1)
                val type = (buf[off + 4].toInt() and 0xFF).toChar()
                val size = buf[off + 5].toInt() and 0xFF
                val repeat = bb.getShort(off + 6).toInt() and 0xFFFF
                val payloadSize = size * repeat
                val start = off + 8
                val end = start + payloadSize
                if (end > to) break
                val children = if (type == '\u0000') parse(buf, start, end) else null
                out.add(Klv(key, type, size, repeat, if (children == null) buf.copyOfRange(start, end) else ByteArray(0), children))
                off = start + ((payloadSize + 3) and 3.inv())
            }
            return out
        }
    }
}

/**
 * GoPro telemetry reader: MP4 `gpmd` track -> [CameraTelemetry]
 * (port of telemetry_gpmf_new.py + the GPMF parts of telemetry_extract.py).
 *
 * Samples inside one payload are spread evenly over the payload's duration on the video
 * clock, like the official gpmf-parser does. Absolute time comes from GPS9 days/seconds or
 * GPSU, so the video start no longer depends on the camera's (often local, tz-less) clock.
 */
object GpmfParser {
    private const val GPS_EPOCH_MS = 946_684_800_000L // 2000-01-01T00:00:00Z
    private val GPSU_FMT = DateTimeFormatter.ofPattern("yyMMddHHmmss.SSS")

    fun read(src: ByteSource): CameraTelemetry? {
        val samples = Mp4MetaTrack.samples(src) ?: return null
        if (samples.isEmpty()) return null
        val acc = Accumulator()
        for (s in samples) {
            if (s.size <= 0 || s.offset + s.size > src.size) continue
            acc.payload(Klv.parse(src.read(s.offset, s.size)), s.startMs, s.durationMs)
        }
        return acc.build()
    }

    /** Parses already extracted payloads (used by tests and by callers holding raw GPMF). */
    fun fromPayloads(payloads: List<Triple<ByteArray, Double, Double>>): CameraTelemetry? {
        val acc = Accumulator()
        for ((bytes, start, dur) in payloads) acc.payload(Klv.parse(bytes), start, dur)
        return acc.build()
    }

    private class Accumulator {
        val gps = ArrayList<CameraTelemetry.GpsSample>()
        val anchors = ArrayList<Long>()
        val iso = ArrayList<Pair<Long, Double>>()
        val shutter = ArrayList<Pair<Long, Double>>()
        val temp = ArrayList<Pair<Long, Double>>()
        val accl = ArrayList<Pair<Long, DoubleArray>>()
        val gyro = ArrayList<Pair<Long, DoubleArray>>()
        var device: String? = null
        var endMs = 0.0

        fun payload(root: List<Klv>, startMs: Double, durMs: Double) {
            endMs = maxOf(endMs, startMs + durMs)
            for (devc in root) {
                if (devc.key != "DEVC" || devc.children == null) continue
                var tempTaken = false
                for (node in devc.children) {
                    if (node.key == "DVNM" && device == null) device = node.text
                    if (node.key != "STRM" || node.children == null) continue
                    tempTaken = stream(node.children, startMs, durMs, tempTaken)
                }
            }
        }

        /** Walks one STRM; SCAL/TYPE and GPS status are sticky until the data KLV that uses them. */
        private fun stream(nodes: List<Klv>, startMs: Double, durMs: Double, tempTakenIn: Boolean): Boolean {
            var tempTaken = tempTakenIn
            var scal: DoubleArray? = null
            var typeStr: String? = null
            var gpsu: Long? = null
            var gpsFix: Int? = null
            for (k in nodes) {
                when (k.key) {
                    "SCAL" -> scal = k.numbers()
                    "TYPE" -> typeStr = k.text
                    "GPSU" -> gpsu = parseGpsu(k.text)
                    "GPSF" -> gpsFix = k.numbers().firstOrNull()?.toInt()
                    "GPS5" -> gps5(k, scal, gpsu, gpsFix, startMs, durMs)
                    "GPS9" -> gps9(k, typeStr, scal, startMs, durMs)
                    "ISOE" -> timed(k, scal, startMs, durMs, iso)
                    "SHUT" -> timed(k, scal, startMs, durMs, shutter)
                    "TMPC" -> if (!tempTaken) {
                        k.numbers().firstOrNull()?.let { temp.add(startMs.toLong() to it); tempTaken = true }
                    }
                    "ACCL" -> vectors(k, scal, startMs, durMs, accl)
                    "GYRO" -> vectors(k, scal, startMs, durMs, gyro)
                }
            }
            return tempTaken
        }

        private fun scaled(v: Double, scal: DoubleArray?, idx: Int): Double {
            if (scal == null || scal.isEmpty()) return v
            val s = if (scal.size == 1) scal[0] else scal.getOrElse(idx) { 1.0 }
            return if (s != 0.0) v / s else v
        }

        private fun timed(k: Klv, scal: DoubleArray?, startMs: Double, durMs: Double, into: MutableList<Pair<Long, Double>>) {
            val v = k.numbers()
            if (v.isEmpty()) return
            for (i in v.indices) into.add((startMs + i * durMs / v.size).toLong() to scaled(v[i], scal, 0))
        }

        private fun vectors(k: Klv, scal: DoubleArray?, startMs: Double, durMs: Double, into: MutableList<Pair<Long, DoubleArray>>) {
            val v = k.numbers()
            val e = k.elementsPerSample
            if (e < 3 || v.size < e) return
            val n = v.size / e
            for (i in 0 until n) {
                into.add((startMs + i * durMs / n).toLong() to DoubleArray(3) { j -> scaled(v[i * e + j], scal, j) })
            }
        }

        private fun gps5(k: Klv, scal: DoubleArray?, gpsu: Long?, fix: Int?, startMs: Double, durMs: Double) {
            val v = k.numbers()
            val e = k.elementsPerSample.coerceAtLeast(5)
            val n = v.size / e
            if (n == 0) return
            if (fix != null && fix < 2) return
            if (gpsu != null) anchors.add(gpsu - startMs.toLong())
            for (i in 0 until n) {
                val f = DoubleArray(5) { j -> scaled(v[i * e + j], scal, j) }
                addGps((startMs + i * durMs / n).toLong(), f[0], f[1], f[2], f[3], f[4])
            }
        }

        private fun gps9(k: Klv, typeStr: String?, scal: DoubleArray?, startMs: Double, durMs: Double) {
            if (typeStr == null || !typeStr.startsWith("lllllll") || k.structSize < 32) return
            val bb = ByteBuffer.wrap(k.data)
            val n = k.data.size / k.structSize
            for (i in 0 until n) {
                val p = i * k.structSize
                val raw = DoubleArray(9) { j ->
                    when {
                        j < 7 -> bb.getInt(p + j * 4).toDouble()
                        else -> (bb.getShort(p + 28 + (j - 7) * 2).toInt() and 0xFFFF).toDouble()
                    }
                }
                val f = DoubleArray(9) { j -> scaled(raw[j], scal, j) }
                if (f[8] < 2) continue // no 2D/3D fix
                val rel = (startMs + i * durMs / n).toLong()
                val days = f[5]
                val secs = f[6]
                if (days > 0 && secs >= 0 && secs < 86_400) {
                    anchors.add(GPS_EPOCH_MS + (days * 86_400_000.0 + secs * 1000.0).toLong() - rel)
                }
                addGps(rel, f[0], f[1], f[2], f[3], f[4])
            }
        }

        private fun addGps(rel: Long, lat: Double, lon: Double, alt: Double, s2d: Double, s3d: Double) {
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0 || (lat == 0.0 && lon == 0.0)) return
            gps.add(
                CameraTelemetry.GpsSample(
                    rel, lat, lon, alt,
                    (s2d * 3.6).takeIf { it in 0.0..500.0 },
                    (s3d * 3.6).takeIf { it in 0.0..500.0 },
                ),
            )
        }

        fun build(): CameraTelemetry? {
            if (gps.isEmpty() && iso.isEmpty() && shutter.isEmpty() && temp.isEmpty() && accl.isEmpty() && gyro.isEmpty()) {
                return null
            }
            val utcStart = anchors.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }
            return CameraTelemetry(
                utcStartMs = utcStart,
                durationMs = endMs.toLong(),
                gps = gps.sortedBy { it.time },
                iso = series(iso),
                exposure = series(shutter.filter { it.second > 0 }.map { it.first to Math.round(1.0 / it.second).toDouble() }),
                cameraTemp = series(temp),
                accel = imu(accl),
                gyro = imu(gyro),
                deviceName = device,
            )
        }

        private fun series(list: List<Pair<Long, Double>>) =
            if (list.isEmpty()) null else com.sportcamhud.core.telemetry.Series.of(list)

        private fun imu(list: List<Pair<Long, DoubleArray>>): ImuSamples? {
            if (list.isEmpty()) return null
            val sorted = list.sortedBy { it.first }
            return ImuSamples(
                LongArray(sorted.size) { sorted[it].first },
                Array(3) { axis -> DoubleArray(sorted.size) { sorted[it].second[axis] } },
            )
        }

        private fun parseGpsu(s: String): Long? = try {
            LocalDateTime.parse(s.trim().take(16), GPSU_FMT).toInstant(ZoneOffset.UTC).toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }
}
