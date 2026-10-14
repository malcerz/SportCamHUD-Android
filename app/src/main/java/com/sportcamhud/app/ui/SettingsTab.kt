package com.sportcamhud.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.sportcamhud.app.HudViewModel

@Composable
fun SettingsTab(vm: HudViewModel, onConnectGarmin: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Garmin Connect", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (vm.garminConnected) "Połączono" + (vm.garminUser?.let { " jako $it" } ?: "") + "."
                    else "Nie połączono. Logowanie odbywa się na stronie Garmin; aplikacja zapisuje tylko zaszyfrowane tokeny sesji, nigdy hasła.",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (vm.garminConnected) {
                        OutlinedButton(onClick = vm::testGarmin) { Text("Sprawdź połączenie") }
                        OutlinedButton(onClick = vm::garminLogout) { Text("Wyloguj") }
                    } else {
                        Button(onClick = onConnectGarmin) { Text("Połącz z Garmin Connect") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Automatycznie szukaj aktywności po wybraniu filmu", Modifier.weight(1f))
                    Switch(checked = vm.autoGarmin, onCheckedChange = vm::changeAutoGarmin)
                }
            }
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp)) {
                Text("Wygląd", style = MaterialTheme.typography.titleMedium)
                Text("Obrys tekstu: ${vm.layout.textOutline}")
                Slider(
                    value = vm.layout.textOutline.toFloat(),
                    onValueChange = { vm.setTextOutline(it.toInt()) },
                    valueRange = 0f..8f,
                    steps = 7,
                )
                OutlinedButton(onClick = { vm.tiles.clear() }) { Text("Wyczyść pamięć podręczną mapy") }
            }
        }
        Text(
            "Mapy: © OpenStreetMap contributors, © CARTO; zdjęcia satelitarne © Esri.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
