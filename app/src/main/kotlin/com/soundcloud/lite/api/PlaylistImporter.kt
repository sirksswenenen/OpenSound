package com.soundcloud.lite.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Imports a playlist (metadata: title + track names + artists + durations)
 * from a foreign service URL. Actual playable streams are resolved later
 * by matching each imported track against Audius / YouTube.
 *
 * Supported sources:
 *   - SoundCloud user playlists (soundcloud.com/USER/sets/SETNAME or on.soundcloud.com short URLs)
 *   - YouTube playlists (youtube.com/playlist?list=…)
 *
 * SC import strategy (in order of preference):
 *   1. SC API-v2 via resolve endpoint — gives ALL tracks with artist names.
 *      Requires a client_id found in SoundCloud's page JS bundles.
 *   2. JSON-LD block — SC caps this at ~5 tracks for large playlists, but
 *      it's a reliable fallback when the client_id can't be extracted.
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

    suspend fun import(url: String): ImportResult {
        val source = detect(url) ?: return ImportResult.UnsupportedUrl
        return try {
            when (source) {
                Source.SOUNDCLOUD -> importSoundCloud(url)
                Source.YOUTUBE -> importYouTube(url)
                Source.SPOTIFY -> ImportResult.Error("Spotify", "Spotify playlists not yet supported.")
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
            u.contains("soundcloud.com/") || u.contains("on.soundcloud.com/") -> Source.SOUNDCLOUD
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

        // Strategy 1: SC API-v2 via resolve endpoint — returns ALL tracks with artist names.
        val clientId = findScClientId(html)
        if (clientId != null) {
            val apiResult = tryScApiImport(url, clientId)
            if (apiResult != null) return apiResult
        }

        // Strategy 2: JSON-LD fallback (limited to ~5 tracks on large playlists).
        val ld = SC_JSONLD_RE.find(html)?.groupValues?.get(1)?.trim()
            ?: return ImportResult.Error("SoundCloud",
                "Playlist metadata not found in page (client_id extraction also failed)")
        return parseScJsonLd(ld)
    }

    /**
     * Finds the SC client_id by scanning:
     *   1. Inline <script> blocks in the page HTML (fast path)
     *   2. Linked JS asset bundles (slightly slower, but more reliable)
     */
    private fun findScClientId(html: String): String? {
        val inline = SC_CLIENT_ID_RE.find(html)?.groupValues?.get(1)
        if (inline != null) return inline

        val assetUrls = SC_ASSET_URL_RE.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        // Check the last few bundles first — config tends to be in later bundles.
        for (assetUrl in assetUrls.takeLast(6)) {
            val js = try { httpGet(assetUrl, asBrowser = false) } catch (_: Exception) { null } ?: continue
            val id = SC_CLIENT_ID_RE.find(js)?.groupValues?.get(1)
            if (id != null) return id
        }
        return null
    }

    private fun tryScApiImport(playlistUrl: String, clientId: String): ImportResult? {
        val resolveUrl = "https://api-v2.soundcloud.com/resolve?url=${
            java.net.URLEncoder.encode(playlistUrl, "UTF-8")
        }&client_id=$clientId"

        val json = try { httpGet(resolveUrl, asBrowser = false) } catch (_: Exception) { null }
            ?: return null

        if (!json.contains("\"tracks\"") && !json.contains("kind")) return null

        return parseScApiPlaylist(json, clientId)
    }

    private fun parseScApiPlaylist(json: String, clientId: String): ImportResult {
        val title = extractJsonString(json, "\"title\"") ?: "SoundCloud Playlist"
        val artworkUrl = extractJsonString(json, "\"artwork_url\"")
            ?.replace("-large.", "-t500x500.")

        val tracksStart = json.indexOf("\"tracks\"")
        if (tracksStart < 0) return ImportResult.Error("SoundCloud", "No tracks array in API response")
        val arrStart = json.indexOf('[', tracksStart)
        if (arrStart < 0) return ImportResult.Error("SoundCloud", "Malformed tracks array")
        val tracksJson = extractJsonArray(json, arrStart)
            ?: return ImportResult.Error("SoundCloud", "Couldn't parse tracks array")

        val trackObjects = splitJsonObjects(tracksJson)
        val partialIds = mutableListOf<String>()
        val tracks = mutableListOf<TrackInfo>()

        for (obj in trackObjects) {
            val scId = extractJsonString(obj, "\"id\"") ?: extractJsonNumber(obj, "\"id\"") ?: continue
            val trackTitle = extractJsonString(obj, "\"title\"")
            if (trackTitle == null) {
                partialIds += scId
                continue
            }
            val userBlock = extractJsonObjectBlock(obj, "\"user\"")
            val artist = if (userBlock != null) extractJsonString(userBlock, "\"username\"") else null
            val durationMs = extractJsonNumber(obj, "\"duration\"")?.toLongOrNull() ?: 0L
            val artwork = extractJsonString(obj, "\"artwork_url\"")?.replace("-large.", "-t500x500.")
            val numericId = AudiusApi.stableIdHash("sc-import:$scId")
            tracks += TrackInfo(
                id = numericId,
                providerId = scId,
                provider = Provider.UNKNOWN,
                title = trackTitle,
                artistName = artist ?: "",
                artworkUrl = artwork,
                duration = durationMs,
                isUnplayable = true,
            )
        }

        // Batch-resolve partial tracks (returned as {kind,id} only for large playlists)
        if (partialIds.isNotEmpty()) {
            tracks += resolvePartialTracks(partialIds, clientId)
        }

        return ImportResult.Success(
            title = title,
            artworkUrl = artworkUrl,
            tracks = tracks,
            sourceName = "SoundCloud",
        )
    }

    /**
     * Fetches full track metadata for IDs that were returned as partial objects.
     * SC allows up to 50 IDs per request via /tracks?ids=...
     */
    private fun resolvePartialTracks(ids: List<String>, clientId: String): List<TrackInfo> {
        val result = mutableListOf<TrackInfo>()
        ids.chunked(50).forEach { chunk ->
            val url = "https://api-v2.soundcloud.com/tracks?ids=${
                chunk.joinToString(",")
            }&client_id=$clientId"
            val json = try { httpGet(url, asBrowser = false) } catch (_: Exception) { null } ?: return@forEach
            val arrStart = json.indexOf('[')
            if (arrStart < 0) return@forEach
            val arrJson = extractJsonArray(json, arrStart) ?: return@forEach
            for (obj in splitJsonObjects(arrJson)) {
                val scId = extractJsonString(obj, "\"id\"") ?: extractJsonNumber(obj, "\"id\"") ?: continue
                val trackTitle = extractJsonString(obj, "\"title\"") ?: continue
                val userBlock = extractJsonObjectBlock(obj, "\"user\"")
                val artist = if (userBlock != null) extractJsonString(userBlock, "\"username\"") else null
                val durationMs = extractJsonNumber(obj, "\"duration\"")?.toLongOrNull() ?: 0L
                val artwork = extractJsonString(obj, "\"artwork_url\"")?.replace("-large.", "-t500x500.")
                val numericId = AudiusApi.stableIdHash("sc-import:$scId")
                result += TrackInfo(
                    id = numericId,
                    providerId = scId,
                    provider = Provider.UNKNOWN,
                    title = trackTitle,
                    artistName = artist ?: "",
                    artworkUrl = artwork,
                    duration = durationMs,
                    isUnplayable = true,
                )
            }
        }
        return result
    }

    // ---- JSON-LD fallback ----

    private fun parseScJsonLd(json: String): ImportResult {
        val name = extractJsonString(json, "\"name\"") ?: "SoundCloud Playlist"
        val image = extractJsonString(json, "\"image\"")
        val tracks = mutableListOf<TrackInfo>()
        val trackBlocks = SC_TRACK_BLOCK_RE.findAll(json).toList()
        for (m in trackBlocks) {
            val block = m.value
            val title = extractJsonString(block, "\"name\"") ?: continue
            val scId = extractJsonString(block, "\"@id\"")?.removePrefix("soundcloud:tracks:")
            val durationIso = extractJsonString(block, "\"duration\"")
            val durationMs = parseIsoDurationMs(durationIso)
            val byArtistBlock = extractJsonObjectBlock(block, "\"byArtist\"")
            val artistName = if (byArtistBlock != null) extractJsonString(byArtistBlock, "\"name\"") ?: "" else ""
            val numericId = AudiusApi.stableIdHash("sc-import:${scId ?: title}")
            tracks += TrackInfo(
                id = numericId,
                providerId = scId ?: "",
                provider = Provider.UNKNOWN,
                title = title,
                artistName = artistName,
                duration = durationMs,
                isUnplayable = true,
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

    // ---- JSON helpers ----

    private fun httpGet(url: String, asBrowser: Boolean): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", if (asBrowser)
                "Mozilla/5.0 (Linux; Android 14; OpenSound) AppleWebKit/537.36"
            else
                "OpenSound/0.1 (Android)")
            .header("Accept", "text/html,application/xhtml+xml,application/json,*/*")
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
                    'u' -> if (i + 5 < block.length) {
                        runCatching { sb.append(block.substring(i + 2, i + 6).toInt(16).toChar()) }
                        i += 4
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

    private fun extractJsonNumber(block: String, key: String): String? {
        val idx = block.indexOf(key)
        if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length || (!block[i].isDigit() && block[i] != '-')) return null
        val sb = StringBuilder()
        while (i < block.length && (block[i].isDigit() || block[i] == '-' || block[i] == '.')) {
            sb.append(block[i++])
        }
        return sb.toString().ifEmpty { null }
    }

    private fun extractJsonObjectBlock(block: String, key: String): String? {
        val idx = block.indexOf(key)
        if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length || block[i] != '{') return null
        var depth = 0
        val sb = StringBuilder()
        while (i < block.length) {
            when (block[i]) {
                '{' -> { depth++; sb.append('{') }
                '}' -> { depth--; sb.append('}'); if (depth == 0) return sb.toString() }
                else -> sb.append(block[i])
            }
            i++
        }
        return null
    }

    private fun extractJsonArray(json: String, startIdx: Int): String? {
        if (startIdx >= json.length || json[startIdx] != '[') return null
        var depth = 0
        val sb = StringBuilder()
        var i = startIdx
        while (i < json.length) {
            when (json[i]) {
                '[' -> { depth++; sb.append('[') }
                ']' -> { depth--; sb.append(']'); if (depth == 0) return sb.toString() }
                else -> sb.append(json[i])
            }
            i++
        }
        return null
    }

    private fun splitJsonObjects(arrayJson: String): List<String> {
        val objects = mutableListOf<String>()
        var i = 0
        while (i < arrayJson.length) {
            if (arrayJson[i] == '{') {
                var depth = 0
                val sb = StringBuilder()
                while (i < arrayJson.length) {
                    when (arrayJson[i]) {
                        '{' -> { depth++; sb.append('{') }
                        '}' -> { depth--; sb.append('}'); if (depth == 0) { objects += sb.toString(); break } }
                        else -> sb.append(arrayJson[i])
                    }
                    i++
                }
            }
            i++
        }
        return objects
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
        return Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
    }

    companion object {
        private val SC_JSONLD_RE = Regex(
            """<script type="application/ld\+json">(\{.+?\})</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        // Track blocks — allows one level of nested braces (e.g. byArtist:{...})
        private val SC_TRACK_BLOCK_RE = Regex(
            """\{(?:[^\{\}]|\{[^\{\}]*\})*?"@type"\s*:\s*"MusicRecording"(?:[^\{\}]|\{[^\{\}]*\})*?\}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val SC_CLIENT_ID_RE = Regex(
            """client_id\s*[=:]\s*["']([A-Za-z0-9]{20,50})["']""",
        )
        private val SC_ASSET_URL_RE = Regex(
            """<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js)"""",
        )
        private val ISO_DURATION_RE = Regex("""PT0*([0-9]+)H0*([0-9]+)M0*([0-9]+)S""")

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }
}
