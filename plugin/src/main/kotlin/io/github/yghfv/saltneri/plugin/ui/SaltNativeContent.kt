@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.yghfv.saltneri.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform
import io.github.yghfv.saltneri.plugin.library.Catalog
import io.nayuki.qrcodegen.QrCode
import kotlinx.coroutines.delay

/** Compiled with the host Kotlin/Compose ABI; only private bindings use reflection. */
class SaltNativeContent(private val ui: SaltNativeUi) : ComposableHook() {
    @Composable
    override fun Content(call: UiHookCall) {
        if (ui.isMusicLibrary(call.getArgument(0))) {
            // A LazyColumn item can contain multiple vertically placed elements.
            Column { SidebarEntries(ui) }
        }
    }
    companion object {
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        fun provider(ui: SaltNativeUi, original: Any): Any {
            val fallback = original as @Composable (Any) -> Unit
            val content: @Composable (Any) -> Unit = { key ->
                val platform = ui.dispatch(key)
                if (platform == null) fallback(key)
                else (ui.entry(platform) as @Composable (Any) -> Unit)(key)
            }
            return content
        }
        @JvmStatic
        fun entry(ui: SaltNativeUi, platform: AccountPlatform): Any {
            val content: @Composable (Any) -> Unit = {
                val style = ui.sidebarStyle()
                if (style == null) NativeText(ui, "\u8bf7\u4ece\u5de6\u4fa7 Bilibili / \u7f51\u6613\u4e91\u91cd\u65b0\u8fdb\u5165\u9875\u9762\u3002")
                else CompositionLocalProvider(ui.styleLocal().provides(style)) { OnlinePage(ui, platform) }
            }
            return content
        }
    }
}

@Composable
private fun SidebarEntries(ui: SaltNativeUi) {
    ui.navigator(currentComposer)
    val style = ui.readSidebarStyle(currentComposer)
    SideEffect { ui.publishSidebarStyle(style) }
    var enabled by remember { mutableStateOf(ui.available()) }
    LaunchedEffect(ui) {
        while (true) { enabled = ui.available(); delay(500) }
    }
    if (enabled) {
        val navigator = ui.navigator(currentComposer)
        // Salt's back stack is snapshot state, so selection follows native back/forward.
        val route = remember(navigator) { navigator.javaClass.getMethod("getNavBackStack") }
        val selected = (route.invoke(navigator) as List<*>).lastOrNull()?.let {
            if (it.javaClass.name == SaltNativeUi.MOD_ROUTE) ui.platform(it) else null
        }
        AccountPlatform.entries.forEach { platform ->
            NativeRow(ui, name(platform), selected == platform, true, { ui.open(platform) }) {
                PlatformIcon(ui, platform)
            }
        }
    }
}

private fun name(platform: AccountPlatform) = if (platform == AccountPlatform.BILIBILI) "Bilibili" else "\u7f51\u6613\u4e91"

@Composable
private fun NativeText(ui: SaltNativeUi, text: String, secondary: Boolean = false, modifier: Modifier = Modifier, heading: Boolean = false) {
    val palette = ui.palette(currentComposer)
    var style: TextStyle = (if (secondary) palette.sub() else palette.main()).copy(
        // Host getters return packed Compose colors, not 32-bit ARGB values accepted by Color(Long).
        color = Color((if (secondary) palette.secondary() else palette.text()).toULong()))
    if (heading) style = style.copy(fontSize = 28.sp, fontWeight = FontWeight.Bold)
    ui.text(text, modifier, style, currentComposer)
}

@Composable
private fun NativeRow(ui: SaltNativeUi, title: String, selected: Boolean = false, enabled: Boolean = true,
                      click: () -> Unit, icon: @Composable BoxScope.() -> Unit = {}) {
    ui.row(title, selected, Modifier.fillMaxWidth().clickable(enabled = enabled, onClick = click), icon, currentComposer)
}

private data class NativeButton(val title: String, val selected: Boolean = false, val enabled: Boolean = true, val click: () -> Unit)

@Composable
private fun NativeButtons(ui: SaltNativeUi, buttons: List<NativeButton>) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 620.dp) Column {
            buttons.forEach { NativeRow(ui, it.title, it.selected, it.enabled, it.click) }
        } else Row {
            buttons.forEach { button ->
                Box(Modifier.weight(1f)) { NativeRow(ui, button.title, button.selected, button.enabled, button.click) }
            }
        }
    }
}

