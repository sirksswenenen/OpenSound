package com.soundcloud.lite.api

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern
import kotlin.jvm.Throws

/**
 * Talks to SoundCloud's `api-v2` HTTP API. The API isn't officially
 * public for new clients, so we lift a `client_id` straight from the
 * web app: load `https://soundcloud.com/`, follow each `<script src=…>`
 * and regex-match the first `client_id: "…"` occurrence. The result is
 * cached for the process lifetime; refresh it if requests start
 * returning 401.
 */
class SoundCloudApi(
    private val http: OkHttpClient = defaultHttp(),
    private val moshi: Moshi = defaultMoshi(),
) {
    @Volatile private var clientId: String? = null
    @Volatile private var oauthToken: String = ""

    fun setOAuthToken(token: String) { oauthToken = token.trim() }

    /** Fetch a fresh `client_id` from soundcloud.com if we don't have one. */
    suspend fun ensureClientId(): String = withContext(Dispatchers.IO) {
        clientId?.let { return@withContext it }
        val home = httpGetString("https://soundcloud.com/")
        val scriptUrls = SCRIPT_SRC_RE.toRegex().findAll(home)
            .map { it.groupValues[1] }
            .filter { it.contains("sndcdn.com") || it.contains("/assets/") }
            .toList()
        for (url in scriptUrls) {
            val body = runCatching { httpGetString(url) }.getOrNull() ?: continue
            val match = CLIENT_ID_RE.matcher(body)
            if (match.find()) {
                val id = match.group(1)
                clientId = id
                return@withContext id
            }
        }
        throw IOException("Could not find client_id on soundcloud.com")
    }

    suspend fun search(query: String, offset: Int = 0, limit: Int = 50): SearchPage =
        withContext(Dispatchers.IO) {
            val cid = ensureClientId()
            val url = "https://api-v2.soundcloud.com/search/tracks".toHttpUrl().newBuilder()
                .addQueryParameter("q", query)
                .addQueryParameter("client_id", cid)
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("linked_partitioning", "1")
                .build()
            val raw = jsonGet<RawCollection<RawTrack>>(url.toString(), rawCollectionAdapter)
            SearchPage(
                tracks = raw.collection.mapNotNull { it.toTrackInfo() },
                nextOffset = raw.nextOffset(),
            )
        }

    suspend fun getTrending(genre: String? = null, offset: Int = 0, limit: Int = 50): SearchPage =
        withContext(Dispatchers.IO) {
            val cid = ensureClientId()
            val genreParam = if (genre.isNullOrBlank()) "soundcloud:genres:all-music" else "soundcloud:genres:$genre"
            val url = "https://api-v2.soundcloud.com/charts".toHttpUrl().newBuilder()
                .addQueryParameter("kind", "trending")
                .addQueryParameter("genre", genreParam)
                .addQueryParameter("client_id", cid)
                .addQueryParameter("limit", limit.toString())
                .addQueryParameter("offset", offset.toString())
                .addQueryParameter("linked_partitioning", "1")
                .build()
            val raw = jsonGet<RawCollection<RawChartItem>>(url.toString(), chartCollectionAdapter)
            SearchPage(
                tracks = raw.collection.mapNotNull { it.track?.toTrackInfo() },
                nextOffset = raw.nextOffset(),
            )
        }

    suspend fun getRelatedTracks(trackId: Long, limit: Int = 30): List<TrackInfo> =
        withContext(Dispatchers.IO) {
            val cid = ensureClientId()
            val url = "https://api-v2.soundcloud.com/tracks/$trackId/related".toHttpUrl().newBuilder()
                .addQueryParameter("client_id", cid)
                .addQueryParameter("limit", limit.toString())
                .build()
            val raw = jsonGet<RawCollection<RawTrack>>(url.toString(), rawCollectionAdapter)
            raw.collection.mapNotNull { it.toTrackInfo() }
        }

    /**
     * Resolve a `soundcloud.com/...` URL to either a track or a playlist.
     * Returns either a single TrackInfo (wrapped in a one-track playlist)
     * or a real Playlist.
     */
    suspend fun resolveUrl(url: String): ResolveResult? = withContext(Dispatchers.IO) {
        val cid = ensureClientId()
        val resolved = "https://api-v2.soundcloud.com/resolve".toHttpUrl().newBuilder()
            .addQueryParameter("url", url)
            .addQueryParameter("client_id", cid)
            .build()
        val body = httpGetString(resolved.toString())
        // Peek at "kind" field
        val reader = JsonReader.of(okio.Buffer().writeUtf8(body))
        var kind: String? = null
        reader.use {
            it.beginObject()
            while (it.hasNext()) {
                val name = it.nextName()
                if (name == "kind") {
                    kind = it.nextString()
                    break
                } else {
                    it.skipValue()
                }
            }
        }
        when (kind) {
            "track" -> {
                val raw = trackAdapter.fromJson(body)
                raw?.toTrackInfo()?.let { ResolveResult.Track(it) }
            }
            "playlist", "system-playlist" -> {
                val raw = playlistAdapter.fromJson(body)
                raw?.toPlaylist()?.let { ResolveResult.PlaylistResult(it) }
            }
            else -> null
        }
    }

    /**
     * Get a directly-playable HTTPS URL (HLS or progressive) for the track.
     * SoundCloud responds with a wrapper that contains a one-shot signed URL,
     * which then redirects to the actual media URL.
     */
    suspend fun getPlayableStream(trackId: Long): String = withContext(Dispatchers.IO) {
        val cid = ensureClientId()
        val info = jsonGet<RawTrack>(
            url = "https://api-v2.soundcloud.com/tracks/$trackId?client_id=$cid",
            adapter = trackAdapter,
        )
        val transcoding = info.media?.transcodings.orEmpty()
            .sortedByDescending { it.preference() }
            .firstOrNull()
            ?: throw IOException("No transcodings available for track $trackId")
        val tokenUrl = "${transcoding.url}?client_id=$cid" +
            (if (oauthToken.isNotEmpty()) "&oauth_token=$oauthToken" else "")
        val token = jsonGet<RawStreamUrl>(tokenUrl, streamUrlAdapter)
        token.url ?: throw IOException("Empty stream URL for track $trackId")
    }

    /** Fetch the SoundCloud waveform PNG-samples JSON. */
    suspend fun fetchWaveformJson(waveformUrl: String): WaveformData? = withContext(Dispatchers.IO) {
        // SoundCloud serves a JSON file at https://wave.sndcdn.com/<id>.json
        val jsonUrl = waveformUrl.replace(".png", ".json")
        runCatching {
            jsonGet<WaveformData>(jsonUrl, waveformAdapter)
        }.getOrNull()
    }

    // -------- HTTP helpers --------

    @Throws(IOException::class)
    private fun httpGetString(url: String): String {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", UA)
            .also { if (oauthToken.isNotEmpty()) it.header("Authorization", "OAuth $oauthToken") }
            .get()
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code} for $url")
            return resp.body?.string() ?: throw IOException("Empty body for $url")
        }
    }

    private fun <T> jsonGet(url: String, adapter: JsonAdapter<T>): T {
        val raw = httpGetString(url)
        return adapter.fromJson(raw) ?: throw IOException("Failed to parse JSON for $url")
    }

    // -------- Models (raw → domain) --------

    @JsonClass(generateAdapter = true)
    data class RawCollection<T>(
        val collection: List<T> = emptyList(),
        val next_href: String? = null,
    ) {
        fun nextOffset(): Int? {
            val nh = next_href ?: return null
            return Regex("offset=(\\d+)").find(nh)?.groupValues?.get(1)?.toIntOrNull()
        }
    }

    @JsonClass(generateAdapter = true)
    data class RawChartItem(val track: RawTrack? = null)

    @JsonClass(generateAdapter = true)
    data class RawTrack(
        val id: Long = 0,
        val title: String? = null,
        val duration: Long = 0,
        val playback_count: Long = 0,
        val likes_count: Long = 0,
        val genre: String? = null,
        val artwork_url: String? = null,
        val permalink_url: String? = null,
        val waveform_url: String? = null,
        val user: RawUser? = null,
        val media: RawMedia? = null,
        val streamable: Boolean = true,
        val policy: String? = null,
    ) {
        fun toTrackInfo(): TrackInfo? {
            if (id == 0L || title.isNullOrBlank()) return null
            return TrackInfo(
                id = id,
                title = title,
                artistName = user?.username.orEmpty(),
                artworkUrl = (artwork_url ?: user?.avatar_url)?.replace("-large.jpg", "-t500x500.jpg"),
                duration = duration,
                playbackCount = playback_count,
                likeCount = likes_count,
                genre = genre.orEmpty(),
                streamUrl = "",
                permalinkUrl = permalink_url.orEmpty(),
                externalVideoId = null,
                waveformUrl = waveform_url,
            )
        }
    }

    @JsonClass(generateAdapter = true)
    data class RawUser(val username: String? = null, val avatar_url: String? = null)

    @JsonClass(generateAdapter = true)
    data class RawMedia(val transcodings: List<RawTranscoding> = emptyList())

    @JsonClass(generateAdapter = true)
    data class RawTranscoding(
        val url: String,
        val preset: String? = null,
        val format: RawFormat? = null,
        val quality: String? = null,
    ) {
        fun preference(): Int {
            // Prefer progressive over HLS (simpler), then higher quality
            val mime = format?.mime_type.orEmpty()
            val pro = if (format?.protocol == "progressive") 100 else 0
            val mp3 = if (mime.contains("mpeg") || mime.contains("mp3")) 50 else 0
            val hq = if (quality == "hq") 30 else 0
            return pro + mp3 + hq
        }
    }

    @JsonClass(generateAdapter = true)
    data class RawFormat(val protocol: String? = null, val mime_type: String? = null)

    @JsonClass(generateAdapter = true)
    data class RawStreamUrl(val url: String? = null)

    @JsonClass(generateAdapter = true)
    data class RawPlaylist(
        val id: Long = 0,
        val title: String? = null,
        val artwork_url: String? = null,
        val permalink_url: String? = null,
        val user: RawUser? = null,
        val tracks: List<RawTrack> = emptyList(),
    ) {
        fun toPlaylist(): com.soundcloud.lite.data.Playlist {
            return com.soundcloud.lite.data.Playlist(
                id = "sc:$id",
                title = title.orEmpty(),
                artistName = user?.username,
                artworkUrl = artwork_url,
                sourceUrl = permalink_url,
                tracks = tracks.mapNotNull { it.toTrackInfo() },
            )
        }
    }

    @JsonClass(generateAdapter = true)
    data class WaveformData(
        val width: Int = 0,
        val height: Int = 0,
        val samples: List<Int> = emptyList(),
    )

    sealed class ResolveResult {
        data class Track(val track: TrackInfo) : ResolveResult()
        data class PlaylistResult(val playlist: com.soundcloud.lite.data.Playlist) : ResolveResult()
    }

    data class SearchPage(val tracks: List<TrackInfo>, val nextOffset: Int?)

    private val rawCollectionAdapter: JsonAdapter<RawCollection<RawTrack>> by lazy {
        moshi.adapter(Types.newParameterizedType(RawCollection::class.java, RawTrack::class.java))
    }
    private val chartCollectionAdapter: JsonAdapter<RawCollection<RawChartItem>> by lazy {
        moshi.adapter(Types.newParameterizedType(RawCollection::class.java, RawChartItem::class.java))
    }
    private val trackAdapter: JsonAdapter<RawTrack> by lazy { moshi.adapter(RawTrack::class.java) }
    private val playlistAdapter: JsonAdapter<RawPlaylist> by lazy { moshi.adapter(RawPlaylist::class.java) }
    private val streamUrlAdapter: JsonAdapter<RawStreamUrl> by lazy { moshi.adapter(RawStreamUrl::class.java) }
    private val waveformAdapter: JsonAdapter<WaveformData> by lazy { moshi.adapter(WaveformData::class.java) }

    companion object {
        private const val UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36"
        private const val SCRIPT_SRC_RE = "<script[^>]*src=\"([^\"]+)\""
        private val CLIENT_ID_RE: Pattern = Pattern.compile("client_id\\s*[:=]\\s*\"([a-zA-Z0-9]{32})\"")

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()

        fun defaultMoshi(): Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()
    }
}
