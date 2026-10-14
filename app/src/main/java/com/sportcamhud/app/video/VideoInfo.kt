package com.sportcamhud.app.video

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** Video properties needed to synchronise telemetry and to size the HUD. */
data class VideoInfo(
    val uri: Uri,
    val name: String,
    val sizeBytes: Long,
    val durationMs: Long,
    val width: Int,
    val height: Int,
    val rotation: Int,
    /** MP4 creation time (epoch ms). Action cameras usually store local time tagged as UTC. */
    val creationTime: Long?,
) {
    /** Size as displayed (rotation applied). */
    val displayWidth: Int get() = if (rotation % 180 != 0) height else width
    val displayHeight: Int get() = if (rotation % 180 != 0) width else height

    /** Stable key used by the Garmin Connect cache. */
    val cacheKey: String get() = "$name|$sizeBytes|$durationMs"

    companion object {
        private val DATE_RE = Regex("""(\d{8})T(\d{6})""")

        fun parseMp4Date(raw: String?): Long? {
            if (raw.isNullOrBlank()) return null
            val m = DATE_RE.find(raw) ?: return null
            return try {
                val ldt = LocalDateTime.parse(m.groupValues[1] + m.groupValues[2], DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                val ms = ldt.toInstant(ZoneOffset.UTC).toEpochMilli()
                // 1904/1970 placeholders written by cameras without a clock.
                if (ldt.year < 2000) null else ms
            } catch (_: Exception) {
                null
            }
        }

        fun probe(context: Context, uri: Uri): VideoInfo {
            var name = uri.lastPathSegment ?: "video.mp4"
            var size = 0L
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    c.getString(0)?.let { name = it }
                    if (!c.isNull(1)) size = c.getLong(1)
                }
            }
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(context, uri)
                fun meta(key: Int) = r.extractMetadata(key)
                return VideoInfo(
                    uri = uri,
                    name = name,
                    sizeBytes = size,
                    durationMs = meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                    width = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1920,
                    height = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 1080,
                    rotation = meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                    creationTime = parseMp4Date(meta(MediaMetadataRetriever.METADATA_KEY_DATE)),
                )
            } finally {
                r.release()
            }
        }
    }
}
