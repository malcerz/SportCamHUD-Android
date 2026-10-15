package com.sportcamhud.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Records why the app died so the next launch can show it (there is no logcat on the
 * user's phone). Covers three cases:
 *  - uncaught Java/Kotlin exceptions on any thread (written by the default handler),
 *  - native crashes, low-memory kills and ANRs (ApplicationExitInfo, Android 11+),
 *  - a render that never finished (the export step log has no end marker).
 */
object CrashLog {
    private const val TAG = "CrashLog"
    private const val END_MARK = "== koniec eksportu =="
    private lateinit var dir: File
    private val stamp get() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())

    private val crashFile get() = File(dir, "crash_last.txt")
    private val stepsFile get() = File(dir, "export_steps.txt")

    fun install(context: Context) {
        dir = context.filesDir
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                crashFile.writeText(
                    "Awaria aplikacji $stamp, wątek \"${thread.name}\"\n${deviceInfo(context)}\n\n$sw" + stepsSection(),
                )
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, e)
        }
    }

    // ---- export step log ---------------------------------------------------------
    fun exportStarted() {
        runCatching { stepsFile.writeText("") }
        step("start eksportu")
    }

    /** Appends one line immediately (survives a native crash a moment later). */
    fun step(msg: String) {
        Log.i(TAG, msg)
        runCatching { stepsFile.appendText("$stamp  $msg\n") }
    }

    fun exportFinished(result: String) {
        step(result)
        step(END_MARK)
    }

    private fun stepsSection(): String {
        val s = runCatching { stepsFile.readText() }.getOrDefault("")
        return if (s.isBlank()) "" else "\n\nKroki eksportu:\n${s.lines().takeLast(60).joinToString("\n")}"
    }

    // ---- report on the next launch -------------------------------------------------
    /** Report about the previous run, or null when it ended normally. Reading it consumes it. */
    fun takeReport(context: Context): String? {
        val parts = mutableListOf<String>()
        runCatching { crashFile.takeIf { it.exists() }?.readText() }.getOrNull()?.let { parts += it }
        exitInfo(context)?.let { parts += it }
        val steps = runCatching { stepsFile.readText() }.getOrDefault("")
        if (parts.isEmpty() && steps.isNotBlank() && !steps.contains(END_MARK)) {
            parts += "Aplikacja zamknęła się w trakcie eksportu.\n${deviceInfo(context)}" + stepsSection()
        } else if (parts.isNotEmpty() && steps.isNotBlank() && !steps.contains(END_MARK) && parts.none { "Kroki eksportu" in it }) {
            parts += stepsSection().trim()
        }
        crashFile.delete()
        if (steps.isNotBlank() && !steps.contains(END_MARK)) runCatching { stepsFile.appendText("$END_MARK (zgłoszone)\n") }
        return parts.takeIf { it.isNotEmpty() }?.joinToString("\n\n")
    }

    private fun exitInfo(context: Context): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return null
            val info = am.getHistoricalProcessExitReasons(context.packageName, 0, 1).firstOrNull() ?: return null
            val prefs = context.getSharedPreferences("crashlog", Context.MODE_PRIVATE)
            if (info.timestamp <= prefs.getLong("seen_exit", 0L)) return null
            prefs.edit().putLong("seen_exit", info.timestamp).apply()
            val reason = when (info.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "awaria natywna (sterownik / kodek / GPU)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "zabita przez system z braku pamięci"
                ApplicationExitInfo.REASON_ANR -> "aplikacja nie odpowiadała (ANR)"
                ApplicationExitInfo.REASON_CRASH -> "wyjątek Java/Kotlin"
                ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "nadmierne zużycie zasobów"
                ApplicationExitInfo.REASON_SIGNALED -> "zakończona sygnałem ${info.status}"
                else -> return null // normal exit, user swipe, update, …
            }
            val sb = StringBuilder()
            sb.append("Poprzednie zamknięcie: $reason\n")
            sb.append("Czas: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date(info.timestamp))}\n")
            info.description?.let { sb.append("Opis: $it\n") }
            sb.append("Pamięć: PSS ${info.pss / 1024} MB, RSS ${info.rss / 1024} MB\n")
            sb.append(deviceInfo(context))
            if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || info.reason == ApplicationExitInfo.REASON_ANR) {
                traceText(info)?.let { sb.append("\n\nŚlad:\n").append(it) }
            }
            sb.toString()
        } catch (e: Throwable) {
            Log.w(TAG, "exit info", e)
            null
        }
    }

    /** ANR traces are text; native tombstones are protobuf, so keep their readable strings (frames, libraries). */
    private fun traceText(info: ApplicationExitInfo): String? {
        val bytes = info.traceInputStream?.use { it.readBytes() } ?: return null
        if (info.reason == ApplicationExitInfo.REASON_ANR) return String(bytes).lines().take(80).joinToString("\n")
        val out = mutableListOf<String>()
        val cur = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 0x20..0x7E) cur.append(c.toChar()) else {
                if (cur.length >= 6) out += cur.toString()
                cur.setLength(0)
            }
        }
        if (cur.length >= 6) out += cur.toString()
        return out.distinct().take(120).joinToString("\n")
    }

    fun deviceInfo(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val rt = Runtime.getRuntime()
        return "Urządzenie: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.HARDWARE}), Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
            "aplikacja $version, sterta maks. ${rt.maxMemory() / 1048576} MB"
    }
}
