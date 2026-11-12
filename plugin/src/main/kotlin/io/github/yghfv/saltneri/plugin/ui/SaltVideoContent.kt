@file:OptIn(androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.yghfv.saltneri.plugin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.window.LocalWindow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import kotlinx.coroutines.delay

/** Keep exactly one native transport active when the video overlay owns its position. */
class SaltVideoTransportContent(private val video: SaltVideoBindings) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        if (!video.isExpanded(currentComposer) || !video.view().requested())
            video.originalTransport(call, currentComposer)
    }
}

/** Full-page video, with Salt's native title bar and transport overlaid while the pointer is active. */
class SaltVideoContent(private val video: SaltVideoBindings) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        val expanded = video.isExpanded(currentComposer)
        val state = video.view()
        // This replaces MainWindow's title-bar call. Render the host caption once per window.
        // Standalone fixtures may omit the native title-bar arguments.
        if ((!expanded || !state.requested()) && call.getArgument(0) != null)
            video.originalTitleBar(call, currentComposer)
        if (expanded) {
            val transportState = remember(video) { video.createTransportState() }
            var activity by remember(video) { mutableLongStateOf(0) }
            var controlsVisible by remember(video) { mutableStateOf(true) }
            LaunchedEffect(state.requested(), state.loading(), activity) {
                controlsVisible = true
                if (state.requested() && !state.loading()) {
                    delay(3000)
                    controlsVisible = false
                }
            }
            val pointer = if (state.requested()) Modifier.pointerInput(video) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Enter || event.type == PointerEventType.Move ||
                            event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) {
                            controlsVisible = true
                            activity++
                            // Native MiniPlayer's Exit handler may hide its buttons during this event.
                            // Restore them after children run so the video timer owns visibility.
                            awaitPointerEvent(PointerEventPass.Final)
                            video.activateTransport(transportState)
                        }
                    }
                }
            } else Modifier
            Box(Modifier.fillMaxSize().then(pointer)) {
                if (state.requested()) {
                    Box(
                        Modifier.fillMaxSize().background(Color.Black).testTag("SaltPlaybackVideo"),
                        contentAlignment = Alignment.Center
                    ) {
                        state.player()?.let { VideoSurface(video, it) }
                        if (state.loading()) BasicText("正在加载视频", style = androidx.compose.ui.text.TextStyle(color = Color.White))
                    }
                    if (controlsVisible) {
                        SideEffect { video.activateTransport(transportState) }
                        if (call.getArgument(0) != null)
                            video.titleBar(call, Modifier.align(Alignment.TopStart), currentComposer)
                        val controls = Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp)
                            .testTag("SaltPlaybackVideoControls")
                        // The title-bar hook has no MiniPlayer geometry or ViewModelStoreOwner.
                        // Provide the overlay's own native state instead of waiting for another hook.
                        CompositionLocalProvider(video.transportGeometry()) {
                            video.transportControls(controls, transportState, currentComposer)
                        }
                    }
                }
                if (controlsVisible && (state.available() || state.requested() || video.hasNativeVideo(call.getArgument(2)))) {
                    val captionWidth = video.captionWidth(currentComposer)
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .padding(end = (captionWidth + 52f).dp, top = 4.dp)
                            .height(32.dp).testTag("SaltPlaybackVideoButton")
                    ) {
                        VideoButton(video, state)
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoSurface(video: SaltVideoBindings, player: Any) {
    val window = LocalWindow.current
    val revision = remember(player) { mutableLongStateOf(0) }
    val surface = remember(player, window) {
        video.surface(player, window) {
            SaltPageContentBinding.onUiThread { revision.longValue++ }
        }
    }
    DisposableEffect(surface) { onDispose { video.disposeSurface(surface) } }
    Canvas(Modifier.fillMaxSize()) {
        revision.longValue
        try {
            surface?.frame(size.width.toInt(), size.height.toInt())?.let { image ->
                val scale = minOf(size.width / image.width, size.height / image.height)
                val width = image.width * scale
                val height = image.height * scale
                drawContext.canvas.skiaCanvas.drawImageRect(image,
                    org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
                    org.jetbrains.skia.Rect.makeXYWH((size.width - width) / 2, (size.height - height) / 2, width, height),
                    org.jetbrains.skia.SamplingMode.LINEAR, null, true)
            }
        } catch (failure: Exception) {
            surface?.fail(failure)
        } catch (failure: LinkageError) {
            surface?.fail(failure)
        }
    }
}

@Composable
private fun VideoButton(video: SaltVideoBindings, state: SaltVideoBindings.View) {
    val label = when {
        state.loading() -> "取消加载视频"
        state.requested() -> "返回封面和歌词"
        else -> "播放视频"
    }
    val icon: @Composable BoxScope.() -> Unit = {
        Canvas(
            Modifier.align(Alignment.Center).size(16.dp).semantics { contentDescription = label }
        ) {
            val color = if (state.requested()) Color(0xff90caf9) else Color.White.copy(alpha = .85f)
            val stroke = Stroke(width = 1.4.dp.toPx())
            drawRoundRect(color, Offset(size.width * .05f, size.height * .16f),
                Size(size.width * .9f, size.height * .68f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()), style = stroke)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(size.width * .4f, size.height * .32f)
                lineTo(size.width * .68f, size.height * .5f)
                lineTo(size.width * .4f, size.height * .68f)
                close()
            }
            drawPath(path, color)
        }
    }
    video.button(label, true, Modifier.width(40.dp).fillMaxHeight(),
        { video.toggle() },
        icon, currentComposer)
}
