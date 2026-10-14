package com.sportcamhud.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sportcamhud.app.HudViewModel
import com.sportcamhud.core.layout.DefaultLayout
import com.sportcamhud.core.layout.IndicatorConfig
import com.sportcamhud.core.render.MapRenderer
import com.sportcamhud.core.telemetry.Fields

private val BUILTIN_NAMES = mapOf(
    "time_display" to "Data, godzina, czas, średnia",
    "speed_visual" to "Prędkość — licznik",
    "speed_text" to "Prędkość — tekst",
    "dist_visual" to "Dystans — suwak",
    "dist_text" to "Dystans — tekst",
    "alt_visual" to "Wysokość — suwak",
    "alt_text" to "Wysokość — tekst",
    "iso_text" to "ISO (tylko GoPro)",
    "exposure_text" to "Ekspozycja (tylko GoPro)",
    "temp_text" to "Temperatura kamery (tylko GoPro)",
    "power_text" to "Moc",
    "atemp_text" to "Temperatura",
    "hr_text" to "Tętno",
    "cad_text" to "Kadencja",
    "battery_text" to "Bateria licznika",
    "track_map" to "Mapa z trasą",
    "compass" to "Kompas",
    "slope_text" to "Nachylenie",
    "lean_indicator" to "Przechył (GoPro)",
)

private val FORM_NAMES = linkedMapOf(
    "text" to "Tekst", "gauge" to "Licznik", "bar" to "Suwak", "segment_bar" to "Segmenty",
    "chart" to "Wykres", "compass" to "Kompas",
)

