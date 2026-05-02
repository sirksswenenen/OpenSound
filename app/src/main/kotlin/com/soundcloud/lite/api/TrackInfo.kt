package com.soundcloud.lite.api

data class TrackInfo(
    val id: Long = 0L,
    val title: String = "",
    val artistName: String = "",
    val artworkUrl: String? = null,
    val duration: Long = 0L,
    val playbackCount: Long = 0L,
    val likeCount: Long = 0L,
    val genre: String = "",
    val streamUrl: String = "",
    val permalinkUrl: String = "",
    val externalVideoId: String? = null,
    val waveformUrl: String? = null,
)
