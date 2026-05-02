package com.soundcloud.lite.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * YouTube client backed by the public Invidious API. Audio streams are
 * served via the instance's `/latest_version?id=…&itag=140` endpoint,
 * which transparently proxies the audio out of googlevideo.com (so we
 * don't have to deal with IP-locked CDN URLs on the device).
 *
 * No auth, no quota — but instances do go down. We try several in the
 * order [INSTANCES] and remember the most recent one that worked for
 * the current session.
 */
class YouTubeApi(
    private val http: OkHttpClient = defaultHttp(),
    private val moshi: Moshi = defaultMoshi(),
    private val instances: List<String> = INSTANCES,
) {
    @Volatile private var preferredInstance: String? = null

    suspend fun search(query: String, limit: Int = 25): List<TrackInfo> {
        val q = percentEncode(query)
        val body = httpGetWithFallback("/api/v1/search?q=$q&type=video")
            ?: return emptyList()
        val list = videoListAdapter.fromJson(body) ?: return emptyList()
        return list.take(limit).mapNotNull { it.toTrackInfo() }
    }

    suspend fun getVideo(videoId: String): TrackInfo? {
        val body = httpGetWithFallback("/api/v1/videos/$videoId") ?: return null
        return videoDetailAdapter.fromJson(body)?.toTrackInfo(videoId)
    }

    /** Returns playlist title + tracks. Tracks come back with stable
     *  Audius-style numeric ids derived from their YouTube videoId. */
    suspend fun getPlaylist(playlistId: String): YtPlaylist? {
        val body = httpGetWithFallback("/api/v1/playlists/$playlistId") ?: return null
        val raw = playlistAdapter.fromJson(body) ?: return null
        val tracks = raw.videos.mapNotNull { it.toTrackInfo() }
        return YtPlaylist(
            playlistId = playlistId,
            title = raw.title ?: "YouTube Playlist",
            artworkUrl = raw.thumbnail ?: raw.thumbnailUrl,
            tracks = tracks,
        )
    }

    /** Returns a stream URL ExoPlayer can use directly. The instance
     *  redirects to an audio mp4/m4a CDN URL on each request. */
    fun streamUrl(videoId: String, itag: Int = 140): String {
        val instance = preferredInstance ?: instances.first()
        return "$instance/latest_version?id=$videoId&itag=$itag"
    }

    private fun httpGetWithFallback(path: String): String? {
        val ordered = preferredInstance?.let { listOf(it) + instances.filterNot { i -> i == preferredInstance } }
            ?: instances
        for (inst in ordered) {
            try {
                val body = httpGet("$inst$path") ?: continue
                preferredInstance = inst
                return body
            } catch (_: Throwable) {
                // try next instance
            }
        }
        return null
    }

    private fun httpGet(url: String): String? {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", "OpenSound/0.1 (Android)")
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.string()
        }
    }

    private fun percentEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    @JsonClass(generateAdapter = true)
    internal data class RawSearchVideo(
        val type: String? = null,
        val title: String? = null,
        val videoId: String? = null,
        val author: String? = null,
        val authorId: String? = null,
        val lengthSeconds: Int? = null,
        val videoThumbnails: List<RawThumb>? = null,
        val authorThumbnails: List<RawThumb>? = null,
    ) {
        fun toTrackInfo(): TrackInfo? {
            val id = videoId ?: return null
            val art = videoThumbnails?.maxByOrNull { it.width ?: 0 }?.let { rewriteThumbHost(it.url) }
            val avatar = authorThumbnails?.maxByOrNull { it.width ?: 0 }?.let { rewriteThumbHost(it.url) }
            return TrackInfo(
                id = stableIdHash("yt:$id"),
                providerId = id,
                provider = Provider.YOUTUBE,
                title = title ?: "Untitled",
                artistName = author ?: "Unknown",
                artistId = authorId ?: "",
                artworkUrl = art,
                avatarUrl = avatar,
                duration = (lengthSeconds?.toLong() ?: 0L) * 1000L,
            )
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class RawThumb(
        val url: String = "",
        val width: Int? = null,
        val height: Int? = null,
        val quality: String? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class RawPlaylist(
        val title: String? = null,
        val thumbnail: String? = null,
        val thumbnailUrl: String? = null,
        val videos: List<RawSearchVideo> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    internal data class RawVideoDetail(
        val title: String? = null,
        val author: String? = null,
        val authorId: String? = null,
        val lengthSeconds: Int? = null,
        val videoThumbnails: List<RawThumb>? = null,
        val authorThumbnails: List<RawThumb>? = null,
        @Json(name = "adaptiveFormats") val adaptiveFormats: List<RawAdaptiveFormat>? = null,
    ) {
        fun toTrackInfo(videoId: String): TrackInfo {
            val art = videoThumbnails?.maxByOrNull { it.width ?: 0 }?.let { rewriteThumbHost(it.url) }
            val avatar = authorThumbnails?.maxByOrNull { it.width ?: 0 }?.let { rewriteThumbHost(it.url) }
            return TrackInfo(
                id = stableIdHash("yt:$videoId"),
                providerId = videoId,
                provider = Provider.YOUTUBE,
                title = title ?: "Untitled",
                artistName = author ?: "Unknown",
                artistId = authorId ?: "",
                artworkUrl = art,
                avatarUrl = avatar,
                duration = (lengthSeconds?.toLong() ?: 0L) * 1000L,
            )
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class RawAdaptiveFormat(
        val type: String? = null,
        val url: String? = null,
        val itag: String? = null,
        val bitrate: Long? = null,
    )

    private val videoListAdapter = run {
        val type = com.squareup.moshi.Types.newParameterizedType(List::class.java, RawSearchVideo::class.java)
        moshi.adapter<List<RawSearchVideo>>(type)
    }
    private val videoDetailAdapter = moshi.adapter(RawVideoDetail::class.java)
    private val playlistAdapter = moshi.adapter(RawPlaylist::class.java)

    data class YtPlaylist(
        val playlistId: String,
        val title: String,
        val artworkUrl: String?,
        val tracks: List<TrackInfo>,
    )

    companion object {
        // Public Invidious instances. invidious.f5.si is the primary —
        // the rest are tried in order if it fails. The list is hardcoded
        // because runtime instance discovery (via the Invidious mesh
        // /api/v1/instances) is itself behind the same flaky network.
        val INSTANCES = listOf(
            "https://invidious.f5.si",
            "https://invidious.nerdvpn.de",
            "https://yewtu.be",
            "https://invidious.privacyredirect.com",
        )

        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        fun defaultMoshi(): Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        /** Use Audius' hashing function so a given (provider, id) pair
         *  always maps to the same Long. We just prefix with "yt:" / "audius:"
         *  to keep the namespaces disjoint. */
        fun stableIdHash(id: String): Long = AudiusApi.stableIdHash(id)

        /** Some Invidious instances return thumbnail URLs that point at
         *  themselves rather than at YouTube's actual host. Those URLs
         *  may not be reachable from clients on different networks; we
         *  rewrite them to the canonical i.ytimg.com host so they always
         *  load (YouTube's image CDN is global and unauthed). */
        fun rewriteThumbHost(url: String): String {
            val m = THUMB_RE.matchEntire(url) ?: return url
            val videoId = m.groupValues[1]
            val quality = m.groupValues[2]
            return "https://i.ytimg.com/vi/$videoId/$quality"
        }

        private val THUMB_RE = Regex("""https://[^/]+/vi/([A-Za-z0-9_-]+)/([A-Za-z0-9._-]+)""")
    }
}
