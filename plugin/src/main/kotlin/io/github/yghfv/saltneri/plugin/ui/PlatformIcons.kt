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
    val path = remember(platform) { PathParser().parsePathString(when(platform) {
        AccountPlatform.BILIBILI -> BILIBILI
        AccountPlatform.NETEASE -> NETEASE
        AccountPlatform.QQMUSIC -> QQMUSIC
        AccountPlatform.APPLEMUSIC -> "M 9 3 L 9 16 C 3 13 2 22 8 21 C 11 21 12 19 12 16 L 12 7 L 20 5 L 20 14 C 14 11 13 20 19 19 C 22 19 23 17 23 14 L 23 1 Z"
    }).toPath() }
    val color = when(platform) {
        AccountPlatform.BILIBILI -> Color(0xFF00A1D6)
        AccountPlatform.NETEASE -> Color(0xFFD43C33)
        AccountPlatform.QQMUSIC -> Color(0xFF31C27C)
        AccountPlatform.APPLEMUSIC -> Color(0xFFFA375A)
    }
    val draw: (DrawScope) -> Unit = { scope -> with(scope) {
        if (platform == AccountPlatform.QQMUSIC || platform == AccountPlatform.APPLEMUSIC) {
            // The upstream 1024 viewport includes large margins; fit the visible brand mark.
            val bounds = path.getBounds()
            val factor = minOf(size.width / bounds.width, size.height / bounds.height)
            val canvas = drawContext.canvas
            canvas.save()
            try {
                canvas.translate((size.width - bounds.width * factor) / 2, (size.height - bounds.height * factor) / 2)
                canvas.scale(factor, factor)
                canvas.translate(-bounds.left, -bounds.top)
                drawPath(path, color)
            } finally { canvas.restore() }
        } else {
            scale(size.width / 24f, size.height / 24f, Offset.Zero) { drawPath(path, color) }
        }
    } }
    ui.canvas(Modifier.size(20.dp).semantics {
        contentDescription = platform.label()
    }, draw, currentComposer)
}

private const val BILIBILI = "M17.813 4.653h.854c1.51.054 2.769.578 3.773 1.574 1.004.995 1.524 2.249 1.56 3.76v7.36c-.036 1.51-.556 2.769-1.56 3.773s-2.262 1.524-3.773 1.56H5.333c-1.51-.036-2.769-.556-3.773-1.56S.036 18.858 0 17.347v-7.36c.036-1.511.556-2.765 1.56-3.76 1.004-.996 2.262-1.52 3.773-1.574h.774l-1.174-1.12a1.234 1.234 0 0 1-.373-.906c0-.356.124-.658.373-.907l.027-.027c.267-.249.573-.373.92-.373.347 0 .653.124.92.373L9.653 4.44c.071.071.134.142.187.213h4.267a.836.836 0 0 1 .16-.213l2.853-2.747c.267-.249.573-.373.92-.373.347 0 .662.151.929.4.267.249.391.551.391.907 0 .355-.124.657-.373.906zM5.333 7.24c-.746.018-1.373.276-1.88.773-.506.498-.769 1.13-.786 1.894v7.52c.017.764.28 1.395.786 1.893.507.498 1.134.756 1.88.773h13.334c.746-.017 1.373-.275 1.88-.773.506-.498.769-1.129.786-1.893v-7.52c-.017-.765-.28-1.396-.786-1.894-.507-.497-1.134-.755-1.88-.773zM8 11.107c.373 0 .684.124.933.373.25.249.383.569.4.96v1.173c-.017.391-.15.711-.4.96-.249.25-.56.374-.933.374s-.684-.125-.933-.374c-.25-.249-.383-.569-.4-.96V12.44c0-.373.129-.689.386-.947.258-.257.574-.386.947-.386zm8 0c.373 0 .684.124.933.373.25.249.383.569.4.96v1.173c-.017.391-.15.711-.4.96-.249.25-.56.374-.933.374s-.684-.125-.933-.374c-.25-.249-.383-.569-.4-.96V12.44c.017-.391.15-.711.4-.96.249-.249.56-.373.933-.373Z"
private const val NETEASE = "M13.046 9.388a3.919 3.919 0 0 0-.66.19c-.809.312-1.447.991-1.666 1.775a2.269 2.269 0 0 0-.074.81c.048.546.333 1.05.764 1.35a1.483 1.483 0 0 0 2.01-.286c.406-.531.355-1.183.24-1.636-.098-.387-.22-.816-.345-1.249a64.76 64.76 0 0 1-.269-.954zm-.82 10.07c-3.984 0-7.224-3.24-7.224-7.223 0-.98.226-3.02 1.884-4.822A7.188 7.188 0 0 1 9.502 5.6a.792.792 0 1 1 .587 1.472 5.619 5.619 0 0 0-2.795 2.462 5.538 5.538 0 0 0-.707 2.7 5.645 5.645 0 0 0 5.638 5.638c1.844 0 3.627-.953 4.542-2.428 1.042-1.68.772-3.931-.627-5.238a3.299 3.299 0 0 0-1.437-.777c.172.589.334 1.18.494 1.772.284 1.12.1 2.181-.519 2.989-.39.51-.956.888-1.592 1.064a3.038 3.038 0 0 1-2.58-.44 3.45 3.45 0 0 1-1.44-2.514c-.04-.467.002-.93.128-1.376.35-1.256 1.356-2.339 2.622-2.826a5.5 5.5 0 0 1 .823-.246l-.134-.505c-.37-1.371.25-2.579 1.547-3.007.329-.109.68-.145 1.025-.105.792.09 1.476.592 1.709 1.023.258.507-.096 1.153-.706 1.153a.788.788 0 0 1-.54-.213c-.088-.08-.163-.174-.259-.247a.825.825 0 0 0-.632-.166.807.807 0 0 0-.634.551c-.056.191-.031.406.02.595.07.256.159.597.217.82 1.11.098 2.162.54 2.97 1.296 1.974 1.844 2.35 4.886.892 7.233-1.197 1.93-3.509 3.177-5.889 3.177zM0 12c0 6.627 5.373 12 12 12s12-5.373 12-12S18.627 0 12 0 0 5.373 0 12Z"

