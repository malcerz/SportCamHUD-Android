package com.sportcamhud.app.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.sportcamhud.app.HudViewModel
import com.sportcamhud.app.render.AndroidHudCanvas
import com.sportcamhud.core.render.IndicatorPlacement

/**
 * Video with the live HUD on top. The HUD is drawn at the video's own resolution and
 * scaled into the view, so the preview matches the exported film exactly.
 */
@OptIn(UnstableApi::class)
@Composable
fun PreviewPane(vm: HudViewModel, editMode: Boolean = false) {
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

    // Selecting a widget does not open a modal: touch its image, then drag it
    // directly. Touch coordinates are mapped from the *video box* to the
    // original video resolution; black letterbox margins never count as pixels.
    // One gesture recognizer handles both short taps and drags. Separate
    // detectTapGestures/detectDragGestures modifiers consume one another's
    // initial pointer down and make dragging appear broken on some devices.
    val editGestures = if (editMode) Modifier.pointerInput(vw, vh) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val hit = IndicatorPlacement.hitTest(
                vm.layout, vw, vh,
                down.position.x * vw / size.width, down.position.y * vh / size.height,
                24f * vw / size.width,
            )
            fun moveBy(dx: Float, dy: Float) {
                val key = hit ?: return
                val cfg = vm.layout.indicators[key] ?: return
                val (x, y) = IndicatorPlacement.clampPosition(
                    cfg, vw, vh,
                    cfg.x + dx / size.width * 100.0,
                    cfg.y + dy / size.height * 100.0,
                )
                vm.updateIndicator(cfg.withAll(mapOf("x" to x, "y" to y)), persist = false)
            }
            val slop = awaitTouchSlopOrCancellation(down.id) { change, overSlop ->
                if (hit != null) {
                    change.consume()
                    vm.selectedKey = hit
                    vm.player.pause()
                    moveBy(overSlop.x, overSlop.y)
                }
            }
            if (slop == null) {
                // Short tap: select a widget without opening the properties dialog.
                vm.selectedKey = hit
            } else if (hit != null) {
                drag(slop.id) { change ->
                    val delta = change.positionChange()
                    change.consume()
                    moveBy(delta.x, delta.y)
                }
                vm.persistNow()
            }
        }
    } else Modifier

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
            Canvas(Modifier.fillMaxSize().then(editGestures)) {
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
                // Draw selection bounds and a handle at the true Windows widget
                // anchor. For topleft maps, x/y are NOT their centre.
                if (editMode) selected?.let { layout.indicators[it] }?.let { cfg ->
                    val rect = IndicatorPlacement.touchBounds(cfg, vw, vh)
                    val sx = size.width / vw
                    val sy = size.height / vh
                    val l = rect.left * sx
                    val t = rect.top * sy
                    val r = rect.right * sx
                    val b = rect.bottom * sy
                    drawRect(
                        Color(0xFF159FA5), topLeft = Offset(l, t),
                        size = Size((r - l).coerceAtLeast(1f), (b - t).coerceAtLeast(1f)),
                        style = Stroke(width = 2f),
                    )
                    val p = Offset(rect.centerX * sx, rect.centerY * sy)
                    drawCircle(Color(0xFF159FA5), radius = 13f, center = p, style = Stroke(width = 3f))
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
