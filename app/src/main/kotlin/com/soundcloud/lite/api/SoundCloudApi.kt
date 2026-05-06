package com.soundcloud.lite.api

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class SoundCloudApi(
    private val http: OkHttpClient = defaultHttp(),
) {
    private val cachedClientId = AtomicReference<String?>(null)
    var oauthToken: String = ""
    var cobaltBaseUrl: String = "https://api.cobalt.tools"
    var forcedClientId: String = ""

    // ── client_id ─────────────────────────────────────────────────────────────

    fun clientId(): String? {
        if (forcedClientId.isNotBlank()) return forcedClientId
        cachedClientId.get()?.let { return it }
        for (id in KNOWN_CLIENT_IDS) {
            if (probeClientId(id)) {
                Log.d(TAG, "Using known client_id: $id")
                cachedClientId.set(id)
                return id
            }
        }
        val scraped = scrapeClientId()
        if (scraped != null) {
            Log.d(TAG, "Scraped client_id: $scraped")
            cachedClientId.set(scraped)
            return scraped
        }
        Log.w(TAG, "Could not obtain client_id")
        return null
    }

    private fun probeClientId(id: String): Boolean {
        val resp = httpGetRaw(
            "https://api-v2.soundcloud.com/tracks?ids=1193518076&client_id=$id",
            "OpenSound/1.0"
        ) ?: return false
        return !resp.contains("\"error\"") && !resp.contains("\"status\":401") && resp.contains("\"id\"")
    }

    private fun scrapeClientId(): String? {
        val html = httpGetRaw("https://soundcloud.com",
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/124 Mobile Safari/537.36"
        ) ?: return null
        SC_CLIENT_ID_RE.find(html)?.groupValues?.get(1)?.let { return it }
        SC_ASSET_URL_RE.findAll(html)
            .map { it.groupValues[1] }.distinct().toList().takeLast(8)
            .forEach { url ->
                val js = httpGetRaw(url, "OpenSound/1.0") ?: return@forEach
                SC_CLIENT_ID_RE.find(js)?.groupValues?.get(1)?.let { return it }
            }
        return null
    }

    private fun invalidateClientId() = cachedClientId.set(null)

    // ── Streams ────────────────────────────────────────────────────────────────

    data class StreamResult(val url: String, val isPreview: Boolean)

    /**
     * Gets a playable stream URL for a SC track.
     *
     * Strategy:
     *   1. SC API-v2 /streams (fastest when client_id is available)
     *   2. Cobalt API (fallback — works without client_id)
     */
    fun getStreamUrl(trackId: String, trackPermalink: String? = null): StreamResult? {
        // Method 1: SC API-v2 /streams
        val cid = clientId()
        if (cid != null) {
            val auth = if (oauthToken.isNotBlank()) "&oauth_token=$oauthToken" else ""
            val url = "https://api-v2.soundcloud.com/tracks/$trackId/streams?client_id=$cid$auth"
            val json = httpGetRaw(url, "OpenSound/1.0")
            Log.d(TAG, "streams response for $trackId: ${json?.take(200)}")
            if (json != null) {
                // SC returns: {"http_mp3_128_url":"...","hls_mp3_128_url":"...",...}
                val mp3Url = extractJsonString(json, "\"http_mp3_128_url\"")
                    ?: extractJsonString(json, "\"http_mp3_128\"")  // alt key
                val previewUrl = extractJsonString(json, "\"preview_mp3_128_url\"")
                    ?: extractJsonString(json, "\"preview\"")
                Log.d(TAG, "mp3=$mp3Url preview=$previewUrl")
                when {
                    mp3Url != null -> return StreamResult(mp3Url, false)
                    previewUrl != null -> return StreamResult(previewUrl, true)
                }
            }
        }

        // Method 2: Cobalt
        val permalink = trackPermalink?.takeIf { it.isNotBlank() }
        if (permalink != null) {
            Log.d(TAG, "Trying Cobalt for $permalink")
            val cobaltUrl = getCobaltStreamUrl(permalink)
            if (cobaltUrl != null) {
                Log.d(TAG, "Cobalt succeeded: $cobaltUrl")
                return StreamResult(cobaltUrl, false)
            }
        } else {
            Log.w(TAG, "No permalink for track $trackId — can't use Cobalt")
        }

        Log.w(TAG, "All stream methods failed for track $trackId")
        return null
    }

    fun getCobaltStreamUrl(scUrl: String, cobaltBase: String = cobaltBaseUrl): String? {
        return try {
            // Cobalt API v7+ format
            val body = """{"url":"${scUrl.replace("\"","\\\"") }","audioFormat":"mp3","isAudioOnly":true,"aFormat":"mp3","filenameStyle":"basic"}"""
            val reqBody = body.toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$cobaltBase/")
                .post(reqBody)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .build()
            http.newCall(req).execute().use { resp ->
                val json = resp.body?.string() ?: return null
                Log.d(TAG, "Cobalt resp ${resp.code}: ${json.take(200)}")
                if (!resp.isSuccessful) return null
                // {"status":"stream"|"redirect"|"tunnel","url":"..."}
                extractJsonString(json, "\"url\"")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Cobalt error: $e")
            null
        }
    }

    // ── Trending ───────────────────────────────────────────────────────────────

    fun getTrending(genre: String? = null, limit: Int = 50): List<TrackInfo> {
        val cid = clientId() ?: run {
            Log.w(TAG, "getTrending: no client_id")
            return emptyList()
        }
        val genreParam = if (genre != null)
            "&genre=soundcloud%3Agenres%3A${java.net.URLEncoder.encode(genre, "UTF-8")}" else ""
        val url = "https://api-v2.soundcloud.com/charts?kind=top&limit=$limit$genreParam&client_id=$cid"
        Log.d(TAG, "getTrending: $url")
        val json = httpGetRaw(url, "OpenSound/1.0") ?: run {
            Log.w(TAG, "getTrending: null response")
            return emptyList()
        }
        Log.d(TAG, "getTrending response: ${json.take(300)}")
        return parseCollection(json)
    }

    fun search(query: String, limit: Int = 10): List<TrackInfo> {
        val cid = clientId() ?: return emptyList()
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val json = httpGetRaw(
            "https://api-v2.soundcloud.com/search/tracks?q=$q&limit=$limit&client_id=$cid",
            "OpenSound/1.0"
        ) ?: return emptyList()
        return parseCollection(json)
    }

    fun getRelated(trackId: String, limit: Int = 20): List<TrackInfo> {
        val cid = clientId() ?: return emptyList()
        val json = httpGetRaw(
            "https://api-v2.soundcloud.com/tracks/$trackId/related?limit=$limit&client_id=$cid",
            "OpenSound/1.0"
        ) ?: return emptyList()
        return parseCollection(json)
    }

    // ── JSON parsing ───────────────────────────────────────────────────────────

    private fun parseCollection(json: String): List<TrackInfo> {
        // Response can be {"collection":[...]} or [...]
        val trimmed = json.trimStart()
        val arrStart = if (trimmed.startsWith('[')) {
            json.indexOf('[')
        } else {
            val ci = json.indexOf("\"collection\"")
            if (ci < 0) {
                Log.w(TAG, "No 'collection' key and not array: ${json.take(100)}")
                return emptyList()
            }
            json.indexOf('[', ci)
        }
        if (arrStart < 0) return emptyList()
        val arrJson = extractJsonArray(json, arrStart) ?: return emptyList()
        val objects = splitJsonObjects(arrJson)
        Log.d(TAG, "parseCollection: ${objects.size} objects")
        return objects.mapNotNull { parseTrack(it) }
    }

    private fun parseTrack(obj: String): TrackInfo? {
        // Charts endpoint wraps track in {"score":...,"track":{...}}
        val trackObj = if (obj.contains("\"track\"") && obj.contains("\"score\""))
            extractJsonObjectBlock(obj, "\"track\"") ?: obj
        else obj

        val id    = extractJsonNumber(trackObj, "\"id\"") ?: return null
        val title = extractJsonString(trackObj, "\"title\"") ?: return null
        if (title.isBlank()) return null

        val userBlock = extractJsonObjectBlock(trackObj, "\"user\"")
        val artist    = if (userBlock != null) extractJsonString(userBlock, "\"username\"") ?: "" else ""
        val artworkRaw = extractJsonString(trackObj, "\"artwork_url\"") ?: ""
        val artwork = if (artworkRaw.isNotBlank())
            artworkRaw.replace("-large.", "-t500x500.") else null
        val durationMs = extractJsonNumber(trackObj, "\"duration\"")?.toLongOrNull() ?: 0L
        val genre      = extractJsonString(trackObj, "\"genre\"")
        val playCount  = extractJsonNumber(trackObj, "\"playback_count\"")?.toLongOrNull()
        val permalink  = extractJsonString(trackObj, "\"permalink_url\"")

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
            isUnplayable = false,
        )
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    fun httpGetRaw(url: String, ua: String): String? = try {
        val b = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/html, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
        if (oauthToken.isNotBlank()) b.header("Authorization", "OAuth $oauthToken")
        http.newCall(b.build()).execute().use { r ->
            if (!r.isSuccessful) {
                Log.w(TAG, "HTTP ${r.code} for $url")
                null
            } else r.body?.string()
        }
    } catch (e: Exception) {
        Log.e(TAG, "httpGet error for $url: $e")
        null
    }

    // ── JSON helpers ───────────────────────────────────────────────────────────

    fun extractJsonString(block: String, key: String): String? {
        var searchFrom = 0
        while (searchFrom < block.length) {
            val idx = block.indexOf(key, searchFrom); if (idx < 0) return null
            val colon = block.indexOf(':', idx + key.length); if (colon < 0) return null
            var i = colon + 1
            while (i < block.length && block[i].isWhitespace()) i++
            if (i >= block.length) return null
            if (block[i] == 'n') { searchFrom = idx + 1; continue } // null value, keep searching
            if (block[i] != '"') { searchFrom = idx + 1; continue }
            i++
            val sb = StringBuilder()
            while (i < block.length) {
                val c = block[i]
                if (c == '\\' && i + 1 < block.length) {
                    when (val n = block[++i]) {
                        'n' -> sb.append('\n'); 't' -> sb.append('\t')
                        '\\' -> sb.append('\\'); '"' -> sb.append('"'); '/' -> sb.append('/')
                        'u' -> if (i + 4 < block.length) {
                            runCatching { sb.append(block.substring(i+1, i+5).toInt(16).toChar()) }; i += 4
                        }
                        else -> sb.append(n)
                    }
                } else if (c == '"') return sb.toString().ifEmpty { null }
                else sb.append(c)
                i++
            }
            return null
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
            when {
                esc -> { sb.append(c); esc = false }
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
            when {
                esc -> { sb.append(c); esc = false }
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
        private const val TAG = "SoundCloudApi"

        // Known valid SC client_ids — update when these expire
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
