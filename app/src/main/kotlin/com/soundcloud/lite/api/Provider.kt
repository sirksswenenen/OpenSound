package com.soundcloud.lite.api

/**
 * Where a [TrackInfo] originated. Determines which API client to call
 * to resolve a playable stream URL at play time, and which badge to
 * show on the track row in the UI.
 */
enum class Provider(val display: String) {
    AUDIUS("Audius"),
    YOUTUBE("YouTube"),
    UNKNOWN("Unknown"),
}
