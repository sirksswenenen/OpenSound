package com.soundcloud.lite.api

/**
 * Where a [TrackInfo] originated. As of v0.5.0 OpenSound is SoundCloud-only,
 * so every newly-created track is [SOUNDCLOUD]. The other values are kept
 * solely to deserialize playlists persisted by earlier versions without
 * crashing; tracks of those types are filtered out at load time.
 */
enum class Provider(val display: String) {
    SOUNDCLOUD("SoundCloud"),

    /** Legacy: previously imported via Audius. Treated as unplayable. */
    AUDIUS("Audius (legacy)"),

    /** Legacy: previously imported via Invidious/YouTube. Treated as unplayable. */
    YOUTUBE("YouTube (legacy)"),

    /** Fallback for unknown / corrupt persisted values. */
    UNKNOWN("Unknown"),
}
