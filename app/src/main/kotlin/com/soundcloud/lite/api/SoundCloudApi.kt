package com.soundcloud.lite.api

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SoundCloud API client.
 *
 * client_id strategy (in order):
 *   1. Use [forcedClientId] if set (user can paste it in Settings).
 *   2. Try the known-working hardcoded fallback IDs (refreshed periodically
 *      by the community; the app will stop needing them if the user sets one).
 *   3. Scrape soundcloud.com for a fresh one (may fail due to Cloudflare).
 *
 * For streams the Cobalt API is tried first (no auth needed), then SC API-v2.
 */
class SoundCloudApi(
    private val http: OkHttpClient = defaultHttp(),
) {
    private val cachedClientId = AtomicReference<String?>(null)
    var oauthToken: String = ""
    var cobaltBaseUrl: String = "https://api.cobalt.tools"

    /** Set this from Settings to skip the scraping step entirely. */
    var forcedClientId: String = ""

    // ── client_id ────────────────────────────────────────────────────────────

    fun clientId(): String? {
        // 1. User-provided
        if (forcedClientId.isNotBlank()) return forcedClientId
        // 2. Cached
        cachedClientId.get()?.let { return it }
        // 3. Try hardcoded fallbacks (valid as of May 2025 — update periodically)
        for (id in KNOWN_CLIENT_IDS) {
            if (probeClientId(id)) {
                cachedClientId.set(id)
                return id
            }
        }
        // 4. Scrape
        val scraped = scrapeClientId()
        if (scraped != null) {
            cachedClientId.set(scraped)
            return scraped
        }
        return null
    }

    private fun probeClientId(id: String): Boolean {
        val resp = httpGetWithAuth(
            "https://api-v2.soundcloud.com/tracks?ids=1&client_id=$id"
        )
        return resp != null && !resp.contains("\"error\"") && !resp.contains("\"status\":401")
    }

    private fun scrapeClientId(): String? {
        val html = httpGet("https://soundcloud.com",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        ) ?: return null
        SC_CLIENT_ID_RE.find(html)?.groupValues?.get(1)?.let { return it }
        SC_ASSET_URL_RE.findAll(html)
            .map { it.groupValues[1] }.distinct().toList().takeLast(8)
            .forEach { url ->
                val js = runCatching { httpGet(url, "OpenSound/1.0") }.getOrNull() ?: return@forEach
                SC_CLIENT_ID_RE.find(js)?.groupValues?.get(1)?.let { return it }
            }
        return null
    }

    private fun invalidateClientId() {
        cachedClientId.set(null)
        KNOWN_CLIENT_IDS  // will be re-probed on next clientId() call
    }

    // ── Stream URLs ──────────────────────────────────────────────────────────

    data class StreamResult(val url: String, val isPreview: Boolean)

    /**
     * Returns a playable stream URL for [trackId].
     *
     * Order:
     *   1. Cobalt API — no auth, handles previews/region locks
     *   2. SC API-v2 /streams with client_id
     */
    fun getStreamUrl(trackId: String, trackPermalink: String? = null): StreamResult? {
        // 1. Cobalt (most reliable, no client_id needed)
        val permalink = trackPermalink
            ?: "https://soundcloud.com/tracks/$trackId"
        getCobaltStreamUrl(permalink, cobaltBaseUrl)?.let {
            return StreamResult(it, false)
        }

        // 2. SC API-v2
        val cid = clientId() ?: return null
        val auth = if (oauthToken.isNotBlank()) "&oauth_token=$oauthToken" else ""
        val json = httpGetWithAuth(
            "https://api-v2.soundcloud.com/tracks/$trackId/streams?client_id=$cid$auth"
        ) ?: run {
            invalidateClientId()
            val cid2 = clientId() ?: return null
            httpGetWithAuth("https://api-v2.soundcloud.com/tracks/$trackId/streams?client_id=$cid2$auth")
        } ?: return null

        val fullUrl    = extractJsonString(json, "\"http_mp3_128_url\"")
            ?: extractJsonString(json, "\"hls_mp3_128_url\"")
        val previewUrl = extractJsonString(json, "\"preview_mp3_128_url\"")
        return when {
            fullUrl    != null -> StreamResult(fullUrl, false)
            previewUrl != null -> StreamResult(previewUrl, true)
            else               -> null
        }
    }

    fun getCobaltStreamUrl(scUrl: String, cobaltBase: String = cobaltBaseUrl): String? {
        return try {
            val body = """{"url":"${scUrl.replace("\"", "\\\"")}","audioFormat":"mp3","isAudioOnly":true}"""
            val reqBody = body.toRequestBody("application/json; charset=utf-8".toMediaType())
            val req = Request.Builder()
                .url("$cobaltBase/")
                .post(reqBody)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val json = resp.body?.string() ?: return null
                extractJsonString(json, "\"url\"")
            }
        } catch (_: Exception) { null }
    }

    // ── Trending ─────────────────────────────────────────────────────────────

    fun getTrending(genre: String? = null, limit: Int = 50): List<TrackInfo> {
        val cid = clientId() ?: return emptyList()
        val genreParam = if (genre != null)
            "&genre=soundcloud%3Agenres%3A${java.net.URLEncoder.encode(genre, "UTF-8")}"
        else ""
        val json = httpGetWithAuth(
            "https://api-v2.soundcloud.com/charts?kind=top&limit=$limit$genreParam&client_id=$cid"
        ) ?: return emptyList()
        return parseCollection(json)
    }

    fun getRelated(trackId: String, limit: Int = 20): List<TrackInfo> {
        val cid = clientId() ?: return emptyList()
        val json = httpGetWithAuth(
            "https://api-v2.soundcloud.com/tracks/$trackId/related?limit=$limit&client_id=$cid"
        ) ?: return emptyList()
        return parseCollection(json)
    }

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
        val arrStart = if (json.trimStart().startsWith('[')) json.indexOf('[')
        else {
            val ci = json.indexOf("\"collection\""); if (ci < 0) return emptyList()
            json.indexOf('[', ci)
        }
        if (arrStart < 0) return emptyList()
        val arrJson = extractJsonArray(json, arrStart) ?: return emptyList()
        return splitJsonObjects(arrJson).mapNotNull { parseTrack(it) }
    }

    private fun parseTrack(obj: String): TrackInfo? {
        val trackObj = if (obj.contains("\"track\""))
            extractJsonObjectBlock(obj, "\"track\"") ?: obj
        else obj

        val id    = extractJsonNumber(trackObj, "\"id\"") ?: return null
        val title = extractJsonString(trackObj, "\"title\"") ?: return null
        if (title.isBlank()) return null

        val userBlock  = extractJsonObjectBlock(trackObj, "\"user\"")
        val artist     = if (userBlock != null) extractJsonString(userBlock, "\"username\"") ?: "" else ""
        val artwork    = extractJsonString(trackObj, "\"artwork_url\"")
            ?.replace("-large.", "-t500x500.")?.takeIf { it.isNotBlank() }
        val durationMs = extractJsonNumber(trackObj, "\"duration\"")?.toLongOrNull() ?: 0L
        val genre      = extractJsonString(trackObj, "\"genre\"")
        val playCount  = extractJsonNumber(trackObj, "\"playback_count\"")?.toLongOrNull()
        val permalink  = extractJsonString(trackObj, "\"permalink_url\"")
        val streamable = extractJsonString(trackObj, "\"streamable\"")

        return TrackInfo(
            id          = AudiusApi.stableIdHash("sc:$id"),
            providerId  = id,
            provider    = Provider.SOUNDCLOUD,
            title       = title,
            artistName  = artist,
            artworkUrl  = artwork,
            duration    = durationMs,
            genre       = genre,
            playCount   = playCount,
            permalink   = permalink,
            isUnplayable = streamable == "false",
        )
    }

    // ── HTTP ─────────────────────────────────────────────────────────────────

    fun httpGetWithAuth(url: String): String? {
        val b = Request.Builder().url(url)
            .header("User-Agent", "OpenSound/1.0 (Android)")
            .header("Accept", "application/json")
        if (oauthToken.isNotBlank()) b.header("Authorization", "OAuth $oauthToken")
        return try { http.newCall(b.build()).execute().use { r -> if (!r.isSuccessful) null else r.body?.string() } }
        catch (_: Exception) { null }
    }

    private fun httpGet(url: String, ua: String): String? = try {
        val req = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "text/html,application/json,*/*")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        http.newCall(req).execute().use { r -> if (!r.isSuccessful) null else r.body?.string() }
    } catch (_: Exception) { null }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun extractJsonString(block: String, key: String): String? {
        val idx = block.indexOf(key); if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length); if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length || block[i] == 'n') return null
        if (block[i] != '"') return null; i++
        val sb = StringBuilder()
        while (i < block.length) {
            val c = block[i]
            if (c == '\\' && i + 1 < block.length) {
                when (val n = block[++i]) {
                    'n' -> sb.append('\n'); 't' -> sb.append('\t')
                    '\\' -> sb.append('\\'); '"' -> sb.append('"'); '/' -> sb.append('/')
                    'u' -> if (i + 4 < block.length) { runCatching { sb.append(block.substring(i+1, i+5).toInt(16).toChar()) }; i += 4 }
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
                else -> sb.append(c) }; i++ }
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
                else -> sb.append(c) }; i++ }
        return null
    }

    private fun splitJsonObjects(arrayJson: String): List<String> {
        val result = mutableListOf<String>(); var i = 0
        while (i < arrayJson.length) {
            if (arrayJson[i] == '{') {
                val obj = extractJsonObject(arrayJson, i)
                if (obj != null) { result += obj; i += obj.length; continue } }; i++ }
        return result
    }

    companion object {
        // Known-working SC client_ids (community-maintained; probe before use)
        // These are the most recently confirmed valid IDs — update periodically.
        private val KNOWN_CLIENT_IDS = listOf(
            "iZIs9mchVcX5lhVRyQGGAYlNPVldzAoX",
            "a3e059563d7fd3372b49b37f00a00bcf",
            "2t9loNQH90kzJcsFCODdigxfp325aq4z",
            "YUKXoArFcqrlQn9tfNHvvyfnDISj04zk",
            "68TuTMPkty1q8q8shN6eFMcbPoYoJiMf",
        )

        private val SC_CLIENT_ID_RE = Regex(
            """(?:client_id|clientId)\s*[=:]\s*["']([A-Za-z0-9]{20,50})["']""")
        private val SC_ASSET_URL_RE = Regex(
            """<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js[^"]*)"""")

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true).followSslRedirects(true)
            .build()
    }
}
