package com.soundcloud.lite.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Small filled dot used in the track row trailing slot to indicate that a
 * track has finished downloading. Replaces the legacy 24dp green download
 * arrow with an 8dp accent-coloured circle. Drawn by smali patch
 * `TrackItemKt$TrackItem$1$3$2.invoke` when downloadState.status == "completed".
 */
@Composable
fun DownloadDot(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(8.dp)
            .background(color = color, shape = CircleShape)
    )
}

/**
 * Static helpers used by the `TrackItemKt$TrackItem$1$3$2.invoke` smali patch
 * to size the trailing icon. We can't easily produce a `Modifier.size(8.dp)`
 * directly in smali because [androidx.compose.ui.unit.Dp] is an inline
 * class with a hash-mangled `size-…` overload, so the safest way is to keep
 * the call on the JVM side and have smali invoke a static getter.
 */
object DownloadDotModifier {
    @JvmStatic
    fun smallDotSize(): Modifier = Modifier.size(8.dp)
}
