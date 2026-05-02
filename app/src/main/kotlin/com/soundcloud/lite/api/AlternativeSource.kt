package com.soundcloud.lite.api

data class AlternativeSource(
    val instance: String = "",
    val title: String = "",
    val uploader: String = "",
    val durationSec: Int = 0,
    val thumbnailUrl: String = "",
    val videoId: String = "",
) {
    val tools: String get() = instance
}
