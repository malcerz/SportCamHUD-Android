package com.sportcamhud.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sportcamhud.app.HudViewModel
import com.sportcamhud.core.telemetry.TimeSync
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProjectTab(vm: HudViewModel) {
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openVideo) }
    val pickFit = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::openFit) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { pickVideo.launch(arrayOf("video/*")) }) { Text("Wybierz film") }
            OutlinedButton(onClick = { pickFit.launch(arrayOf("*/*")) }) { Text("Wczytaj plik FIT") }
            OutlinedButton(onClick = vm::fetchFromGarmin, enabled = vm.video != null && vm.garminConnected && !vm.busy) {
                Text("Pobierz z Garmin Connect")
            }
        }
        if (!vm.garminConnected) {
            Text(
                "Aby pobierać aktywności automatycznie, połącz konto w zakładce Ustawienia.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        vm.video?.let { v ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Film", style = MaterialTheme.typography.titleMedium)
                    Text(v.name)
                    Text("${v.displayWidth}×${v.displayHeight}, ${formatMs(v.durationMs)}")
                    Text(
                        "Data nagrania: " + (v.creationTime?.let { DATE_TIME.format(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC)) }
                            ?: "brak w pliku"),
                    )
                    vm.camera?.let { cam ->
                        val parts = buildList {
                            if (cam.hasGps) add("GPS")
                            if (cam.iso != null) add("ISO")
                            if (cam.exposure != null) add("migawka")
                            if (cam.cameraTemp != null) add("temperatura")
                            if (cam.hasImu) add("przechył (IMU)")
                        }
                        Text("Telemetria GoPro${cam.deviceName?.let { " ($it)" } ?: ""}: ${parts.joinToString(", ")}")
                        cam.utcStartMs?.let { Text("Start wg GPS kamery (UTC): " + DATE_TIME.format(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC))) }
                    }
                }
            }
        }

        vm.fitSummary?.let { s ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("Telemetria", style = MaterialTheme.typography.titleMedium)
                    Text(vm.telemetryName ?: "")
                    Text("Sport: ${s.sport ?: "—"}, rekordów: ${s.recordCount}, GPS: ${if (s.hasGps) "tak" else "nie"}")
                    Text("Start (UTC): " + DATE_TIME.format(Instant.ofEpochMilli(s.start).atOffset(ZoneOffset.UTC)))
                    s.totalDistanceM?.let { Text("Dystans: %.2f km".format(it / 1000)) }
                    Text(
                        "Dane: " + s.availableFields.sorted().joinToString(", ") { vm.dataset?.info(it)?.label ?: it },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = vm::clearTelemetry, modifier = Modifier.padding(top = 8.dp)) { Text("Usuń telemetrię") }
                }
            }
            SyncCard(vm)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SyncCard(vm: HudViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("Synchronizacja czasu", style = MaterialTheme.typography.titleMedium)
            val sync = vm.autoSync
            Text(
                when (sync?.method) {
                    TimeSync.Method.GPS_TRACK -> "Dopasowano ślad GPS z FIT do GPS kamery GoPro."
                    TimeSync.Method.ABSOLUTE_TIME -> "Czas filmu mieści się w aktywności — bez przesunięcia."
                    TimeSync.Method.TIMEZONE_FALLBACK -> "Wykryto różnicę strefy czasowej kamery: ${sync.timezoneHours} h."
                    TimeSync.Method.ALIGN_START -> "Zegary kamery i licznika się nie zgadzają — wyrównano początek aktywności do początku filmu."
                    TimeSync.Method.NO_VIDEO_START, null -> "Film nie ma daty nagrania — telemetria zaczyna się razem z filmem."
                },
                style = MaterialTheme.typography.bodySmall,
            )
            Text("Korekta ręczna: %+.1f s".format(vm.manualOffsetMs / 1000.0), Modifier.padding(top = 8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                for (step in listOf(-10_000L, -1_000L, -500L, 500L, 1_000L, 10_000L)) {
                    OutlinedButton(onClick = { vm.setManualOffset(vm.manualOffsetMs + step) }) {
                        Text("%+.1f s".format(step / 1000.0))
                    }
                }
                OutlinedButton(onClick = { vm.setManualOffset(0) }) { Text("Zeruj") }
            }
            Text(
                "Plus opóźnia wskaźniki względem obrazu, minus je przyspiesza.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
