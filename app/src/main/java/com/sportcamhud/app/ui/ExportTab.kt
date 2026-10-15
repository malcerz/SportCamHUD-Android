package com.sportcamhud.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sportcamhud.app.HudViewModel

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
        Text(
            "Wynik: ${v.displayWidth}×${v.displayHeight}, z dźwiękiem, zapis do Filmy/SportCamHUD.",
            style = MaterialTheme.typography.bodySmall,
        )

        if (vm.exporting) {
            LinearProgressIndicator(progress = { vm.exportProgress / 100f }, modifier = Modifier.fillMaxWidth())
            Text("Renderowanie: ${vm.exportProgress}%")
            OutlinedButton(onClick = vm::cancelExport) { Text("Przerwij") }
        } else {
            Button(onClick = {
                val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
                if (needsPermission) askStorage.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) else vm.startExport()
            }) { Text("Renderuj film z nakładką") }
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
