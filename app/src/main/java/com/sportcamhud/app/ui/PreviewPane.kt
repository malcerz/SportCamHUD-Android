package com.sportcamhud.app.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sportcamhud.app.HudViewModel
import com.sportcamhud.app.render.AndroidHudCanvas

/**
 * Video with the live HUD on top. The HUD is drawn at the video's own resolution and
 * scaled into the view, so the preview matches the exported film exactly.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewPane(vm: HudViewModel) {
    val v = vm.video
    if (v == null) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black),
            contentAlignment = Alignment.Center,
        ) { Text("Wybierz film w zakładce Projekt", color = Color.LightGray) }
        return
    }
    var position by remember { mutableLongStateOf(0L) }
    var playing by remember { mutableStateOf(false) }
    val tilesVersion by vm.tiles.version.collectAsState()

    LaunchedEffect(vm.player) {
        while (true) {
            withFrameMillis { }
            position = vm.player.currentPosition
            playing = vm.player.isPlaying
        }
    }
    val frame = remember(position, vm.timeline) { vm.frameAt(position) }
    val vw = v.displayWidth
    val vh = v.displayHeight
    val holder = remember(vw, vh) { arrayOfNulls<AndroidHudCanvas>(1) }

    Column {
        Box(Modifier.fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(max = 420.dp).aspectRatio(vw.toFloat() / vh)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        player = vm.player
                    }
                },
            )
            val selected = vm.selectedKey
            Canvas(
                Modifier.fillMaxSize().pointerInput(selected) {
                    if (selected == null) return@pointerInput
                    detectDragGestures(
                        onDragEnd = { vm.persistNow() },
                        onDrag = { change, drag ->
                            change.consume()
                            val cfg = vm.layout.indicators[selected] ?: return@detectDragGestures
                            vm.updateIndicator(
                                cfg.withAll(
                                    mapOf(
                                        "x" to (cfg.x + drag.x / size.width * 100.0).coerceIn(0.0, 100.0),
                                        "y" to (cfg.y + drag.y / size.height * 100.0).coerceIn(0.0, 100.0),
                                    ),
                                ),
                                persist = false,
                            )
                        },
                    )
                },
            ) {
                if (tilesVersion < 0) return@Canvas // reading it redraws when map tiles arrive
                val layout = vm.layout
                drawIntoCanvas { c ->
                    val nc = c.nativeCanvas
                    nc.save()
                    nc.scale(size.width / vw, size.height / vh)
                    val hud = holder[0]?.also { it.reset(nc) } ?: AndroidHudCanvas(nc, vw, vh).also { holder[0] = it }
                    vm.previewCompositor.render(hud, vw, vh, layout, frame, vm.tiles)
                    nc.restore()
                }
                // Marker of the indicator being edited (drag to move it).
                selected?.let { layout.indicators[it] }?.let { cfg ->
                    val p = Offset((cfg.x / 100.0 * size.width).toFloat(), (cfg.y / 100.0 * size.height).toFloat())
                    drawCircle(Color(0xFF159FA5), radius = 14f, center = p, style = Stroke(width = 3f))
                    drawCircle(Color(0xFF159FA5), radius = 3f, center = p)
                }
            }
        }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { if (vm.player.isPlaying) vm.player.pause() else vm.player.play() }) {
                Text(if (playing) "⏸" else "▶")
            }
            Slider(
                value = if (v.durationMs > 0) position.toFloat() / v.durationMs else 0f,
                onValueChange = { vm.player.seekTo((it * v.durationMs).toLong()) },
                modifier = Modifier.weight(1f),
            )
            Text(formatMs(position) + " / " + formatMs(v.durationMs), style = MaterialTheme.typography.bodySmall)
        }
    }
}

fun formatMs(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s % 3600 / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
