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
 * Two bitmaps are used alternately: BitmapOverlay re-uploads its texture only when it
 * receives a different Bitmap instance, so ping-ponging guarantees a fresh HUD per frame.
 *
 * For large videos (4K) the HUD is drawn at most [maxDim] pixels wide/high and scaled up on
 * the GPU: a full 4K ARGB bitmap per frame is slow and can exhaust memory on phones.
 */
@OptIn(UnstableApi::class)
class HudOverlay(
    private val maxDim: Int = 1920,
    /** Draws the HUD for [presentationTimeUs] onto a transparent canvas of the given size. */
    private val draw: (canvas: Canvas, width: Int, height: Int, presentationTimeUs: Long) -> Unit,
) : BitmapOverlay() {
    private var width = 0
    private var height = 0
    private var factor = 1f
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var index = 0
    private var drawErrors = 0

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        factor = minOf(1f, maxDim.toFloat() / max(videoSize.width, videoSize.height))
        width = (videoSize.width * factor).roundToInt().coerceAtLeast(1)
        height = (videoSize.height * factor).roundToInt().coerceAtLeast(1)
    }

    override fun getOverlaySettings(presentationTimeUs: Long): OverlaySettings =
        StaticOverlaySettings.Builder().setScale(1f / factor, 1f / factor).build()

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        index = 1 - index
        var bmp = buffers[index]
        if (bmp == null || bmp.width != width || bmp.height != height) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffers[index] = bmp
        }
        bmp.eraseColor(Color.TRANSPARENT)
        try {
            draw(Canvas(bmp), width, height, presentationTimeUs)
        } catch (e: Exception) {
            // A drawing bug must not abort the whole export; the frame just gets no HUD.
            if (drawErrors++ < 5) Log.e("HudOverlay", "HUD draw failed at ${presentationTimeUs}us", e)
        }
        return bmp
    }

    override fun release() {
        super.release()
        buffers.forEach { it?.recycle() }
        buffers.fill(null)
    }
}
