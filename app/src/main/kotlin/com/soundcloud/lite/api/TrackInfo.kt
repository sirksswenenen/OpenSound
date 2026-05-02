package com.soundcloud.lite.api

/**
 * Provider-agnostic track model. `id` stays a Long for back-compat with
 * the rest of the app; `providerId` carries the original (provider-
 * specific) string id we need to call back into the API for streaming.
 */
data class TrackInfo(
    val id: Long = 0L,
    val providerId: String = "",
    val provider: Provider = Provider.UNKNOWN,
    val title: String = "",
    val artistName: String = "",
    val artistId: String = "",
    val artworkUrl: String? = null,
    val avatarUrl: String? = null,
    val duration: Long = 0L,
    val permalink: String? = null,
    val streamHint: String? = null,
    val genre: String? = null,
    val playCount: Long? = null,
    val favoriteCount: Long? = null,
    /** Marks the track as a placeholder imported from a foreign service
     *  for which we couldn't find a playable match in our integrated
     *  providers. Such tracks render greyed-out and won't start playback. */
    val isUnplayable: Boolean = false,
)
