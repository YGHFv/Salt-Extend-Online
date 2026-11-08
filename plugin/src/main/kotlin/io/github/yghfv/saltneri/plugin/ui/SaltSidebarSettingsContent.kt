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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.unit.dp
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
                    saved.forEach { child, original -> ui.captionModifier(child,original) }
                    saved.clear()
                }
            }) }
            DisposableEffect(ui) {
                onDispose {
                    saved.forEach { child, original ->
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

@Composable
fun SidebarConfigurationPage(ui: SaltNativeUi) {
    val back: @Composable () -> Unit = {ui.secondaryBack({ui.settingsBack()},Modifier,currentComposer)}
    val content: @Composable BoxScope.(PaddingValues) -> Unit = { insets ->
        LazyColumn(Modifier.fillMaxSize(),contentPadding=insets) {
            item {
                ui.sidebarConfigurationCaption("\u8bbe\u7f6e\u5165\u53e3\u59cb\u7ec8\u4fdd\u7559\uff0c\u5176\u4ed6\u6309\u94ae\u53ef\u81ea\u7531\u663e\u9690\u3002",currentComposer)
            }
            item {
                val switches: @Composable ColumnScope.() -> Unit = {
                    SidebarPolicy.BUTTONS.filter {it!="Settings"}.forEach { button ->
                        key(button) {ui.sidebarSwitch(button,ui.sidebarVisible(button),currentComposer)}
                    }
                }
                ui.sidebarConfigurationColumn(switches,currentComposer)
            }
        }
    }
    ui.sidebarConfigurationScreen("\u4fa7\u680f\u7ba1\u7406",back,content,currentComposer)
}
