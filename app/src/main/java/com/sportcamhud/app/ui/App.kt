package com.sportcamhud.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.sportcamhud.app.HudViewModel
import com.sportcamhud.app.garmin.GarminLoginScreen
import com.sportcamhud.core.garmin.ActivityMatcher
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HudColors = darkColorScheme(
    primary = Color(0xFF159FA5),
    secondary = Color(0xFFDC3232),
    background = Color(0xFF101418),
    surface = Color(0xFF161C22),
)

@Composable
fun SportCamHudApp(vm: HudViewModel) {
    MaterialTheme(colorScheme = HudColors) {
        var tab by rememberSaveable { mutableIntStateOf(0) }
        var showGarminLogin by rememberSaveable { mutableStateOf(false) }

        if (showGarminLogin) {
            Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
                GarminLoginScreen(vm.garminAuth) { ok, msg ->
                    showGarminLogin = false
                    vm.onGarminLoginFinished(ok, msg)
                }
            }
            return@MaterialTheme
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    listOf("🎬" to "Projekt", "📊" to "Wskaźniki", "💾" to "Eksport", "⚙️" to "Ustawienia").forEachIndexed { i, (icon, label) ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Text(icon) },
                            label = { Text(label) },
                        )
                    }
                }
            },
        ) { padding ->
            Column(Modifier.padding(padding).fillMaxSize()) {
                PreviewPane(vm)
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                StatusBar(vm)
                HorizontalDivider()
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    when (tab) {
                        0 -> ProjectTab(vm)
                        1 -> IndicatorsTab(vm)
                        2 -> ExportTab(vm)
                        else -> SettingsTab(vm, onConnectGarmin = { showGarminLogin = true })
                    }
                }
            }
        }

        vm.activityChoices?.let { ActivityChoiceDialog(it, onChoose = vm::chooseActivity) }
        vm.crashReport?.let { CrashReportDialog(it, onClose = { vm.crashReport = null }) }
    }
}

@Composable
private fun StatusBar(vm: HudViewModel) {
    val msg = vm.status ?: return
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { vm.status = null }) { Text("OK") }
    }
}

/** Shown when several Garmin activities match the video (NEEDS_SELECTION). */
@Composable
fun ActivityChoiceDialog(ranked: List<ActivityMatcher.Ranked>, onChoose: (com.sportcamhud.core.garmin.ActivityCandidate?) -> Unit) {
    val fmt = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault())
    AlertDialog(
        onDismissRequest = { onChoose(null) },
        title = { Text("Wybierz aktywność Garmin") },
        text = {
            LazyColumn {
                items(ranked) { r ->
                    val c = r.candidate
                    val mins = (c.durationS / 60).toInt()
                    val secs = (c.durationS % 60).toInt()
                    TextButton(onClick = { onChoose(c) }, modifier = Modifier.fillMaxWidth()) {
                        Text(
                            "${fmt.format(Instant.ofEpochMilli(c.start))} ${c.sportType} (${c.name}) — " +
                                "%02d:%02d — %d%%".format(mins, secs, (r.score * 100).toInt()),
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { onChoose(null) }) { Text("Pomiń") } },
    )
}

/** Shown once after the app died: the saved cause, to copy or share with the developer. */
@Composable
fun CrashReportDialog(report: String, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Aplikacja została ostatnio zamknięta z błędem") },
        text = {
            Column {
                Text("Wyślij ten raport, żeby można było znaleźć przyczynę.", style = MaterialTheme.typography.bodySmall)
                Text(
                    report,
                    Modifier.padding(top = 8.dp).heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, "SportCamHUD: raport błędu")
                    .putExtra(Intent.EXTRA_TEXT, report)
                context.startActivity(Intent.createChooser(send, "Wyślij raport"))
            }) { Text("Udostępnij") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("Kopiuj") }
                TextButton(onClick = onClose) { Text("Zamknij") }
            }
        },
    )
}
