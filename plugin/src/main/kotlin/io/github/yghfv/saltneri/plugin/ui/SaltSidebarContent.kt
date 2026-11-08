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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException

/** Uses the native toggle callback and snapshot state, never a second sidebar preference. */
class SaltSidebarContent(private val binding: SaltSidebarBindings, private val ui: SaltNativeUi) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        var available by remember { mutableStateOf(ui.available()) }
        LaunchedEffect(ui) { while (true) { available = ui.available(); delay(500) } }
        if (!binding.enabled() || !available) {
            binding.original(call, currentComposer)
            return
        }
        val state = binding.state(currentComposer)
        if (binding.expanded(state)) {
            binding.original(call, currentComposer)
            return
        }
        val navigator = ui.navigator(currentComposer)
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
            val maxWidth = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() }
            // Reserve window controls and a drag area; overflow stays within this scrollable strip.
            Row(Modifier.widthIn(max = (maxWidth - 320.dp).coerceAtLeast(80.dp)), verticalAlignment = Alignment.CenterVertically) {
                binding.original(call, currentComposer)
                Row(Modifier.weight(1f, fill = false).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    val selectedRoute = binding.selected(navigator)
                    binding.entries().forEach { entry ->
                        key(entry.route()) {
                            SidebarShortcut(binding, ui, binding.label(entry, currentComposer), selectedRoute == entry.route(),
                                { if (ui.available()) binding.open(navigator, state, entry) }) {
                                Image(binding.painter(entry, currentComposer), null, Modifier.size(20.dp),
                                    colorFilter = ColorFilter.tint(Color(ui.palette(currentComposer).secondary().toULong())))
                            }
                        }
                        if (entry.route().javaClass.simpleName == "MusicLibrary") {
                            AccountPlatform.entries.forEach { platform ->
                                key(platform) {
                                    SidebarShortcut(binding, ui, if (platform == AccountPlatform.BILIBILI) "Bilibili" else "\u7f51\u6613\u4e91",
                                        ui.platform(selectedRoute) == platform, { if (ui.available()) ui.open(platform) }) {
                                        PlatformIcon(ui, platform)
                                    }
                                }
                            }
                        }
                    }
                    playlists.forEach { entry ->
                        key(entry.route()) {
                            SidebarShortcut(binding, ui, entry.label(), selectedRoute == entry.route(),
                                { if (ui.available()) binding.open(navigator, state, entry) }) {
                                Image(binding.painter(entry, currentComposer), null, Modifier.size(20.dp),
                                    colorFilter = ColorFilter.tint(Color(ui.palette(currentComposer).secondary().toULong())))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SidebarShortcut(binding: SaltSidebarBindings, ui: SaltNativeUi, label: String, active: Boolean,
                            action: () -> Unit, icon: @Composable BoxScope.() -> Unit) {
    val palette = ui.palette(currentComposer)
    val modifier = Modifier.semantics { selected = active; contentDescription = label }.clip(palette.shape())
        .background(if (active) Color(palette.background().toULong()) else Color.Transparent)
    binding.button(label, modifier, action, icon, currentComposer)
}
