package com.sportcamhud.core.render

import com.sportcamhud.core.layout.IndicatorConfig
import com.sportcamhud.core.telemetry.Fields
import com.sportcamhud.core.telemetry.FrameData
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tan

/** Moving map with the GPS track (`indicators/moving_map.py`), Web-Mercator tiles. */
object MapRenderer {
    const val TILE = 256

    /** Map styles of the Windows app. */
    val STYLES = linkedMapOf(
        "light_all" to "https://a.basemaps.cartocdn.com/light_all/{z}/{x}/{y}.png",
        "light_nolabels" to "https://a.basemaps.cartocdn.com/light_nolabels/{z}/{x}/{y}.png",
        "dark_all" to "https://a.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}.png",
        "dark_nolabels" to "https://a.basemaps.cartocdn.com/dark_nolabels/{z}/{x}/{y}.png",
        "voyager_all" to "https://a.basemaps.cartocdn.com/voyager_all/{z}/{x}/{y}.png",
        "voyager_nolabels" to "https://a.basemaps.cartocdn.com/voyager_nolabels/{z}/{x}/{y}.png",
        "satellite" to "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}",
    )

    fun tileUrl(style: String, z: Int, x: Int, y: Int): String =
        (STYLES[style] ?: STYLES.getValue("light_all")).replace("{z}", "$z").replace("{x}", "$x").replace("{y}", "$y")

    fun worldX(lon: Double, z: Int): Double = (lon + 180.0) / 360.0 * TILE * 2.0.pow(z)
    fun worldY(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0511, 85.0511))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0 * TILE * 2.0.pow(z)
    }

    /** All tiles touched by the track at [z] within the map radius, for export prefetching. */
    fun tilesForTrack(points: List<Pair<Double, Double>>, z: Int, radiusPx: Double): Set<Triple<Int, Int, Int>> {
        val out = HashSet<Triple<Int, Int, Int>>()
        val n = 1 shl z
        for ((lat, lon) in points) {
            val wx = worldX(lon, z)
            val wy = worldY(lat, z)
            val x0 = floor((wx - radiusPx) / TILE).toInt()
            val x1 = floor((wx + radiusPx) / TILE).toInt()
            val y0 = floor((wy - radiusPx) / TILE).toInt()
            val y1 = floor((wy + radiusPx) / TILE).toInt()
            for (tx in x0..x1) for (ty in y0..y1) if (ty in 0 until n) out += Triple(z, ((tx % n) + n) % n, ty)
        }
        return out
    }

    fun draw(ctx: RenderContext, cfg: IndicatorConfig, frame: FrameData?) {
        val ds = frame?.dataset ?: return
        if (ds.track.size < 2) return
        val c = ctx.canvas
        val size = ctx.s(cfg.double("size", 18.0), ctx.width)
        val half = size / 2
        val cx = ctx.px(cfg)
        val cy = ctx.py(cfg)
        val z = cfg.int("zoom", 16).coerceIn(1, 19)
        val ts = max(0.5f, ctx.scale1080)
        val style = cfg.string("map_style", "light_all")
        val circle = cfg.string("map_shape", "square") == "circle"
        val trackUp = cfg.string("map_orientation", "north_up") == "track_up"

        val pos = ds.positionAt(frame.time) ?: ds.track[ds.trackIndexAt(frame.time).coerceAtLeast(0)].let { it.lat to it.lon }
        val wx = worldX(pos.second, z)
        val wy = worldY(pos.first, z)
        val heading = if (trackUp) ds.valueAt(Fields.HEADING, frame.time) ?: 0.0 else 0.0

        c.save()
        if (cfg.rotation % 360 != 0) c.rotate(-cfg.rotation.toFloat(), cx, cy)
        c.save()
        if (circle) c.clipCircle(cx, cy, half) else c.clipRect(cx - half, cy - half, cx + half, cy + half)
        c.fillRect(cx - half, cy - half, cx + half, cy + half, Colors.argb(220, 32, 32, 32))
        if (trackUp) c.rotate(-heading.toFloat(), cx, cy)

        // Tiles.
        val reach = half * 1.45 / ts
        val n = 1 shl z
        val tiles = ctx.tiles
        if (tiles != null) {
            for (tx in floor((wx - reach) / TILE).toInt()..floor((wx + reach) / TILE).toInt()) {
                for (ty in floor((wy - reach) / TILE).toInt()..floor((wy + reach) / TILE).toInt()) {
                    if (ty !in 0 until n) continue
                    val img = tiles.tile(style, z, ((tx % n) + n) % n, ty) ?: continue
                    val l = cx + ((tx * TILE - wx) * ts).toFloat()
                    val t = cy + ((ty * TILE - wy) * ts).toFloat()
                    c.image(img, l, t, l + TILE * ts + 0.5f, t + TILE * ts + 0.5f)
                }
            }
        }

        // Track: full route dimmed, travelled part highlighted.
        val pts = ds.track
        val xs = FloatArray(pts.size)
        val ys = FloatArray(pts.size)
        for (i in pts.indices) {
            xs[i] = cx + ((worldX(pts[i].lon, z) - wx) * ts).toFloat()
            ys[i] = cy + ((worldY(pts[i].lat, z) - wy) * ts).toFloat()
        }
        val lw = max(2f, cfg.float("track_width", 3f) * ts)
        val routeColor = Colors.parse(cfg.string("track_color", "#FF5A1E")) ?: Colors.argb(255, 255, 90, 30)
        c.polyline(xs, ys, xs.size, Colors.argb(160, 0, 0, 0), lw + 2 * ts)
        c.polyline(xs, ys, xs.size, Colors.withAlpha(routeColor, 140), lw)
        val done = (ds.trackIndexAt(frame.time) + 1).coerceIn(0, xs.size)
        if (done >= 2) c.polyline(xs, ys, done, routeColor, lw)
        c.restore()

        // Frame and position marker (always in the centre).
        if (circle) c.strokeCircle(cx, cy, half, Colors.argb(200, 255, 255, 255), max(1f, 2 * ts))
        else c.strokeRect(cx - half, cy - half, cx + half, cy + half, Colors.argb(200, 255, 255, 255), max(1f, 2 * ts))
        val mr = max(3f, cfg.float("marker_size", 7f) * ts)
        c.fillCircle(cx, cy, mr + 2 * ts, Colors.argb(255, 30, 30, 30))
        c.fillCircle(cx, cy, mr, Colors.parse(cfg.string("marker_color", "#FFFFFF")) ?: Colors.WHITE)
        c.restore()
    }
}
