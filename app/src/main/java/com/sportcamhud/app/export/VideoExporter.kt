package com.sportcamhud.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.TransformationRequest
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.common.Effect
import androidx.media3.effect.TextureOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class ExportFailed(message: String) : Exception(message)

/**
 * Renders the HUD into the video with Media3 Transformer (replaces the FFmpeg pipeline
 * of the Windows app). Audio is passed through; video is re-encoded with the overlay.
 */
@OptIn(UnstableApi::class)
class VideoExporter(private val context: Context) {

    /** Output size and HDR handling for one render attempt. */
    data class Plan(val width: Int, val height: Int, val hdrMode: Int, val label: String)

    /** What the exporter needs to know about the source video track. */
    data class SourceInfo(val mime: String?, val hdr: Boolean, val profile: Int?, val describe: String)

    /** Reads codec and colour info of the first video track (HDR GoPro footage needs tone mapping). */
    fun probe(input: Uri): SourceInfo = try {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(context, input, null)
            val fmt = (0 until ex.trackCount).map { ex.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            if (fmt == null) {
                SourceInfo(null, false, null, "brak ścieżki wideo?")
            } else {
                fun int(key: String) = if (fmt.containsKey(key)) fmt.getInteger(key) else null
                val transfer = int(MediaFormat.KEY_COLOR_TRANSFER)
                val hdr = transfer == MediaFormat.COLOR_TRANSFER_ST2084 || transfer == MediaFormat.COLOR_TRANSFER_HLG
                SourceInfo(
                    fmt.getString(MediaFormat.KEY_MIME), hdr, int(MediaFormat.KEY_PROFILE),
                    "${fmt.getString(MediaFormat.KEY_MIME)} ${int(MediaFormat.KEY_WIDTH)}x${int(MediaFormat.KEY_HEIGHT)} " +
                        "profil=${int(MediaFormat.KEY_PROFILE)} transfer=$transfer standard=${int(MediaFormat.KEY_COLOR_STANDARD)} " +
                        "fps=${if (fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) fmt.getNumber(MediaFormat.KEY_FRAME_RATE) else "?"}",
                )
            }
        } finally {
            ex.release()
        }
    } catch (e: Exception) {
        SourceInfo(null, false, null, "nie udało się odczytać formatu: ${e.message}")
    }

    /**
     * Attempts from the preferred one to the safest: HDR is tone-mapped by the hardware codec
     * first (Android 12+) and by OpenGL next, then everything is retried at 720p.
     */
    fun plans(source: SourceInfo, width: Int, height: Int): List<Plan> {
        val modes = if (source.hdr) {
            listOfNotNull(
                Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC.takeIf { Build.VERSION.SDK_INT >= 31 },
                Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL,
            )
        } else {
            listOf(Composition.HDR_MODE_KEEP_HDR)
        }
        fun modeName(m: Int) = when (m) {
            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_MEDIACODEC -> "HDR→SDR kodek"
            Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL -> "HDR→SDR OpenGL"
            else -> "SDR"
        }
        val out = modes.map { Plan(width, height, it, "${width}x$height ${modeName(it)}") }.toMutableList()
        val short = minOf(width, height)
        if (short > 720) {
            val f = 720.0 / short
            val w = even(width * f)
            val h = even(height * f)
            out += Plan(w, h, modes.last(), "${w}x$h ${modeName(modes.last())}")
        }
        return out
    }

    private fun even(v: Double) = (Math.round(v / 2.0) * 2).toInt().coerceAtLeast(2)

