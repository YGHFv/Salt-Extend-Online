@file:OptIn(androidx.compose.runtime.InternalComposeApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class,
    com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.yghfv.saltneri.plugin.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.skiaCanvas
import androidx.compose.ui.window.LocalWindow
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import kotlinx.coroutines.delay

/** Retain Salt's transport geometry, sheet animation and expand/collapse callback. */
class SaltVideoTransportContent(private val video: SaltVideoBindings) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        if (!video.isExpanded(currentComposer) || !video.view().requested()) {
            SideEffect { video.restoreNativeTransport() }
            video.originalConditional(call, currentComposer)
        }
        else if (video.controlsVisible()) {
            CompositionLocalProvider(*video.darkThemeValues(currentComposer)) {
                video.originalConditional(call, currentComposer)
            }
        }
    }
}

/** Replace the artwork/lyrics inside the original animated and draggable player sheet. */
class SaltVideoScreenContent(private val video: SaltVideoBindings) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        val state = video.view()
        if (!state.requested()) video.originalConditional(call, currentComposer)
        else {
            val expanded = video.isExpanded(currentComposer)
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = video.screenOpacity() }.background(Color.Black)
                .then(if (expanded) Modifier.videoActivity(video).pointerInput(video) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        if (currentEvent.buttons.isPrimaryPressed) {
                            var cancelled = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                cancelled = cancelled || change.isConsumed || event.buttons.isSecondaryPressed ||
                                    (change.position - down.position).getDistance() >= viewConfiguration.touchSlop
                                if (!change.pressed) {
                                    if (!cancelled) {
                                        change.consume()
                                        video.togglePlayback()
                                    }
                                    break
                                }
                            }
                        }
                    }
                } else Modifier).testTag("SaltPlaybackVideo"), contentAlignment = Alignment.Center) {
                state.player()?.let { VideoSurface(video, it) }
                if (state.loading()) BasicText("正在加载视频",
                    style = androidx.compose.ui.text.TextStyle(color = Color.White))
            }
        }
    }
}

class SaltVideoScreenStateContent(private val video: SaltVideoBindings) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        @Suppress("UNCHECKED_CAST")
        val opacity = call.getArgument(0) as () -> Float
        SideEffect { video.screenOpacity(opacity) }
    }
}

class SaltVideoFloatCoverContent(private val video: SaltVideoBindings) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        // Alpha alone leaves this independent cover above the video in hit testing.
        // Its layout is separate from the MiniPlayer; remove it only in expanded video.
        if (!video.isExpanded(currentComposer) || !video.view().requested())
            video.originalFloatCover(call, currentComposer)
    }
}

/** Window caption buttons live outside MainTitleBar and share the video's timer. */
class SaltVideoWindowFrameContent(private val video: SaltVideoBindings) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        val properties = call.getArgument(2)
        SideEffect { video.captionProperties(properties) }
        // Keep the entire sheet and its video surface at one composition call site.
        // Caption and transport supply their own theme without rebuilding the window.
        video.originalWindowFrame(call, currentComposer)
    }
}

class SaltVideoWindowCaptionContent(private val video: SaltVideoBindings) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        val window = LocalWindow.current
        if (video.windowControlsVisible(window)) {
            if (video.windowVideoActive(window)) video.originalConditional(call, true, currentComposer)
            else video.originalConditional(call, currentComposer)
        }
    }
}

/** One native title bar and video switch, synchronized with the outer window caption. */
class SaltVideoContent(private val video: SaltVideoBindings) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        val expanded = video.isExpanded(currentComposer)
        val state = video.view()
        val window = LocalWindow.current
        DisposableEffect(video, window) {
            val observer = video.observeWindow(window)
            onDispose { observer.close() }
        }
        val activity = video.activity()
        LaunchedEffect(expanded, state.requested(), state.loading(), activity) {
            video.showControls()
            if (expanded && state.requested() && !state.loading()) {
                delay(3000)
                video.hideControls()
            }
        }
        val visible = !expanded || !state.requested() || video.controlsVisible()
        if (visible && call.getArgument(0) != null)
            video.originalTitleBar(call, currentComposer)
        if (expanded) {
            Box(Modifier.fillMaxSize()) {
                if (visible && (state.available() || state.requested() || video.hasNativeVideo(call.getArgument(2)))) {
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

private fun Modifier.videoActivity(video: SaltVideoBindings): Modifier = pointerInput(video) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type == PointerEventType.Enter || event.type == PointerEventType.Move) video.pointerMoved()
            else if (event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) {
                video.pointerActivity()
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
