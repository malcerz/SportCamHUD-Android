package com.sportcamhud.core

import com.sportcamhud.core.render.HAlign
import com.sportcamhud.core.render.HudCanvas
import com.sportcamhud.core.render.HudImage
import com.sportcamhud.core.render.TextStyle
import com.sportcamhud.core.render.VAlign
import java.awt.AlphaComposite
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.font.TextLayout
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.ArrayDeque

/** java.awt implementation of [HudCanvas] used to render HUD previews in unit tests. */
class AwtHudCanvas(val image: BufferedImage) : HudCanvas {
    override val width get() = image.width
    override val height get() = image.height

    private val stack = ArrayDeque<Graphics2D>()
    private var g: Graphics2D = image.createGraphics().also { setup(it) }

    private fun setup(gr: Graphics2D) {
        gr.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        gr.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        gr.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
        gr.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
    }

    private fun color(c: Int) = Color(c, true)
    private fun font(size: Float, bold: Boolean) = Font(Font.SANS_SERIF, if (bold) Font.BOLD else Font.PLAIN, 12).deriveFont(size)

    override fun save() { stack.push(g); g = g.create() as Graphics2D }
    override fun restore() { g.dispose(); g = stack.pop() }
    override fun translate(dx: Float, dy: Float) = g.translate(dx.toDouble(), dy.toDouble())
    override fun rotate(degrees: Float, px: Float, py: Float) = g.rotate(Math.toRadians(degrees.toDouble()), px.toDouble(), py.toDouble())
    override fun beginLayer(alpha: Float) { save(); g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha) }
    override fun endLayer() = restore()
    override fun clipRect(l: Float, t: Float, r: Float, b: Float) = g.clip(java.awt.geom.Rectangle2D.Float(l, t, r - l, b - t))
    override fun clipCircle(cx: Float, cy: Float, radius: Float) = g.clip(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))

    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float, roundCap: Boolean) {
        g.color = color(color)
        g.stroke = BasicStroke(width, if (roundCap) BasicStroke.CAP_ROUND else BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
        g.draw(Line2D.Float(x1, y1, x2, y2))
    }

    private fun path(xs: FloatArray, ys: FloatArray, n: Int, close: Boolean) = Path2D.Float().apply {
        moveTo(xs[0], ys[0])
        for (i in 1 until n) lineTo(xs[i], ys[i])
        if (close) closePath()
    }

    override fun polyline(xs: FloatArray, ys: FloatArray, count: Int, color: Int, width: Float, closed: Boolean) {
        if (count < 2) return
        g.color = color(color)
        g.stroke = BasicStroke(width, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g.draw(path(xs, ys, count, closed))
    }

    override fun fillPolygon(xs: FloatArray, ys: FloatArray, count: Int, color: Int) {
        if (count < 3) return
        g.color = color(color)
        g.fill(path(xs, ys, count, true))
    }

    override fun fillCircle(cx: Float, cy: Float, radius: Float, color: Int) {
        g.color = color(color); g.fill(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
    }

    override fun strokeCircle(cx: Float, cy: Float, radius: Float, color: Int, width: Float) {
        g.color = color(color); g.stroke = BasicStroke(width)
        g.draw(Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2))
    }

    override fun fillRect(l: Float, t: Float, r: Float, b: Float, color: Int, cornerRadius: Float) {
        g.color = color(color); g.fill(RoundRectangle2D.Float(l, t, r - l, b - t, cornerRadius * 2, cornerRadius * 2))
    }

    override fun strokeRect(l: Float, t: Float, r: Float, b: Float, color: Int, width: Float, cornerRadius: Float) {
        g.color = color(color); g.stroke = BasicStroke(width)
        g.draw(RoundRectangle2D.Float(l, t, r - l, b - t, cornerRadius * 2, cornerRadius * 2))
    }

    override fun textWidth(text: String, sizePx: Float, bold: Boolean): Float =
        g.getFontMetrics(font(sizePx, bold)).stringWidth(text).toFloat()

    override fun capHeight(sizePx: Float): Float =
        font(sizePx, false).createGlyphVector(g.fontRenderContext, "H").visualBounds.height.toFloat()

    override fun text(text: String, x: Float, y: Float, style: TextStyle, hAlign: HAlign, vAlign: VAlign) {
        if (text.isEmpty()) return
        val f = font(style.sizePx, style.bold)
        val layout = TextLayout(text, f, g.fontRenderContext)
        val w = layout.advance
        val cap = capHeight(style.sizePx)
        val left = when (hAlign) { HAlign.LEFT -> x; HAlign.CENTER -> x - w / 2; HAlign.RIGHT -> x - w }
        val baseline = when (vAlign) {
            VAlign.TOP -> y + cap
            VAlign.MIDDLE -> y + cap / 2
            VAlign.BASELINE -> y
            VAlign.BOTTOM -> y - layout.descent
        }
        val shape = layout.getOutline(AffineTransform.getTranslateInstance(left.toDouble(), baseline.toDouble()))
        if (style.strokeWidth > 0) {
            g.color = color(style.strokeColor)
            g.stroke = BasicStroke(style.strokeWidth * 2, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g.draw(shape)
        }
        g.color = color(style.color)
        g.fill(shape)
    }

    class Img(val img: BufferedImage) : HudImage {
        override val width get() = img.width
        override val height get() = img.height
    }

    override fun image(image: HudImage, l: Float, t: Float, r: Float, b: Float, alpha: Float) {
        val i = (image as Img).img
        g.drawImage(i, l.toInt(), t.toInt(), (r - l).toInt(), (b - t).toInt(), null)
    }
}