    /**
     * Must be called from the main thread (Transformer needs a Looper thread).
     * Tries each of [plans] in turn until one renders.
     * @return the content Uri of the saved film in Movies/SportCamHUD.
     */
    suspend fun export(
        input: Uri,
        clipStartMs: Long,
        clipEndMs: Long?,
        overlayFactory: () -> HudOverlay,
        outputName: String,
        plans: List<Plan>,
        log: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ): Uri {
        val out = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val errors = mutableListOf<String>()
        try {
            for ((i, plan) in plans.withIndex()) {
                log("próba ${i + 1}/${plans.size}: ${plan.label}, wyjście H.264/AVC")
                onProgress(0)
                try {
                    // A failed Transformer may have released the previous overlay's GL texture.
                    // Give every attempt its own bitmap, compositor and GL lifetime.
                    render(input, clipStartMs, clipEndMs, overlayFactory(), plan, out, log, onProgress)
                    log("render OK: ${plan.label}")
                    errors.clear()
                    break
                } catch (e: ExportFailed) {
                    log("błąd: ${e.message}")
                    errors += "${plan.label}: ${e.message}"
                    out.delete()
                }
            }
            if (errors.isNotEmpty()) throw ExportFailed(errors.joinToString("\n\n"))
            onProgress(100)
            return withContext(Dispatchers.IO) { saveToMovies(out, outputName) }
        } finally {
            out.delete()
        }
    }

    private suspend fun render(
        input: Uri,
        clipStartMs: Long,
        clipEndMs: Long?,
        overlay: HudOverlay,
        plan: Plan,
        out: File,
        log: (String) -> Unit,
        onProgress: (Int) -> Unit,
    ) {
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clipStartMs).apply {
            if (clipEndMs != null) setEndPositionMs(clipEndMs)
        }.build()
        val item = MediaItem.Builder().setUri(input).setClippingConfiguration(clipping).build()
        val videoEffects = listOf<Effect>(
            Presentation.createForWidthAndHeight(plan.width, plan.height, Presentation.LAYOUT_SCALE_TO_FIT),
            OverlayEffect(listOf<TextureOverlay>(overlay)),
        )
        val edited = EditedMediaItem.Builder(item).setEffects(Effects(listOf(), videoEffects)).build()
        @Suppress("DEPRECATION")
        val composition = Composition.Builder(EditedMediaItemSequence(listOf(edited)))
            .setHdrMode(plan.hdrMode)
            .build()

        coroutineScope {
            var transformer: Transformer? = null
            val poller = launch {
                val holder = ProgressHolder()
                var logged = -10
                while (true) {
                    delay(300)
                    val t = transformer ?: continue
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(holder.progress)
                        if (holder.progress >= logged + 10) {
                            logged = holder.progress - holder.progress % 10
                            log("postęp ${holder.progress}%")
                        }
                    }
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val t = Transformer.Builder(context)
                        // The source may be GoPro HEVC Main10, but the phone's HEVC encoder
                        // may not support editing/overlays. AVC is the compatible SDR output.
                        // Audio is still passed through by Transformer when possible.
                        .setVideoMimeType(MimeTypes.VIDEO_H264)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) {
                                    cont.resumeWithException(
                                        ExportFailed("Renderowanie nie powiodło się: ${exportException.errorCodeName}" + describeCause(exportException)),
                                    )
                                }
                            }

                            override fun onFallbackApplied(
                                composition: Composition,
                                originalTransformationRequest: TransformationRequest,
                                fallbackTransformationRequest: TransformationRequest,
                            ) {
                                log("kodek zmienił ustawienia: $fallbackTransformationRequest")
                            }
                        })
                        .build()
                    transformer = t
                    cont.invokeOnCancellation { t.cancel() }
                    log("Transformer.start")
                    t.start(composition, out.absolutePath)
                }
            } finally {
                poller.cancel()
            }
        }
    }

    /** Innermost causes of a Media3 error, so a failure on the phone says what actually broke. */
    private fun describeCause(e: Throwable): String {
        val parts = generateSequence(e.cause) { it.cause }.take(4)
            .map { "${it.javaClass.simpleName}: ${it.message ?: ""}".trim() }.toList()
        return if (parts.isEmpty()) "" else "\n" + parts.joinToString("\n")
    }

    private fun saveToMovies(file: File, baseName: String): Uri {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val name = "${baseName.substringBeforeLast('.')}_HUD_$stamp.mp4"
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SportCamHUD")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "SportCamHUD")
                dir.mkdirs()
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, File(dir, name).absolutePath)
            }
        }
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw ExportFailed("Nie udało się utworzyć pliku w galerii.")
        resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
            ?: throw ExportFailed("Nie udało się zapisać filmu w galerii.")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            resolver.update(uri, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
        }
        return uri
    }
}
