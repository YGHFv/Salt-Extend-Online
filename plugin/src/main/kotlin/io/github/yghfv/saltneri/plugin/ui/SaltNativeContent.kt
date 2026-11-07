@file:OptIn(androidx.compose.runtime.InternalComposeApi::class, com.xuncorp.spw.workshop.api.UnstableSpwWorkshopApi::class)

package io.github.yghfv.saltneri.plugin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.border
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.xuncorp.spw.workshop.api.hook.ComposableHook
import com.xuncorp.spw.workshop.api.hook.UiHookCall
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform
import io.github.yghfv.saltneri.plugin.auth.AccountLoginController
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
    val palette = ui.palette(currentComposer)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.forEach { button ->
            // Navigation rows reserve a leading icon slot; tabs must center against their full width.
            Box(Modifier.weight(1f).heightIn(min = 40.dp).clip(palette.shape())
                .background(if (button.selected) Color(palette.background().toULong()).copy(alpha = 0.65f) else Color.Transparent)
                .clickable(enabled = button.enabled, role = Role.Tab, onClick = button.click)
                .semantics { selected = button.selected }, contentAlignment = Alignment.Center) {
                if (button.selected) Box(Modifier.align(Alignment.CenterStart).padding(start = 4.dp)
                    .width(3.dp).height(16.dp).clip(CircleShape).background(Color(palette.accent().toULong())))
                ui.text(button.title, Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    palette.main().copy(color = Color((if (button.enabled) palette.text() else palette.secondary()).toULong()),
                        textAlign = TextAlign.Center), currentComposer)
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
    Box(Modifier.width(84.dp).height(56.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (thumbnail == null) NativeText(ui, "\u97f3\u4e50", true)
        else Image(thumbnail.bitmap(), "\u5c01\u9762", Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
    }
}

private enum class HeaderGlyph { SEARCH, ACCOUNT, PLAY, SORT }

@Composable
private fun HeaderIcon(ui: SaltNativeUi, label: String, glyph: HeaderGlyph, click: () -> Unit) {
    val color = Color(ui.palette(currentComposer).text().toULong())
    val play = remember { PathParser().parsePathString("M 7 3 L 21 12 L 7 21 Z").toPath() }
    val draw: (DrawScope) -> Unit = { scope -> with(scope) {
        val center = Offset(size.width * 0.43f, size.height * 0.43f)
        when (glyph) {
        HeaderGlyph.SEARCH -> {
            drawCircle(color, size.width * 0.25f, center, style = Stroke(2.dp.toPx()))
            drawLine(color, Offset(size.width * 0.62f, size.height * 0.62f), Offset(size.width * 0.88f, size.height * 0.88f), 2.dp.toPx())
        }
        HeaderGlyph.ACCOUNT -> {
            drawCircle(color, size.width * 0.16f, Offset(size.width / 2, size.height * 0.3f), style = Stroke(2.dp.toPx()))
            drawArc(color, 180f, 180f, false, Offset(size.width * 0.15f, size.height * 0.55f), Size(size.width * 0.7f, size.height * 0.7f), style = Stroke(2.dp.toPx()))
        }
        HeaderGlyph.PLAY -> scale(size.width / 24f, size.height / 24f, Offset.Zero) { drawPath(play, color) }
        HeaderGlyph.SORT -> {
            for (i in 0..2) {
                val y = size.height * (0.23f + i * 0.27f)
                drawLine(color, Offset(size.width * 0.1f, y), Offset(size.width * (0.58f - i * 0.12f), y), 2.dp.toPx())
            }
            drawLine(color, Offset(size.width * 0.78f, size.height * 0.15f), Offset(size.width * 0.78f, size.height * 0.85f), 2.dp.toPx())
            drawLine(color, Offset(size.width * 0.61f, size.height * 0.66f), Offset(size.width * 0.78f, size.height * 0.85f), 2.dp.toPx())
            drawLine(color, Offset(size.width * 0.95f, size.height * 0.66f), Offset(size.width * 0.78f, size.height * 0.85f), 2.dp.toPx())
        }
        }
    } }
    Box(Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = click).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        ui.canvas(Modifier.size(22.dp), draw, currentComposer)
    }
}

@Composable
private fun AccountHeader(ui: SaltNativeUi, platform: AccountPlatform, login: AccountLoginController.Snapshot?, action: (() -> Unit) -> Unit,
                          memory: PageMemory) {
    var menu by remember(platform, login?.revision()) { mutableStateOf(false) }
    val loggedIn = login?.phase() == AccountLoginController.Phase.LOGGED_IN
    var png by remember(platform, login?.revision()) { mutableStateOf<ByteArray?>(null) }
    LaunchedEffect(ui, platform, login?.revision(), loggedIn) {
        if (loggedIn) while (true) { png = ui.avatar(platform, login.revision()); if (png != null) break; delay(350) }
    }
    val image = remember(png) { png?.let { try { ui.thumbnail(it) } catch (_: Exception) { null } } }
    DisposableEffect(image) { onDispose { image?.close() } }
    val palette = ui.palette(currentComposer)
    val menuOffset = with(LocalDensity.current) { 44.dp.roundToPx() }
    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        NativeText(ui, name(platform), modifier = if(memory.searching) Modifier.width(105.dp) else Modifier.weight(1f), heading = true)
        if (platform == AccountPlatform.BILIBILI) {
            if(memory.searching) SearchInput(ui,memory.searchText,{memory.searchText=it},modifier=Modifier.weight(1f)) { keyword -> action {ui.desk().search(keyword);memory.view=Catalog.View.SEARCH} }
            HeaderIcon(ui, "\u641c\u7d22B\u7ad9", HeaderGlyph.SEARCH) {
                memory.searching = !memory.searching
            }
        }
        Spacer(Modifier.width(8.dp))
        Box {
            if (loggedIn && image != null) {
                Image(image.bitmap(), "\u8d26\u53f7\u83dc\u5355", Modifier.size(40.dp).clip(CircleShape).clickable { menu = !menu }, contentScale = ContentScale.Crop)
            } else HeaderIcon(ui, if (loggedIn) "\u8d26\u53f7\u83dc\u5355" else "\u767b\u5f55\u8d26\u53f7", HeaderGlyph.ACCOUNT) {
                if (loggedIn) menu = !menu else action { ui.desk().action(platform, NativeDesk.Action.LOGIN) }
            }
            if (menu && loggedIn) Popup(alignment = Alignment.TopEnd, offset = IntOffset(0, menuOffset), onDismissRequest = { menu = false },
                properties = PopupProperties(focusable = true)) {
                Column(Modifier.width(200.dp).background(Color(palette.pageBackground().toULong()).copy(alpha = 1f), palette.shape())
                    .border(1.dp, Color(palette.secondary().toULong()).copy(alpha = 0.3f), palette.shape()).padding(8.dp)) {
                    val nickname = login.profile().name()
                    if (nickname.isNotEmpty()) NativeText(ui, nickname, modifier = Modifier.padding(8.dp))
                    NativeRow(ui, "\u5207\u6362\u8d26\u6237", click = { menu = false; action { ui.desk().action(platform, NativeDesk.Action.SWITCH_ACCOUNT) } })
                    NativeRow(ui, "\u9000\u51fa\u767b\u5f55", click = { menu = false; action { ui.desk().action(platform, NativeDesk.Action.LOGOUT) } })
                }
            }
        }
    }
}

@Composable
private fun SearchInput(ui: SaltNativeUi, keyword: String, change: (String) -> Unit, inside: Boolean = false, modifier: Modifier = Modifier, submit: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    val palette = ui.palette(currentComposer)
    val search = { if (inside || keyword.isNotBlank()) submit(keyword.trim()) }
    LaunchedEffect(Unit) { focus.requestFocus() }
        BasicTextField(value = keyword, onValueChange = { if (it.length <= 100 && it.none { c -> c.isISOControl() }) change(it) },
            modifier = modifier.border(1.dp, Color(palette.secondary().toULong()).copy(alpha = 0.5f), palette.shape())
                .padding(horizontal=8.dp,vertical=10.dp).focusRequester(focus).semantics { contentDescription = if(inside) "\u5408\u96c6\u5185\u641c\u7d22\u8f93\u5165" else "B\u7ad9\u641c\u7d22\u8f93\u5165" },
            textStyle = palette.main().copy(color = Color(palette.text().toULong())), singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search), keyboardActions = KeyboardActions(onSearch = { search() }),
            cursorBrush = SolidColor(Color(palette.accent().toULong())), decorationBox = { field ->
                Box { if (keyword.isEmpty()) NativeText(ui, if(inside) "\u5408\u96c6\u5185\u641c\u7d22" else "\u641c\u7d22 B\u7ad9", true); field() }
            })
}

