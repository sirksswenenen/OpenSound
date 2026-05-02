package com.soundcloud.lite.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Imports a playlist (just metadata: title + track names + artists)
 * from a foreign service URL. The actual playable streams are resolved
 * later by matching each imported track against our integrated
 * providers (Audius / YouTube).
 *
 * Supported sources:
 *   - SoundCloud user playlists (soundcloud.com/USER/sets/SETNAME)
 *   - YouTube playlists (youtube.com/playlist?list=… or youtu.be/…)
 *
 * Spotify, Yandex.Music, Deezer and Apple Music are intentionally
 * out-of-scope right now — they need either OAuth client registration
 * or service-specific scrapers, which we'll add iteratively.
 */
class PlaylistImporter(
    private val youTubeApi: YouTubeApi,
    private val http: OkHttpClient = defaultHttp(),
) {

    sealed class ImportResult {
        data class Success(
            val title: String,
            val artworkUrl: String?,
            val tracks: List<TrackInfo>,
            val sourceName: String,
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
        val html = httpGet(url, asBrowser = true)
            ?: return ImportResult.Error("SoundCloud", "Couldn't fetch playlist page")
        val ld = SC_JSONLD_RE.find(html)?.groupValues?.get(1)?.trim()
            ?: return ImportResult.Error("SoundCloud", "Playlist metadata block not found in page")
        return parseScJsonLd(ld)
    }

    private fun parseScJsonLd(json: String): ImportResult {
        // Hand-rolled tiny JSON walker to avoid dragging in an extra Moshi
        // adapter for what's basically a single doc with a known shape.
        val name = extractJsonString(json, "\"name\"") ?: "SoundCloud Playlist"
        val image = extractJsonString(json, "\"image\"")
        val tracks = mutableListOf<TrackInfo>()
        // The "track" array contains music recording entries; each has
        // {"@id":"soundcloud:tracks:NNN","name":"...","duration":"PT0H3M14S",...}.
        val trackBlocks = SC_TRACK_BLOCK_RE.findAll(json).toList()
        for (m in trackBlocks) {
            val block = m.value
            val title = extractJsonString(block, "\"name\"") ?: continue
            val scId = extractJsonString(block, "\"@id\"")?.removePrefix("soundcloud:tracks:")
            val durationIso = extractJsonString(block, "\"duration\"")
            val durationMs = parseIsoDurationMs(durationIso)
            // SoundCloud playlist JSON-LD doesn't include per-track artist;
            // we leave artistName blank and let the importer caller try to
            // resolve via YouTube/Audius search using just the title.
            val numericId = AudiusApi.stableIdHash("sc-import:${scId ?: title}")
            tracks += TrackInfo(
                id = numericId,
                providerId = scId ?: "",
                provider = Provider.UNKNOWN,
                title = title,
                duration = durationMs,
                isUnplayable = true, // placeholder until matched
            )
        }
        return ImportResult.Success(
            title = name,
            artworkUrl = image,
            tracks = tracks,
            sourceName = "SoundCloud",
        )
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

    private fun httpGet(url: String, asBrowser: Boolean): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", if (asBrowser)
                "Mozilla/5.0 (Linux; Android 14; OpenSound) AppleWebKit/537.36"
            else
                "OpenSound/0.1 (Android)")
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }

    private fun extractJsonString(block: String, key: String): String? {
        val idx = block.indexOf(key)
        if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length || block[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < block.length) {
            val c = block[i]
            if (c == '\\' && i + 1 < block.length) {
                val n = block[i + 1]
                when (n) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    '"' -> sb.append('"')
                    'u' -> {
                        if (i + 5 < block.length) {
                            val hex = block.substring(i + 2, i + 6)
                            runCatching { sb.append(hex.toInt(16).toChar()) }
                            i += 4
                        }
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else if (c == '"') {
                return sb.toString()
            } else {
                sb.append(c); i++
            }
        }
        return null
    }

    private fun parseIsoDurationMs(iso: String?): Long {
        if (iso.isNullOrBlank()) return 0L
        val m = ISO_DURATION_RE.matchEntire(iso) ?: return 0L
        val h = m.groupValues[1].toLongOrNull() ?: 0L
        val mi = m.groupValues[2].toLongOrNull() ?: 0L
        val s = m.groupValues[3].toLongOrNull() ?: 0L
        return ((h * 3600) + (mi * 60) + s) * 1000L
    }

    private fun extractYouTubePlaylistId(url: String): String? {
        // Examples:
        //   https://www.youtube.com/playlist?list=PLABC...
        //   https://music.youtube.com/playlist?list=PLABC...
        //   https://youtu.be/VID?list=PLABC...
        val re = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
        return re.find(url)?.groupValues?.get(1)
    }

    companion object {
        private val SC_JSONLD_RE = Regex(
            """<script type="application/ld\+json">(\{.+?})</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        // Each track JSON-LD object (within the "track":[…] array). We're
        // permissive about field order so we just scan for blocks that
        // contain a "MusicRecording" type and let extractJsonString fish
        // out the fields by name.
        private val SC_TRACK_BLOCK_RE = Regex(
            """\{[^{}]*?"@type"\s*:\s*"MusicRecording"[^{}]*?\}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val ISO_DURATION_RE = Regex(
            """PT0*([0-9]+)H0*([0-9]+)M0*([0-9]+)S""",
        )

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
