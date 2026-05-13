package com.soundcloud.lite.api

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
 *  3. Fan the partial ids out to `api-v2.soundcloud.com/tracks?ids=...`
 *     through [SoundCloudApi.getTracksByIds] to fetch full metadata.
 *  4. JSON-LD `MusicRecording` blocks act as a last-ditch fallback when
 *     hydration is missing (rare — only seen on classic-style pages).
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
        val u = url.trim().lowercase()
        if (!u.contains("soundcloud.com/")) return ImportResult.UnsupportedUrl

        val html = sc.httpGetRaw(url, BROWSER_UA)
            ?: return ImportResult.Error("Couldn't fetch playlist page")

        val hydration = extractHydration(html)
        if (hydration != null) {
            val parsed = parseHydration(hydration)
            if (parsed != null) {
                val full = sc.getTracksByIds(parsed.partialIds)
                val merged = (parsed.fullTracks + full).distinctBy { it.providerId }
                if (merged.isNotEmpty()) return ImportResult.Success(
                    title = parsed.title,
                    artworkUrl = parsed.artworkUrl,
                    tracks = merged,
                )
            }
        }
        return parseJsonLd(html)
            ?: ImportResult.Error("No playlist data found in page")
    }

    // ── Hydration ──────────────────────────────────────────────────────────────

    private data class HydrationResult(
        val title: String,
        val artworkUrl: String?,
        val fullTracks: List<TrackInfo>,
        val partialIds: List<String>,
    )

    private fun extractHydration(html: String): String? {
        val marker = "window.__sc_hydration = "
        val start = html.indexOf(marker); if (start < 0) return null
        val arrStart = html.indexOf('[', start + marker.length); if (arrStart < 0) return null
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
        if (keyIdx < 0) return HydrationResult(title, artwork, emptyList(), emptyList())
        val arrIdx = playlist.indexOf('[', keyIdx + tracksKey.length)
        if (arrIdx < 0) return HydrationResult(title, artwork, emptyList(), emptyList())
        val arr = extractArrayBlock(playlist, arrIdx)
            ?: return HydrationResult(title, artwork, emptyList(), emptyList())

        val full = mutableListOf<TrackInfo>()
        val partial = mutableListOf<String>()
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
                        val scId = sc.extractJsonString(obj, "\"id\"") ?: extractNumber(obj, "\"id\"")
                        val trackTitle = sc.extractJsonString(obj, "\"title\"")
                        if (scId != null && trackTitle != null) {
                            val user = extractObjectBlockByKey(obj, "\"user\"")
                            val artist = if (user != null) sc.extractJsonString(user, "\"username\"") ?: "" else ""
                            val durationMs = extractNumber(obj, "\"duration\"")?.toLongOrNull() ?: 0L
                            val artworkRaw = sc.extractJsonString(obj, "\"artwork_url\"") ?: ""
                            val artworkUrl = artworkRaw.takeIf { it.isNotBlank() }
                                ?.replace("-large.", "-t500x500.")
                            full += TrackInfo(
                                id = SoundCloudApi.stableIdHash("sc:$scId"),
                                providerId = scId,
                                provider = Provider.SOUNDCLOUD,
                                title = trackTitle,
                                artistName = artist,
                                artworkUrl = artworkUrl,
                                duration = durationMs,
                            )
                        } else if (scId != null) {
                            partial += scId
                        }
                    }
                }
                else -> sb.append(c)
            }
            i++
        }
        return HydrationResult(title, artwork, full, partial)
    }

    // ── JSON-LD fallback ───────────────────────────────────────────────────────

    private fun parseJsonLd(html: String): ImportResult.Success? {
        val match = SC_JSONLD_RE.find(html) ?: return null
        val json = match.groupValues[1]
        val name = sc.extractJsonString(json, "\"name\"") ?: "SoundCloud Playlist"
        val image = sc.extractJsonString(json, "\"image\"")
        val tracks = SC_TRACK_BLOCK_RE.findAll(json).mapNotNull { m ->
            val block = m.value
            val title = sc.extractJsonString(block, "\"name\"") ?: return@mapNotNull null
            val scId = sc.extractJsonString(block, "\"@id\"")
                ?.removePrefix("soundcloud:tracks:") ?: return@mapNotNull null
            val byArtistBlock = extractObjectBlockByKey(block, "\"byArtist\"")
            val artist = if (byArtistBlock != null) sc.extractJsonString(byArtistBlock, "\"name\"") ?: "" else ""
            TrackInfo(
                id = SoundCloudApi.stableIdHash("sc:$scId"),
                providerId = scId,
                provider = Provider.SOUNDCLOUD,
                title = title,
                artistName = artist,
                isUnplayable = false,
            )
        }.toList()
        if (tracks.isEmpty()) return null
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
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124 Mobile Safari/537.36"

        private val SC_JSONLD_RE = Regex(
            """<script type="application/ld\+json">(\{.+?\})</script>""",
            RegexOption.DOT_MATCHES_ALL,
        )
        private val SC_TRACK_BLOCK_RE = Regex(
            """\{(?:[^\{\}]|\{[^\{\}]*\})*?"@type"\s*:\s*"MusicRecording"""" +
                """(?:[^\{\}]|\{[^\{\}]*\})*?\}""",
            RegexOption.DOT_MATCHES_ALL,
        )
    }
}