private class PageMemory(platform: AccountPlatform) {
    var view by mutableStateOf(if(platform == AccountPlatform.BILIBILI) Catalog.View.MINE else Catalog.View.DISCOVER)
    var selectedRow by mutableStateOf("")
    var searching by mutableStateOf(false)
    var searchText by mutableStateOf("")
    var localSearch by mutableStateOf(false)
    var localText by mutableStateOf("")
    val list = LazyListState()
    var location: List<Any?>? = null
}

@Composable
private fun PlaylistTitle(ui: SaltNativeUi, platform: AccountPlatform, title: String, count: Long, action: (() -> Unit) -> Unit, memory: PageMemory) {
    var menu by remember { mutableStateOf(false) }
    val palette=ui.palette(currentComposer)
    val offset=with(LocalDensity.current) { 40.dp.roundToPx() }
    Row(Modifier.fillMaxWidth(), verticalAlignment=Alignment.CenterVertically) {
        NativeText(ui, title + if(count>=0) " ($count)" else "", modifier=Modifier.weight(if(memory.localSearch) 0.6f else 1f))
        if(memory.localSearch) SearchInput(ui,memory.localText,{memory.localText=it},true,Modifier.weight(1f)) { keyword -> action {ui.desk().filter(keyword)} }
        HeaderIcon(ui,"\u641c\u7d22\u5408\u96c6",HeaderGlyph.SEARCH) {
            memory.localSearch = !memory.localSearch
        }
        HeaderIcon(ui,"\u5168\u90e8\u64ad\u653e",HeaderGlyph.PLAY) { action {ui.desk().playPlaylist()} }
        Box {
            HeaderIcon(ui,"\u6392\u5e8f",HeaderGlyph.SORT) { menu=!menu }
            if(menu) Popup(alignment=Alignment.TopEnd,offset=IntOffset(0,offset),onDismissRequest={menu=false},properties=PopupProperties(focusable=true)) {
                Column(Modifier.width(180.dp).background(Color(palette.pageBackground().toULong()).copy(alpha=1f),palette.shape()).padding(8.dp)) {
                    val sorts = listOf(Catalog.Sort.DEFAULT to "\u9ed8\u8ba4\u987a\u5e8f",Catalog.Sort.REVERSE to "\u5012\u5e8f",Catalog.Sort.TITLE to "\u6807\u9898",Catalog.Sort.ARTIST to "\u4f5c\u8005",Catalog.Sort.DURATION to "\u65f6\u957f") +
                        if (platform == AccountPlatform.BILIBILI) listOf(Catalog.Sort.PLAY_COUNT to "\u64ad\u653e\u91cf") else emptyList()
                    sorts.forEach { (sort,label) ->
                        NativeRow(ui,label,click={menu=false;action {ui.desk().sort(sort)}})
                    }
                }
            }
        }
    }
}

