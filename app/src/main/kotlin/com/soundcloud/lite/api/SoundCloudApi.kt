package com.soundcloud.lite.api

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal projection of an SC `/resolve` response for a playlist:
 * the title and cover art for the playlist itself, plus the ordered
 * list of track ids (which the caller is expected to fan out via
 * [SoundCloudApi.getTracksByIds] to retrieve full per-track metadata).
 */
data class ResolvedPlaylist(
    val title: String,
    val artworkUrl: String?,
    val trackIds: List<String>,
)

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

    /**
     * A client_id is "good" if it returns a populated /charts trending
     * page. We deliberately don't use `/tracks?ids=...` (the old probe)
     * because a perfectly working id can still return an empty `[]` if
     * the hard-coded test id was deleted on SC — which is exactly what
     * killed every probe in v0.5.0–0.5.2.
     */
    private fun probeClientId(id: String): Boolean {
        val resp = httpGetRaw(
            "https://api-v2.soundcloud.com/charts" +
                "?kind=trending&genre=soundcloud:genres:all-music" +
                "&limit=1&client_id=$id",
            BROWSER_UA,
        ) ?: return false
        // Success looks like `{"genre":"...", "kind":"trending", "collection":[ { "track": {...} } ]}`
        // A bad client_id 404s (resp = null, handled above) or returns `{\"errors\":[...]}` / `{}`.
        if (resp.startsWith("{}")) return false
        if (resp.contains("\"errors\"")) return false
        return resp.contains("\"track\"") && resp.contains("\"id\"")
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
     * The legacy `/tracks/{id}/streams` endpoint **is gone** — it now 404s
     * for everyone. The working flow that the SC web player uses today is:
     *   1. GET `/tracks?ids={id}` (or `/tracks/{id}`) — the response
     *      includes a `media.transcodings[]` array.
     *   2. Pick a transcoding (we prefer `progressive` over `hls`, and
     *      anything non-`SUB_HIGH_TIER` over `SUB_HIGH_TIER` so non-Go+
     *      requests succeed).
     *   3. GET the transcoding's `url` with `?client_id=...` (and the
     *      user's OAuth if they have a Go+ token saved) — the response
     *      is `{"url": "<signed cdn url>"}`.
     *   4. ExoPlayer can play the signed mp3 / HLS URL directly.
     */
    fun getStreamUrl(trackId: String, trackPermalink: String? = null): StreamResult? {
        if (trackId.isBlank()) {
            Log.w(TAG, "getStreamUrl called with blank trackId")
            return null
        }
        val first = fetchStreamWith(trackId, useCache = true)
        if (first != null) return first
        Log.d(TAG, "First streams attempt failed for $trackId — refreshing client_id")
        invalidateClientId()
        return fetchStreamWith(trackId, useCache = false)
    }

    private fun fetchStreamWith(trackId: String, useCache: Boolean): StreamResult? {
        if (!useCache) cachedClientId.set(null)
        val cid = clientId() ?: return null
        // 1. Pull the full track JSON to find the transcoding URLs.
        val trackUrl = "https://api-v2.soundcloud.com/tracks/$trackId?client_id=$cid"
        val trackJson = httpGetRaw(trackUrl, BROWSER_UA) ?: run {
            lastError = "SoundCloud rejected the request for track $trackId."
            return null
        }
        val transcodings = parseTranscodings(trackJson)
        if (transcodings.isEmpty()) {
            Log.w(TAG, "No transcodings for $trackId: ${trackJson.take(200)}")
            lastError = "That track has no playable transcodings (private, removed, or region-blocked)."
            return null
        }
        // 2. Sort: progressive > hls; within those, non-SUB_HIGH_TIER first
        //    (Go+ exclusive). We pick the first that resolves to a real URL.
        val sorted = transcodings.sortedWith(
            compareBy(
                { if (it.protocol == "progressive") 0 else 1 },
                { if (it.quality == "sq") 0 else 1 },
                { if (it.snippet) 1 else 0 },
            ),
        )
        for (tr in sorted) {
            val sep = if (tr.url.contains('?')) '&' else '?'
            val resolveUrl = "${tr.url}${sep}client_id=$cid"
            // Anonymous (client_id-only) request. Sending the user's
            // OAuth token here would surface 401s if the token is
            // stale, and the public progressive/HLS URLs don't need
            // OAuth to play. Go+ exclusive transcodings will simply
            // be skipped if anonymous resolution returns 401/403.
            val json = httpGetRaw(resolveUrl, BROWSER_UA) ?: continue
            val signed = extractJsonString(json, "\"url\"") ?: continue
            Log.d(TAG, "Resolved ${tr.protocol} for $trackId (snippet=${tr.snippet})")
            lastError = null
            return StreamResult(signed, isPreview = tr.snippet)
        }
        lastError = "SoundCloud refused to sign any stream URL for this track."
        return null
    }

    private data class Transcoding(
        val url: String,
        val protocol: String,
        val quality: String,
        val snippet: Boolean,
    )

    private fun parseTranscodings(trackJson: String): List<Transcoding> {
        val transBlock = extractJsonArrayBlock(trackJson, "\"transcodings\"") ?: return emptyList()
        val items = splitJsonObjects(transBlock)
        return items.mapNotNull { obj ->
            val u = extractJsonString(obj, "\"url\"") ?: return@mapNotNull null
            val formatBlock = extractJsonObjectBlock(obj, "\"format\"") ?: ""
            val protocol = extractJsonString(formatBlock, "\"protocol\"") ?: "hls"
            val quality = extractJsonString(obj, "\"quality\"") ?: "sq"
            val snippet = obj.contains("\"snipped\":true")
            Transcoding(u, protocol, quality, snippet)
        }
    }

    // ── Trending ───────────────────────────────────────────────────────────────

    /**
     * Top trending tracks. SoundCloud's `/charts` endpoint **requires** a
     * non-empty `genre` AND a `kind`. The web player uses
     * `kind=trending` (NOT `top` — `top` 404s for every genre we've
     * tried as of 2026). Genre is `soundcloud:genres:<slug>`; the slug
     * must be a real SC genre slug — `all-music` is the catch-all.
     */
    fun getTrending(genre: String? = null, limit: Int = 50): List<TrackInfo> {
        val cid = clientId() ?: run {
            Log.w(TAG, "getTrending: no client_id")
            return emptyList()
        }
        val genreSlug = genre ?: "all-music"
        val url = "https://api-v2.soundcloud.com/charts" +
            "?kind=trending&genre=soundcloud:genres:$genreSlug" +
            "&limit=$limit&client_id=$cid"
        Log.d(TAG, "getTrending: $url")
        val json = httpGetRaw(url, BROWSER_UA) ?: run {
            Log.w(TAG, "getTrending: null response (network or 4xx)")
            lastError = "Couldn't reach SoundCloud charts (network or rate-limit)."
            return emptyList()
        }
        Log.d(TAG, "getTrending response: ${json.take(300)}")
        val parsed = parseCollection(json)
        if (parsed.isEmpty() && json.contains("\"errors\"")) {
            lastError = "SoundCloud rejected the trending query: ${json.take(200)}"
        }
        return parsed
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

    /**
     * Follow HTTP redirects for [url] and return the final destination
     * URL (or null on error). Used to expand SC short-links like
     * `https://on.soundcloud.com/<slug>` into the canonical
     * `https://soundcloud.com/<user>/sets/<slug>` URL before passing
     * them to [resolvePlaylist] (SC's `/resolve` endpoint does NOT
     * follow short-link redirects server-side).
     */
    fun resolveFinalUrl(url: String, ua: String = BROWSER_UA): String? = try {
        val req = Request.Builder().url(url).header("User-Agent", ua).build()
        http.newCall(req).execute().use { r ->
            if (r.isSuccessful) r.request.url.toString() else null
        }
    } catch (e: Exception) {
        Log.e(TAG, "resolveFinalUrl error for $url: $e")
        null
    }

    /**
     * Look up a SoundCloud playlist by its canonical URL through the
     * official `api-v2.soundcloud.com/resolve` endpoint. Unlike the
     * HTML hydration block (which only embeds the first ~5 tracks of a
     * playlist), `/resolve` returns the **full** ordered `tracks`
     * array as id-only stubs, so callers can then fan out to
     * [getTracksByIds] to retrieve full metadata + artwork.
     *
     * Returns null if there's no client id, the request fails, or the
     * response is not a playlist (e.g. caller pointed it at a track URL).
     */
    fun resolvePlaylist(url: String): ResolvedPlaylist? {
        val cid = clientId() ?: return null
        val encoded = URLEncoder.encode(url, "UTF-8")
        val apiUrl = "https://api-v2.soundcloud.com/resolve?url=$encoded&client_id=$cid"
        val json = httpGetRaw(apiUrl, BROWSER_UA) ?: run {
            Log.w(TAG, "resolvePlaylist: HTTP failed for $url")
            return null
        }
        val tracksArr = extractJsonArrayBlock(json, "\"tracks\"") ?: run {
            Log.w(TAG, "resolvePlaylist: no \"tracks\" array in /resolve response for $url")
            return null
        }
        val objects = splitJsonObjects(tracksArr)
        val ids = objects.mapNotNull { extractJsonNumber(it, "\"id\"") }
        if (ids.isEmpty()) {
            Log.w(TAG, "resolvePlaylist: tracks[] present but no ids extractable")
            return null
        }
        val title = extractJsonString(json, "\"title\"") ?: "SoundCloud Playlist"
        val artwork = extractJsonString(json, "\"artwork_url\"")
            ?.replace("-large.", "-t500x500.")
        Log.i(TAG, "resolvePlaylist: $url → ${ids.size} tracks, title='$title'")
        return ResolvedPlaylist(title, artwork, ids)
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

    /**
     * Parses SC's `collection: [...]` (or bare top-level array) of track
     * entries.
     *
     * **Deduplicates by `providerId`**: `/charts?kind=trending` will
     * occasionally include the same track in multiple chart positions,
     * and `/search/tracks` can echo a track that's also surfaced via
     * `relatedTo`. The downstream `LazyColumn` keys items by id and
     * crashes on duplicates, so we drop them here.
     */
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
        return objects.mapNotNull { parseTrack(it) }.dedupById()
    }

    private fun parseTrackArray(json: String): List<TrackInfo> {
        val start = json.indexOf('[')
        if (start < 0) return emptyList()
        val arr = extractJsonArray(json, start) ?: return emptyList()
        return splitJsonObjects(arr).mapNotNull { parseTrack(it) }.dedupById()
    }

    /**
     * Drop duplicates from the SC response. `/charts` returns the same
     * track multiple times for repeated chart positions, which crashes
     * LazyColumn's stable-key contract downstream.
     */
    private fun List<TrackInfo>.dedupById(): List<TrackInfo> {
        if (size <= 1) return this
        val seen = HashSet<String>(size)
        val out = ArrayList<TrackInfo>(size)
        for (t in this) {
            val key = if (t.providerId.isNotBlank()) t.providerId else t.id.toString()
            if (seen.add(key)) out.add(t)
        }
        return out
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

    private fun extractJsonArrayBlock(block: String, key: String): String? {
        val idx = block.indexOf(key); if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length); if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length || block[i] != '[') return null
        return extractJsonArray(block, i)
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