fun indicatorName(cfg: IndicatorConfig): String =
    BUILTIN_NAMES[cfg.key] ?: cfg.label.ifBlank { cfg.key }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndicatorsTab(vm: HudViewModel) {
    var editing by remember { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    val importPreset = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm::importLayout) }
    val exportPreset = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { it?.let(vm::exportLayout) }

    Column(Modifier.fillMaxSize()) {
        FlowRow(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box {
                OutlinedButton(onClick = { addMenu = true }) { Text("Dodaj wskaźnik") }
                DropdownMenu(expanded = addMenu, onDismissRequest = { addMenu = false }) {
                    val fields = vm.availableFields.ifEmpty {
                        listOf(Fields.SPEED, Fields.DISTANCE, Fields.ALT, Fields.HEART_RATE, Fields.CADENCE, Fields.POWER, Fields.SLOPE, Fields.HEADING)
                    }
                    for (f in fields) {
                        DropdownMenuItem(
                            text = { Text(vm.fieldLabel(f)) },
                            onClick = { addMenu = false; vm.addIndicator(f); editing = vm.selectedKey },
                        )
                    }
                }
            }
            OutlinedButton(onClick = { importPreset.launch(arrayOf("application/json", "*/*")) }) { Text("Wczytaj preset") }
            OutlinedButton(onClick = { exportPreset.launch("sportcamhud_preset.json") }) { Text("Zapisz preset") }
            TextButton(onClick = vm::resetLayout) { Text("Domyślne") }
        }
        Text(
            "Stuknij wskaźnik, aby go edytować. Zaznaczony wskaźnik możesz przeciągać palcem na podglądzie.",
            Modifier.padding(horizontal = 12.dp),
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(Modifier.fillMaxSize()) {
            items(vm.layout.indicators.values.toList(), key = { it.key }) { cfg ->
                val selected = vm.selectedKey == cfg.key
                Row(
                    Modifier.fillMaxWidth()
                        .background(if (selected) Color(0x33159FA5) else Color.Transparent)
                        .clickable {
                            vm.selectedKey = cfg.key
                            editing = cfg.key
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(indicatorName(cfg))
                        Text(FORM_NAMES[cfg.form] ?: cfg.form, style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = cfg.enabled, onCheckedChange = { vm.updateIndicator(cfg.with("enabled", it)) })
                }
            }
        }
    }

    editing?.let { key ->
        vm.layout.indicators[key]?.let { cfg ->
            IndicatorEditor(
                cfg = cfg,
                onChange = { vm.updateIndicator(it) },
                onDelete = if (key !in BUILTIN_NAMES) ({ vm.removeIndicator(key); editing = null }) else null,
                onClose = { editing = null },
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IndicatorEditor(
    cfg: IndicatorConfig,
    onChange: (IndicatorConfig) -> Unit,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(indicatorName(cfg)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LabeledSwitch("Włączony", cfg.enabled) { onChange(cfg.with("enabled", it)) }
                TextProp("Etykieta", cfg.label) { onChange(cfg.with("label", it)) }
                SliderProp("Pozycja X", cfg.x, 0.0, 100.0) { onChange(cfg.with("x", it)) }
                SliderProp("Pozycja Y", cfg.y, 0.0, 100.0) { onChange(cfg.with("y", it)) }

                val form = cfg.form
                if (form != "time_display" && form != "map" && form != "lean") {
                    Text("Forma")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for ((f, name) in FORM_NAMES) {
                            FilterChip(selected = form == f, onClick = { onChange(cfg.with("form", f)) }, label = { Text(name) })
                        }
                    }
                }
                if (form == "time_display") {
                    SliderProp("Rozmiar", cfg.double("size", 1.0), 0.3, 3.0) { onChange(cfg.with("size", it)) }
                    for ((k, n) in listOf("show_date" to "Data", "show_time" to "Godzina", "show_elapsed" to "Czas jazdy", "show_avg_speed" to "Średnia prędkość")) {
                        LabeledSwitch(n, cfg.bool(k, true)) { onChange(cfg.with(k, it)) }
                    }
                } else {
                    SliderProp("Rozmiar", cfg.double("size", 10.0), 1.0, 60.0) { onChange(cfg.with("size", it)) }
                }
                if (form != "map") SliderProp("Czcionka", cfg.double("font_size", 1.8), 0.5, 10.0) { onChange(cfg.with("font_size", it)) }

                Text("Obrót")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (r in listOf(0, 90, 180, 270)) {
                        FilterChip(selected = cfg.rotation == r, onClick = { onChange(cfg.with("rotation", r)) }, label = { Text("$r°") })
                    }
                }

                when (form) {
                    "gauge", "bar", "segment_bar" -> {
                        if (form != "gauge") LabeledSwitch("Zakres z aktywności", cfg.bool("auto_range", false)) { onChange(cfg.with("auto_range", it)) }
                        NumberProp("Minimum", cfg.double("min_val", 0.0)) { onChange(cfg.with("min_val", it)) }
                        NumberProp("Maksimum", cfg.double("max_val", 100.0)) { onChange(cfg.with("max_val", it)) }
                        SliderProp("Podziałki", cfg.int("ticks", 5).toDouble(), 0.0, 20.0, integer = true) { onChange(cfg.with("ticks", it.toInt())) }
                        if (form == "gauge") {
                            ColorProp("Kolor wskazówki", cfg.string("needle_color", "#DC3232")) { onChange(cfg.with("needle_color", it)) }
                            LabeledSwitch("Pokaż wartość", cfg.bool("show_value", true)) { onChange(cfg.with("show_value", it)) }
                            LabeledSwitch("Cień", cfg.bool("shadow_enabled", false)) { onChange(cfg.with("shadow_enabled", it)) }
                        } else {
                            ColorProp("Kolor znacznika", cfg.string("marker_color", "#159FA5")) { onChange(cfg.with("marker_color", it)) }
                        }
                    }
                    "chart" -> {
                        Text("Zakres wykresu")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for ((s, n) in listOf("activity" to "Cała aktywność", "video" to "Film", "window" to "Okno")) {
                                FilterChip(selected = cfg.string("chart_time_scope", "activity") == s, onClick = { onChange(cfg.with("chart_time_scope", s)) }, label = { Text(n) })
                            }
                        }
                        if (cfg.string("chart_time_scope", "activity") == "window") {
                            SliderProp("Okno [s]", cfg.double("chart_window_s", 60.0), 5.0, 600.0, integer = true) { onChange(cfg.with("chart_window_s", it)) }
                        }
                        ColorProp("Kolor linii", cfg.string("chart_color", "")) { onChange(cfg.with("chart_color", it)) }
                        SliderProp("Wypełnienie", cfg.int("fill_alpha", 40).toDouble(), 0.0, 255.0, integer = true) { onChange(cfg.with("fill_alpha", it.toInt())) }
                    }
                    "compass" -> {
                        Text("Tryb")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for ((m, n) in listOf("needle" to "Igła (północ u góry)", "rose" to "Róża (kurs u góry)")) {
                                FilterChip(selected = cfg.string("compass_rotation_mode", "needle") == m, onClick = { onChange(cfg.with("compass_rotation_mode", m)) }, label = { Text(n) })
                            }
                        }
                    }
                    "map" -> {
                        SliderProp("Powiększenie (zoom)", cfg.int("zoom", 16).toDouble(), 10.0, 19.0, integer = true) { onChange(cfg.with("zoom", it.toInt())) }
                        Text("Styl mapy")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for (s in MapRenderer.STYLES.keys) {
                                FilterChip(selected = cfg.string("map_style", "light_all") == s, onClick = { onChange(cfg.with("map_style", s)) }, label = { Text(s) })
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            FilterChip(cfg.string("map_orientation", "north_up") == "north_up", { onChange(cfg.with("map_orientation", "north_up")) }, { Text("Północ u góry") })
                            FilterChip(cfg.string("map_orientation", "north_up") == "track_up", { onChange(cfg.with("map_orientation", "track_up")) }, { Text("Kierunek jazdy u góry") })
                            FilterChip(cfg.string("map_shape", "square") == "square", { onChange(cfg.with("map_shape", "square")) }, { Text("Kwadrat") })
                            FilterChip(cfg.string("map_shape", "square") == "circle", { onChange(cfg.with("map_shape", "circle")) }, { Text("Koło") })
                        }
                    }
                    "lean" -> {
                        Text("Źródło danych")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for ((src, n) in listOf("gyro" to "IMU GoPro (kąt)", "raw_gyro_z" to "Żyroskop Z [°/s]", "grade" to "Nachylenie z FIT")) {
                                FilterChip(selected = cfg.string("source", "gyro") == src, onClick = { onChange(cfg.with("source", src)) }, label = { Text(n) })
                            }
                        }
                        if (cfg.string("source", "gyro") == "gyro") {
                            Text("Oś przechyłu")
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                for ((a, n) in listOf("y" to "Roll (Y)", "x" to "Pitch (X)", "z" to "Yaw (Z)")) {
                                    FilterChip(selected = cfg.string("axis", "y") == a, onClick = { onChange(cfg.with("axis", a)) }, label = { Text(n) })
                                }
                            }
                        }
                        SliderProp("Kalibracja [°]", cfg.double("calibration", 0.0), -90.0, 90.0) { onChange(cfg.with("calibration", it)) }
                        LabeledSwitch("Odwróć kierunek", cfg.bool("invert_axis", false)) { onChange(cfg.with("invert_axis", it)) }
                        SliderProp("Mnożnik wychyłu", cfg.double("sensitivity", 1.0), 0.0, 5.0) { onChange(cfg.with("sensitivity", it)) }
                        SliderProp("Maks. kąt [°]", cfg.double("max_angle", 30.0), 1.0, 90.0, integer = true) { onChange(cfg.with("max_angle", it)) }
                        SliderProp("Wygładzanie [s]", cfg.double("lean_smoothing_s", 0.0), 0.0, 5.0) { onChange(cfg.with("lean_smoothing_s", it)) }
                        Text("Grafika")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            for ((gname, n) in listOf("bike" to "Rower", "none" to "Brak")) {
                                FilterChip(selected = cfg.string("graphic", "bike") == gname, onClick = { onChange(cfg.with("graphic", gname)) }, label = { Text(n) })
                            }
                        }
                        LabeledSwitch("Linia 0°", cfg.bool("show_reference", true)) { onChange(cfg.with("show_reference", it)) }
                        LabeledSwitch("Podziałka", cfg.bool("show_ticks", true)) { onChange(cfg.with("show_ticks", it)) }
                        LabeledSwitch("Pokaż wartość", cfg.bool("show_value", true)) { onChange(cfg.with("show_value", it)) }
                        ColorProp("Kolor grafiki", cfg.string("marker_color", "#FFFFFF")) { onChange(cfg.with("marker_color", it)) }
                    }
                    "text" -> {
                        LabeledSwitch("Wyśrodkuj", cfg.string("align", "left") == "center") { onChange(cfg.with("align", if (it) "center" else "left")) }
                        LabeledSwitch("Pokaż jednostkę", cfg.bool("show_units", true)) { onChange(cfg.with("show_units", it)) }
                    }
                }
                if (form != "map" && form != "time_display" && form != "gauge" && form != "compass") {
                    SliderProp("Miejsca po przecinku", cfg.int("decimals", 1).toDouble(), 0.0, 3.0, integer = true) { onChange(cfg.with("decimals", it.toInt())) }
                }
                if (form != "map") ColorProp("Kolor tekstu", cfg.string("text_color", "#FFFFFF")) { onChange(cfg.with("text_color", it)) }
                SliderProp("Krycie", cfg.double("opacity", 1.0), 0.1, 1.0) { onChange(cfg.with("opacity", it)) }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Gotowe") } },
        dismissButton = { if (onDelete != null) TextButton(onClick = onDelete) { Text("Usuń") } },
    )
}

