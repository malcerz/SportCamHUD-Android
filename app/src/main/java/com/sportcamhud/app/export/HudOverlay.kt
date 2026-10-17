package com.sportcamhud.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.OverlaySettings
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay
import androidx.media3.effect.StaticOverlaySettings
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Media3 overlay that draws the HUD for every exported frame.
 *
 * The same Bitmap is redrawn for each frame. Media3 BitmapOverlay (1.7.1) checks
 * Bitmap.getGenerationId(), so eraseColor()/drawing triggers a new GL texture upload.
 * One bitmap avoids wasting memory on a second full-frame allocation.
 *
 * For large videos (4K) the HUD is drawn at most [maxDim] pixels wide/high and scaled up on
 * the GPU: a full 4K ARGB bitmap per frame is slow and can exhaust memory on phones.
 */
@OptIn(UnstableApi::class)
class HudOverlay(
    private val maxDim: Int = 1920,
    /** Diagnostics sink (the export step log). */
    private val log: ((String) -> Unit)? = null,
    /** Draws the HUD for [presentationTimeUs] onto a transparent canvas of the given size. */
    private val draw: (canvas: Canvas, width: Int, height: Int, presentationTimeUs: Long) -> Unit,
) : BitmapOverlay() {
    private var width = 0
    private var height = 0
    private var factor = 1f
    private var buffer: Bitmap? = null
    private var drawErrors = 0

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        StaticOverlaySettings.Builder().setScale(1f / factor, 1f / factor).build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        var bmp = buffer
        if (bmp == null || bmp.width != width || bmp.height != height) {
            // Avoid recycling a bitmap still referenced by Media3/OpenGL.
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer = bmp
        }
        // Updates the generation ID and forces BitmapOverlay to upload this frame.
        bmp.eraseColor(Color.TRANSPARENT)
        try {
            draw(Canvas(bmp), width, height, presentationTimeUs)
        } catch (e: Throwable) {
            // A drawing bug (or OOM) must not kill the app mid-export; the frame just gets no HUD.
            if (drawErrors++ < 5) {
                Log.e("HudOverlay", "HUD draw failed at ${presentationTimeUs}us", e)
                log?.invoke("błąd rysowania HUD przy ${presentationTimeUs / 1000} ms: $e")
            }
        }
        return bmp
    }

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        factor = minOf(1f, maxDim.toFloat() / max(videoSize.width, videoSize.height))
        width = (videoSize.width * factor).roundToInt().coerceAtLeast(1)
        height = (videoSize.height * factor).roundToInt().coerceAtLeast(1)
        log?.invoke("nakładka: wideo ${videoSize.width}x${videoSize.height}, HUD ${width}x$height")
    }

    override fun release() {
        super.release()
        buffer = null
    }
}
