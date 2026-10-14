package com.sportcamhud.core.garmin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/** A remote activity candidate (port of `ActivityCandidate`). Times are epoch ms UTC. */
data class ActivityCandidate(
    val activityId: String,
    val provider: String,
    val name: String,
    val sportType: String,
    val start: Long,
    val end: Long,
    val durationS: Double,
    val distanceM: Double = 0.0,
    val startLat: Double? = null,
    val startLon: Double? = null,
)

/** Garmin Connect activity provider (port of `GarminProvider`). */
class GarminConnectProvider(private val auth: GarminAuthClient) {
    var lastError: String = ""
        private set

    fun connect(): Boolean = try {
        auth.validAccessToken()
        lastError = ""
        true
    } catch (e: GarminAuthException) {
        lastError = e.message ?: ""
        false
    }

    /** Validates the session; returns (ok, user name or error). */
    fun testConnection(): Pair<Boolean, String> = try {
        if (!connect()) {
            false to lastError
        } else {
            val profile = Json.parseToJsonElement(auth.apiGet("/userprofile-service/socialProfile").decodeToString())
            if (profile !is JsonObject) {
                false to "Garmin Connect: nieprawidłowa odpowiedź profilu."
            } else {
                val name = profile.str("fullName") ?: profile.str("displayName") ?: "Garmin Connect"
                true to name
            }
        }
    } catch (e: GarminAuthException) {
        false to (e.message ?: "")
    } catch (_: Exception) {
        false to "Garmin Connect: nieprawidłowa odpowiedź profilu."
    }

    /** Activities between [startMs] and [endMs] (dates, inclusive), at most 1000. */
    fun listActivities(startMs: Long, endMs: Long): List<ActivityCandidate> {
        if (!connect()) throw GarminAuthException(lastError)
        val fmt = DateTimeFormatter.ISO_LOCAL_DATE
        val startStr = fmt.format(OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(startMs), ZoneOffset.UTC))
        val endStr = fmt.format(OffsetDateTime.ofInstant(java.time.Instant.ofEpochMilli(endMs), ZoneOffset.UTC))

        val raw = ArrayList<JsonElement>()
        var complete = false
        for (offset in 0 until 1000 step 100) {
            val page = try {
                Json.parseToJsonElement(
                    auth.apiGet(
                        "/activitylist-service/activities/search/activities",
                        linkedMapOf("startDate" to startStr, "endDate" to endStr, "start" to "$offset", "limit" to "100"),
                    ).decodeToString(),
                )
            } catch (e: GarminAuthException) {
                throw e
            } catch (_: Exception) {
                throw GarminAuthException("Garmin Connect: nieprawidłowa lista aktywności.")
            }
            if (page !is JsonArray) throw GarminAuthException("Garmin Connect: nieprawidłowa lista aktywności.")
            raw.addAll(page)
            if (page.size < 100) { complete = true; break }
        }
        if (!complete) throw GarminAuthException("Garmin Connect: zbyt wiele aktywności w wybranym zakresie.")
        return raw.mapNotNull { parseActivity(it) }
    }

    /** Downloads the original FIT (unpacking Garmin's ZIP container). */
    fun downloadFit(activityId: String): ByteArray {
        if (activityId.isEmpty() || !activityId.all { it.isDigit() } || activityId.toBigInteger().signum() <= 0) {
            throw GarminAuthException("Nieprawidłowy identyfikator aktywności Garmin.")
        }
        if (!connect()) throw GarminAuthException(lastError)
        val raw = auth.apiGet("/download-service/files/activity/$activityId")
        if (raw.isEmpty()) throw GarminAuthException("Pusty plik pobrany z Garmin dla aktywności $activityId")
        val fit = if (raw.size >= 4 && raw[0] == 'P'.code.toByte() && raw[1] == 'K'.code.toByte() &&
            raw[2] == 3.toByte() && raw[3] == 4.toByte()
        ) {
            unzipFirstFit(raw) ?: throw GarminAuthException("W archiwum ZIP z Garmin nie znaleziono pliku .fit")
        } else {
            raw
        }
        if (fit.size < 12 || String(fit, 8, 4, Charsets.US_ASCII) != ".FIT") {
            throw GarminAuthException("Garmin Connect: pobrany plik nie jest plikiem FIT.")
        }
        return fit
    }

    private fun unzipFirstFit(zip: ByteArray): ByteArray? {
        ZipInputStream(ByteArrayInputStream(zip)).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: return null
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".fit")) return zin.readBytes()
            }
        }
    }

    companion object {
        private val GMT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        private fun JsonObject.str(key: String): String? =
            (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotBlank() }

        private fun JsonObject.num(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

        internal fun parseStartGmt(raw: String): Long? {
            val s = raw.trim()
            return try {
                LocalDateTime.parse(s, GMT_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
            } catch (_: Exception) {
                try {
                    OffsetDateTime.parse(s.replace("Z", "+00:00")).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    try { LocalDateTime.parse(s).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) { null }
                }
            }
        }

        internal fun parseActivity(item: JsonElement): ActivityCandidate? {
            val o = item as? JsonObject ?: return null
            val id = (o["activityId"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() } ?: return null
            val start = o.str("startTimeGMT")?.let { parseStartGmt(it) } ?: return null
            val duration = (o.num("duration")?.takeIf { it > 0 } ?: o.num("elapsedDuration")) ?: 0.0
            val sport = (o["activityType"] as? JsonObject)?.str("typeKey") ?: "cycling"
            return ActivityCandidate(
                activityId = id,
                provider = "garmin",
                name = o.str("activityName") ?: "Aktywność Garmin",
                sportType = sport,
                start = start,
                end = start + (duration * 1000).toLong(),
                durationS = duration,
                distanceM = o.num("distance") ?: 0.0,
                startLat = o.num("startLatitude"),
                startLon = o.num("startLongitude"),
            )
        }
    }
}
