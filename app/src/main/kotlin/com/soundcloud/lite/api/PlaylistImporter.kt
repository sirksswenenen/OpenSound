package com.soundcloud.lite.api

import android.util.Log

/**
 * Imports a SoundCloud playlist (`/{user}/sets/{slug}`) into an in-memory
 * [ImportResult.Success]. Other services (YouTube, Spotify, etc.) were
 * supported in earlier versions but were removed when OpenSound became
 * SoundCloud-only.
 *
 * Strategy:
 *  1. Fetch the playlist page HTML using a desktop browser UA.
 *  2. Locate the `window.__sc_hydration` block — it always carries the
 *     playlist title, artwork and the full list of track ids (some
 *     tracks come back as partial objects).
 *  3. Collect **all** track ids from that array (full + partial) in
 *     playlist order, and fan them out to
 *     `api-v2.soundcloud.com/tracks?ids=...` through
 *     [SoundCloudApi.getTracksByIds] to fetch full metadata (titles,
 *     artists, **artwork**). This is critical — SC's hydration only
 *     embeds full data for the first ~5 tracks of a playlist, the rest
 *     are id-only stubs, so we must always go to the API.
 *  4. JSON-LD `MusicRecording` blocks act as a last-ditch fallback when
 *     hydration is missing (rare — only seen on classic-style pages or
 *     when SC briefly blocks our UA). We also pull full metadata via
 *     the API there.
 */
