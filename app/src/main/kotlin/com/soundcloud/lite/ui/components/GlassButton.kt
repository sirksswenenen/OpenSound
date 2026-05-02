package com.soundcloud.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Static helpers used by smali patches in `PlayerScreenKt$PlayerScreen$3$1$…`
 * to give the full-screen player's transport / top-bar `IconButton`s a
 * lightweight Liquid Glass look (translucent fill + faint highlight border)
 * without needing to wrap each button in a [GlassSurface] composable from
 * smali — which would require synthesising a new content-lambda class for
 * every call site.
 *
 * The effect is intentionally subtle: the goal is just to lift the buttons
 * off the artwork backdrop so they no longer read as flat solid icons.
 * Real backdrop sampling is still done by [GlassSurface] elsewhere in the
 * UI; this helper is a fast-path approximation tailored for circular
 * `IconButton`s where wrapping them in a real GlassSurface from smali is
 * impractical.
 */
object GlassButtonHelper {
    @JvmStatic
    fun glassy(modifier: Modifier): Modifier =
        modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.10f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
}
