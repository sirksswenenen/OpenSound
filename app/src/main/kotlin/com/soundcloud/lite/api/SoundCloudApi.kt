package com.soundcloud.lite.api

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SoundCloud API client.
 *
 * Requires a client_id extracted from SC's JS bundles. We cache it in
 * memory; if it expires (HTTP 401/403) we re-fetch it automatically.
 *
 * Optionally uses an OAuth token for accounts with a Go+ subscription
 * (lets you stream tracks that are otherwise preview-only).
 */
class SoundCloudApi(
    private val http: OkHttpClient = defaultHttp(),
) {
    private val cachedClientId = AtomicReference<String?>(null)
    var oauthToken: String = ""

    // ── client_id ────────────────────────────────────────────────────────────

    private fun fetchClientId(): String? {
        val html = httpGet("https://soundcloud.com",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36") ?: return null

        // 1. Inline JS
        SC_CLIENT_ID_RE.find(html)?.groupValues?.get(1)?.let { return it }

        // 2. CDN asset bundles
        SC_ASSET_URL_RE.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
            .takeLast(8)
            .forEach { url ->
                val js = runCatching { httpGet(url, "OpenSound/1.0") }.getOrNull() ?: return@forEach
                SC_CLIENT_ID_RE.find(js)?.groupValues?.get(1)?.let { return it }
            }
        return null
    }

    fun clientId(): String? {
        cachedClientId.get()?.let { return it }
        val id = fetchClientId() ?: return null
        cachedClientId.set(id)
        return id
    }

    private fun invalidateClientId() = cachedClientId.set(null)

    // ── Stream URLs ──────────────────────────────────────────────────────────

    /**
     * Resolves a playable/downloadable stream URL for a SC track ID.
     * Returns null if the track is not streamable (e.g. region-locked, premium).
     *
     * Also returns whether the URL is a preview (≤31 s) so callers can
     * decide to substitute a YouTube version.
     */
    data class StreamResult(val url: String, val isPreview: Boolean, val durationMs: Long)

    fun getStreamUrl(trackId: String): StreamResult? {
        val cid = clientId() ?: return null
        val auth = if (oauthToken.isNotBlank()) "&oauth_token=$oauthToken" else ""
        val url = "https://api-v2.soundcloud.com/tracks/$trackId/streams?client_id=$cid$auth"
        val json = httpGetWithAuth(url) ?: run {
            // Might be expired client_id — retry once
            invalidateClientId()
            val cid2 = clientId() ?: return null
            httpGetWithAuth("https://api-v2.soundcloud.com/tracks/$trackId/streams?client_id=$cid2$auth")
        } ?: return null

        // Prefer full mp3, fall back to preview
        val fullUrl = extractJsonString(json, "\"http_mp3_128_url\"")
            ?: extractJsonString(json, "\"hls_mp3_128_url\"")
        val previewUrl = extractJsonString(json, "\"preview_mp3_128_url\"")

        return when {
            fullUrl != null -> StreamResult(fullUrl, false, 0L)
            previewUrl != null -> StreamResult(previewUrl, true, 0L)
            else -> null
        }
    }

    // ── Track metadata ───────────────────────────────────────────────────────

    fun getTrack(trackId: String): TrackInfo? {
        val cid = clientId() ?: return null
        val json = httpGetWithAuth(
            "https://api-v2.soundcloud.com/tracks/$trackId?client_id=$cid"
        ) ?: return null
        return parseTrack(json)
    }

    // ── Trending ─────────────────────────────────────────────────────────────

    /**
     * Returns trending tracks optionally seeded by a genre string.
     * SC's /charts endpoint returns the most-played tracks globally or
     * filtered by genre.
     */
    fun getTrending(genre: String? = null, limit: Int = 50): List<TrackInfo> {
        val cid = clientId() ?: return emptyList()
        val genreParam = if (genre != null)
            "&genre=soundcloud%3Agenres%3A${java.net.URLEncoder.encode(genre, "UTF-8")}"
        else ""
        val url = "https://api-v2.soundcloud.com/charts?kind=top&limit=$limit$genreParam&client_id=$cid"
        val json = httpGetWithAuth(url) ?: return emptyList()
        return parseCollection(json)
    }

    /**
     * Returns tracks related to a given SC track ID.
     */
    fun getRelated(trackId: String, limit: Int = 20): List<TrackInfo> {
        val cid = clientId() ?: return emptyList()
        val json = httpGetWithAuth(
            "https://api-v2.soundcloud.com/tracks/$trackId/related?limit=$limit&client_id=$cid"
        ) ?: return emptyList()
        return parseCollection(json)
    }

    /**
     * Searches SC for tracks matching [query].
     */
    fun search(query: String, limit: Int = 10): List<TrackInfo> {
        val cid = clientId() ?: return emptyList()
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val json = httpGetWithAuth(
            "https://api-v2.soundcloud.com/search/tracks?q=$q&limit=$limit&client_id=$cid"
        ) ?: return emptyList()
        return parseCollection(json)
    }

    // ── JSON parsing ─────────────────────────────────────────────────────────

    private fun parseCollection(json: String): List<TrackInfo> {
        // Response is {"collection":[...]} or just [...]
        val arrStart = if (json.trimStart().startsWith('[')) {
            json.indexOf('[')
        } else {
            val collIdx = json.indexOf("\"collection\"")
            if (collIdx < 0) return emptyList()
            json.indexOf('[', collIdx)
        }
        if (arrStart < 0) return emptyList()
        val arrJson = extractJsonArray(json, arrStart) ?: return emptyList()
        return splitJsonObjects(arrJson).mapNotNull { parseTrack(it) }
    }

    private fun parseTrack(obj: String): TrackInfo? {
        // Some endpoints wrap in {"track":{...}}
        val trackObj = if (obj.contains("\"track\"")) {
            extractJsonObjectBlock(obj, "\"track\"") ?: obj
        } else obj

        val id = extractJsonNumber(trackObj, "\"id\"") ?: return null
        val title = extractJsonString(trackObj, "\"title\"") ?: return null
        if (title.isBlank()) return null

        val userBlock = extractJsonObjectBlock(trackObj, "\"user\"")
        val artist = if (userBlock != null) extractJsonString(userBlock, "\"username\"") ?: "" else ""
        val artworkRaw = extractJsonString(trackObj, "\"artwork_url\"")
        val artwork = artworkRaw?.replace("-large.", "-t500x500.")
            ?.takeIf { it.isNotBlank() }
        val durationMs = extractJsonNumber(trackObj, "\"duration\"")?.toLongOrNull() ?: 0L
        val genre = extractJsonString(trackObj, "\"genre\"")
        val playCount = extractJsonNumber(trackObj, "\"playback_count\"")?.toLongOrNull()
        val streamable = extractJsonString(trackObj, "\"streamable\"")

        return TrackInfo(
            id = AudiusApi.stableIdHash("sc:$id"),
            providerId = id,
            provider = Provider.SOUNDCLOUD,
            title = title,
            artistName = artist,
            artworkUrl = artwork,
            duration = durationMs,
            genre = genre,
            playCount = playCount,
            isUnplayable = streamable == "false",
        )
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    private fun httpGetWithAuth(url: String): String? {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenSound/1.0 (Android)")
            .header("Accept", "application/json")
        if (oauthToken.isNotBlank()) {
            builder.header("Authorization", "OAuth $oauthToken")
        }
        return try {
            http.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Exception) { null }
    }

    private fun httpGet(url: String, ua: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/json")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    } catch (_: Exception) { null }

    // ── JSON helpers (minimal, no external deps) ─────────────────────────────

    private fun extractJsonString(block: String, key: String): String? {
        val idx = block.indexOf(key); if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length); if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length) return null
        if (block[i] == 'n') return null // null
        if (block[i] != '"') return null
        i++
        val sb = StringBuilder()
        while (i < block.length) {
            val c = block[i]
            if (c == '\\' && i + 1 < block.length) {
                when (val n = block[++i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t')
                    '\\' -> sb.append('\\'); '"' -> sb.append('"')
                    'u' -> if (i + 4 < block.length) {
                        runCatching { sb.append(block.substring(i + 1, i + 5).toInt(16).toChar()) }
                        i += 4
                    }
                    else -> sb.append(n)
                }
            } else if (c == '"') return sb.toString()
            else sb.append(c)
            i++
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

    private fun extractJsonObject(json: String, start: Int): String? {
        if (start >= json.length || json[start] != '{') return null
        var depth = 0; val sb = StringBuilder(); var i = start
        var inStr = false; var esc = false
        while (i < json.length) {
            val c = json[i]
            when { esc -> { sb.append(c); esc = false }
                c == '\\' && inStr -> { sb.append(c); esc = true }
                c == '"' -> { sb.append(c); inStr = !inStr }
                inStr -> sb.append(c)
                c == '{' -> { depth++; sb.append(c) }
                c == '}' -> { depth--; sb.append(c); if (depth == 0) return sb.toString() }
                else -> sb.append(c)
            }; i++
        }
        return null
    }

    private fun extractJsonArray(json: String, start: Int): String? {
        if (start >= json.length || json[start] != '[') return null
        var depth = 0; val sb = StringBuilder(); var i = start
        var inStr = false; var esc = false
        while (i < json.length) {
            val c = json[i]
            when { esc -> { sb.append(c); esc = false }
                c == '\\' && inStr -> { sb.append(c); esc = true }
                c == '"' -> { sb.append(c); inStr = !inStr }
                inStr -> sb.append(c)
                c == '[' -> { depth++; sb.append(c) }
                c == ']' -> { depth--; sb.append(c); if (depth == 0) return sb.toString() }
                else -> sb.append(c)
            }; i++
        }
        return null
    }

    private fun splitJsonObjects(arrayJson: String): List<String> {
        val result = mutableListOf<String>(); var i = 0
        while (i < arrayJson.length) {
            if (arrayJson[i] == '{') {
                val obj = extractJsonObject(arrayJson, i)
                if (obj != null) { result += obj; i += obj.length; continue }
            }; i++
        }
        return result
    }

    companion object {
        private val SC_CLIENT_ID_RE = Regex("""(?:client_id|clientId)\s*[=:]\s*["']([A-Za-z0-9]{20,50})["']""")
        private val SC_ASSET_URL_RE = Regex("""<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js[^"]*)"""")

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()
    }
}
