package com.sportcamhud.app.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.annotation.OptIn
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BitmapOverlay

/**
 * Media3 overlay that draws the HUD for every exported frame.
 *
 * Two bitmaps are used alternately: BitmapOverlay re-uploads its texture only when it
 * receives a different Bitmap instance, so ping-ponging guarantees a fresh HUD per frame.
 */
@OptIn(UnstableApi::class)
class HudOverlay(
    /** Draws the HUD for [presentationTimeUs] onto a transparent canvas of the given size. */
    private val draw: (canvas: Canvas, width: Int, height: Int, presentationTimeUs: Long) -> Unit,
) : BitmapOverlay() {
    private var width = 0
    private var height = 0
    private val buffers = arrayOfNulls<Bitmap>(2)
    private var index = 0

    override fun configure(videoSize: Size) {
        super.configure(videoSize)
        width = videoSize.width
        height = videoSize.height
    }

    override fun getBitmap(presentationTimeUs: Long): Bitmap {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        index = 1 - index
        var bmp = buffers[index]
        if (bmp == null || bmp.width != w || bmp.height != h) {
            bmp?.recycle()
            bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buffers[index] = bmp
        }
        bmp.eraseColor(Color.TRANSPARENT)
        draw(Canvas(bmp), w, h, presentationTimeUs)
        return bmp
    }

    override fun release() {
        super.release()
        buffers.forEach { it?.recycle() }
        buffers.fill(null)
    }
}
