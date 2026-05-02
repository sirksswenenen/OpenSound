package com.soundcloud.lite.ui

import com.soundcloud.lite.api.AlternativeSource
import com.soundcloud.lite.api.TrackInfo

data class AltSourceState(
    val originalTrack: TrackInfo,
    val currentPlaylistId: String? = null,
    val results: List<AlternativeSource> = emptyList(),
    val error: String? = null,
)
