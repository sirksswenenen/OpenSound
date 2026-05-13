package com.soundcloud.lite.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Thin wrapper around SoundCloud's public `api-v2` endpoints. We never use
 * an OAuth-protected API: anonymous public traffic uses a rotating
 * `client_id` that SoundCloud's own web player ships with the JS bundle.
 *
 * Resolution order for `client_id`:
 *   1. [forcedClientId] (user-set in Settings) — wins if non-blank.
 *   2. Cached value from a previous successful call.
 *   3. The first entry in [KNOWN_CLIENT_IDS] that probes successfully.
 *   4. A live scrape of soundcloud.com (and its asset bundles).
 *
 * If all of the above fail we return `null` and the caller surfaces a
 * "couldn't reach SoundCloud" toast.
 */
class SoundCloudApi(
    private val http: OkHttpClient = defaultHttp(),
) {
    private val cachedClientId = AtomicReference<String?>(null)
    /**
     * Set of `forcedClientId` values we've already proved don't work.
     * The user can keep a bad value in Settings without us re-probing
     * (and re-failing) it on every API call. Cleared when the user
     * changes the Settings value to something different.
     */
    private val deadForcedIds = mutableSetOf<String>()
    @Volatile private var lastForcedSeen: String = ""
    var oauthToken: String = ""
    /**
     * Setting this to a non-blank string makes [clientId] **try** that id
     * first. If it works it's cached and used for everything; if it
     * doesn't (typo, revoked id, etc.) we fall back to the bundled known
     * list + a fresh scrape, exactly as if the user hadn't supplied
     * anything. We never silently keep using a bad user id — that's how
     * the old build wedged itself when the user typed a typo and got
     * "trends don't load" with no recourse.
     */
    var forcedClientId: String = ""

    /**
     * The id we're currently using (after [clientId] picks one). Read by
     * the UI to show the user which id is actually in effect, since
     * forcedClientId may be ignored if it failed its probe.
     */
    @Volatile var activeClientId: String? = null
        private set

    /** Last user-visible error from a request, or null. */
    @Volatile var lastError: String? = null
        private set

    // ── client_id ─────────────────────────────────────────────────────────────

    fun clientId(): String? {
        // Drop the "this forced id is dead" memo if the user has since
        // changed the value (e.g. fixed a typo in Settings).
        if (forcedClientId != lastForcedSeen) {
            deadForcedIds.removeAll { it == lastForcedSeen }
            cachedClientId.set(null)
            lastForcedSeen = forcedClientId
        }
        // 1. User-supplied id from Settings — probe once, cache result.
        //    If it works we use it; if not we fall through to the bundled
        //    list. We DON'T just trust the user blindly: a bad user id
        //    would otherwise poison every request silently.
        val forced = forcedClientId
        if (forced.isNotBlank() && forced !in deadForcedIds) {
            cachedClientId.get()?.let { cached ->
                if (cached == forced) return cached
            }
            if (probeClientId(forced)) {
                Log.d(TAG, "Using user-supplied client_id from Settings")
                cachedClientId.set(forced)
                activeClientId = forced
                lastError = null
                return forced
            }
            Log.w(TAG, "User-supplied client_id failed its probe — falling back to defaults")
            deadForcedIds.add(forced)
            lastError = "Your Settings client_id was rejected by SoundCloud — using the built-in fallback id instead."
        }
        cachedClientId.get()?.let {
            activeClientId = it
            return it
        }
        for (id in KNOWN_CLIENT_IDS) {
            if (probeClientId(id)) {
                Log.d(TAG, "Using known client_id: $id")
                cachedClientId.set(id)
                activeClientId = id
                return id
            }
        }
        val scraped = scrapeClientId()
        if (scraped != null) {
            Log.d(TAG, "Scraped client_id: $scraped")
            cachedClientId.set(scraped)
            activeClientId = scraped
            return scraped
        }
        Log.w(TAG, "Could not obtain client_id")
        lastError = "Couldn't obtain a SoundCloud client_id — check internet connectivity."
        activeClientId = null
        return null
    }

    /** Drop the cached id so the next request retries known + scrape paths. */
    fun invalidateClientId() = cachedClientId.set(null)

    private fun probeClientId(id: String): Boolean {
        val resp = httpGetRaw(
            "https://api-v2.soundcloud.com/tracks?ids=1193518076&client_id=$id",
            BROWSER_UA,
        ) ?: return false
        return !resp.contains("\"error\"") &&
            !resp.contains("\"status\":401") &&
            resp.contains("\"id\"")
    }

    private fun scrapeClientId(): String? {
        val html = httpGetRaw("https://soundcloud.com", BROWSER_UA) ?: return null
        SC_CLIENT_ID_RE.find(html)?.groupValues?.get(1)?.let { return it }
        SC_ASSET_URL_RE.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
            .takeLast(8)
            .forEach { url ->
                val js = httpGetRaw(url, BROWSER_UA) ?: return@forEach
                SC_CLIENT_ID_RE.find(js)?.groupValues?.get(1)?.let { return it }
            }
        return null
    }

    // ── Streams ────────────────────────────────────────────────────────────────

    data class StreamResult(val url: String, val isPreview: Boolean)

    /**
     * Resolve a playable HTTP audio URL for a SoundCloud track.
     *
     * The `streams` endpoint returns a small JSON blob containing both
     * progressive (`http_mp3_128_url`) and HLS (`hls_mp3_128_url`) variants
     * plus a 30-second `preview_mp3_128_url`. We prefer progressive MP3 since
     * ExoPlayer handles its 302 redirect to the signed CDN URL out of the
     * box. HLS is used as a second choice — ExoPlayer's HLS source factory
     * is wired up in [com.soundcloud.lite.player.PlaybackService]. Previews
     * are returned with `isPreview = true` so callers can warn the user.
     */
    fun getStreamUrl(trackId: String, trackPermalink: String? = null): StreamResult? {
        if (trackId.isBlank()) {
            Log.w(TAG, "getStreamUrl called with blank trackId")
            return null
        }

        // Attempt 1: api-v2 /streams with current client_id (and OAuth if set).
        val first = fetchStreamWith(trackId, useCache = true)
        if (first != null) return first

        // Attempt 2: invalidate the cached id (probably stale) and retry once.
        Log.d(TAG, "First streams attempt failed for $trackId — refreshing client_id")
        invalidateClientId()
        return fetchStreamWith(trackId, useCache = false)
    }

    private fun fetchStreamWith(trackId: String, useCache: Boolean): StreamResult? {
        if (!useCache) cachedClientId.set(null)
        val cid = clientId() ?: return null
        // Streams is the only endpoint where the user's OAuth actually
        // unlocks something (Go+ full-length playback). Pass it via the
        // Authorization header — which is what the SC web player uses.
        val url = "https://api-v2.soundcloud.com/tracks/$trackId/streams?client_id=$cid"
        val json = httpGetAuthed(url, BROWSER_UA) ?: return null
        Log.d(TAG, "streams response for $trackId: ${json.take(200)}")
        val mp3 = extractJsonString(json, "\"http_mp3_128_url\"")
            ?: extractJsonString(json, "\"http_mp3_128\"")
        if (mp3 != null) return StreamResult(mp3, false)
        val hls = extractJsonString(json, "\"hls_mp3_128_url\"")
            ?: extractJsonString(json, "\"hls_mp3_128\"")
        if (hls != null) return StreamResult(hls, false)
        val preview = extractJsonString(json, "\"preview_mp3_128_url\"")
            ?: extractJsonString(json, "\"preview\"")
        if (preview != null) return StreamResult(preview, true)
        return null
    }

    // ── Trending ───────────────────────────────────────────────────────────────

    /**
     * Top-50 chart. SoundCloud's `/charts` endpoint *requires* a `genre`
     * parameter — without one the server returns 422. We pass
     * `soundcloud:genres:all-music` by default which corresponds to the
     * "All music genres" tab on the SC web player. Callers may override
     * with a specific genre slug like `pop`, `electronic`, etc.
     */
    fun getTrending(genre: String? = null, limit: Int = 50): List<TrackInfo> {
        val cid = clientId() ?: run {
            Log.w(TAG, "getTrending: no client_id")
            return emptyList()
        }
        val genreSlug = genre ?: "all-music"
        val genreParam = "&genre=soundcloud%3Agenres%3A${java.net.URLEncoder.encode(genreSlug, "UTF-8")}"
        val url = "https://api-v2.soundcloud.com/charts?kind=top&limit=$limit$genreParam&client_id=$cid"
        Log.d(TAG, "getTrending: $url")
        val json = httpGetRaw(url, BROWSER_UA) ?: run {
            Log.w(TAG, "getTrending: null response (network or 4xx)")
            return emptyList()
        }
        Log.d(TAG, "getTrending response: ${json.take(300)}")
        return parseCollection(json)
    }

    fun search(query: String, limit: Int = 20, offset: Int = 0): List<TrackInfo> {
        if (query.isBlank()) return emptyList()
        val cid = clientId() ?: return emptyList()
        val q = java.net.URLEncoder.encode(query, "UTF-8")
        val url = "https://api-v2.soundcloud.com/search/tracks" +
            "?q=$q&limit=$limit&offset=$offset&client_id=$cid"
        val json = httpGetRaw(url, BROWSER_UA) ?: return emptyList()
        return parseCollection(json)
    }

    fun getRelated(trackId: String, limit: Int = 20): List<TrackInfo> {
        if (trackId.isBlank()) return emptyList()
        val cid = clientId() ?: return emptyList()
        val json = httpGetRaw(
            "https://api-v2.soundcloud.com/tracks/$trackId/related?limit=$limit&client_id=$cid",
            BROWSER_UA,
        ) ?: return emptyList()
        return parseCollection(json)
    }

    /** Look up full track metadata for a list of numeric SC ids. */
    fun getTracksByIds(ids: List<String>): List<TrackInfo> {
        if (ids.isEmpty()) return emptyList()
        val cid = clientId() ?: return emptyList()
        // SC accepts comma-separated lists; cap at 50 ids per call.
        return ids.chunked(50).flatMap { chunk ->
            val joined = chunk.joinToString(",")
            val url = "https://api-v2.soundcloud.com/tracks?ids=$joined&client_id=$cid"
            val json = httpGetRaw(url, BROWSER_UA) ?: return@flatMap emptyList()
            parseTrackArray(json)
        }
    }

    // ── JSON parsing ───────────────────────────────────────────────────────────

    private fun parseCollection(json: String): List<TrackInfo> {
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

    private fun parseTrackArray(json: String): List<TrackInfo> {
        val start = json.indexOf('[')
        if (start < 0) return emptyList()
        val arr = extractJsonArray(json, start) ?: return emptyList()
        return splitJsonObjects(arr).mapNotNull { parseTrack(it) }
    }

    private fun parseTrack(obj: String): TrackInfo? {
        val trackObj = if (obj.contains("\"track\"") && obj.contains("\"score\""))
            extractJsonObjectBlock(obj, "\"track\"") ?: obj
        else obj

        val id = extractJsonNumber(trackObj, "\"id\"") ?: return null
        val title = extractJsonString(trackObj, "\"title\"") ?: return null
        if (title.isBlank()) return null

        val userBlock = extractJsonObjectBlock(trackObj, "\"user\"")
        val artist = if (userBlock != null) extractJsonString(userBlock, "\"username\"") ?: "" else ""
        val avatar = if (userBlock != null) extractJsonString(userBlock, "\"avatar_url\"") else null

        val artworkRaw = extractJsonString(trackObj, "\"artwork_url\"") ?: ""
        val artwork = if (artworkRaw.isNotBlank())
            artworkRaw.replace("-large.", "-t500x500.") else avatar
        val durationMs = extractJsonNumber(trackObj, "\"duration\"")?.toLongOrNull() ?: 0L
        val genre = extractJsonString(trackObj, "\"genre\"")
        val playCount = extractJsonNumber(trackObj, "\"playback_count\"")?.toLongOrNull()
        val permalink = extractJsonString(trackObj, "\"permalink_url\"")

        return TrackInfo(
            id = stableIdHash("sc:$id"),
            providerId = id,
            provider = Provider.SOUNDCLOUD,
            title = title,
            artistName = artist,
            artworkUrl = artwork,
            avatarUrl = avatar,
            duration = durationMs,
            genre = genre,
            playCount = playCount,
            permalink = permalink,
            isUnplayable = false,
        )
    }

    // ── HTTP ───────────────────────────────────────────────────────────────────

    /**
     * Public anonymous GET. Deliberately **does not** attach the user's
     * OAuth token — if the token is expired SoundCloud returns 401 even
     * for endpoints that don't require auth (charts, search, public
     * tracks), which would silently break the whole app. Use
     * [httpGetAuthed] for endpoints that actually need OAuth (notably
     * `/streams` for Go+ subscribers).
     */
    fun httpGetRaw(url: String, ua: String): String? = httpGet(url, ua, withOAuth = false)

    private fun httpGetAuthed(url: String, ua: String): String? = httpGet(url, ua, withOAuth = true)

    private fun httpGet(url: String, ua: String, withOAuth: Boolean): String? = try {
        val b = Request.Builder().url(url)
            .header("User-Agent", ua)
            .header("Accept", "application/json, text/html, */*")
            .header("Accept-Language", "en-US,en;q=0.9")
        if (withOAuth && oauthToken.isNotBlank()) {
            b.header("Authorization", "OAuth $oauthToken")
        }
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
            if (block[i] == 'n') { searchFrom = idx + 1; continue }
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
                            runCatching { sb.append(block.substring(i + 1, i + 5).toInt(16).toChar()) }
                            i += 4
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
            }
            i++
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
            }
            i++
        }
        return null
    }

    private fun splitJsonObjects(arrayJson: String): List<String> {
        val result = mutableListOf<String>(); var i = 0
        while (i < arrayJson.length) {
            if (arrayJson[i] == '{') {
                val obj = extractJsonObject(arrayJson, i)
                if (obj != null) { result += obj; i += obj.length; continue }
            }
            i++
        }
        return result
    }

    companion object {
        private const val TAG = "SoundCloudApi"

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

        /**
         * Snapshots of SoundCloud's public anonymous `client_id`, in
         * roughly newest-first order. SC rotates this token every few
         * weeks: once all of these stop probing OK the scraper kicks in
         * and a fresh value is cached.
         */
        private val KNOWN_CLIENT_IDS = listOf(
            "gxPRNsEq7CDD7Wvem4iymWOq3YfU7KS8",
            "iZIs9mchVcX5lhVRyQGGAYlNPVldzAoX",
            "a3e059563d7fd3372b49b37f00a00bcf",
            "2t9loNQH90kzJcsFCODdigxfp325aq4z",
            "YUKXoArFcqrlQn9tfNHvvyfnDISj04zk",
            "68TuTMPkty1q8q8shN6eFMcbPoYoJiMf",
        )

        private val SC_CLIENT_ID_RE = Regex(
            """(?:client_id|clientId)\s*[=:]\s*["']([A-Za-z0-9]{20,50})["']"""
        )
        private val SC_ASSET_URL_RE = Regex(
            """<script[^>]+src="(https://a-v2\.sndcdn\.com/assets/[^"]+\.js[^"]*)""""
        )

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        /**
         * Folds a SoundCloud track id (which is a numeric string but we
         * deliberately treat as opaque) into a stable positive Long so
         * the rest of the app can keep using `TrackInfo.id: Long` as a
         * primary key. FNV-1a 64-bit; collisions are negligible for the
         * sizes we deal with at runtime.
         */
        fun stableIdHash(id: String): Long {
            var hash = -3750763034362895579L
            for (c in id) {
                hash = hash xor c.code.toLong()
                hash *= 1099511628211L
            }
            return hash and 0x7FFFFFFFFFFFFFFFL
        }
    }
}
