package com.soundcloud.lite.ui.components

/**
 * Static factory used by the `QueueScreenKt$QueueRow` smali patch to provide
 * a custom `positionalThreshold` lambda to `rememberSwipeToDismissBoxState`,
 * so a queue row only gets dismissed when the user has swiped through at
 * least half the row's width — instead of Material3's default 56dp threshold,
 * which makes it far too easy to accidentally remove a track with a tiny
 * horizontal flick.
 *
 * Returning a `Function1<Float, Float>` from a `@JvmStatic fun` lets smali
 * load the threshold without having to materialise a Kotlin lambda
 * directly (which would require creating a fresh anonymous class for the
 * patch).
 */
object SwipeThresholdHelper {
    @JvmStatic
    fun half(): (Float) -> Float = HalfThreshold

    private val HalfThreshold: (Float) -> Float = { totalDistance -> totalDistance * 0.5f }
}
