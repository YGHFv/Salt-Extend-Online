@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.yghfv.saltneri.plugin.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall

class SaltSidebarToggleContent(private val ui: SaltNativeUi) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        // A positioned marker finds only its preceding native caption sibling.
        // Keep original modifiers for restoration, including nested click semantics.
        key(ui) {
            // Retain native caption height: a centered titlebar row must not shift
            // the now zero-width caption and shortcut anchor down by half its height.
            val hidden=remember { Modifier.layout { _, _ -> layout(0,ui.sidebarCaptionHeight().dp.roundToPx()) {} } }
            val saved=remember { java.util.IdentityHashMap<Any,Modifier>() }
            val target=remember { arrayOfNulls<Any>(1) }
            val hide=ui.available() && ui.hiddenSidebarToggle()
            key(hide) { Box(Modifier.size(0.dp).onGloballyPositioned { coordinates ->
                val node=target[0] ?: ui.captionNode(coordinates)?.also { target[0]=it }
                if(node==null) return@onGloballyPositioned
                if(hide) {
                    ui.captionTree(node).forEach { child ->
                        val current=ui.captionModifier(child)
                        val replacement=if(child===node) hidden else Modifier
                        if(current!==replacement) saved[child]=current
                        ui.captionModifier(child,replacement)
                    }
                } else {
                    for ((child, original) in saved) ui.captionModifier(child,original)
                    saved.clear()
                }
            }) }
            DisposableEffect(ui) {
                onDispose {
                    // A Kotlin Map.forEach callback is a separate class first loaded on
                    // disposal. PF4J may already have closed the old plugin loader then.
                    for ((child, original) in saved) {
                        if(ui.captionModifier(child)===hidden || ui.captionModifier(child)===Modifier) ui.captionModifier(child,original)
                    }
                }
            }
        }
    }
}

class SaltSidebarRouteContent(private val binding: SaltSidebarSettingsBindings, private val ui: SaltNativeUi) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        val item=call.getArgument(0)
        if(!ui.available() || ui.sidebarItemVisible(item)) binding.route(item,currentComposer)
    }
}

class SaltSidebarSettingsContent(private val binding: SaltSidebarSettingsBindings, private val ui: SaltNativeUi) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        val showHidden=!ui.available() || ui.sidebarOptimized()
        var choices by remember(ui) {mutableStateOf(emptyList<io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice>())}
        LaunchedEffect(ui) {choices=withContext(Dispatchers.IO) {ui.nativePlaylistChoices()}}
        ui.homePlaylistChoices(choices)
        // Native config items are positionally remembered; inserting a switch must rebuild their slots.
        key(showHidden,choices) { binding.group(call.getArgument(0),call.getArgument(1) as String,showHidden,currentComposer) }
    }
}

/** Follow the selected row, including keyboard clicks, scrolling and window resizing. */
internal class SettingPopupPositionProvider : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize,
        layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = if (layoutDirection == LayoutDirection.Ltr)
            anchorBounds.right - popupContentSize.width else anchorBounds.left
        val y = if (anchorBounds.bottom + popupContentSize.height <= windowSize.height)
            anchorBounds.bottom else anchorBounds.top - popupContentSize.height
        return IntOffset(x.coerceIn(0, maxOf(0, windowSize.width - popupContentSize.width)),
            y.coerceIn(0, maxOf(0, windowSize.height - popupContentSize.height)))
    }
}

@Composable
fun SettingsConfigurationPage(ui: SaltNativeUi,page: SettingsCatalog.Page) {
    val revision=ui.settingsRevision()
    var playlists by remember(ui,page) {mutableStateOf(emptyList<io.github.yghfv.saltneri.plugin.host.SaltPlaylistGateway.Choice>())}
    LaunchedEffect(ui,page) {
        if(page==SettingsCatalog.Page.HOME){playlists=withContext(Dispatchers.IO){ui.nativePlaylistChoices()};ui.homePlaylistChoices(playlists)}
    }
    val back: @Composable () -> Unit = {ui.secondaryBack({ui.settingsBack()},Modifier,currentComposer)}
    val content: @Composable BoxScope.(PaddingValues) -> Unit = { insets ->
        LazyColumn(Modifier.fillMaxSize(),contentPadding=insets) {
            if(page==SettingsCatalog.Page.SIDEBAR)item {
                ui.sidebarConfigurationCaption("\u8bbe\u7f6e\u5165\u53e3\u59cb\u7ec8\u4fdd\u7559\uff0c\u5176\u4ed6\u6309\u94ae\u53ef\u81ea\u7531\u663e\u9690\u3002",currentComposer)
            }
            item {
                key(revision,playlists,page) {
                val settings=ui.settingsItems(page)
                val controls: @Composable ColumnScope.() -> Unit = {
                    settings.filter {it.key()!="ui.hiddenSidebar" || ui.sidebarOptimized()}.forEach {setting ->
                        key(setting.key(),setting.title()) {
                            when(setting.type()) {
                                "switch" -> ui.settingSwitch(setting,currentComposer)
                                "caption" -> ui.sidebarConfigurationCaption(setting.title()+" · "+setting.summary(),currentComposer)
                                else -> {
                                    var show by remember {mutableStateOf(false)}
                                    val position=remember { SettingPopupPositionProvider() }
                                    Box(Modifier.fillMaxWidth()) {
                                        ui.settingAction(setting,{
                                            if(setting.type()=="list")show=true else ui.repairSidebarSetting()
                                        },currentComposer)
                                        if(show)ui.settingChoices(position,{show=false},setting,currentComposer)
                                    }
                                }
                            }
                        }
                    }
                }
                ui.sidebarConfigurationColumn(controls,currentComposer)
                }
            }
            if(page==SettingsCatalog.Page.SIDEBAR)item {
                val switches: @Composable ColumnScope.() -> Unit = {
                    SidebarPolicy.BUTTONS.filter {it!="Settings"}.forEach { button ->
                        key(button) {ui.sidebarSwitch(button,ui.sidebarVisible(button),currentComposer)}
                    }
                }
                ui.sidebarConfigurationColumn(switches,currentComposer)
            }
        }
    }
    ui.sidebarConfigurationScreen(page.title(),back,content,currentComposer)
}
