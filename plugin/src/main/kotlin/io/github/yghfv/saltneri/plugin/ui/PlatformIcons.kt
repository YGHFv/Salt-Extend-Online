@file:OptIn(androidx.compose.runtime.InternalComposeApi::class)

package io.github.yghfv.saltneri.plugin.ui

import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.yghfv.saltneri.plugin.auth.AccountPlatform

// Simple Icons 16.36.0 (CC0); brand marks remain the property of their owners.
@Composable
internal fun PlatformIcon(ui: SaltNativeUi, platform: AccountPlatform) {
    val bili = platform == AccountPlatform.BILIBILI
    val path = remember(platform) { PathParser().parsePathString(if (bili) BILIBILI else NETEASE).toPath() }
    val color = if (bili) Color(0xFF00A1D6) else Color(0xFFD43C33)
    val draw: (DrawScope) -> Unit = { scope -> with(scope) {
        scale(size.width / 24f, size.height / 24f, Offset.Zero) { drawPath(path, color) }
    } }
    ui.canvas(Modifier.size(20.dp).semantics {
        contentDescription = if (bili) "Bilibili" else "NetEase Cloud Music"
    }, draw, currentComposer)
}

private const val BILIBILI = "M17.813 4.653h.854c1.51.054 2.769.578 3.773 1.574 1.004.995 1.524 2.249 1.56 3.76v7.36c-.036 1.51-.556 2.769-1.56 3.773s-2.262 1.524-3.773 1.56H5.333c-1.51-.036-2.769-.556-3.773-1.56S.036 18.858 0 17.347v-7.36c.036-1.511.556-2.765 1.56-3.76 1.004-.996 2.262-1.52 3.773-1.574h.774l-1.174-1.12a1.234 1.234 0 0 1-.373-.906c0-.356.124-.658.373-.907l.027-.027c.267-.249.573-.373.92-.373.347 0 .653.124.92.373L9.653 4.44c.071.071.134.142.187.213h4.267a.836.836 0 0 1 .16-.213l2.853-2.747c.267-.249.573-.373.92-.373.347 0 .662.151.929.4.267.249.391.551.391.907 0 .355-.124.657-.373.906zM5.333 7.24c-.746.018-1.373.276-1.88.773-.506.498-.769 1.13-.786 1.894v7.52c.017.764.28 1.395.786 1.893.507.498 1.134.756 1.88.773h13.334c.746-.017 1.373-.275 1.88-.773.506-.498.769-1.129.786-1.893v-7.52c-.017-.765-.28-1.396-.786-1.894-.507-.497-1.134-.755-1.88-.773zM8 11.107c.373 0 .684.124.933.373.25.249.383.569.4.96v1.173c-.017.391-.15.711-.4.96-.249.25-.56.374-.933.374s-.684-.125-.933-.374c-.25-.249-.383-.569-.4-.96V12.44c0-.373.129-.689.386-.947.258-.257.574-.386.947-.386zm8 0c.373 0 .684.124.933.373.25.249.383.569.4.96v1.173c-.017.391-.15.711-.4.96-.249.25-.56.374-.933.374s-.684-.125-.933-.374c-.25-.249-.383-.569-.4-.96V12.44c.017-.391.15-.711.4-.96.249-.249.56-.373.933-.373Z"
private const val NETEASE = "M13.046 9.388a3.919 3.919 0 0 0-.66.19c-.809.312-1.447.991-1.666 1.775a2.269 2.269 0 0 0-.074.81c.048.546.333 1.05.764 1.35a1.483 1.483 0 0 0 2.01-.286c.406-.531.355-1.183.24-1.636-.098-.387-.22-.816-.345-1.249a64.76 64.76 0 0 1-.269-.954zm-.82 10.07c-3.984 0-7.224-3.24-7.224-7.223 0-.98.226-3.02 1.884-4.822A7.188 7.188 0 0 1 9.502 5.6a.792.792 0 1 1 .587 1.472 5.619 5.619 0 0 0-2.795 2.462 5.538 5.538 0 0 0-.707 2.7 5.645 5.645 0 0 0 5.638 5.638c1.844 0 3.627-.953 4.542-2.428 1.042-1.68.772-3.931-.627-5.238a3.299 3.299 0 0 0-1.437-.777c.172.589.334 1.18.494 1.772.284 1.12.1 2.181-.519 2.989-.39.51-.956.888-1.592 1.064a3.038 3.038 0 0 1-2.58-.44 3.45 3.45 0 0 1-1.44-2.514c-.04-.467.002-.93.128-1.376.35-1.256 1.356-2.339 2.622-2.826a5.5 5.5 0 0 1 .823-.246l-.134-.505c-.37-1.371.25-2.579 1.547-3.007.329-.109.68-.145 1.025-.105.792.09 1.476.592 1.709 1.023.258.507-.096 1.153-.706 1.153a.788.788 0 0 1-.54-.213c-.088-.08-.163-.174-.259-.247a.825.825 0 0 0-.632-.166.807.807 0 0 0-.634.551c-.056.191-.031.406.02.595.07.256.159.597.217.82 1.11.098 2.162.54 2.97 1.296 1.974 1.844 2.35 4.886.892 7.233-1.197 1.93-3.509 3.177-5.889 3.177zM0 12c0 6.627 5.373 12 12 12s12-5.373 12-12S18.627 0 12 0 0 5.373 0 12Z"
