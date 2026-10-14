package com.sportcamhud.app.garmin

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.sportcamhud.core.garmin.GarminAuthClient
import com.sportcamhud.core.garmin.GarminAuthException
import com.sportcamhud.core.garmin.GarminConnectProvider
import com.sportcamhud.core.garmin.GarminSso
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Garmin SSO login in a WebView (port of `gui/qt/garmin_login.py`).
 *
 * The page runs on Garmin's own domain; the app only captures the one-time service
 * ticket (ST-...) and exchanges it for DI OAuth2 tokens. Cookies are wiped before and
 * after, so no Garmin login session or password is kept on the device.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GarminLoginScreen(
    auth: GarminAuthClient,
    onFinished: (ok: Boolean, message: String) -> Unit,
) {
    var phase by remember { mutableStateOf("Zaloguj się na stronie Garmin. Jeśli Garmin poprosi o kod MFA, potwierdź go na tej stronie. Hasło pozostaje u Garmina.") }
    var exchanging by remember { mutableStateOf(false) }
    val handled = remember { AtomicBoolean(false) }
    val cancelled = remember { AtomicBoolean(false) }
    val scope = rememberCoroutineScope()
    var webView by remember { mutableStateOf<WebView?>(null) }

    fun finish(ok: Boolean, msg: String) {
        cancelled.set(true)
        webView?.let {
            it.stopLoading()
            it.loadUrl("about:blank")
        }
        CookieManager.getInstance().removeAllCookies(null)
        onFinished(ok, msg)
    }

    fun accept(text: String?) {
        val ticket = GarminSso.extractServiceTicket(text) ?: return
        if (!handled.compareAndSet(false, true)) return
        exchanging = true
        phase = "Logowanie potwierdzone. Pobieranie tokenów i sprawdzanie połączenia…"
        scope.launch {
            val (ok, msg) = withContext(Dispatchers.IO) {
                try {
                    auth.exchangeServiceTicket(ticket) { cancelled.get() }
                    GarminConnectProvider(auth).testConnection()
                } catch (e: GarminAuthException) {
                    false to (e.message ?: "Garmin Connect: autoryzacja nie powiodła się.")
                } catch (_: Exception) {
                    false to "Garmin Connect: autoryzacja nie powiodła się. Połącz ponownie."
                }
            }
            if (!cancelled.get()) finish(ok, msg)
        }
    }

    // Poll the page for the ticket every 500 ms (as the Qt dialog does) and time out after 5 minutes.
    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        val deadline = System.currentTimeMillis() + 300_000
        while (!handled.get() && !cancelled.get()) {
            if (System.currentTimeMillis() > deadline) {
                finish(false, "Garmin Connect: przekroczono czas oczekiwania na potwierdzenie logowania.")
                break
            }
            if (GarminSso.isGarminUrl(wv.url)) {
                wv.evaluateJavascript(GarminSso.SCAN_SCRIPT) { result -> accept(result) }
            }
            delay(500)
        }
    }

    DisposableEffect(Unit) {
        CookieManager.getInstance().removeAllCookies(null)
        onDispose {
            cancelled.set(true)
            CookieManager.getInstance().removeAllCookies(null)
            webView?.destroy()
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(phase, Modifier.padding(16.dp))
        if (exchanging) {
            CircularProgressIndicator(Modifier.padding(24.dp).align(Alignment.CenterHorizontally))
        } else {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.cacheMode = WebSettings.LOAD_NO_CACHE
                        CookieManager.getInstance().setAcceptCookie(true)
                        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                            WebViewCompat.addDocumentStartJavaScript(
                                this, GarminSso.CAPTURE_SCRIPT, setOf("https://garmin.com", "https://*.garmin.com"),
                            )
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val url = request.url.toString()
                                if (GarminSso.isGarminUrl(url)) accept(url)
                                return false
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                if (GarminSso.isGarminUrl(url)) accept(url)
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                if (!GarminSso.isGarminUrl(url)) return
                                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                                    view.evaluateJavascript(GarminSso.CAPTURE_SCRIPT, null)
                                }
                                view.evaluateJavascript(GarminSso.SCAN_SCRIPT) { accept(it) }
                            }
                        }
                        loadUrl(GarminSso.SSO_LOGIN_URL)
                        webView = this
                    }
                },
            )
        }
        TextButton(onClick = { finish(false, "Anulowano logowanie Garmin Connect.") }, Modifier.align(Alignment.End).padding(8.dp)) {
            Text("Anuluj")
        }
    }
}
