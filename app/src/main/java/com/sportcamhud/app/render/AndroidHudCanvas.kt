package com.sportcamhud.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.sportcamhud.core.render.HAlign
import com.sportcamhud.core.render.HudCanvas
import com.sportcamhud.core.render.HudImage
import com.sportcamhud.core.render.TextStyle
import com.sportcamhud.core.render.VAlign

/** A bitmap usable by the shared HUD renderers (map tiles). */
class AndroidHudImage(val bitmap: Bitmap) : HudImage {
    override val width: Int get() = bitmap.width
    override val height: Int get() = bitmap.height
}

/**
 * [HudCanvas] on top of android.graphics.Canvas. The same instance type draws the
 * live preview (scaled view canvas) and every exported video frame (overlay bitmap).
 */
class AndroidHudCanvas(private var canvas: Canvas, override val width: Int, override val height: Int) : HudCanvas {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val textFill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        style = Paint.Style.FILL
        typeface = Typeface.DEFAULT
    }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
        typeface = Typeface.DEFAULT
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rectF = RectF()
    private val bounds = Rect()
    private val capCache = HashMap<Float, Float>()

    /** Lets one canvas wrapper be reused for every frame of an export. */
    fun reset(newCanvas: Canvas) {
        canvas = newCanvas
    }

    override fun save() { canvas.save() }
    override fun restore() { canvas.restore() }
    override fun translate(dx: Float, dy: Float) = canvas.translate(dx, dy)
    override fun rotate(degrees: Float, px: Float, py: Float) = canvas.rotate(degrees, px, py)

    override fun beginLayer(alpha: Float) {
        canvas.saveLayerAlpha(null, (alpha.coerceIn(0f, 1f) * 255).toInt())
    }

    override fun endLayer() = canvas.restore()

    override fun clipRect(l: Float, t: Float, r: Float, b: Float) {
        canvas.clipRect(l, t, r, b)
    }

    override fun clipCircle(cx: Float, cy: Float, radius: Float) {
        path.reset()
        path.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.clipPath(path)
    }

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, roundCap: Boolean) {
        stroke.color = color
        stroke.strokeWidth = width
        stroke.strokeCap = if (roundCap) Paint.Cap.ROUND else Paint.Cap.BUTT
        canvas.drawLine(x1, y1, x2, y2, stroke)
    }

    private fun buildPath(xs: FloatArray, ys: FloatArray, count: Int, close: Boolean) {
        path.reset()
        path.moveTo(xs[0], ys[0])
        for (i in 1 until count) path.lineTo(xs[i], ys[i])
        if (close) path.close()
    }

    override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float, closed: Boolean) {
        if (count < 2) return
        buildPath(xs, ys, count, closed)
        stroke.color = color
        stroke.strokeWidth = width
        stroke.strokeCap = Paint.Cap.ROUND
        canvas.drawPath(path, stroke)
    }

    override fun fillPolygon(xs: FloatArray, ys: FloatArray, count: Int, color: Int) {
        if (count < 3) return
        buildPath(xs, ys, count, true)
        fill.color = color
        canvas.drawPath(path, fill)
    }

    override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(cx, cy, radius, fill)
    }

    override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        canvas.drawCircle(cx, cy, radius, stroke)
    }

    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int, cornerRadius: Float) {
        fill.color = color
        rectF.set(l, t, r, b)
        if (cornerRadius > 0f) canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, fill) else canvas.drawRect(rectF, fill)
    }

    override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, cornerRadius: Float) {
        stroke.color = color
        stroke.strokeWidth = width
        rectF.set(l, t, r, b)
        if (cornerRadius > 0f) canvas.drawRoundRect(rectF, cornerRadius, cornerRadius, stroke) else canvas.drawRect(rectF, stroke)
    }

    private fun typeface(bold: Boolean): Typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    override fun textWidth(text: String, sizePx: Float, bold: Boolean): Float {
        textFill.textSize = sizePx
        textFill.typeface = typeface(bold)
        return textFill.measureText(text)
    }

    override fun capHeight(sizePx: Float): Float = capCache.getOrPut(sizePx) {
        textFill.textSize = sizePx
        textFill.typeface = Typeface.DEFAULT
        textFill.getTextBounds("H", 0, 1, bounds)
        bounds.height().toFloat()
    }

    override fun text(text: String, x: Float, y: Float, style: TextStyle, hAlign: HAlign, vAlign: VAlign) {
        if (text.isEmpty()) return
        val tf = typeface(style.bold)
        textFill.typeface = tf
        textFill.textSize = style.sizePx
        val w = textFill.measureText(text)
        val cap = capHeight(style.sizePx)
        val left = when (hAlign) {
            HAlign.LEFT -> x
            HAlign.CENTER -> x - w / 2f
            HAlign.RIGHT -> x - w
        }
        val baseline = when (vAlign) {
            VAlign.TOP -> y + cap
            VAlign.MIDDLE -> y + cap / 2f
            VAlign.BASELINE -> y
            VAlign.BOTTOM -> y - textFill.fontMetrics.descent
        }
        if (style.strokeWidth > 0f) {
            textStroke.typeface = tf
            textStroke.textSize = style.sizePx
            textStroke.strokeWidth = style.strokeWidth * 2f
            textStroke.color = style.strokeColor
            canvas.drawText(text, left, baseline, textStroke)
        }
        textFill.color = style.color
        canvas.drawText(text, left, baseline, textFill)
    }

    override fun image(image: HudImage, l: Float, t: Float, r: Float, b: Float, alpha: Float) {
        val bmp = (image as? AndroidHudImage)?.bitmap ?: return
        if (bmp.isRecycled) return
        bitmapPaint.alpha = (alpha.coerceIn(0f, 1f) * 255).toInt()
        rectF.set(l, t, r, b)
        canvas.drawBitmap(bmp, null, rectF, bitmapPaint)
    }
}
