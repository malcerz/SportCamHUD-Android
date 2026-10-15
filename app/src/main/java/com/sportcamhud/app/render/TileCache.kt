package com.sportcamhud.app.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import com.sportcamhud.core.render.HudImage
import com.sportcamhud.core.render.MapRenderer
import com.sportcamhud.core.render.TileSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Map tiles for the moving map: memory LRU + disk cache, downloaded with the same
 * tile servers as the Windows app (CARTO basemaps / Esri imagery).
 */
class TileCache(private val dir: File) : TileSource {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val memory = object : LruCache<String, AndroidHudImage>(48 * 1024 * 1024) {
        override fun sizeOf(key: String, value: AndroidHudImage) = value.bitmap.byteCount
    }
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val limiter = Semaphore(4)

    private val _version = MutableStateFlow(0)
    /** Bumped whenever a tile arrives, so the preview can redraw. */
    val version: StateFlow<Int> = _version

    private fun key(style: String, z: Int, x: Int, y: Int) = "$style/$z/$x/$y"
    private fun file(style: String, z: Int, x: Int, y: Int) = File(dir, "$style/$z/$x/$y.png")

    /** Non-blocking: returns a cached tile or schedules the download. */
    override fun tile(style: String, z: Int, x: Int, y: Int): HudImage? {
        val k = key(style, z, x, y)
        memory.get(k)?.let { return it }
        val f = file(style, z, x, y)
        if (f.isFile) {
            decode(f)?.let { memory.put(k, it); return it }
        }
        if (inFlight.add(k)) {
            scope.launch {
                limiter.withPermit {
                    try {
                        if (download(style, z, x, y)) _version.value = _version.value + 1
                    } finally {
                        inFlight.remove(k)
                    }
                }
            }
        }
        return null
    }

    private fun decode(f: File): AndroidHudImage? =
        BitmapFactory.decodeFile(f.absolutePath)?.let { AndroidHudImage(it.copy(Bitmap.Config.ARGB_8888, false)) }

    private fun download(style: String, z: Int, x: Int, y: Int): Boolean {
        val f = file(style, z, x, y)
        if (f.isFile) return true
        val req = Request.Builder()
            .url(MapRenderer.tileUrl(style, z, x, y))
            .header("User-Agent", "SportCamHUD-Android/1.1 (+https://github.com/malcerz/SportCamHUD-Android)")
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val bytes = resp.body?.bytes() ?: return false
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeBytes(bytes)
                tmp.renameTo(f)
            }
        } catch (_: Exception) {
            false
        }
    }

    /** Blocking prefetch used before an export so every frame has its tiles. */
    fun prefetch(style: String, tiles: Set<Triple<Int, Int, Int>>, onProgress: (Int, Int) -> Unit = { _, _ -> }) {
        var done = 0
        for ((z, x, y) in tiles) {
            download(style, z, x, y)
            onProgress(++done, tiles.size)
        }
    }

    fun clear() {
        memory.evictAll()
        dir.deleteRecursively()
        _version.value = _version.value + 1
    }
}
