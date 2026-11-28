package com.sportcamhud.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sportcamhud.app.HudViewModel
import com.sportcamhud.app.export.DeviceVideoCodecs
import com.sportcamhud.app.export.VideoCodec
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ExportTab(vm: HudViewModel) {
    val context = LocalContext.current
    val v = vm.video
    val askStorage = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.startExport() else vm.status = "Bez zgody na zapis nie można zapisać filmu w galerii."
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (v == null) {
            Text("Najpierw wybierz film w zakładce Projekt.")
            return@Column
        }
        Text(vm.cameraStatus, style = MaterialTheme.typography.bodySmall)
        if (vm.dataset == null && vm.camera == null) {
            Text(
                "Brak telemetrii — film zostanie wyrenderowany tylko z datą i godziną. Wczytaj plik FIT lub pobierz go z Garmin Connect.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Text("Fragment filmu: ${formatMs(vm.clipStartMs)} – ${formatMs(vm.clipEndMs)}")
        RangeSlider(
            value = vm.clipStartMs.toFloat()..vm.clipEndMs.toFloat().coerceAtLeast(vm.clipStartMs.toFloat()),
            onValueChange = { r ->
                vm.clipStartMs = r.start.toLong()
                vm.clipEndMs = r.endInclusive.toLong()
            },
            valueRange = 0f..v.durationMs.toFloat().coerceAtLeast(1f),
            enabled = !vm.exporting,
        )
        Text("Rozdzielczość filmu wynikowego")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Oryginalna", 1080 to "1080p", 720 to "720p").forEach { (px, label) ->
                FilterChip(
                    selected = vm.exportShortSide == px,
                    onClick = { vm.changeExportShortSide(px) },
                    label = { Text(label) },
                    enabled = !vm.exporting,
                )
            }
        }
        val (outW, outH) = vm.exportSize(v)
        val encoders = remember(outW, outH) { DeviceVideoCodecs.availability(outW, outH) }
        val selected = encoders.first { it.codec == vm.selectedCodec }
        Text("Kodek wideo (MP4)")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (cap in encoders) {
                FilterChip(
                    selected = vm.selectedCodec == cap.codec,
                    onClick = { vm.changeVideoCodec(cap.codec) },
                    label = {
                        Text(if (cap.enabled) cap.codec.label
                            else "${cap.codec.label} (brak HW)")
                    },
                    enabled = !vm.exporting && cap.enabled,
                )
            }
        }
        Text(
            if (selected.hardwareEncoder != null)
                "Koder sprzętowy: ${selected.hardwareEncoder}" +
                    (selected.estimatedMaxFps?.let {
                        " · API zgłasza do ${"%.0f".format(Locale.getDefault(), it)} FPS dla tej rozdzielczości (orientacyjnie)"
                    } ?: "")
            else if (vm.selectedCodec == VideoCodec.AVC)
                "H.264: zgodność zachowana; API nie wykryło sprzętowego kodera Surface w tym rozmiarze."
            else "Ten kodek nie ma dostępnego sprzętowego enkodera Surface w tej rozdzielczości.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "Wynik: ${outW}×${outH}, z dźwiękiem, zapis do Filmy/SportCamHUD." +
                if (minOf(v.displayWidth, v.displayHeight) > 1080 && vm.exportShortSide == 0) {
                    " Eksport w 4K jest wolny i nie każdy telefon go udźwignie."
                } else "",
            style = MaterialTheme.typography.bodySmall,
        )

        if (vm.exporting) {
            LinearProgressIndicator(progress = { vm.exportProgress / 100f }, modifier = Modifier.fillMaxWidth())
            Text(
                if (vm.benchmarkRunning)
                    "Benchmark: ${vm.benchmarkPhase} — ${vm.exportProgress}%"
                else "Renderowanie: ${vm.exportProgress}%"
            )
            val stats = vm.exportStats
            Text("FPS: ${"%.1f".format(Locale.getDefault(), stats.fps)}  " +
                "(średnio ${"%.1f".format(Locale.getDefault(), stats.averageFps)})")
            Text("Czas do końca: ${stats.remainingSeconds?.let(::formatExportSeconds) ?: "obliczanie…"}")
            Text("Upłynęło: ${formatExportSeconds(stats.elapsedSeconds)}, klatek: ${stats.frames}",
                style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = vm::cancelExport) {
                Text(if (vm.benchmarkRunning) "Przerwij benchmark" else "Przerwij")
            }
        } else {
            Button(onClick = {
                val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                if (needsPermission) askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.startExport()
            }, enabled = selected.enabled) { Text("Renderuj film z nakładką") }
            OutlinedButton(onClick = vm::startBenchmark, enabled = selected.enabled) {
                Text("Benchmark: 3 × 12 s")
            }
            Text(
                "Trzy identyczne fragmenty: sam kodek (bez HUD), pusta nakładka GL, " +
                    "pełny HUD. Wyniki w FPS i sekundach. Bez zapisywania filmów w galerii; " +
                    "w benchmarku audio jest wyłączone. Etap niewykonujący faktycznego transkodowania będzie oznaczony jako nieważny.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (vm.benchmarkResults.isNotEmpty()) {
            Text("Wyniki benchmarku — ${vm.selectedCodec.label}, ${outW}×${outH}",
                style = MaterialTheme.typography.titleMedium)
            for (result in vm.benchmarkResults) {
                Text(result.mode.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    if (result.valid)
                        "${"%.1f".format(Locale.getDefault(), result.fps)} FPS · " +
                            "${"%.2f".format(Locale.getDefault(), result.elapsedMs / 1000.0)} s · " +
                            "${result.frames} klatek"
                    else "Nieudany / niemiarodajny: ${result.detail}",
                )
                if (result.valid) Text(result.detail, style = MaterialTheme.typography.bodySmall)
            }
        }

        if (!vm.exporting && !vm.benchmarkRunning && vm.exportResult != null && vm.exportStats.frames > 0) {
            Text("Ostatni eksport: ${vm.exportStats.frames} klatek HUD, " +
                "średnio ${"%.1f".format(Locale.getDefault(), vm.exportStats.averageFps)} FPS",
                style = MaterialTheme.typography.bodySmall)
        }

        vm.exportResult?.let { uri ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
                    )
                }) { Text("Otwórz") }
                OutlinedButton(onClick = {
                    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    context.startActivity(Intent.createChooser(send, "Udostępnij film"))
                }) { Text("Udostępnij") }
            }
        }
    }
}

/** Only an estimate: based on Media3 total export progress, not the HUD frame count. */
private fun formatExportSeconds(seconds: Long): String {
    val s = seconds.coerceAtLeast(0L)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60)
        else "%02d:%02d".format(s / 60, s % 60)
}