@Composable
private fun Cover(ui: SaltNativeUi, token: String) {
    var png by remember(token) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(ui, token) {
        while (true) { png = ui.cover(token); if (png != null) break; delay(350) }
    }
    val thumbnail = remember(png) { png?.let { try { ui.thumbnail(it) } catch (_: Exception) { null } } }
    DisposableEffect(thumbnail) { onDispose { thumbnail?.close() } }
    val palette = ui.palette(currentComposer)
    Box(Modifier.size(56.dp).background(Color(palette.background().toULong()).copy(alpha = 0.35f), palette.shape()), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (thumbnail == null) NativeText(ui, "\u97f3\u4e50", true)
        else Image(thumbnail.bitmap(), "\u5c01\u9762", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun OnlinePage(ui: SaltNativeUi, platform: AccountPlatform) {
    var state by remember(ui, platform) { mutableStateOf<NativeDesk.State?>(null) }
    var error by remember(ui, platform) { mutableStateOf("") }
    var enabled by remember(ui) { mutableStateOf(ui.available()) }
    var view by remember(platform) { mutableStateOf(if (platform == AccountPlatform.BILIBILI) Catalog.View.MINE else Catalog.View.DISCOVER) }
    var selectedRow by remember(platform) { mutableStateOf("") }
    val action: (() -> Unit) -> Unit = { block ->
        try { block(); error = "" }
        catch (failure: Exception) { error = "\u64cd\u4f5c\u672a\u5b8c\u6210\uff08${failure.javaClass.simpleName}\uff09\uff0c\u8bf7\u68c0\u67e5\u6388\u6743\u548c\u5b9e\u9a8c\u9002\u914d\u72b6\u6001\u3002" }
    }
    LaunchedEffect(ui, platform) {
        while (true) {
            enabled = ui.available()
            state = if (enabled) try { ui.desk().snapshot(platform) } catch (_: Exception) { null } else null
            delay(350)
        }
    }
    DisposableEffect(ui, platform) {
        val lease = ui.pageEntered(platform)
        onDispose { ui.pageLeft(lease); ui.desk().leave(); ui.clearCovers() }
    }
    val palette = ui.palette(currentComposer)
    val catalog = state?.catalog()
    val playback = state?.playback()
    val login = state?.login()
    val rows = if (catalog?.get("platform") == platform.name) catalog["items"] as? List<*> else null
    // Salt paints the window backdrop below navigation; an opaque page would hide its effects.
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 28.dp, bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            NativeText(ui, name(platform), modifier = Modifier.padding(bottom = 12.dp), heading = true)
            NativeText(ui, "\u53d1\u73b0 / \u4e2a\u4eba\u6b4c\u5355 / \u6536\u85cf", true)
            NativeText(ui, "\u5355\u51fb\u6253\u5f00\u6b4c\u5355\uff0c\u53cc\u51fb\u66f2\u76ee\u76f4\u63a5\u64ad\u653e\u3002", true)
        }
        if (!enabled) {
            item { NativeText(ui, "\u539f\u751f\u9875\u9762\u5df2\u505c\u7528\uff0c\u8bf7\u8fd4\u56de\u5e76\u68c0\u67e5\u6a21\u7ec4\u6388\u6743\u3002") }
        } else {
            item {
                Column(Modifier.padding(8.dp)) {
                    NativeText(ui, login?.status() ?: "\u8bfb\u53d6\u672c\u63d2\u4ef6\u8d26\u53f7\u72b6\u6001", true, Modifier.padding(8.dp))
                    NativeButtons(ui, listOf(
                        NativeButton("\u626b\u7801\u767b\u5f55") { action { ui.desk().action(platform, NativeDesk.Action.LOGIN) } },
                        NativeButton("\u9000\u51fa\u8d26\u53f7", enabled = login?.phase()?.name == "LOGGED_IN") {
                            action { ui.desk().action(platform, NativeDesk.Action.LOGOUT) }
                        }
                    ))
                    login?.qrContent()?.let { uri ->
                        Qr(ui, uri.toASCIIString())
                        NativeRow(ui, "\u53d6\u6d88\u5f85\u786e\u8ba4\u767b\u5f55", click = {
                            action { ui.desk().action(platform, NativeDesk.Action.CANCEL_LOGIN) }
                        })
                    }
                }
            }
            item {
                val views = listOf(Catalog.View.DISCOVER to if (platform == AccountPlatform.BILIBILI) "\u53d1\u73b0\uff08\u6682\u672a\u63a5\u5165\uff09" else "\u53d1\u73b0",
                    Catalog.View.MINE to if (platform == AccountPlatform.NETEASE) "\u6211\u7684\u6b4c\u5355 / \u559c\u6b22" else "\u521b\u5efa\u7684\u6536\u85cf",
                    Catalog.View.SAVED to "\u6536\u85cf / \u8ba2\u9605")
                NativeButtons(ui, views.map { (destination, label) ->
                    NativeButton(label, view == destination, enabled = platform != AccountPlatform.BILIBILI || destination != Catalog.View.DISCOVER) {
                        action { ui.desk().browse(platform, destination); view = destination }
                    }
                })
            }
            if (playback?.get("active") == true) item {
                NativeText(ui, playback["status"] as? String ?: "", true)
            }
            item {
                if (error.isNotEmpty()) NativeText(ui, error)
                NativeText(ui, catalog?.get("status") as? String ?: "\u8bf7\u9009\u62e9\u680f\u76ee", true)
                NativeText(ui, catalog?.get("title") as? String ?: "")
            }
            rows?.forEach { value ->
                val row = value as Map<*, *>
                item(key = row["token"] as String) {
                    val token = row["token"] as String
                    val track = row["kind"] == "TRACK"
                    Row(Modifier.fillMaxWidth().background(if (selectedRow == token) Color(palette.background().toULong()).copy(alpha = 0.65f) else Color.Transparent, palette.shape())
                        .combinedClickable(enabled = row["enabled"] == true, onDoubleClick = if (track) ({ action { ui.desk().playRow(token); selectedRow = token } }) else null,
                            onClick = { action { if (track) selectedRow = token else ui.desk().choose(token) } }).padding(8.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Box(Modifier.width(3.dp).height(32.dp).background(if (selectedRow == token) Color(palette.accent().toULong()) else Color.Transparent))
                        Spacer(Modifier.width(8.dp))
                        Cover(ui, token)
                        Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            val title = row["title"] as String
                            NativeText(ui, title, modifier = Modifier.fillMaxWidth())
                            NativeText(ui, row["subtitle"] as String, true, Modifier.fillMaxWidth())
                        }
                    }
                }
            }
            item {
                val previous = (catalog?.get("page") as? Number)?.toInt()?.let { it > 1 } == true
                val next = catalog?.get("more") == true
                if (rows != null && (previous || next)) NativeButtons(ui, listOf(
                    NativeButton("\u4e0a\u4e00\u9875", enabled = previous) { action { ui.desk().page(-1) } },
                    NativeButton("\u4e0b\u4e00\u9875", enabled = next) { action { ui.desk().page(1) } }
                ))
                NativeText(ui, "\u6bcf\u9875\u6700\u591a 20 \u9879\u3002\u5c01\u9762\u6309\u53ef\u89c1\u9879\u52a0\u8f7d\uff1b\u6682\u65e0\u6574\u5355\u81ea\u52a8\u8fde\u64ad\u548c\u6b4c\u8bcd\u3002", true)
            }
        }
    }
}

@Composable
private fun Qr(ui: SaltNativeUi, payload: String) {
    val qr = remember(payload) {
        require(payload.length <= 2048)
        QrCode.encodeText(payload, QrCode.Ecc.MEDIUM)
    }
    val draw: (DrawScope) -> Unit = { scope ->
        with(scope) {
            drawRect(Color.White)
            val cell = kotlin.math.floor(kotlin.math.min(size.width, size.height) / (qr.size + 8))
            val origin = Offset((size.width - cell * (qr.size + 8)) / 2, (size.height - cell * (qr.size + 8)) / 2)
            for (y in 0 until qr.size) for (x in 0 until qr.size) if (qr.getModule(x, y)) {
                drawRect(Color.Black, origin + Offset((x + 4) * cell, (y + 4) * cell), Size(cell, cell))
            }
        }
    }
    ui.canvas(Modifier.size(240.dp).semantics { contentDescription = "\u5b98\u65b9 App \u767b\u5f55\u4e8c\u7ef4\u7801" }, draw, currentComposer)
}
