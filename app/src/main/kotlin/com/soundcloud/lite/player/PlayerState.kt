package com.soundcloud.lite.player

import com.soundcloud.lite.api.TrackInfo

data class PlayerState(
    val currentTrack: TrackInfo? = null,
    val isPlaying: Boolean = false,
    val position: Long = 0L,
    val duration: Long = 0L,
    val queue: List<TrackInfo> = emptyList(),
    val queueIndex: Int = 0,
    val repeatMode: Int = 0,
    val shuffle: Boolean = false,
    val error: String? = null,
)
