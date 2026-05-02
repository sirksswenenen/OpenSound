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
    /** Non-null while the queue is shuffled; holds the pre-shuffle order so
     *  a second tap of the shuffle button can restore the original list. */
    val shuffledOrder: List<TrackInfo>? = null,
)
