package com.sportcamhud.app

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import com.sportcamhud.app.export.HudOverlay
import com.sportcamhud.app.export.VideoExporter
import com.sportcamhud.app.garmin.KeystoreTokenStore
import com.sportcamhud.app.render.AndroidHudCanvas
import com.sportcamhud.app.render.TileCache
import com.sportcamhud.app.video.VideoInfo
import com.sportcamhud.core.fit.FitReader
import com.sportcamhud.core.fit.FitSummary
import com.sportcamhud.core.garmin.ActivityCandidate
import com.sportcamhud.core.garmin.ActivityMatcher
import com.sportcamhud.core.garmin.FileRemoteTelemetryCache
import com.sportcamhud.core.garmin.GarminAuthClient
import com.sportcamhud.core.garmin.GarminConnectProvider
import com.sportcamhud.core.garmin.RemoteActivityResolver
import com.sportcamhud.core.layout.DefaultLayout
import com.sportcamhud.core.layout.HudLayout
import com.sportcamhud.core.layout.IndicatorConfig
import com.sportcamhud.core.render.HudCompositor
import com.sportcamhud.core.render.MapRenderer
import com.sportcamhud.core.gpmf.ChannelByteSource
import com.sportcamhud.core.gpmf.GpmfParser
import com.sportcamhud.core.telemetry.CameraTelemetry
import com.sportcamhud.core.telemetry.FrameData
import com.sportcamhud.core.telemetry.TelemetryDataset
import com.sportcamhud.core.telemetry.TelemetryTimeline
import com.sportcamhud.core.telemetry.TimeSync
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class HudViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx: Context get() = getApplication()
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val layoutFile = File(app.filesDir, "layout.json")

    val player: ExoPlayer = ExoPlayer.Builder(app).build()
    val tiles = TileCache(File(app.cacheDir, "tiles_v2")) // v2: CARTO tiles with the API-key stamp are not reused
    val previewCompositor = HudCompositor()

    val tokenStore = KeystoreTokenStore(app)
    val garminAuth = GarminAuthClient(tokenStore)
    private val garmin = GarminConnectProvider(garminAuth)
    private val remoteCache = FileRemoteTelemetryCache(File(app.filesDir, "garmin"))

    // ---- state ---------------------------------------------------------------
    var video by mutableStateOf<VideoInfo?>(null); private set
    var dataset by mutableStateOf<TelemetryDataset?>(null); private set
    /** GoPro GPMF telemetry embedded in the video (GPS, ISO, shutter, temperature, IMU). */
    var camera by mutableStateOf<CameraTelemetry?>(null); private set
    var fitSummary by mutableStateOf<FitSummary?>(null); private set
    var telemetryName by mutableStateOf<String?>(null); private set
    var autoSync by mutableStateOf<TimeSync.Result?>(null); private set
    var manualOffsetMs by mutableLongStateOf(prefs.getLong("manual_offset_ms", 0L)); private set
    var layout by mutableStateOf(loadLayout()); private set
    var selectedKey by mutableStateOf<String?>(null)
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false); private set

    var garminConnected by mutableStateOf(garminAuth.isConnected()); private set
    var garminUser by mutableStateOf<String?>(prefs.getString("garmin_user", null)); private set
    var autoGarmin by mutableStateOf(prefs.getBoolean("auto_garmin", true)); private set
    var activityChoices by mutableStateOf<List<ActivityMatcher.Ranked>?>(null); private set
    private var activityDecision: CompletableDeferred<ActivityCandidate?>? = null

    var exporting by mutableStateOf(false); private set
    var exportProgress by mutableIntStateOf(0); private set
    var exportResult by mutableStateOf<Uri?>(null); private set
    var clipStartMs by mutableLongStateOf(0L)
    var clipEndMs by mutableLongStateOf(0L)
    private var exportJob: Job? = null

    /** UTC of the first video frame: from the camera's GPS when present, else the MP4 date. */
    val videoStartUtc: Long? get() = camera?.utcStartMs ?: video?.creationTime

    /** Fields an indicator can be bound to (activity file + camera). */
    val availableFields: List<String>
        get() = ((dataset ?: timeline?.dataset)?.fields?.keys.orEmpty() + camera?.fields?.keys.orEmpty()).distinct().sorted()

    fun fieldLabel(field: String): String =
        CameraTelemetry.INFO[field]?.takeIf { camera?.has(field) == true }?.label
            ?: (dataset ?: timeline?.dataset)?.info(field)?.label ?: com.sportcamhud.core.telemetry.Fields.INFO[field]?.label ?: field

    /** Telemetry on the video clock (SmartSync + manual correction). */
    var timeline by mutableStateOf<TelemetryTimeline?>(null); private set

    // ---- video -----------------------------------------------------------------
    fun openVideo(uri: Uri) {
        try {
            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Not a persistable document (e.g. shared via SEND); fine for this session.
        }
        viewModelScope.launch {
            busy = true
            try {
                val info = withContext(Dispatchers.IO) { VideoInfo.probe(ctx, uri) }
                camera = withContext(Dispatchers.IO) { readCameraTelemetry(uri) }
                video = info
                clipStartMs = 0
                clipEndMs = info.durationMs
                exportResult = null
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                rebuildTimeline()
                status = "Wczytano film ${info.name} (${info.displayWidth}×${info.displayHeight})." +
                    (camera?.let { " " + describeCamera(it) } ?: "")
            } catch (e: Exception) {
                status = "Nie udało się otworzyć filmu: ${e.message}"
                return@launch
            } finally {
                busy = false
            }
            if (dataset == null && autoGarmin && garminConnected) fetchFromGarmin()
        }
    }

    private fun readCameraTelemetry(uri: Uri): CameraTelemetry? = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
            java.io.FileInputStream(pfd.fileDescriptor).use { GpmfParser.read(ChannelByteSource(it.channel)) }
        }
    } catch (e: Exception) {
        null
    }

    private fun describeCamera(c: CameraTelemetry): String {
        val parts = buildList {
            if (c.hasGps) add("GPS")
            if (c.iso != null) add("ISO")
            if (c.exposure != null) add("migawka")
            if (c.cameraTemp != null) add("temperatura")
            if (c.hasImu) add("przechył")
        }
        return "Telemetria GoPro: ${parts.joinToString(", ")}."
    }

    // ---- telemetry -------------------------------------------------------------
    fun openFit(uri: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Brak dostępu do pliku")
                }
                val name = uri.lastPathSegment?.substringAfterLast('/') ?: "aktywność.fit"
                loadFitBytes(bytes, name)
            } catch (e: Exception) {
                status = "Nie udało się wczytać pliku FIT: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun loadFitBytes(bytes: ByteArray, name: String) {
        val (ds, summary) = withContext(Dispatchers.Default) { FitReader.read(bytes) to FitReader.summary(bytes) }
        dataset = ds
        fitSummary = summary
        telemetryName = name
        previewCompositor.invalidate()
        rebuildTimeline()
        val fields = summary.availableFields.size
        status = "Wczytano $name: ${summary.recordCount} rekordów, $fields strumieni danych."
    }

    fun clearTelemetry() {
        dataset = null
        fitSummary = null
        telemetryName = null
        autoSync = null
        previewCompositor.invalidate()
        rebuildTimeline()
    }

    fun setManualOffset(ms: Long) {
        manualOffsetMs = ms
        prefs.edit().putLong("manual_offset_ms", ms).apply()
        previewCompositor.invalidate()
        rebuildTimeline()
    }

    private fun rebuildTimeline() {
        val v = video
        val cam = camera
        val start = videoStartUtc
        val ds = dataset
        if (ds == null || ds.start == null) {
            autoSync = null
            // Without a FIT file the GoPro GPS (if any) drives speed, distance, altitude and the map.
            val camDs = if (cam != null && start != null) cam.toDataset(start) else null
            timeline = v?.let { TelemetryTimeline(camDs, start ?: System.currentTimeMillis(), it.durationMs, camera = cam) }
            return
        }
        val camTrack = cam?.utcStartMs?.let { s0 -> cam.relativeTrack().map { it.copy(time = it.time + s0) } }.orEmpty()
        val sync = TimeSync.smartOffset(ds.start!!, ds.end!!, start, ds.track, camTrack)
        autoSync = sync
        val shifted = ds.shifted(sync.offsetMs + manualOffsetMs)
        val videoStart = start ?: ds.start!!
        timeline = TelemetryTimeline(shifted, videoStart, v?.durationMs ?: (ds.end!! - ds.start!!), camera = cam)
    }

    fun frameAt(positionMs: Long): FrameData? = timeline?.frameAt(positionMs)

    // ---- Garmin Connect ----------------------------------------------------------
    fun onGarminLoginFinished(ok: Boolean, message: String) {
        garminConnected = ok && garminAuth.isConnected()
        if (ok) {
            garminUser = message
            prefs.edit().putString("garmin_user", message).apply()
            status = "Połączono z Garmin Connect jako $message."
        } else {
            status = message
        }
    }

    fun garminLogout() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { garminAuth.logout() }
            withContext(Dispatchers.Main) {
                garminConnected = false
                garminUser = null
                prefs.edit().remove("garmin_user").apply()
                status = "Wylogowano z Garmin Connect."
            }
        }
    }

    fun testGarmin() {
        viewModelScope.launch {
            busy = true
            val (ok, msg) = withContext(Dispatchers.IO) { garmin.testConnection() }
            busy = false
            garminConnected = garminAuth.isConnected()
            status = if (ok) "Garmin Connect działa — zalogowano jako $msg." else msg
        }
    }

    fun changeAutoGarmin(value: Boolean) {
        autoGarmin = value
        prefs.edit().putBoolean("auto_garmin", value).apply()
    }

    /** Finds the activity recorded during the video and downloads its FIT. */
    fun fetchFromGarmin() {
        val v = video ?: run { status = "Najpierw wybierz film."; return }
        val start = videoStartUtc ?: run {
            status = "Film nie ma zapisanej daty nagrania — wskaż plik FIT ręcznie."
            return
        }
        viewModelScope.launch {
            busy = true
            try {
                val resolver = RemoteActivityResolver(garmin, remoteCache)
                val file = withContext(Dispatchers.IO) {
                    resolver.resolve(
                        videoKey = v.cacheKey,
                        videoStart = start,
                        videoEnd = start + v.durationMs,
                        onStatus = { _, msg -> viewModelScope.launch(Dispatchers.Main) { status = msg } },
                        select = { ranked -> withContext(Dispatchers.Main) { askForActivity(ranked) } },
                    )
                }
                if (file != null) {
                    val bytes = withContext(Dispatchers.IO) { file.readBytes() }
                    loadFitBytes(bytes, "Garmin Connect ${file.nameWithoutExtension}")
                }
            } catch (e: Exception) {
                status = "Garmin Connect: ${e.message}"
            } finally {
                busy = false
            }
        }
    }

    private suspend fun askForActivity(ranked: List<ActivityMatcher.Ranked>): ActivityCandidate? {
        val d = CompletableDeferred<ActivityCandidate?>()
        activityDecision = d
        activityChoices = ranked
        return try {
            d.await()
        } finally {
            activityChoices = null
            activityDecision = null
        }
    }

    fun chooseActivity(candidate: ActivityCandidate?) {
        activityDecision?.complete(candidate)
    }

    // ---- layout ---------------------------------------------------------------
    private fun loadLayout(): HudLayout = try {
        if (layoutFile.isFile) HudLayout.fromJsonMergedWithDefaults(layoutFile.readText()) else DefaultLayout.create()
    } catch (_: Exception) {
        DefaultLayout.create()
    }

    private fun persistLayout(l: HudLayout) {
        viewModelScope.launch(Dispatchers.IO) { runCatching { layoutFile.writeText(l.toJson()) } }
    }

    /** [persist] = false while dragging on the preview; call [persistNow] when the gesture ends. */
    fun updateIndicator(cfg: IndicatorConfig, persist: Boolean = true) {
        layout = layout.replace(cfg)
        previewCompositor.invalidate()
        if (persist) persistLayout(layout)
    }

    fun persistNow() = persistLayout(layout)

    fun removeIndicator(key: String) {
        layout = layout.remove(key)
        if (selectedKey == key) selectedKey = null
        persistLayout(layout)
    }

    fun addIndicator(field: String) {
        val label = fieldLabel(field)
        val cfg = DefaultLayout.newIndicatorFor(field, label, layout.indicators.keys)
        updateIndicator(cfg)
        selectedKey = cfg.key
    }

    fun resetLayout() {
        layout = DefaultLayout.create()
        previewCompositor.invalidate()
        persistLayout(layout)
    }

    fun importLayout(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }
                    ?: error("Brak dostępu do pliku")
                layout = HudLayout.fromJsonMergedWithDefaults(text)
                previewCompositor.invalidate()
                persistLayout(layout)
                status = "Wczytano preset wskaźników."
            } catch (e: Exception) {
                status = "Nieprawidłowy preset: ${e.message}"
            }
        }
    }

    fun exportLayout(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching { ctx.contentResolver.openOutputStream(uri)?.use { it.write(layout.toJson().toByteArray()) } }.isSuccess
            withContext(Dispatchers.Main) { status = if (ok) "Zapisano preset wskaźników." else "Nie udało się zapisać presetu." }
        }
    }

    fun setTextOutline(value: Int) {
        val g = kotlinx.serialization.json.JsonObject(layout.global + ("text_outline" to kotlinx.serialization.json.JsonPrimitive(value)))
        layout = HudLayout(LinkedHashMap(layout.indicators), g, layout.extra)
        persistLayout(layout)
    }

    // ---- export ---------------------------------------------------------------
    fun startExport() {
        val v = video ?: return
        if (exporting) return
        exporting = true
        exportProgress = 0
        exportResult = null
        player.pause()
        val exportLayout = layout
        val tl = timeline
        val clipStart = clipStartMs
        val clipEnd = clipEndMs.takeIf { it in (clipStart + 1) until v.durationMs }
        exportJob = viewModelScope.launch {
            try {
                // Download every map tile the route needs before rendering starts.
                val map = exportLayout.indicators.values.firstOrNull { it.enabled && it.form == "map" }
                val track = tl?.dataset?.track
                if (map != null && !track.isNullOrEmpty()) {
                    status = "Pobieranie kafelków mapy…"
                    val z = map.int("zoom", 16).coerceIn(1, MapRenderer.maxZoom(map.string("map_style", "light_all")))
                    // Same reach as MapRenderer: half the map size (+45 % for track-up rotation) in tile pixels.
                    val tileScale = maxOf(0.5, minOf(v.displayWidth, v.displayHeight) / 1080.0)
                    val radius = map.double("size", 18.0) / 100.0 * v.displayWidth / 2 * 1.45 / tileScale
                    val needed = MapRenderer.tilesForTrack(track.map { it.lat to it.lon }, z, radius)
                    withContext(Dispatchers.IO) { tiles.prefetch(map.string("map_style", "light_all"), needed) }
                }
                status = "Renderowanie filmu z nakładką…"
                val compositor = HudCompositor()
                var hudCanvas: AndroidHudCanvas? = null
                val overlay = HudOverlay { canvas, w, h, ptsUs ->
                    val c = hudCanvas?.also { it.reset(canvas) } ?: AndroidHudCanvas(canvas, w, h).also { hudCanvas = it }
                    // Clipped exports restart presentation time at 0: add the clip start back.
                    val posMs = clipStart + ptsUs / 1000
                    compositor.render(c, w, h, exportLayout, tl?.frameAt(posMs), tiles)
                }
                val uri = VideoExporter(ctx).export(v.uri, clipStart, clipEnd, overlay, v.name) { exportProgress = it }
                exportResult = uri
                status = "Gotowe! Film zapisano w Filmy/SportCamHUD."
            } catch (e: kotlinx.coroutines.CancellationException) {
                status = "Przerwano renderowanie."
            } catch (e: Exception) {
                status = e.message ?: "Renderowanie nie powiodło się."
            } finally {
                exporting = false
            }
        }
    }

    fun cancelExport() {
        exportJob?.cancel()
    }

    override fun onCleared() {
        player.release()
        super.onCleared()
    }
}