class PlaylistImporter(
    private val sc: SoundCloudApi,
) {
    sealed class ImportResult {
        data class Success(
            val title: String,
            val artworkUrl: String?,
            val tracks: List<TrackInfo>,
        ) : ImportResult()
        data class Error(val message: String) : ImportResult()
        data object UnsupportedUrl : ImportResult()
    }

    fun import(url: String): ImportResult {
        val cleanUrl = url.trim()
        val ulower = cleanUrl.lowercase()
        if (!ulower.contains("soundcloud.com/")) return ImportResult.UnsupportedUrl

        // Short-links like `https://on.soundcloud.com/<slug>` redirect
        // on the HTTP layer but SC's /resolve does NOT chase redirects
        // server-side. Expand them client-side first.
        val canonicalUrl = if (ulower.contains("on.soundcloud.com")) {
            sc.resolveFinalUrl(cleanUrl) ?: cleanUrl
        } else cleanUrl
        if (canonicalUrl != cleanUrl) {
            Log.i(TAG, "import: short-link $cleanUrl → $canonicalUrl")
        }

        // Path 1 — the right way. api-v2 /resolve returns the *full*
        // ordered list of track ids for the playlist (HTML hydration
        // only embeds 5).
        val resolved = sc.resolvePlaylist(canonicalUrl)
        if (resolved != null) {
            val byPid = sc.getTracksByIds(resolved.trackIds).associateBy { it.providerId }
            val ordered = resolved.trackIds.mapNotNull { byPid[it] }
            if (ordered.isNotEmpty()) {
                Log.i(TAG, "import: /resolve → ${ordered.size}/${resolved.trackIds.size} tracks")
                return ImportResult.Success(
                    title = resolved.title,
                    artworkUrl = resolved.artworkUrl,
                    tracks = ordered,
                )
            }
            Log.w(TAG, "import: /resolve gave ${resolved.trackIds.size} ids but /tracks?ids= returned 0")
        }

        // Path 2 — HTML hydration fallback (older SC layouts).
        val html = sc.httpGetRaw(canonicalUrl, BROWSER_UA)
            ?: return ImportResult.Error("Couldn't fetch playlist page")

        val hydration = extractHydration(html)
        if (hydration != null) {
            val parsed = parseHydration(hydration)
            if (parsed != null && parsed.orderedIds.isNotEmpty()) {
                val tracks = resolveTracks(parsed.orderedIds, parsed.embeddedByPid)
                if (tracks.isNotEmpty()) {
                    Log.i(TAG, "import: hydration → ${tracks.size}/${parsed.orderedIds.size} tracks")
                    return ImportResult.Success(
                        title = parsed.title,
                        artworkUrl = parsed.artworkUrl,
                        tracks = tracks,
                    )
                }
            }
        } else {
            Log.w(TAG, "import: no hydration block found, falling back to JSON-LD")
        }

        // Path 3 — JSON-LD fallback (last-ditch, classic-style pages).
        return parseJsonLd(html) ?: ImportResult.Error("No playlist data found in page")
    }

    /**
     * Resolve a list of provider ids to full [TrackInfo]s.
     *
     *  - Calls the SC `tracks?ids=…` endpoint in chunks of 50 (handled
     *    by [SoundCloudApi.getTracksByIds]).
     *  - Preserves playlist order from [orderedIds].
     *  - For ids the API didn't return, falls back to whatever embedded
     *    metadata we already have (so the user at least sees a title,
     *    even if artwork is missing for a single stubborn track).
     */
    private fun resolveTracks(
        orderedIds: List<String>,
        embeddedByPid: Map<String, TrackInfo>,
    ): List<TrackInfo> {
        val fromApi = sc.getTracksByIds(orderedIds).associateBy { it.providerId }
        Log.d(TAG, "resolveTracks: ${fromApi.size}/${orderedIds.size} returned by API")
        return orderedIds.mapNotNull { pid ->
            fromApi[pid] ?: embeddedByPid[pid]
        }
    }

    // ── Hydration ──────────────────────────────────────────────────────────────

    private data class HydrationResult(
        val title: String,
        val artworkUrl: String?,
        /** Track provider-ids in playlist order, deduped. */
        val orderedIds: List<String>,
        /** Embedded TrackInfo per provider-id (only first ~5 are full). */
        val embeddedByPid: Map<String, TrackInfo>,
    )

    private fun extractHydration(html: String): String? {
        // Tolerate format variants:
        //   window.__sc_hydration = [...]
        //   window.__sc_hydration=[...]
        //   window.__sc_hydration =[...]
        val match = HYDRATION_RE.find(html) ?: return null
        val arrStart = match.range.last  // position of the '[' captured by regex
        // arrStart points to the '[' char.
        return extractArrayBlock(html, arrStart)
    }

    private fun parseHydration(hydration: String): HydrationResult? {
        val playlistMarker = "\"hydratable\":\"playlist\""
        val markerIdx = hydration.indexOf(playlistMarker); if (markerIdx < 0) return null
        val dataIdx = hydration.indexOf("\"data\":", markerIdx); if (dataIdx < 0) return null
        val objStart = hydration.indexOf('{', dataIdx); if (objStart < 0) return null
        val playlist = extractObjectBlock(hydration, objStart) ?: return null

        val title = sc.extractJsonString(playlist, "\"title\"") ?: "SoundCloud Playlist"
        val artwork = sc.extractJsonString(playlist, "\"artwork_url\"")
            ?.replace("-large.", "-t500x500.")

        val tracksKey = "\"tracks\":"
        val keyIdx = playlist.indexOf(tracksKey)
        if (keyIdx < 0) return HydrationResult(title, artwork, emptyList(), emptyMap())
        val arrIdx = playlist.indexOf('[', keyIdx + tracksKey.length)
        if (arrIdx < 0) return HydrationResult(title, artwork, emptyList(), emptyMap())
        val arr = extractArrayBlock(playlist, arrIdx)
            ?: return HydrationResult(title, artwork, emptyList(), emptyMap())

        val orderedIds = mutableListOf<String>()
        val seenIds = HashSet<String>()
        val embedded = HashMap<String, TrackInfo>()

        var depth = 0; val sb = StringBuilder(); var i = 0
        var inStr = false; var esc = false
        while (i < arr.length) {
            val c = arr[i]
            when {
                esc -> { sb.append(c); esc = false }
                c == '\\' && inStr -> { sb.append(c); esc = true }
                c == '"' -> { sb.append(c); inStr = !inStr }
                inStr -> sb.append(c)
                c == '{' -> { depth++; sb.append(c) }
                c == '}' -> {
                    depth--; sb.append(c)
                    if (depth == 0) {
                        val obj = sb.toString(); sb.clear()
                        val scId = sc.extractJsonString(obj, "\"id\"")
                            ?: extractNumber(obj, "\"id\"")
                        if (scId != null && seenIds.add(scId)) {
                            orderedIds += scId
                            // If the embedded entry happens to be a full
                            // track (first ~5 of the playlist), preserve
                            // a TrackInfo for it so we can fall back to
                            // it if the API call drops this id.
                            val trackTitle = sc.extractJsonString(obj, "\"title\"")
                            if (!trackTitle.isNullOrBlank()) {
                                val user = extractObjectBlockByKey(obj, "\"user\"")
                                val artist = if (user != null)
                                    sc.extractJsonString(user, "\"username\"") ?: ""
                                else ""
                                val durationMs =
                                    extractNumber(obj, "\"duration\"")?.toLongOrNull() ?: 0L
                                val artworkRaw = sc.extractJsonString(obj, "\"artwork_url\"") ?: ""
                                val artworkUrl = artworkRaw.takeIf { it.isNotBlank() }
                                    ?.replace("-large.", "-t500x500.")
                                embedded[scId] = TrackInfo(
                                    id = SoundCloudApi.stableIdHash("sc:$scId"),
                                    providerId = scId,
                                    provider = Provider.SOUNDCLOUD,
                                    title = trackTitle,
                                    artistName = artist,
                                    artworkUrl = artworkUrl,
                                    duration = durationMs,
                                )
                            }
                        }
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        Log.d(TAG, "parseHydration: ${orderedIds.size} ids (${embedded.size} embedded full)")
        return HydrationResult(title, artwork, orderedIds, embedded)
    }

    // ── JSON-LD fallback ───────────────────────────────────────────────────────

    private fun parseJsonLd(html: String): ImportResult.Success? {
        val match = SC_JSONLD_RE.find(html) ?: return null
        val json = match.groupValues[1]
        val name = sc.extractJsonString(json, "\"name\"") ?: "SoundCloud Playlist"
        val image = sc.extractJsonString(json, "\"image\"")

        // Pull every "soundcloud:tracks:N" id we can see, in document
        // order. This is more robust than walking individual
        // MusicRecording blocks (which often only list the first few).
        val orderedIds = SC_TRACK_ID_RE.findAll(json)
            .map { it.groupValues[1] }
            .distinct()
            .toList()
        if (orderedIds.isEmpty()) return null

        // Also keep the minimal embedded data we can extract as a
        // best-effort fallback for tracks the API can't return.
        val embedded = HashMap<String, TrackInfo>()
        for (m in SC_TRACK_BLOCK_RE.findAll(json)) {
            val block = m.value
            val scId = sc.extractJsonString(block, "\"@id\"")
                ?.removePrefix("soundcloud:tracks:") ?: continue
            val title = sc.extractJsonString(block, "\"name\"") ?: continue
            val byArtistBlock = extractObjectBlockByKey(block, "\"byArtist\"")
            val artist = if (byArtistBlock != null)
                sc.extractJsonString(byArtistBlock, "\"name\"") ?: "" else ""
            embedded[scId] = TrackInfo(
                id = SoundCloudApi.stableIdHash("sc:$scId"),
                providerId = scId,
                provider = Provider.SOUNDCLOUD,
                title = title,
                artistName = artist,
                isUnplayable = false,
            )
        }

        val tracks = resolveTracks(orderedIds, embedded)
        if (tracks.isEmpty()) return null
        Log.i(TAG, "import: JSON-LD → ${tracks.size}/${orderedIds.size} tracks")
        return ImportResult.Success(title = name, artworkUrl = image, tracks = tracks)
    }

    // ── Local lightweight JSON helpers (subset of SoundCloudApi's) ──────────────

    private fun extractObjectBlockByKey(block: String, key: String): String? {
        val idx = block.indexOf(key); if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length); if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length || block[i] != '{') return null
        return extractObjectBlock(block, i)
    }

    private fun extractObjectBlock(json: String, start: Int): String? {
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

    private fun extractArrayBlock(json: String, start: Int): String? {
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

    private fun extractNumber(block: String, key: String): String? {
        val idx = block.indexOf(key); if (idx < 0) return null
        val colon = block.indexOf(':', idx + key.length); if (colon < 0) return null
        var i = colon + 1
        while (i < block.length && block[i].isWhitespace()) i++
        if (i >= block.length || (!block[i].isDigit() && block[i] != '-')) return null
        val sb = StringBuilder()
        while (i < block.length && (block[i].isDigit() || block[i] == '-' || block[i] == '.')) sb.append(block[i++])
        return sb.toString().ifEmpty { null }
    }

    private companion object {
        private const val TAG = "PlaylistImporter"

        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

        /**
         * Matches `window.__sc_hydration <ws>* = <ws>* [` and anchors on
         * the opening `[` so [extractHydration] can extract the array
         * body via depth tracking.
         */
        private val HYDRATION_RE = Regex("""window\.__sc_hydration\s*=\s*\[""")

        private val SC_JSONLD_RE = Regex(
            """<script type="application/ld\+json">(\{.+?\})</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val SC_TRACK_BLOCK_RE = Regex(
            """\{(?:[^\{\}]|\{[^\{\}]*\})*?"@type"\s*:\s*"MusicRecording"""" +
                """(?:[^\{\}]|\{[^\{\}]*\})*?\}""",
            RegexOption.DOT_MATCHES_ALL,
        )
        /** Catches every `soundcloud:tracks:<id>` reference in JSON-LD. */
        private val SC_TRACK_ID_RE = Regex("""soundcloud:tracks:(\d+)""")
    }
}
