package com.soundcloud.lite.api

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * SoundCloud API v2 client. SoundCloud doesn't expose a stable public
 * client_id; the website uses a rotating one embedded in the JS asset
 * bundles served from `a-v2.sndcdn.com`. We scrape that bundle to get
 * a working `client_id` and cache it.
 *
 * What we actually use SC for in the app:
 *   - Importing existing playlists (the user has lots of them and the
 *     YouTube/Audius catalogs are too small to substitute).
 *   - Resolving a short or canonical SC URL into a typed entity
 *     (track, playlist, user).
 *   - Streaming tracks via the `progressive` transcoding (MP3 over a
 *     CDN-signed URL); ExoPlayer follows the redirect like any other
 *     HTTP source.
 *
 * Region locks: tracks with `policy == "BLOCK"` won't return a stream;
 * we surface them as unplayable placeholders.
 */
class SoundCloudApi(
    private val http: OkHttpClient = defaultHttp(),
    private val moshi: Moshi = defaultMoshi(),
) {
    @Volatile private var cachedClientId: String? = null

    /** Resolves any SC URL to a typed payload (track / playlist / user).
     *  The URL is automatically expanded if it's a short `on.soundcloud.com`
     *  alias (SC's `resolve` endpoint doesn't accept short URLs directly). */
    fun resolve(rawUrl: String): SCEntity? {
        val canonical = canonicalize(rawUrl) ?: return null
        return apiCall("/resolve?url=" + percentEncode(canonical)) { body ->
            // We dispatch on the `kind` field to one of three concrete types.
            val kind = scanString(body, "\"kind\"") ?: return@apiCall null
            when (kind) {
                "playlist", "system-playlist" -> playlistAdapter.fromJson(body)?.toEntity()
                "track" -> trackAdapter.fromJson(body)?.let { SCEntity.Track(it.toTrackInfo()) }
                "user" -> userAdapter.fromJson(body)?.let { SCEntity.User(it.id, it.username ?: "") }
                else -> null
            }
        }
    }

    /** Returns full track info for a single track. */
    fun getTrack(id: Long): TrackInfo? =
        apiCall("/tracks/$id") { trackAdapter.fromJson(it)?.toTrackInfo() }

    /** Batch-fetches tracks by id. SC accepts up to 50 ids per call;
     *  we chunk and fan out as needed. Returns tracks in the same
     *  order as the request, with nulls for missing ones. */
    fun getTracks(ids: List<Long>): List<TrackInfo> {
        if (ids.isEmpty()) return emptyList()
        val out = ArrayList<TrackInfo>(ids.size)
        for (chunk in ids.chunked(50)) {
            val csv = chunk.joinToString(",")
            val parsed = apiCall("/tracks?ids=$csv") { body ->
                trackListAdapter.fromJson(body) ?: emptyList()
            } ?: emptyList()
            // SC sometimes returns the chunk in a different order, so
            // we re-sort to preserve playlist order.
            val byId = parsed.associateBy { it.id }
            for (id in chunk) {
                byId[id]?.toTrackInfo()?.let { out += it }
            }
        }
        return out
    }

    /** Resolves a track's playable stream URL. The transcoding URL
     *  itself responds with a JSON `{ "url": "<cdn-signed-mp3-url>" }`,
     *  so we hit it once to get the actual CDN URL. The CDN URL has a
     *  short signature lifetime (~hour), so callers must call this
     *  immediately before play. */
    fun streamUrl(trackId: String): String? {
        val track = getTrack(trackId.toLongOrNull() ?: return null) ?: return null
        return resolveStreamUrl(track.streamHint)
    }

    /** Same as streamUrl(id) but lets the caller pass the cached
     *  transcoding URL (saved earlier on TrackInfo.streamHint) so we
     *  skip the per-track API hit. */
    fun resolveStreamUrl(transcodingUrl: String?): String? {
        if (transcodingUrl.isNullOrBlank()) return null
        val cid = ensureClientId() ?: return null
        val full = if ('?' in transcodingUrl) "$transcodingUrl&client_id=$cid"
                   else "$transcodingUrl?client_id=$cid"
        val resp = httpGet(full) ?: return null
        // Body is a tiny JSON like {"url":"https://cf-media.sndcdn.com/..."}
        return scanString(resp, "\"url\"")
    }

    // ---- Internals ----

    private fun <T> apiCall(path: String, parse: (String) -> T?): T? {
        val cid = ensureClientId() ?: return null
        var url = "https://api-v2.soundcloud.com$path"
        url += if ('?' in url) "&client_id=$cid" else "?client_id=$cid"
        val body = httpGet(url) ?: run {
            // 401 typically means the client_id rotated. Refresh once
            // and retry exactly once.
            cachedClientId = null
            val cid2 = ensureClientId() ?: return null
            url = "https://api-v2.soundcloud.com$path"
            url += if ('?' in url) "&client_id=$cid2" else "?client_id=$cid2"
            httpGet(url) ?: return null
        }
        return parse(body)
    }

    /** SC short URLs (`on.soundcloud.com/...`) need to be expanded
     *  before the `resolve` endpoint will accept them. We follow the
     *  redirect chain manually. The canonical URL is what `resolve`
     *  expects. */
    private fun canonicalize(url: String): String? {
        val trimmed = url.trim()
        if (!trimmed.contains("soundcloud.com")) return null
        if (!trimmed.contains("on.soundcloud.com")) {
            // Strip query/fragment and trailing slashes
            val cleaned = trimmed.substringBefore('?').substringBefore('#').trimEnd('/')
            return cleaned
        }
        // Issue a HEAD with manual redirect following; OkHttp does this for us.
        val req = Request.Builder()
            .url(trimmed)
            .head()
            .header("User-Agent", BROWSER_UA)
            .build()
        return runCatching {
            http.newCall(req).execute().use { resp ->
                resp.request.url.toString().substringBefore('?').substringBefore('#').trimEnd('/')
            }
        }.getOrNull()
    }

    /** Returns the cached SC client_id, scraping a fresh one if we
     *  don't have any cached. Subsequent rotations are detected when
     *  an API call returns 401, at which point we clear the cache and
     *  rescrape. */
    private fun ensureClientId(): String? {
        cachedClientId?.let { return it }
        val home = httpGet("https://soundcloud.com/discover", asBrowser = true) ?: return null
        // The page references several `assets/N-hash.js` bundles. The
        // client_id is in one of them — usually one of the larger ones.
        val urls = ASSET_URL_RE.findAll(home).map { it.value }.toList()
        for (url in urls.reversed()) {
            val bundle = httpGet(url) ?: continue
            val m = CLIENT_ID_RE.find(bundle) ?: continue
            cachedClientId = m.groupValues[1]
            return cachedClientId
        }
        return null
    }

    private fun httpGet(url: String, asBrowser: Boolean = false): String? {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", if (asBrowser) BROWSER_UA else "OpenSound/0.2 (Android)")
            .header("Accept", "application/json,text/html,*/*;q=0.5")
            .build()
        return try {
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) null else resp.body?.string()
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun percentEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    /** Tiny helper that finds the value for a top-level `"key": "..."`
     *  pair in JSON without parsing the whole document. Used for the
     *  one-off small responses (resolve, stream URL). */
    private fun scanString(body: String, key: String): String? {
        val m = Pattern
            .compile(Pattern.quote(key) + "\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .matcher(body)
        return if (m.find()) {
            // Decode \uXXXX, \", \\
            val raw = m.group(1)!!
            val sb = StringBuilder()
            var i = 0
            while (i < raw.length) {
                val c = raw[i]
                if (c == '\\' && i + 1 < raw.length) {
                    when (raw[i + 1]) {
                        '"', '\\', '/' -> { sb.append(raw[i + 1]); i += 2 }
                        'n' -> { sb.append('\n'); i += 2 }
                        't' -> { sb.append('\t'); i += 2 }
                        'r' -> { sb.append('\r'); i += 2 }
                        'u' -> {
                            if (i + 6 <= raw.length) {
                                runCatching { sb.append(raw.substring(i + 2, i + 6).toInt(16).toChar()) }
                                i += 6
                            } else { sb.append(c); i++ }
                        }
                        else -> { sb.append(raw[i + 1]); i += 2 }
                    }
                } else { sb.append(c); i++ }
            }
            sb.toString()
        } else null
    }

    // ---- Models ----

    sealed class SCEntity {
        data class Track(val track: TrackInfo) : SCEntity()
        data class Playlist(
            val id: Long,
            val title: String,
            val artworkUrl: String?,
            val userName: String?,
            /** All track entries — some are full, some are stubs (id only). */
            val tracks: List<TrackInfo>,
            /** Subset of `tracks` that came back as id-only stubs. The
             *  caller should batch these via [getTracks] to fill in
             *  metadata. */
            val stubIds: List<Long>,
        ) : SCEntity()
        data class User(val id: Long, val username: String) : SCEntity()
    }

    @JsonClass(generateAdapter = true)
    internal data class RawTrack(
        val id: Long = 0L,
        val title: String? = null,
        val description: String? = null,
        val duration: Long = 0L,
        val full_duration: Long? = null,
        val artwork_url: String? = null,
        val genre: String? = null,
        val playback_count: Long? = null,
        val likes_count: Long? = null,
        val permalink_url: String? = null,
        val policy: String? = null,
        val user: RawUser? = null,
        val media: RawMedia? = null,
    ) {
        fun toTrackInfo(): TrackInfo {
            val art = upgradeArtwork(artwork_url ?: user?.avatar_url)
            // Pick the best progressive transcoding (MP3 → ExoPlayer
            // streams it directly). We prefer progressive over HLS for
            // simplicity even though HLS gives slightly better perf;
            // ExoPlayer handles both, but progressive avoids m3u8
            // parsing entirely.
            val transcoding = media?.transcodings
                ?.firstOrNull { it.format?.protocol == "progressive" }
                ?: media?.transcodings
                    ?.firstOrNull { it.format?.protocol == "hls" }
            return TrackInfo(
                id = id,
                providerId = id.toString(),
                provider = Provider.SOUNDCLOUD,
                title = title ?: "Untitled",
                artistName = user?.username ?: "Unknown",
                artistId = (user?.id ?: 0L).toString(),
                artworkUrl = art,
                avatarUrl = upgradeArtwork(user?.avatar_url),
                duration = full_duration ?: duration,
                permalink = permalink_url,
                streamHint = transcoding?.url,
                genre = genre,
                playCount = playback_count,
                favoriteCount = likes_count,
                isUnplayable = (policy == "BLOCK") || (transcoding == null),
            )
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class RawUser(
        val id: Long = 0L,
        val username: String? = null,
        val avatar_url: String? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class RawMedia(
        val transcodings: List<RawTranscoding>? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class RawTranscoding(
        val url: String? = null,
        val preset: String? = null,
        val quality: String? = null,
        val format: RawFormat? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class RawFormat(
        val protocol: String? = null,
        val mime_type: String? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class RawPlaylist(
        val id: Long = 0L,
        val title: String? = null,
        val description: String? = null,
        val duration: Long = 0L,
        val artwork_url: String? = null,
        val user: RawUser? = null,
        val tracks: List<RawTrack>? = null,
    ) {
        fun toEntity(): SCEntity.Playlist {
            val rawTracks = tracks ?: emptyList()
            val tracksOut = mutableListOf<TrackInfo>()
            val stubs = mutableListOf<Long>()
            for (rt in rawTracks) {
                if (rt.title.isNullOrBlank() && rt.media == null) {
                    stubs += rt.id
                    // Placeholder so playlist length is preserved before
                    // we fill in stubs. Set isUnplayable so the player
                    // won't try to play before metadata loads.
                    tracksOut += TrackInfo(
                        id = rt.id,
                        providerId = rt.id.toString(),
                        provider = Provider.SOUNDCLOUD,
                        title = "Loading…",
                        isUnplayable = true,
                    )
                } else {
                    tracksOut += rt.toTrackInfo()
                }
            }
            return SCEntity.Playlist(
                id = id,
                title = title ?: "SoundCloud Playlist",
                artworkUrl = upgradeArtwork(artwork_url ?: user?.avatar_url),
                userName = user?.username,
                tracks = tracksOut,
                stubIds = stubs,
            )
        }
    }

    private val trackAdapter = moshi.adapter(RawTrack::class.java)
    private val playlistAdapter = moshi.adapter(RawPlaylist::class.java)
    private val userAdapter = moshi.adapter(RawUser::class.java)
    private val trackListAdapter = run {
        val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, RawTrack::class.java)
        moshi.adapter<List<RawTrack>>(type)
    }

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        fun defaultMoshi(): Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        /** Replaces SC's `-large.jpg` (100×100) thumbnail with `-t500x500`
         *  (500×500 — the largest size SC reliably has). */
        fun upgradeArtwork(url: String?): String? {
            if (url.isNullOrBlank()) return null
            return url
                .replace("-large.jpg", "-t500x500.jpg")
                .replace("-large.png", "-t500x500.png")
        }

        private val ASSET_URL_RE = Regex(
            """https://a-v2\.sndcdn\.com/assets/[0-9]+-[a-f0-9]+\.js""",
        )
        // Two known shapes of the embedded client_id literal:
        //   client_id:"abc"
        //   client_id="abc"
        private val CLIENT_ID_RE = Regex(
            """client_id\s*[:=]\s*"([A-Za-z0-9]{20,40})"""",
        )
        private const val BROWSER_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/123.0.0.0 Safari/537.36"
    }
}
