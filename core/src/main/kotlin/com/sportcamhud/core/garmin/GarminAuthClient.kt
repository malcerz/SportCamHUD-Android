package com.sportcamhud.core.garmin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.Base64
import java.util.concurrent.TimeUnit

/** A safe, user-facing error; never includes response bodies or URLs. */
class GarminAuthException(message: String) : Exception(message)

/** DI OAuth2 session. Times are epoch seconds. */
data class GarminTokens(
    val clientId: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val refreshExpiresAt: Long?,
) {
    fun toJson(): String = buildJsonObject {
        put("client_id", JsonPrimitive(clientId))
        put("access_token", JsonPrimitive(accessToken))
        put("refresh_token", JsonPrimitive(refreshToken))
        put("expires_at", JsonPrimitive(expiresAt))
        put("refresh_expires_at", JsonPrimitive(refreshExpiresAt))
    }.toString()

    companion object {
        fun fromJson(text: String): GarminTokens? = try {
            val o = Json.parseToJsonElement(text).jsonObject
            GarminTokens(
                clientId = o["client_id"]!!.jsonPrimitive.content,
                accessToken = o["access_token"]!!.jsonPrimitive.content,
                refreshToken = o["refresh_token"]!!.jsonPrimitive.content,
                expiresAt = o["expires_at"]!!.jsonPrimitive.longOrNull!!,
                refreshExpiresAt = o["refresh_expires_at"]?.jsonPrimitive?.longOrNull,
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** Secure persistence of the DI tokens (Android Keystore on device). */
interface GarminTokenStore {
    fun load(): GarminTokens?
    fun save(tokens: GarminTokens): Boolean
    fun delete(): Boolean
}

class InMemoryTokenStore(private var tokens: GarminTokens? = null) : GarminTokenStore {
    override fun load() = tokens
    override fun save(tokens: GarminTokens): Boolean { this.tokens = tokens; return true }
    override fun delete(): Boolean { tokens = null; return true }
}

/**
 * Browser SSO -> DI OAuth2 client (port of `GarminAuthClient` from garmin_auth.py).
 *
 * Network methods block; call them from a background dispatcher.
 */
class GarminAuthClient(
    private val store: GarminTokenStore,
    private val http: OkHttpClient = defaultHttpClient(),
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    private val tokenUrl: HttpUrl = DI_TOKEN_URL.toHttpUrl(),
    private val apiBase: HttpUrl = API_BASE.toHttpUrl(),
) {
    companion object {
        const val DI_TOKEN_URL = "https://diauth.garmin.com/di-oauth2-service/oauth/token"
        const val DI_GRANT_TYPE = "https://connectapi.garmin.com/di-oauth2-service/oauth/grant/service_ticket"
        val DI_CLIENT_IDS = listOf(
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2025Q2",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI_2024Q4",
            "GARMIN_CONNECT_MOBILE_ANDROID_DI",
            "GARMIN_CONNECT_MOBILE_IOS_DI",
        )
        const val API_BASE = "https://connectapi.garmin.com"
        val NATIVE_HEADERS = linkedMapOf(
            "User-Agent" to "GCM-Android-5.23",
            "X-Garmin-User-Agent" to "com.garmin.android.apps.connectmobile/5.23; ; " +
                "Google/sdk_gphone64_arm64/google; Android/33; Dalvik/2.1.0",
            "X-Garmin-Paired-App-Version" to "10861",
            "X-Garmin-Client-Platform" to "Android",
            "X-App-Ver" to "10861",
            "X-Lang" to "pl",
            "X-GCExperience" to "GC5",
            "Accept-Language" to "pl-PL,pl;q=0.9,en;q=0.7",
            "Accept" to "application/json, text/plain, */*",
        )

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        const val MSG_SESSION_EXPIRED = "Sesja wygasła — połącz ponownie z Garmin Connect."
        const val MSG_NOT_CONNECTED = "Niepołączono z Garmin Connect — wybierz Połącz z Garmin Connect."
    }

    private val lock = Any()

    private fun execute(request: Request): Response = try {
        http.newCall(request).execute()
    } catch (_: InterruptedIOException) {
        throw GarminAuthException("Garmin Connect: przekroczono czas oczekiwania na serwis.")
    } catch (_: IOException) {
        throw GarminAuthException("Garmin Connect: brak połączenia lub serwis niedostępny.")
    }

    private fun tokenRequest(clientId: String, form: Map<String, String>): Response {
        val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
        val basic = Base64.getEncoder().encodeToString("$clientId:".toByteArray())
        val req = Request.Builder().url(tokenUrl).post(body).apply {
            NATIVE_HEADERS.forEach { (k, v) -> header(k, v) }
            header("Authorization", "Basic $basic")
        }.build()
        return execute(req)
    }

    private fun parseTokens(response: Response, clientId: String, previous: GarminTokens? = null): GarminTokens {
        try {
            val body = response.body?.string() ?: throw IllegalStateException()
            val o: JsonObject = Json.parseToJsonElement(body).jsonObject
            val access = o["access_token"]?.jsonPrimitive?.contentOrNull
            val refresh = o["refresh_token"]?.jsonPrimitive?.contentOrNull ?: previous?.refreshToken
            if (access.isNullOrEmpty() || refresh.isNullOrEmpty()) throw IllegalStateException()
            val now = clock()
            val expiresIn = o["expires_in"]?.jsonPrimitive?.longOrNull ?: 3600L
            val refreshIn = o["refresh_token_expires_in"]?.jsonPrimitive?.longOrNull ?: 0L
            return GarminTokens(
                clientId = clientId,
                accessToken = access,
                refreshToken = refresh,
                expiresAt = now + maxOf(60L, expiresIn),
                refreshExpiresAt = if (refreshIn > 0) now + refreshIn else previous?.refreshExpiresAt,
            )
        } catch (_: Exception) {
            throw GarminAuthException("Garmin Connect: nieprawidłowa odpowiedź wymiany tokenów.")
        } finally {
            response.close()
        }
    }

    /** Exchanges the one-time SSO service ticket for DI OAuth2 tokens and stores them. */
    fun exchangeServiceTicket(ticket: String, cancelled: () -> Boolean = { false }) {
        if (!GarminSso.isValidTicket(ticket)) {
            throw GarminAuthException("Garmin Connect: nieprawidłowe potwierdzenie logowania.")
        }
        synchronized(lock) {
            for (clientId in DI_CLIENT_IDS) {
                if (cancelled()) throw GarminAuthException("Anulowano logowanie Garmin Connect.")
                val response = tokenRequest(
                    clientId,
                    mapOf(
                        "client_id" to clientId,
                        "service_ticket" to ticket,
                        "grant_type" to DI_GRANT_TYPE,
                        "service_url" to GarminSso.SSO_LOGIN_BASE,
                    ),
                )
                if (response.isSuccessful) {
                    val tokens = parseTokens(response, clientId)
                    if (cancelled()) throw GarminAuthException("Anulowano logowanie Garmin Connect.")
                    if (!store.save(tokens)) {
                        throw GarminAuthException("Nie udało się zapisać sesji w magazynie kluczy Androida.")
                    }
                    return
                }
                response.close()
            }
            throw GarminAuthException("Garmin Connect: wymiana ticketu na tokeny nie powiodła się. Połącz ponownie.")
        }
    }

    fun isConnected(): Boolean = store.load() != null

    /** Returns a valid access token, refreshing it when it is about to expire. */
    fun validAccessToken(forceRefresh: Boolean = false): String = synchronized(lock) {
        val current = store.load() ?: throw GarminAuthException(MSG_NOT_CONNECTED)
        val now = clock()
        if (!forceRefresh && current.expiresAt > now + 90) return current.accessToken
        val refreshExpiry = current.refreshExpiresAt
        if (refreshExpiry != null && refreshExpiry <= now + 30) {
            store.delete()
            throw GarminAuthException(MSG_SESSION_EXPIRED)
        }
        val response = tokenRequest(
            current.clientId,
            mapOf(
                "grant_type" to "refresh_token",
                "client_id" to current.clientId,
                "refresh_token" to current.refreshToken,
            ),
        )
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            if (code == 400 || code == 401) {
                store.delete()
                throw GarminAuthException(MSG_SESSION_EXPIRED)
            }
            throw GarminAuthException("Garmin Connect: nie udało się odświeżyć sesji.")
        }
        val tokens = parseTokens(response, current.clientId, current)
        if (!store.save(tokens)) {
            throw GarminAuthException("Nie udało się zapisać odświeżonej sesji w magazynie kluczy Androida.")
        }
        tokens.accessToken
    }

    /** Authenticated GET against connectapi.garmin.com; retries once with a refreshed token on 401. */
    fun apiGet(path: String, params: Map<String, String> = emptyMap()): ByteArray {
        if (!path.startsWith("/") || path.startsWith("//")) {
            throw GarminAuthException("Nieprawidłowy endpoint Garmin Connect.")
        }
        val url = apiBase.newBuilder().encodedPath(path).apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        for (attempt in 0..1) {
            val req = Request.Builder().url(url).get().apply {
                NATIVE_HEADERS.forEach { (k, v) -> header(k, v) }
                header("Authorization", "Bearer " + validAccessToken(forceRefresh = attempt == 1))
            }.build()
            execute(req).use { response ->
                if (response.code == 401 && attempt == 0) return@use
                if (response.code == 401) throw GarminAuthException(MSG_SESSION_EXPIRED)
                if (!response.isSuccessful) {
                    throw GarminAuthException("Garmin Connect: serwis zwrócił błąd HTTP ${response.code}.")
                }
                return response.body?.bytes() ?: ByteArray(0)
            }
        }
        throw GarminAuthException(MSG_SESSION_EXPIRED)
    }

    fun logout() = synchronized(lock) {
        if (!store.delete()) throw GarminAuthException("Nie udało się usunąć sesji z magazynu kluczy Androida.")
    }
}
