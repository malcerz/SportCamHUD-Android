package com.sportcamhud.core.garmin

import java.io.File

/** Local cache of downloaded activities, keyed by video (port of remote_cache.py). */
interface RemoteTelemetryCache {
    fun find(videoKey: String): File?
    fun save(videoKey: String, provider: String, activityId: String, fit: ByteArray, meta: Map<String, String>): File
}

/**
 * Finds the Garmin Connect activity recorded during the video and downloads its FIT
 * (port of `resolve_remote_activity` from integrations/coordinator.py).
 */
class RemoteActivityResolver(
    private val provider: GarminConnectProvider,
    private val cache: RemoteTelemetryCache,
) {
    enum class Stage { CACHED, SEARCHING, NO_MATCH, SELECTION, CANCELLED, DOWNLOADING, DOWNLOADED, ERROR }

    /**
     * @param select called when several activities match; return the chosen one or null to skip.
     * @return the local FIT file or null.
     */
    suspend fun resolve(
        videoKey: String,
        videoStart: Long,
        videoEnd: Long,
        videoGps: Pair<Double, Double>? = null,
        onStatus: (Stage, String) -> Unit = { _, _ -> },
        select: suspend (List<ActivityMatcher.Ranked>) -> ActivityCandidate?,
    ): File? {
        val label = "Garmin Connect"
        cache.find(videoKey)?.let {
            onStatus(Stage.CACHED, "$label: użyto zapisanej telemetrii — ${it.name}")
            return it
        }
        return try {
            onStatus(Stage.SEARCHING, "$label: szukanie aktywności pasującej do czasu filmu…")
            val windowMs = 2 * 3600_000L
            val candidates = provider.listActivities(videoStart - windowMs, videoEnd + windowMs)
            if (candidates.isEmpty()) {
                onStatus(Stage.NO_MATCH, "$label: nie znaleziono aktywności w czasie filmu (±2 godziny).")
                return null
            }
            val (decision, ranked) = ActivityMatcher.rank(candidates, videoStart, videoEnd, videoGps)
            val best = ranked.first()
            val chosen = when (decision) {
                ActivityMatcher.Decision.AUTO_ACCEPT -> best.candidate
                ActivityMatcher.Decision.NEEDS_SELECTION -> {
                    onStatus(Stage.SELECTION, "$label: wybierz pasującą aktywność.")
                    select(ranked) ?: run {
                        onStatus(Stage.CANCELLED, "$label: pominięto wybór aktywności.")
                        return null
                    }
                }
                ActivityMatcher.Decision.NO_MATCH -> {
                    onStatus(Stage.NO_MATCH, "$label: brak pasującej aktywności. Możesz wskazać plik FIT ręcznie.")
                    return null
                }
            }
            onStatus(Stage.DOWNLOADING, "$label: znaleziono aktywność; pobieranie telemetrii…")
            val fit = provider.downloadFit(chosen.activityId)
            val score = ranked.firstOrNull { it.candidate.activityId == chosen.activityId }?.score ?: best.score
            val file = cache.save(
                videoKey, "garmin", chosen.activityId, fit,
                mapOf(
                    "name" to chosen.name,
                    "sport_type" to chosen.sportType,
                    "duration_s" to chosen.durationS.toString(),
                    "match_score" to "%.2f".format(java.util.Locale.ROOT, score),
                    "start" to chosen.start.toString(),
                ),
            )
            onStatus(Stage.DOWNLOADED, "$label: pobrano ${file.name}; wczytywanie telemetrii…")
            file
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onStatus(
                Stage.ERROR,
                (e as? GarminAuthException)?.message
                    ?: "$label: nie udało się pobrać telemetrii. Sprawdź połączenie i logowanie w Ustawieniach.",
            )
            null
        }
    }
}

/** File-system cache: <dir>/<activityId>.fit plus an index of video -> activity. */
class FileRemoteTelemetryCache(private val dir: File) : RemoteTelemetryCache {
    private val index = File(dir, "video_index.properties")

    private fun load(): java.util.Properties = java.util.Properties().apply {
        if (index.isFile) index.inputStream().use { load(it) }
    }

    @Synchronized
    override fun find(videoKey: String): File? {
        val id = load().getProperty(videoKey) ?: return null
        return File(dir, "$id.fit").takeIf { it.isFile && it.length() > 12 }
    }

    @Synchronized
    override fun save(videoKey: String, provider: String, activityId: String, fit: ByteArray, meta: Map<String, String>): File {
        dir.mkdirs()
        val f = File(dir, "$activityId.fit")
        f.writeBytes(fit)
        val p = load()
        p.setProperty(videoKey, activityId)
        meta.forEach { (k, v) -> p.setProperty("$activityId.$k", v) }
        index.outputStream().use { p.store(it, "SportCamHUD remote telemetry cache") }
        return f
    }
}