@Composable
private fun OnlinePage(ui: SaltNativeUi, platform: AccountPlatform) {
    val memory=remember(ui,platform) { ui.pageMemory(platform) { PageMemory(platform) } as PageMemory }
    var state by remember(ui, platform) { mutableStateOf<NativeDesk.State?>(if(ui.available()) ui.desk().snapshot(platform) else null) }
    var error by remember(ui, platform) { mutableStateOf("") }
    var enabled by remember(ui) { mutableStateOf(ui.available()) }
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
    val catalog = state?.catalog()?.takeIf { it["platform"] == platform.name }
    val playback = state?.playback()
    val login = state?.login()
    val rows = catalog?.get("items") as? List<*>
    val listState = memory.list
    val location=listOf(catalog?.get("view"),catalog?.get("page"),catalog?.get("title"),catalog?.get("filter"),catalog?.get("sort"))
    LaunchedEffect(location) {
        // Schedule the next layout rather than forcing remeasure while LazyColumn applies new items.
        if(memory.location!=location) { memory.location=location; listState.requestScrollToItem(0) }
    }
    // Salt paints the window backdrop below navigation; an opaque page would hide its effects.
        LazyColumn(Modifier.fillMaxSize().padding(horizontal = 20.dp), state = listState,
            contentPadding = PaddingValues(top = 28.dp, bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                AccountHeader(ui, platform, login, action, memory)
            }
            if (!enabled) {
                item { NativeText(ui, "\u539f\u751f\u9875\u9762\u5df2\u505c\u7528\uff0c\u8bf7\u8fd4\u56de\u5e76\u68c0\u67e5\u6a21\u7ec4\u6388\u6743\u3002") }
            } else {
                if (login?.phase() != AccountLoginController.Phase.LOGGED_IN) item {
                    Column(Modifier.padding(8.dp)) {
                        if (login?.phase()?.name in listOf("CREATING", "WAITING_SCAN", "WAITING_CONFIRMATION", "VERIFICATION_REQUIRED", "WEB_LOGIN", "EXPIRED", "ERROR")) {
                            NativeText(ui, login?.status() ?: "", modifier = Modifier.padding(8.dp))
                        }
                        if (platform == AccountPlatform.NETEASE && login?.phase()?.name in listOf("VERIFICATION_REQUIRED", "ERROR", "EXPIRED")) {
                            NativeRow(ui, "\u5b98\u65b9\u7f51\u9875\u767b\u5f55", click = { action { ui.desk().action(platform, NativeDesk.Action.WEB_LOGIN) } })
                        }
                        if (login?.phase() == AccountLoginController.Phase.WEB_LOGIN) {
                            NativeRow(ui, "\u53d6\u6d88\u7f51\u9875\u767b\u5f55", click = { action { ui.desk().action(platform, NativeDesk.Action.CANCEL_LOGIN) } })
                        }
                        login?.qrContent()?.let { uri ->
                            Qr(ui, uri.toASCIIString())
                            NativeRow(ui, "\u53d6\u6d88\u5f85\u786e\u8ba4\u767b\u5f55", click = {
                                action { ui.desk().action(platform, NativeDesk.Action.CANCEL_LOGIN) }
                            })
                        }
                    }
                }
                item {
                    val views = listOf(Catalog.View.DISCOVER to "\u53d1\u73b0",
                        Catalog.View.MINE to if (platform == AccountPlatform.NETEASE) "\u6211\u7684\u6b4c\u5355 / \u559c\u6b22" else "\u6536\u85cf",
                        Catalog.View.SAVED to "\u8ba2\u9605")
                    NativeButtons(ui, views.map { (destination, label) ->
                        NativeButton(label, memory.view == destination) {
                            action { ui.desk().browse(platform, destination); memory.view = destination; memory.localSearch=false; memory.localText="" }
                        }
                    })
                }
                if(platform==AccountPlatform.BILIBILI && catalog?.get("view") in listOf("DISCOVER","MUSIC_HOT","MUSIC_ORIGINAL")) item {
                    NativeButtons(ui, listOf(Catalog.View.DISCOVER to "\u70ed\u95e8\u89c6\u9891",Catalog.View.MUSIC_HOT to "\u97f3\u4e50\u70ed\u95e8\u699c",Catalog.View.MUSIC_ORIGINAL to "\u539f\u521b\u699c").map { (destination,label) ->
                        NativeButton(label, catalog?.get("view")==destination.name) {
                            action {ui.desk().browse(platform,destination);memory.view=Catalog.View.DISCOVER}
                        }
                    })
                }
                if (playback?.get("active") == true && (playback["error"] as? String).orEmpty().isNotEmpty()) item {
                    NativeText(ui, playback["error"] as String)
                }
                item {
                    if (error.isNotEmpty()) NativeText(ui, error)
                    val catalogError = (catalog?.get("error") as? String).orEmpty()
                    if (catalogError.isNotEmpty()) NativeText(ui, catalogError)
                    val title=catalog?.get("title") as? String ?: ""
                    val folder=catalog?.get("view") in listOf("FOLDER","COLLECTION")
                    Column {
                        if(folder) PlaylistTitle(ui,platform,title,(catalog?.get("totalCount") as? Number)?.toLong() ?: -1,action,memory)
                        else NativeText(ui,title)
                        if(catalog?.get("loadingAll")==true) NativeText(ui,"\u6b63\u5728\u8bfb\u53d6\u5b8c\u6574\u5408\u96c6\u2026",true)
                    }
                }
                rows?.forEach { value ->
                    val row = value as Map<*, *>
                    item(key = row["token"] as String) {
                        val token = row["token"] as String
                        val track = row["kind"] == "TRACK"
                        Row(Modifier.fillMaxWidth().background(if (memory.selectedRow == token) Color(palette.background().toULong()).copy(alpha = 0.65f) else Color.Transparent, palette.shape())
                            .combinedClickable(enabled = row["enabled"] == true, onDoubleClick = if (track) ({ action { ui.desk().playRow(token); memory.selectedRow = token } }) else null,
                                onClick = { action { if (track) memory.selectedRow = token else {ui.desk().choose(token);memory.localSearch=false;memory.localText=""} } }).padding(8.dp),
                            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Box(Modifier.width(3.dp).height(32.dp).background(if (memory.selectedRow == token) Color(palette.accent().toULong()) else Color.Transparent))
                            Spacer(Modifier.width(8.dp))
                            Cover(ui, token)
                            Column(Modifier.weight(1f).padding(start = 16.dp, end = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                val count=(row["count"] as? Number)?.toLong() ?: -1
                                val title = (row["title"] as String) + if(!track && count>=0) " ($count)" else ""
                                NativeText(ui, title, modifier = Modifier.fillMaxWidth())
                                NativeText(ui, row["subtitle"] as String, true, Modifier.fillMaxWidth())
                            }
                        }
                    }
                }
                val page = (catalog?.get("page") as? Number)?.toInt() ?: 1
                val total = (catalog?.get("totalPages") as? Number)?.toInt() ?: 0
                val previous = page > 1
                val next = catalog?.get("more") == true
                if (previous || next) item(key = "pagination") {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) { NativeRow(ui, "\u4e0a\u4e00\u9875", enabled = previous && catalog?.get("loaded") == true,
                            click = { action { ui.desk().page(-1) } }) }
                        NativeText(ui, "$page/${if (total > 0) total.toString() else "?"}", modifier = Modifier.padding(horizontal = 16.dp))
                        Box(Modifier.weight(1f)) { NativeRow(ui, "\u4e0b\u4e00\u9875", enabled = next && catalog?.get("loaded") == true,
                            click = { action { ui.desk().page(1) } }) }
                    }
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
