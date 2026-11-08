@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.yghfv.saltneri.plugin.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Only measures the toggle's real position; never draws inside the clipped sidebar-width region. */
class SaltSidebarAnchor(private val binding: SaltSidebarBindings, private val ui: SaltNativeUi) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        if (!binding.enabled() || !ui.available()) return
        val state = binding.state(currentComposer)
        val hidden=ui.hiddenSidebar()
        LaunchedEffect(hidden,state) { if(hidden) ui.collapseHiddenSidebar(state) }
        if (binding.expanded(state)) return
        DisposableEffect(binding) { onDispose { binding.anchor(null) } }
        Box(Modifier.size(0.dp).onGloballyPositioned {
            // A zero-height marker is centered differently by native caption rows.
            // Locate the actual caption instead of using the marker's own y coordinate.
            val p = (ui.captionCoordinates(it) ?: it).positionInRoot()
            binding.anchor(SaltSidebarBindings.Anchor(p.x, p.y))
        })
    }
}

/** Uses the native toggle callback and snapshot state, never a second sidebar preference. */
class SaltSidebarContent(private val binding: SaltSidebarBindings, private val ui: SaltNativeUi) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        var available by remember { mutableStateOf(ui.available()) }
        LaunchedEffect(ui) { while (true) { available = ui.available(); delay(500) } }
        SideEffect { binding.rendered() }
        if (!binding.enabled() || !available) return
        val state = binding.state(currentComposer)
        val anchor = binding.anchor() ?: return
        if (binding.expanded(state)) return
        DisposableEffect(binding) { onDispose { binding.visibleWidth(0) } }
        val navigator = ui.navigator(currentComposer)
        LaunchedEffect(ui,navigator,ui.navigationStack(navigator)) {ui.observeNavigation(navigator)}
        val style = binding.style(currentComposer)
        SideEffect { ui.publishSidebarStyle(style) }
        var playlists by remember { mutableStateOf(emptyList<SaltSidebarBindings.Entry>()) }
        LaunchedEffect(binding) {
            try {
                withContext(Dispatchers.IO) {
                    binding.playlists().collect { values -> playlists = binding.playlistEntries(values) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { /* Native actions remain available if the optional database is unavailable. */ }
        }
        CompositionLocalProvider(ui.styleLocal().provides(style)) {
            val density = LocalDensity.current
            var origin by remember { mutableStateOf(Offset.Zero) }
            // This slot is a sibling of the entire native titlebar, outside its 260dp left region.
            BoxWithConstraints(Modifier.fillMaxWidth().height(binding.titleHeight().dp).onGloballyPositioned { origin = it.positionInRoot() }) {
                val start = with(density) { (anchor.x() - origin.x).toDp() } + if(ui.hiddenSidebarToggle()) 0.dp else binding.captionWidth().dp
                val top = with(density) { (anchor.y() - origin.y).toDp() }
                Row(Modifier.offset(x = start, y = top).widthIn(max = (maxWidth - start - 240.dp).coerceAtLeast(40.dp))
                    .height(binding.captionHeight().dp).horizontalScroll(rememberScrollState()).onSizeChanged { binding.visibleWidth(it.width) }, verticalAlignment = Alignment.CenterVertically) {
                    val selectedRoute = binding.selected(navigator)
                    binding.entries().forEach { entry ->
                        if(ui.sidebarVisible(entry.route().javaClass.simpleName)) {
                        key(entry.route()) {
                            SidebarShortcut(binding, ui, binding.label(entry, currentComposer), selectedRoute == entry.route(),
                                { if (ui.available()) binding.open(navigator, state, entry) }) {
                                Image(binding.painter(entry, currentComposer), null, Modifier.size(20.dp))
                            }
                        }
                        }
                        if (entry.route().javaClass.simpleName == "MusicLibrary") {
                            AccountPlatform.entries.forEach { platform ->
                                if(ui.sidebarVisible(platform.name)) {
                                key(platform) {
                                    SidebarShortcut(binding, ui, if (platform == AccountPlatform.BILIBILI) "Bilibili" else "\u7f51\u6613\u4e91",
                                        ui.platform(selectedRoute) == platform, { if (ui.available()) ui.open(platform) }) {
                                        PlatformIcon(ui, platform)
                                    }
                                }
                                }
                            }
                        }
                    }
                    playlists.forEach { entry ->
                        key(entry.route()) {
                            val popup=ui.playlistMenuState(currentComposer)
                            val native=binding.nativePlaylist(entry)
                            val context=Modifier.onPointerEvent(PointerEventType.Press) {
                                if(it.buttons.isSecondaryPressed) {
                                    ui.showPlaylistMenu(popup)
                                    it.changes.forEach { change -> change.consume() }
                                }
                            }
                            SidebarShortcut(binding, ui, entry.label(), selectedRoute == entry.route(),
                                { if (ui.available()) binding.open(navigator, state, entry) },context) {
                                Image(binding.painter(entry, currentComposer), null, Modifier.size(20.dp))
                            }
                            ui.playlistMenu(popup,native,navigator,{binding.currentPlaylist(native)},currentComposer)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarShortcut(binding: SaltSidebarBindings, ui: SaltNativeUi, label: String, active: Boolean,
                            action: () -> Unit, context: Modifier = Modifier, icon: @Composable BoxScope.() -> Unit) {
    val palette = ui.palette(currentComposer)
    val modifier = context.semantics { selected = active; contentDescription = label }.clip(palette.shape())
        .background(if (active) Color(palette.background().toULong()) else Color.Transparent)
    val centered: @Composable BoxScope.() -> Unit = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(20.dp).semantics { contentDescription = "$label icon" }, contentAlignment = Alignment.Center, content = icon)
    } }
    binding.button(label, modifier, action, centered, currentComposer)
}
