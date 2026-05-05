package com.soundcloud.lite.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Imports a playlist from a foreign service URL.
 * Actual streams are resolved later against Audius/YouTube.
 *
 * SC import strategy (in order):
 *  1. Parse window.__sc_hydration — embedded in HTML, always present.
 *     Contains all track IDs (may be partial objects for large playlists).
 *  2. If client_id found (in hydration app data, inline JS, or asset bundles):
 *     batch-fetch full track data via /tracks?ids=…
 *  3. JSON-LD fallback (only ~5 tracks but works without client_id).
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
                Source.YOUTUBE    -> importYouTube(url)
                Source.SPOTIFY    -> ImportResult.Error("Spotify", "Spotify import not yet supported.")
                Source.YANDEX_MUSIC -> ImportResult.Error("Yandex Music", "Yandex.Music import not yet supported.")
                Source.DEEZER     -> ImportResult.Error("Deezer", "Deezer import not yet supported.")
                Source.APPLE_MUSIC -> ImportResult.Error("Apple Music", "Apple Music import not yet supported.")
            }
        } catch (t: Throwable) {
            ImportResult.Error(source.display, t.message ?: t::class.java.simpleName)
        }
    }

    enum class Source(val display: String) {
        SOUNDCLOUD("SoundCloud"), YOUTUBE("YouTube"), SPOTIFY("Spotify"),
        YANDEX_MUSIC("Yandex Music"), DEEZER("Deezer"), APPLE_MUSIC("Apple Music"),
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

    // =========================================================
    //  SoundCloud
    // =========================================================

    private fun importSoundCloud(url: String): ImportResult {
        val html = httpGet(url, asBrowser = true)
            ?: return ImportResult.Error("SoundCloud", "Couldn't fetch playlist page")

        // ── Step 1: parse __sc_hydration (always in the HTML) ──────────────
        val hydration = extractHydration(html)

        // ── Step 2: try to find client_id everywhere we can ─────────────────
        val clientId = findClientId(html, hydration)

        // ── Step 3: build track list ─────────────────────────────────────────
        if (hydration != null) {
            val result = buildFromHydration(hydration, clientId)
            if (result != null) return result
        }

        // ── Step 4: JSON-LD last resort ──────────────────────────────────────
        val ld = SC_JSONLD_RE.find(html)?.groupValues?.get(1)?.trim()
            ?: return ImportResult.Error("SoundCloud",
                "No playlist data found in page (hydration and JSON-LD both missing)")
        return parseScJsonLd(ld)
    }

    // ---- Hydration parsing ----

    /** Extracts window.__sc_hydration array content from the page HTML. */
    private fun extractHydration(html: String): String? {
        val marker = "window.__sc_hydration = "
        val start = html.indexOf(marker)
        if (start < 0) return null
        val arrStart = html.indexOf('[', start + marker.length)
        if (arrStart < 0) return null
        return extractJsonArray(html, arrStart)
    }

    private data class HydrationResult(
        val title: String,
        val artworkUrl: String?,
        val trackIds: List<String>,        // all track IDs (including partial)
        val fullTracks: List<TrackInfo>,   // tracks with complete metadata already
        val trackCount: Int,
    )

    private fun buildFromHydration(hydrationJson: String, clientId: String?): ImportResult? {
        val hr = parseHydration(hydrationJson) ?: return null
        if (hr.trackIds.isEmpty() && hr.fullTracks.isEmpty()) return null

        val tracks = hr.fullTracks.toMutableList()

        // Fetch metadata for the IDs that came back as partial objects
        val missingIds = hr.trackIds.filterNot { id ->
            hr.fullTracks.any { t -> t.providerId == id }
        }
        if (missingIds.isNotEmpty() && clientId != null) {
            tracks += resolveTrackIds(missingIds, clientId)
        } else if (missingIds.isNotEmpty()) {
            // No client_id — add placeholders so the track count is right
            for (id in missingIds) {
                tracks += TrackInfo(
                    id = scIdToLong("$id"),
                    providerId = id,
                    provider = Provider.SOUNDCLOUD,
                    title = "Track $id",
                    isUnplayable = true,
                )
            }
        }

        if (tracks.isEmpty()) return null

        return ImportResult.Success(
            title = hr.title,
            artworkUrl = hr.artworkUrl,
            tracks = deduplicateIds(tracks),
            sourceName = "SoundCloud",
        )
    }

    private fun parseHydration(hydrationJson: String): HydrationResult? {
        // Find the playlist hydratable entry
        val playlistMarker = "\"hydratable\":\"playlist\""
        val idx = hydrationJson.indexOf(playlistMarker)
        if (idx < 0) return null

        // The data object follows: "data":{...}
        val dataKey = "\"data\":"
        val dataIdx = hydrationJson.indexOf(dataKey, idx)
        if (dataIdx < 0) return null
        val objStart = hydrationJson.indexOf('{', dataIdx + dataKey.length)
        if (objStart < 0) return null
        val playlistJson = extractJsonObject(hydrationJson, objStart) ?: return null

        val title = extractJsonString(playlistJson, "\"title\"") ?: "SoundCloud Playlist"
        val artworkUrl = extractJsonString(playlistJson, "\"artwork_url\"")
            ?.replace("-large.", "-t500x500.")

        val trackCount = extractJsonNumber(playlistJson, "\"track_count\"")?.toIntOrNull() ?: 0

        // Parse tracks array
        val tracksKey = "\"tracks\":"
        val tracksKeyIdx = playlistJson.indexOf(tracksKey)
        if (tracksKeyIdx < 0) return HydrationResult(title, artworkUrl, emptyList(), emptyList(), trackCount)

        val arrStart = playlistJson.indexOf('[', tracksKeyIdx + tracksKey.length)
        if (arrStart < 0) return HydrationResult(title, artworkUrl, emptyList(), emptyList(), trackCount)
        val tracksJson = extractJsonArray(playlistJson, arrStart) ?: return HydrationResult(title, artworkUrl, emptyList(), emptyList(), trackCount)

        val trackObjects = splitJsonObjects(tracksJson)
        val fullTracks = mutableListOf<TrackInfo>()
        val allIds = mutableListOf<String>()

        for (obj in trackObjects) {
            val scId = extractJsonString(obj, "\"id\"") ?: extractJsonNumber(obj, "\"id\"") ?: continue
            allIds += scId
            val trackTitle = extractJsonString(obj, "\"title\"") ?: continue  // partial object
            val userBlock = extractJsonObjectBlock(obj, "\"user\"")
            val artist = if (userBlock != null) extractJsonString(userBlock, "\"username\"") ?: "" else ""
            val durationMs = extractJsonNumber(obj, "\"duration\"")?.toLongOrNull() ?: 0L
            val artwork = (extractJsonString(obj, "\"artwork_url\"") ?: "")
                .takeIf { it.isNotBlank() }
                ?.replace("-large.", "-t500x500.")
            fullTracks += TrackInfo(
                id = scIdToLong("$scId"),
                providerId = scId,
                provider = Provider.SOUNDCLOUD,
                title = trackTitle,
                artistName = artist,
                artworkUrl = artwork,
                duration = durationMs,
                isUnplayable = true,
            )
        }

        return HydrationResult(title, artworkUrl, allIds, fullTracks, trackCount)
    }

    // ---- client_id extraction ----

    private fun findClientId(html: String, hydrationJson: String?): String? {
        // 1. Look in hydration app entry: {"hydratable":"app","data":{"clientId":"..."}}
        if (hydrationJson != null) {
            val appMarker = "\"hydratable\":\"app\""
            val appIdx = hydrationJson.indexOf(appMarker)
            if (appIdx >= 0) {
                val dataIdx = hydrationJson.indexOf("\"data\":", appIdx)
                if (dataIdx >= 0) {
                    val objStart = hydrationJson.indexOf('{', dataIdx)
                    if (objStart >= 0) {
                        val appData = extractJsonObject(hydrationJson, objStart)
                        if (appData != null) {
                            val id = extractJsonString(appData, "\"clientId\"")
                                ?: extractJsonString(appData, "\"client_id\"")
                            if (!id.isNullOrBlank()) return id
                        }
                    }
                }
            }
        }

        // 2. Inline <script> content
        val inlineMatch = SC_CLIENT_ID_RE.find(html)?.groupValues?.get(1)
        if (!inlineMatch.isNullOrBlank()) return inlineMatch

        // 3. Script src URLs in the page (client_id sometimes appears in query params)
        val srcMatch = SC_CLIENT_ID_IN_SRC_RE.find(html)?.groupValues?.get(1)
        if (!srcMatch.isNullOrBlank()) return srcMatch

        // 4. Scan linked JS bundles
        return findClientIdInBundles(html)
    }

    private fun findClientIdInBundles(html: String): String? {
        val assetUrls = SC_ASSET_URL_RE.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        // Check last 8 bundles (config/vendor tend to be at the end)
        for (url in assetUrls.takeLast(8)) {
            val js = try { httpGet(url, asBrowser = false) } catch (_: Exception) { null } ?: continue
            val id = SC_CLIENT_ID_RE.find(js)?.groupValues?.get(1)
            if (!id.isNullOrBlank()) return id
        }
        return null
    }

    // ---- SC API batch track fetch ----

    /** Fetches full metadata for the given SC track IDs via API-v2. */
    private fun resolveTrackIds(ids: List<String>, clientId: String): List<TrackInfo> {
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
                val artist = if (userBlock != null) extractJsonString(userBlock, "\"username\"") ?: "" else ""
                val durationMs = extractJsonNumber(obj, "\"duration\"")?.toLongOrNull() ?: 0L
                val artwork = (extractJsonString(obj, "\"artwork_url\"") ?: "")
                    .takeIf { it.isNotBlank() }?.replace("-large.", "-t500x500.")
                result += TrackInfo(
                    id = scIdToLong("$scId"),
                    providerId = scId,
                    provider = Provider.SOUNDCLOUD,
                    title = trackTitle,
                    artistName = artist,
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
            val numericId = scIdToLong(scId ?: AudiusApi.stableIdHash(title.hashCode().toString()).toString())
            tracks += TrackInfo(
                id = numericId,
                providerId = scId ?: "",
                provider = Provider.SOUNDCLOUD,
                title = title,
                artistName = artistName,
                duration = durationMs,
                isUnplayable = true,
            )
        }
        return ImportResult.Success(title = name, artworkUrl = image, tracks = deduplicateIds(tracks), sourceName = "SoundCloud")
    }

    // =========================================================
    //  YouTube
    // =========================================================

    private suspend fun importYouTube(url: String): ImportResult {
        val playlistId = Regex("""[?&]list=([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
            ?: return ImportResult.Error("YouTube", "URL doesn't look like a YouTube playlist")
        val pl = youTubeApi.getPlaylist(playlistId)
            ?: return ImportResult.Error("YouTube", "Couldn't fetch playlist (Invidious not reachable)")
        return ImportResult.Success(title = pl.title, artworkUrl = pl.artworkUrl, tracks = pl.tracks, sourceName = "YouTube")
    }

    // =========================================================
    //  JSON helpers
    // =========================================================

    private fun httpGet(url: String, asBrowser: Boolean): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", if (asBrowser)
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            else
                "OpenSound/1.0 (Android)")
            .header("Accept", "text/html,application/xhtml+xml,application/json,*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        return http.newCall(req).execute().use { resp -> if (!resp.isSuccessful) null else resp.body?.string() }
    }

    private fun extractJsonString(block: String, key: String): String? {
        val idx = block.indexOf(key); if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length); if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length || block[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < block.length) {
            val c = block[i]
            if (c == '\\' && i + 1 < block.length) {
                when (val n = block[i + 1]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t'); 'r' -> sb.append('\r')
                    '\\' -> sb.append('\\'); '/' -> sb.append('/'); '"' -> sb.append('"')
                    'u' -> if (i + 5 < block.length) {
                        runCatching { sb.append(block.substring(i + 2, i + 6).toInt(16).toChar()) }; i += 4
                    }
                    else -> sb.append(n)
                }
                i += 2
            } else if (c == '"') return sb.toString()
            else { sb.append(c); i++ }
        }
        return null
    }

    private fun extractJsonNumber(block: String, key: String): String? {
        val idx = block.indexOf(key); if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length); if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length || (!block[i].isDigit() && block[i] != '-')) return null
        val sb = StringBuilder()
        while (i < block.length && (block[i].isDigit() || block[i] == '-' || block[i] == '.')) sb.append(block[i++])
        return sb.toString().ifEmpty { null }
    }

    private fun extractJsonObjectBlock(block: String, key: String): String? {
        val idx = block.indexOf(key); if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length); if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length || block[i] != '{') return null
        return extractJsonObject(block, i)
    }

    private fun extractJsonObject(json: String, startIdx: Int): String? {
        if (startIdx >= json.length || json[startIdx] != '{') return null
        var depth = 0; val sb = StringBuilder(); var i = startIdx; var inString = false; var escape = false
        while (i < json.length) {
            val c = json[i]
            when {
                escape -> { sb.append(c); escape = false }
                c == '\\' && inString -> { sb.append(c); escape = true }
                c == '"' -> { sb.append(c); inString = !inString }
                inString -> sb.append(c)
                c == '{' -> { depth++; sb.append(c) }
                c == '}' -> { depth--; sb.append(c); if (depth == 0) return sb.toString() }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }

    private fun extractJsonArray(json: String, startIdx: Int): String? {
        if (startIdx >= json.length || json[startIdx] != '[') return null
        var depth = 0; val sb = StringBuilder(); var i = startIdx; var inString = false; var escape = false
        while (i < json.length) {
            val c = json[i]
            when {
                escape -> { sb.append(c); escape = false }
                c == '\\' && inString -> { sb.append(c); escape = true }
                c == '"' -> { sb.append(c); inString = !inString }
                inString -> sb.append(c)
                c == '[' -> { depth++; sb.append(c) }
                c == ']' -> { depth--; sb.append(c); if (depth == 0) return sb.toString() }
                else -> sb.append(c)
            }
            i++
        }
        return null
    }

    private fun splitJsonObjects(arrayJson: String): List<String> {
        val objects = mutableListOf<String>(); var i = 0
        while (i < arrayJson.length) {
            if (arrayJson[i] == '{') {
                val obj = extractJsonObject(arrayJson, i)
                if (obj != null) { objects += obj; i += obj.length; continue }
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

    companion object {
        private val SC_JSONLD_RE = Regex(
            """<script type="application/ld\+json">(\{.+?\})</script>""", RegexOption.DOT_MATCHES_ALL)
        private val SC_TRACK_BLOCK_RE = Regex(
            """\{(?:[^\{\}]|\{[^\{\}]*\})*?"@type"\s*:\s*"MusicRecording"(?:[^\{\}]|\{[^\{\}]*\})*?\}""",
            RegexOption.DOT_MATCHES_ALL)
        private val SC_CLIENT_ID_RE = Regex(
            """(?:client_id|clientId)\s*[=:]\s*["']([A-Za-z0-9]{20,50})["']""")
        private val SC_CLIENT_ID_IN_SRC_RE = Regex(
            """[?&]client_id=([A-Za-z0-9]{20,50})""")
        private val SC_ASSET_URL_RE = Regex(
            """<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js[^"]*)"""")
        private val ISO_DURATION_RE = Regex("""PT0*([0-9]+)H0*([0-9]+)M0*([0-9]+)S""")

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        /**
         * SC track IDs are numeric strings (e.g. "1234567890").
         * Use them directly as Long to guarantee uniqueness — no hash collisions.
         * For non-numeric IDs (shouldn't happen but just in case) fall back to FNV hash.
         */
        fun scIdToLong(scId: String): Long =
            scId.toLongOrNull() ?: AudiusApi.stableIdHash("sc-import:$scId")

        /**
         * After building a track list, ensure every id is unique.
         * If two tracks ended up with the same id (e.g. hash collision on non-numeric ids),
         * offset subsequent duplicates by their list position.
         */
        fun deduplicateIds(tracks: List<TrackInfo>): List<TrackInfo> {
            val seen = mutableSetOf<Long>()
            return tracks.mapIndexed { idx, t ->
                var id = t.id
                while (!seen.add(id)) id = id xor (idx.toLong() + 1L)
                if (id == t.id) t else t.copy(id = id)
            }
        }
    }
}
