package com.soundcloud.lite.api

/**
 * Provider-agnostic track model. `id` stays a Long for back-compat with
 * the rest of the app; `providerId` carries the original (provider-
 * specific) string id we need to call back into the API for streaming.
 */
data class TrackInfo(
    val id: Long = 0L,
    val providerId: String = "",
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
)
