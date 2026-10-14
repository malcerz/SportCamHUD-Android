package com.sportcamhud.app.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
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

    /**
     * Must be called from the main thread (Transformer needs a Looper thread).
     * @return the content Uri of the saved film in Movies/SportCamHUD.
     */
    suspend fun export(
        input: Uri,
        clipStartMs: Long,
        clipEndMs: Long?,
        overlay: HudOverlay,
        outputName: String,
        onProgress: (Int) -> Unit,
    ): Uri {
        val out = File(context.cacheDir, "export_${System.currentTimeMillis()}.mp4")
        val clipping = MediaItem.ClippingConfiguration.Builder().setStartPositionMs(clipStartMs).apply {
            if (clipEndMs != null) setEndPositionMs(clipEndMs)
        }.build()
        val item = MediaItem.Builder().setUri(input).setClippingConfiguration(clipping).build()
        val edited = EditedMediaItem.Builder(item)
            .setEffects(Effects(listOf(), listOf<Effect>(OverlayEffect(listOf<TextureOverlay>(overlay)))))
            .build()

        coroutineScope {
            var transformer: Transformer? = null
            val poller = launch {
                val holder = ProgressHolder()
                while (true) {
                    delay(300)
                    val t = transformer ?: continue
                    if (t.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { cont ->
                    val t = Transformer.Builder(context)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                if (cont.isActive) cont.resume(Unit)
                            }

                            override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                                if (cont.isActive) {
                                    cont.resumeWithException(
                                        ExportFailed("Renderowanie nie powiodło się: ${exportException.errorCodeName}"),
                                    )
                                }
                            }
                        })
                        .build()
                    transformer = t
                    cont.invokeOnCancellation { t.cancel() }
                    t.start(edited, out.absolutePath)
                }
            } finally {
                poller.cancel()
            }
        }
        onProgress(100)
        return try {
            withContext(Dispatchers.IO) { saveToMovies(out, outputName) }
        } finally {
            out.delete()
        }
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
