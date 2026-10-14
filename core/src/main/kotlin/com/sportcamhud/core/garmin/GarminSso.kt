package com.sportcamhud.core.garmin

import java.net.URI
import java.net.URLDecoder

/**
 * Garmin SSO browser-login constants and service-ticket capture
 * (ported from `garmin_auth.py` / `gui/qt/garmin_login.py`).
 *
 * Only the one-time service ticket (ST-...) ever leaves the login page;
 * the Garmin password stays with Garmin.
 */
object GarminSso {
    const val SSO_LOGIN_BASE = "https://sso.garmin.com/sso/embed"
    const val SSO_LOGIN_URL = SSO_LOGIN_BASE + "?id=gauth-widget&embedWidget=true" +
        "&gauthHost=https%3A%2F%2Fsso.garmin.com%2Fsso" +
        "&clientId=GarminConnect&locale=en_US" +
        "&redirectAfterAccountLoginUrl=https%3A%2F%2Fsso.garmin.com%2Fsso%2Fembed" +
        "&service=https%3A%2F%2Fsso.garmin.com%2Fsso%2Fembed"

    private val TICKET = Regex("""\bST-[A-Za-z0-9-]+""")
    private val TICKET_FULL = Regex("""ST-[A-Za-z0-9-]+""")

    /** Injected at document start: listens for the SSO postMessage that carries the ticket. */
    const val CAPTURE_SCRIPT = """(function () {
    if (location.protocol !== 'https:' ||
        !(location.hostname === 'garmin.com' || location.hostname.endsWith('.garmin.com'))) return;
    window.__hudGarminTicket = window.__hudGarminTicket || '';
    window.addEventListener('message', function (ev) {
        try {
            var origin = new URL(ev.origin);
            if (origin.protocol !== 'https:' ||
                !(origin.hostname === 'garmin.com' || origin.hostname.endsWith('.garmin.com'))) return;
            var value = typeof ev.data === 'string' ? ev.data : JSON.stringify(ev.data);
            var match = (value || '').match(/\bST-[A-Za-z0-9-]+/);
            if (match) window.__hudGarminTicket = match[0];
        } catch (e) {}
    }, false);
})();"""

    /** Polled every 500 ms; returns only the ticket (never page HTML). */
    const val SCAN_SCRIPT = """(function () {
    if (window.__hudGarminTicket) return window.__hudGarminTicket;
    var h = document.documentElement ? document.documentElement.outerHTML : '';
    var m = h.match(/ticket=(ST-[A-Za-z0-9-]+)/);
    if (m) return m[1];
    var t = document.body ? document.body.innerText : '';
    m = t.match(/\bST-[A-Za-z0-9-]+/);
    return m ? m[0] : '';
})();"""

    fun isGarminUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val u = URI(url)
            val host = u.host ?: return false
            u.scheme == "https" && (host == "garmin.com" || host.endsWith(".garmin.com"))
        } catch (_: Exception) {
            false
        }
    }

    fun extractServiceTicket(text: String?): String? {
        if (text.isNullOrEmpty()) return null
        val decoded = try { URLDecoder.decode(text, "UTF-8") } catch (_: Exception) { text }
        return TICKET.find(decoded)?.value
    }

    fun isValidTicket(ticket: String?): Boolean = ticket != null && TICKET_FULL.matches(ticket)
}
