package com.sportcamhud.core

import com.sportcamhud.core.garmin.GarminAuthClient
import com.sportcamhud.core.garmin.GarminAuthException
import com.sportcamhud.core.garmin.GarminConnectProvider
import com.sportcamhud.core.garmin.GarminSso
import com.sportcamhud.core.garmin.GarminTokens
import com.sportcamhud.core.garmin.InMemoryTokenStore
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class GarminClientTest {
    private lateinit var server: MockWebServer
    private val store = InMemoryTokenStore()
    private var now = 1_800_000_000L
    private val requests = mutableListOf<RecordedRequest>()
    private var fitBytes = ByteArray(0)
    private var zipped = false
    private var expireNextApiCall = false

    @TempDir
    lateinit var dir: File

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val path = request.path ?: ""
                return when {
                    path.startsWith("/token") -> {
                        val body = request.body.readUtf8()
                        val auth = request.getHeader("Authorization") ?: ""
                        // Only the third client id is "accepted", to exercise the fallback loop.
                        val accepted = auth == "Basic " + java.util.Base64.getEncoder()
                            .encodeToString("GARMIN_CONNECT_MOBILE_ANDROID_DI:".toByteArray())
                        when {
                            "service_ticket=ST-" in body && accepted -> MockResponse().setBody(
                                """{"access_token":"A1","refresh_token":"R1","expires_in":3600,"refresh_token_expires_in":7776000}""",
                            )
                            "grant_type=refresh_token" in body -> MockResponse().setBody("""{"access_token":"A2","expires_in":3600}""")
                            else -> MockResponse().setResponseCode(400)
                        }
                    }
                    path.startsWith("/userprofile-service/socialProfile") -> authed(request) { MockResponse().setBody("""{"fullName":"Jan Kowalski"}""") }
                    path.startsWith("/activitylist-service") -> authed(request) {
                        MockResponse().setBody(
                            """[{"activityId":111,"activityName":"Poranna jazda","activityType":{"typeKey":"road_biking"},
                               "startTimeGMT":"2026-10-02 06:26:47","duration":1200.0,"distance":9000.0,
                               "startLatitude":50.06,"startLongitude":19.93},
                               {"activityName":"no id"}]""",
                        )
                    }
                    path.startsWith("/download-service/files/activity/111") -> authed(request) {
                        MockResponse().setBody(Buffer().write(if (zipped) zip(fitBytes) else fitBytes))
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    private fun authed(r: RecordedRequest, ok: () -> MockResponse): MockResponse {
        if (expireNextApiCall) { expireNextApiCall = false; return MockResponse().setResponseCode(401) }
        return if (r.getHeader("Authorization")?.startsWith("Bearer A") == true) ok() else MockResponse().setResponseCode(401)
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    private fun client() = GarminAuthClient(
        store, clock = { now },
        tokenUrl = server.url("/token"), apiBase = server.url("/"),
    )

    private fun zip(data: ByteArray): ByteArray {
        val bos = ByteArrayOutputStream()
        ZipOutputStream(bos).use { z -> z.putNextEntry(ZipEntry("111_ACTIVITY.fit")); z.write(data); z.closeEntry() }
        return bos.toByteArray()
    }

    @Test
    fun `ticket capture helpers`() {
        assertTrue(GarminSso.isGarminUrl("https://sso.garmin.com/sso/embed?ticket=ST-123-abc"))
        assertFalse(GarminSso.isGarminUrl("https://garmin.com.evil.example/"))
        assertFalse(GarminSso.isGarminUrl("http://sso.garmin.com/"))
        assertEquals("ST-123-abc-cas", GarminSso.extractServiceTicket("https://sso.garmin.com/sso/embed?ticket=ST-123-abc-cas"))
        assertEquals("ST-9", GarminSso.extractServiceTicket("x%3DST-9%26"))
        assertNull(GarminSso.extractServiceTicket("no ticket"))
    }

    @Test
    fun `exchange ticket, list activities and download zipped fit`() {
        val c = client()
        assertThrows<GarminAuthException> { c.exchangeServiceTicket("not-a-ticket") }
        c.exchangeServiceTicket("ST-1234-abc")
        assertEquals("GARMIN_CONNECT_MOBILE_ANDROID_DI", store.load()!!.clientId)
        assertEquals(now + 3600, store.load()!!.expiresAt)

        val p = GarminConnectProvider(c)
        assertEquals(true to "Jan Kowalski", p.testConnection())

        val acts = p.listActivities(1_790_000_000_000L, 1_790_100_000_000L)
        assertEquals(1, acts.size)
        assertEquals("111", acts[0].activityId)
        assertEquals("road_biking", acts[0].sportType)
        assertEquals(java.time.Instant.parse("2026-10-02T06:26:47Z").toEpochMilli(), acts[0].start)
        assertEquals(acts[0].start + 1_200_000, acts[0].end)
        val listReq = requests.last { it.path!!.startsWith("/activitylist") }
        assertTrue(listReq.path!!.contains("limit=100"))
        assertEquals("GCM-Android-5.23", listReq.getHeader("User-Agent"))

        fitBytes = SampleFit.write(File(dir, "a.fit")).readBytes()
        zipped = true
        assertTrue(p.downloadFit("111").contentEquals(fitBytes))
        assertThrows<GarminAuthException> { p.downloadFit("../1") }
    }

    @Test
    fun `refreshes expired token and retries once on 401`() {
        store.save(GarminTokens("GARMIN_CONNECT_MOBILE_ANDROID_DI", "A1", "R1", now - 10, now + 1000))
        val c = client()
        assertEquals("A2", c.validAccessToken())
        assertEquals("R1", store.load()!!.refreshToken)

        expireNextApiCall = true
        val p = GarminConnectProvider(c)
        assertEquals(true to "Jan Kowalski", p.testConnection())
    }

    @Test
    fun `expired refresh token clears the session`() {
        store.save(GarminTokens("X", "A1", "R1", now - 10, now + 5))
        val e = assertThrows<GarminAuthException> { client().validAccessToken() }
        assertEquals(GarminAuthClient.MSG_SESSION_EXPIRED, e.message)
        assertNull(store.load())
    }
}
