package com.soundcloud.lite.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Imports a playlist from a foreign service URL. The result is a
 * [ImportResult.Success] carrying enough metadata to render and play
 * the playlist immediately.
 *
 * Supported sources:
 *   - SoundCloud playlists (`soundcloud.com/USER/sets/SETNAME`,
 *     `soundcloud.com/USER/likes`, `on.soundcloud.com/<short>`).
 *     Tracks are returned with `provider = SOUNDCLOUD` so playback
 *     hits SoundCloud's CDN directly — no fuzzy matching across
 *     providers, no missing tracks.
 *   - YouTube playlists (`youtube.com/playlist?list=…`,
 *     `youtu.be/…?list=…`, `music.youtube.com/playlist?list=…`)
 *     via the public Invidious mesh. Tracks are returned with
 *     `provider = YOUTUBE`.
 *
 * Spotify, Yandex.Music, Deezer and Apple Music are intentionally
 * out-of-scope right now — they need either OAuth client registration
 * or service-specific scrapers, which we'll add iteratively.
 */
class PlaylistImporter(
    private val youTubeApi: YouTubeApi,
    private val soundCloudApi: SoundCloudApi,
    private val http: OkHttpClient = defaultHttp(),
) {

    sealed class ImportResult {
        data class Success(
            val title: String,
            val artworkUrl: String?,
            val tracks: List<TrackInfo>,
            val sourceName: String,
            /** Diagnostics shown to the user as a toast. e.g. "5/91
             *  tracks couldn't load metadata". */
            val warning: String? = null,
        ) : ImportResult()

        data class Error(val sourceName: String, val message: String) : ImportResult()

        data object UnsupportedUrl : ImportResult()
    }

    /** Routes the URL to the right scraper based on its host. */
    suspend fun import(url: String): ImportResult {
        val source = detect(url) ?: return ImportResult.UnsupportedUrl
        return try {
            when (source) {
                Source.SOUNDCLOUD -> importSoundCloud(url)
                Source.YOUTUBE -> importYouTube(url)
                Source.SPOTIFY -> ImportResult.Error("Spotify", "Spotify playlists not yet supported. Coming in a future build.")
                Source.YANDEX_MUSIC -> ImportResult.Error("Yandex Music", "Yandex.Music import not yet supported.")
                Source.DEEZER -> ImportResult.Error("Deezer", "Deezer import not yet supported.")
                Source.APPLE_MUSIC -> ImportResult.Error("Apple Music", "Apple Music import not yet supported.")
            }
        } catch (t: Throwable) {
            ImportResult.Error(source.display, t.message ?: t::class.java.simpleName)
        }
    }

    enum class Source(val display: String) {
        SOUNDCLOUD("SoundCloud"),
        YOUTUBE("YouTube"),
        SPOTIFY("Spotify"),
        YANDEX_MUSIC("Yandex Music"),
        DEEZER("Deezer"),
        APPLE_MUSIC("Apple Music"),
    }

    fun detect(url: String): Source? {
        val u = url.trim().lowercase()
        return when {
            u.contains("soundcloud.com/") -> Source.SOUNDCLOUD
            u.contains("youtube.com/") || u.contains("youtu.be/") || u.contains("music.youtube.com/") -> Source.YOUTUBE
            u.contains("open.spotify.com/") || u.startsWith("spotify:") -> Source.SPOTIFY
            u.contains("music.yandex.ru/") || u.contains("music.yandex.com/") -> Source.YANDEX_MUSIC
            u.contains("deezer.com/") -> Source.DEEZER
            u.contains("music.apple.com/") -> Source.APPLE_MUSIC
            else -> null
        }
    }

    // ---- SoundCloud ----

    private fun importSoundCloud(url: String): ImportResult {
        val entity = soundCloudApi.resolve(url)
            ?: return ImportResult.Error("SoundCloud", "Couldn't resolve URL — playlist may be private, deleted or unsupported")
        return when (entity) {
            is SoundCloudApi.SCEntity.Playlist -> {
                // The /resolve endpoint returns the first ~5 tracks fully
                // hydrated and the rest as id-only stubs. We batch-fetch
                // those stubs so every track is renderable and playable.
                val filledStubs = if (entity.stubIds.isNotEmpty()) {
                    soundCloudApi.getTracks(entity.stubIds).associateBy { it.id }
                } else emptyMap()
                val merged = entity.tracks.map { t ->
                    if (t.title == "Loading…") filledStubs[t.id] ?: t else t
                }
                val unfilledCount = merged.count { it.title.isBlank() || it.title == "Loading…" }
                val unplayableCount = merged.count { it.isUnplayable }
                val warning = buildString {
                    if (unfilledCount > 0) append("$unfilledCount tracks failed to load metadata. ")
                    if (unplayableCount > 0) append("$unplayableCount tracks are region-locked or unavailable.")
                }.takeIf { it.isNotBlank() }
                ImportResult.Success(
                    title = entity.title,
                    artworkUrl = entity.artworkUrl,
                    tracks = merged,
                    sourceName = "SoundCloud",
                    warning = warning,
                )
            }
            is SoundCloudApi.SCEntity.Track -> {
                // User pasted a single-track URL; treat it as a 1-track
                // playlist named after the track for convenience.
                ImportResult.Success(
                    title = entity.track.title,
                    artworkUrl = entity.track.artworkUrl,
                    tracks = listOf(entity.track),
                    sourceName = "SoundCloud",
                )
            }
            is SoundCloudApi.SCEntity.User ->
                ImportResult.Error("SoundCloud", "Imported URL is a user profile — paste a playlist or track URL instead")
        }
    }

    // ---- YouTube ----

    private suspend fun importYouTube(url: String): ImportResult {
        val playlistId = extractYouTubePlaylistId(url)
            ?: return ImportResult.Error("YouTube", "URL doesn't look like a YouTube playlist")
        val pl = youTubeApi.getPlaylist(playlistId)
            ?: return ImportResult.Error("YouTube", "Couldn't fetch playlist (Invidious not reachable)")
        return ImportResult.Success(
            title = pl.title,
            artworkUrl = pl.artworkUrl,
            tracks = pl.tracks,
            sourceName = "YouTube",
        )
    }

    // ---- Helpers ----

    private fun extractYouTubePlaylistId(url: String): String? {
        // Examples:
        //   https://www.youtube.com/playlist?list=PLABC...
        //   https://music.youtube.com/playlist?list=PLABC...
        //   https://youtu.be/VID?list=PLABC...
        val re = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
        return re.find(url)?.groupValues?.get(1)
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