// QQ Music vector adapted from the pinned NeriPlayer drawable (GPL-3.0-or-later).
private const val QQMUSIC = "M630,137.3c-35.3,10.2 -70.4,21.9 -106.2,30.3 -40.4,9.5 -82,14 -123.6,13.5a226.7,226.7 0,0 0,-37.1 2,21.5 21.5,0 0,0 -19,23.1 21.4,21.4 0,0 0,3.7 10.2c19.5,34.7 39.7,68.6 59.6,102 16.9,29.5 33.3,59.4 51.2,89 21.2,36.7 43.3,72.9 64.7,109.6 3.6,5.9 6.1,12 10.2,20.2 -12.3,-1.3 -21.5,-2.3 -30.9,-2.8a181.9,181.9 0,0 0,-79.4 12.7,168.6 168.6,0 0,0 -98.3,93.6c-9.5,23.3 -11.8,49 -6.4,73.7a144.6,144.6 0,0 0,96.5 106.5c12.5,6.7 26.1,11 40.2,12.8 25.1,2.7 50.5,1.5 75.3,-3.3 17.5,-4.4 34.7,-10.2 51.2,-17.6a160.3,160.3 0,0 0,68.8 -63.7,127.1 127.1,0 0,0 3.1,-124.2c-11.5,-22.2 -25.6,-43.4 -37.6,-64.8 -16.4,-28.6 -32.8,-57.1 -49.4,-85.7 -21.5,-37.2 -43.3,-74.2 -64.7,-111.1a336,336 0,0 1,-13.1 -25.5,467.6 467.6,0 0,0 210.1,-229.5l2.8,1.8c3.1,17.8 6.7,35.4 8.7,53.6a20.8,20.8 0,0 0,9.2 17.3,378.1 378.1,0 0,1 140.3,211.1c9.7,41.1 12.8,83.5 9,125.4a349.8,349.8 0,0 1,-19.5 94.6,460.8 460.8,0 0,1 -33.8,71.9 372.2,372.2 0,0 1,-97.5 109.2,399.7 399.7,0 0,1 -81.9,46.6 385.4,385.4 0,0 1,-98 27,395.3 395.3,0 0,1 -102.4,0 361,361 0,0 1,-122.6 -37.7,392.7 392.7,0 0,1 -174.1,-178.5 327,327 0,0 1,-30.2 -96.7A485.3,485.3 0,0 1,102.4 484.9a386.7,386.7 0,0 1,95.2 -250.4A362.6,362.6 0,0 1,321.5 140.8c22.6,-10 45.9,-18.5 69.6,-25.5a351.2,351.2 0,0 1,134.4 -10.7,476.7 476.7,0 0,1 67.3,13.5c9.7,2.5 19.4,5.5 28.7,9.2 2.9,1.4 5.7,3.1 8.4,4.8v5.1z"
