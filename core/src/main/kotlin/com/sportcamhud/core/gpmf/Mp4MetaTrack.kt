package com.sportcamhud.core.gpmf

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Random-access bytes of a video file (a FileChannel on Android, a RandomAccessFile in tests). */
interface ByteSource {
    val size: Long
    fun read(pos: Long, len: Int): ByteArray
}

class ChannelByteSource(private val channel: FileChannel) : ByteSource {
    override val size: Long get() = channel.size()
    override fun read(pos: Long, len: Int): ByteArray {
        val buf = ByteBuffer.allocate(len)
        var p = pos
        while (buf.hasRemaining()) {
            val n = channel.read(buf, p)
            if (n <= 0) break
            p += n
        }
        return buf.array().copyOf(buf.position())
    }
}

class FileByteSource(file: File) : ByteSource, AutoCloseable {
    private val raf = RandomAccessFile(file, "r")
    override val size: Long get() = raf.length()
    override fun read(pos: Long, len: Int): ByteArray {
        val out = ByteArray(len)
        raf.seek(pos)
        var done = 0
        while (done < len) {
            val n = raf.read(out, done, len - done)
            if (n <= 0) break
            done += n
        }
        return if (done == len) out else out.copyOf(done)
    }
    override fun close() = raf.close()
}

/** One GPMF payload (normally one second of telemetry) and where it sits on the video clock. */
data class MetaSample(val offset: Long, val size: Int, val startMs: Double, val durationMs: Double)

/**
 * Finds the GoPro metadata track (sample entry `gpmd`) in an MP4/MOV container and lists its
 * samples. This replaces the ffprobe/ffmpeg stream copy of the Windows app.
 */
object Mp4MetaTrack {

    private class Box(val type: String, val start: Long, val headerSize: Int, val size: Long) {
        val bodyStart get() = start + headerSize
        val end get() = start + size
    }

    fun samples(src: ByteSource): List<MetaSample>? {
        val moov = children(src, 0, src.size).firstOrNull { it.type == "moov" } ?: return null
        for (trak in children(src, moov.bodyStart, moov.end).filter { it.type == "trak" }) {
            val mdia = child(src, trak, "mdia") ?: continue
            val minf = child(src, mdia, "minf") ?: continue
            val stbl = child(src, minf, "stbl") ?: continue
            val stsd = child(src, stbl, "stsd") ?: continue
            // stsd: version/flags(4) + entry count(4), then entries (size + format).
            val fmt = String(src.read(stsd.bodyStart + 12, 4), Charsets.ISO_8859_1)
            if (fmt != "gpmd") continue
            val timescale = child(src, mdia, "mdhd")?.let { mdhdTimescale(src, it) } ?: continue
            return buildSamples(src, stbl, timescale)
        }
        return null
    }

    private fun mdhdTimescale(src: ByteSource, b: Box): Long {
        val head = src.read(b.bodyStart, 32)
        val bb = ByteBuffer.wrap(head)
        val version = head[0].toInt()
        return if (version == 1) bb.getInt(20).toLong() and 0xFFFFFFFFL else bb.getInt(12).toLong() and 0xFFFFFFFFL
    }

    private fun buildSamples(src: ByteSource, stbl: Box, timescale: Long): List<MetaSample>? {
        val sizes = child(src, stbl, "stsz")?.let { b ->
            val bb = body(src, b)
            val fixed = bb.getInt(4)
            val count = bb.getInt(8)
            IntArray(count) { if (fixed != 0) fixed else bb.getInt(12 + it * 4) }
        } ?: return null
        val chunkOffsets: LongArray = child(src, stbl, "stco")?.let { b ->
            val bb = body(src, b)
            LongArray(bb.getInt(4)) { bb.getInt(8 + it * 4).toLong() and 0xFFFFFFFFL }
        } ?: child(src, stbl, "co64")?.let { b ->
            val bb = body(src, b)
            LongArray(bb.getInt(4)) { bb.getLong(8 + it * 8) }
        } ?: return null
        // stsc: (first_chunk, samples_per_chunk, desc_index) runs.
        val stsc = child(src, stbl, "stsc")?.let { b ->
            val bb = body(src, b)
            List(bb.getInt(4)) { bb.getInt(8 + it * 12) to bb.getInt(12 + it * 12) }
        } ?: listOf(1 to 1)
        val deltas = child(src, stbl, "stts")?.let { b ->
            val bb = body(src, b)
            val out = ArrayList<Long>()
            repeat(bb.getInt(4)) { i ->
                val count = bb.getInt(8 + i * 8)
                val delta = bb.getInt(12 + i * 8).toLong() and 0xFFFFFFFFL
                repeat(count) { out.add(delta) }
            }
            out
        } ?: return null

        val result = ArrayList<MetaSample>(sizes.size)
        var sampleIndex = 0
        var ticks = 0L
        for (chunk in chunkOffsets.indices) {
            val chunkNo = chunk + 1
            val perChunk = stsc.lastOrNull { it.first <= chunkNo }?.second ?: 1
            var off = chunkOffsets[chunk]
            repeat(perChunk) {
                if (sampleIndex >= sizes.size) return@repeat
                val delta = deltas.getOrElse(sampleIndex) { deltas.lastOrNull() ?: timescale }
                result.add(
                    MetaSample(off, sizes[sampleIndex], ticks * 1000.0 / timescale, delta * 1000.0 / timescale),
                )
                off += sizes[sampleIndex]
                ticks += delta
                sampleIndex++
            }
        }
        return result
    }

    private fun body(src: ByteSource, b: Box): ByteBuffer =
        ByteBuffer.wrap(src.read(b.bodyStart, (b.end - b.bodyStart).coerceAtMost(64L * 1024 * 1024).toInt()))

    private fun child(src: ByteSource, parent: Box, type: String): Box? =
        children(src, parent.bodyStart, parent.end).firstOrNull { it.type == type }

    private fun children(src: ByteSource, from: Long, to: Long): List<Box> {
        val out = ArrayList<Box>()
        var pos = from
        while (pos + 8 <= to) {
            val h = src.read(pos, 16)
            if (h.size < 8) break
            val bb = ByteBuffer.wrap(h)
            var size = bb.getInt(0).toLong() and 0xFFFFFFFFL
            val type = String(h, 4, 4, Charsets.ISO_8859_1)
            var header = 8
            if (size == 1L && h.size >= 16) {
                size = bb.getLong(8)
                header = 16
            } else if (size == 0L) {
                size = to - pos
            }
            if (size < header) break
            out.add(Box(type, pos, header, size))
            pos += size
        }
        return out
    }
}
