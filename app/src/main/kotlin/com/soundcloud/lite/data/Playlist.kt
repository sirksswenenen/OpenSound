package com.soundcloud.lite.data

import com.soundcloud.lite.api.TrackInfo

data class Playlist(
    val id: String = "",
    val title: String = "",
    val artistName: String? = null,
    val artworkUrl: String? = null,
    val sourceUrl: String? = null,
    val tracks: List<TrackInfo> = emptyList(),
) {
    companion object
}