@Composable
private fun LabeledSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SliderProp(label: String, value: Double, min: Double, max: Double, integer: Boolean = false, onChange: (Double) -> Unit) {
    Column {
        Text("$label: " + if (integer) "%.0f".format(value) else "%.1f".format(value), style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat().coerceIn(min.toFloat(), max.toFloat()),
            onValueChange = { v -> onChange(if (integer) Math.round(v).toDouble() else Math.round(v * 10) / 10.0) },
            valueRange = min.toFloat()..max.toFloat(),
        )
    }
}

@Composable
private fun TextProp(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun NumberProp(label: String, value: Double, onChange: (Double) -> Unit) {
    var text by remember(value) { mutableStateOf(if (value == Math.floor(value)) value.toLong().toString() else value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { t -> text = t; t.replace(',', '.').toDoubleOrNull()?.let(onChange) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.width(160.dp),
    )
}

@Composable
private fun ColorProp(label: String, value: String, onChange: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = text,
            onValueChange = { t ->
                text = t
                if (t.isEmpty() || Regex("#?[0-9A-Fa-f]{6}").matches(t)) onChange(if (t.isEmpty() || t.startsWith("#")) t else "#$t")
            },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        val parsed = com.sportcamhud.core.render.Colors.parse(text)
        Box(
            Modifier.padding(start = 8.dp).size(28.dp)
                .background(if (parsed != null) Color(parsed) else Color.Transparent),
        )
    }
}
