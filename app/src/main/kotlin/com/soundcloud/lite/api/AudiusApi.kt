package com.soundcloud.lite.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * REST client for the Audius decentralized music network. We use the
 * official rendezvous endpoint at api.audius.co — it transparently
 * load-balances onto a healthy discovery node, so we don't have to
 * juggle host lists ourselves.
 *
 * Audius is open and unauthenticated for read-only endpoints, so unlike
 * SoundCloud there's no client_id scrape, no OAuth dance, and no
 * region-locked tracks. We just append `app_name=OpenSound` so Audius'
 * dashboards can attribute the traffic.
 *
 * API surface used here:
 *   GET /v1/tracks/trending     — chart, optional &genre=
 *   GET /v1/tracks/search       — full-text search
 *   GET /v1/tracks/{id}         — track metadata
 *   GET /v1/tracks/{id}/stream  — HTTP 302 to a CDN mp3 URL
 *   GET /v1/tracks/{id}/related — same-artist / similar tracks (may 404)
 */
class AudiusApi(
    private val http: OkHttpClient = defaultHttp(),
    private val moshi: Moshi = defaultMoshi(),
    private val baseUrl: String = "https://api.audius.co",
    private val appName: String = "OpenSound",
) {

    fun setOAuthToken(@Suppress("UNUSED_PARAMETER") token: String) {
        // Audius needs no auth for read endpoints; kept for parity with
        // SoundCloudApi so callers don't have to special-case providers.
    }

    /** Page through Audius' featured trending tracks. */
    suspend fun getTrending(genre: String? = null, offset: Int = 0, limit: Int = 50): SearchPage {
        val sb = StringBuilder("$baseUrl/v1/tracks/trending?app_name=$appName&offset=$offset&limit=$limit")
        if (!genre.isNullOrBlank()) sb.append("&genre=").append(percentEncode(genre))
        val body = httpGet(sb.toString())
        val parsed = trackListAdapter.fromJson(body) ?: return SearchPage(emptyList(), null)
        val tracks = parsed.data.map { it.toTrackInfo() }
        // Trending is bounded — no real "next page", but expose +50 in
        // case the user keeps scrolling so we don't dead-end the UI.
        val next = if (tracks.size == limit) offset + limit else null
        return SearchPage(tracks, next)
    }

    suspend fun search(query: String, offset: Int = 0, limit: Int = 25): SearchPage {
        val q = percentEncode(query)
        val url = "$baseUrl/v1/tracks/search?app_name=$appName&query=$q&limit=$limit&offset=$offset"
        val body = httpGet(url)
        val parsed = trackListAdapter.fromJson(body) ?: return SearchPage(emptyList(), null)
        val tracks = parsed.data.map { it.toTrackInfo() }
        val next = if (tracks.size == limit) offset + limit else null
        return SearchPage(tracks, next)
    }

    /**
     * Tries the dedicated /related endpoint first, then falls back to a
     * search by artist name to keep the screen useful for less-popular
     * tracks where Audius doesn't ship a precomputed related list.
     */
    suspend fun getRelatedTracks(trackId: String, limit: Int = 25): List<TrackInfo> {
        val direct = runCatching {
            val url = "$baseUrl/v1/tracks/$trackId/related?app_name=$appName&limit=$limit"
            val body = httpGet(url)
            trackListAdapter.fromJson(body)?.data?.map { it.toTrackInfo() } ?: emptyList()
        }.getOrDefault(emptyList())
        if (direct.isNotEmpty()) return direct

        // Fallback: get the track's artist and search by their name.
        val track = runCatching { getTrack(trackId) }.getOrNull() ?: return emptyList()
        val artist = track.artistName.takeIf { it.isNotBlank() } ?: return emptyList()
        return search(artist, limit = limit).tracks.filter { it.id != track.id }
    }

    suspend fun getTrack(trackId: String): TrackInfo {
        val url = "$baseUrl/v1/tracks/$trackId?app_name=$appName"
        val body = httpGet(url)
        val raw = trackEnvelopeAdapter.fromJson(body)
            ?: throw IOException("Audius: malformed track envelope")
        return raw.data.toTrackInfo()
    }

    /**
     * Resolves a track id to a final, playable HTTPS URL. We don't
     * follow redirects so ExoPlayer can do that itself with full
     * range-request support; we just hand back the indirect endpoint.
     */
    fun streamUrl(trackId: String): String =
        "$baseUrl/v1/tracks/$trackId/stream?app_name=$appName"

    // ---- HTTP plumbing ----

    private fun httpGet(url: String): String {
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        http.newCall(req).execute().use { resp ->
            return checkAndRead(resp)
        }
    }

    private fun checkAndRead(resp: Response): String {
        if (!resp.isSuccessful) {
            throw IOException("Audius HTTP ${resp.code} for ${resp.request.url}")
        }
        return resp.body?.string() ?: throw IOException("Audius empty body")
    }

    private fun percentEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    // ---- JSON models ----

    @JsonClass(generateAdapter = true)
    internal data class RawTrack(
        val id: String,
        val title: String?,
        val duration: Int? = null,
        val genre: String? = null,
        val mood: String? = null,
        val description: String? = null,
        @Json(name = "artwork") val artwork: RawArtwork? = null,
        @Json(name = "user") val user: RawUser? = null,
        @Json(name = "play_count") val playCount: Int? = null,
        @Json(name = "favorite_count") val favoriteCount: Int? = null,
    ) {
        fun toTrackInfo(): TrackInfo {
            val artwork = artwork?.s1000 ?: artwork?.s480 ?: artwork?.s150
            val artist = user?.name ?: user?.handle ?: "Unknown"
            val artistId = user?.id ?: ""
            val avatar = user?.profilePicture?.s480 ?: user?.profilePicture?.s150
            // TrackInfo.id is Long for back-compat with the old SoundCloud
            // models; we hash the alphanumeric Audius id to fit it.
            val numericId = stableIdHash(id)
            return TrackInfo(
                id = numericId,
                providerId = id,
                title = title ?: "Untitled",
                artistName = artist,
                artistId = artistId,
                artworkUrl = artwork,
                avatarUrl = avatar,
                duration = (duration?.toLong() ?: 0L) * 1000L,
                permalink = null,
                streamHint = null,
                genre = genre,
                playCount = playCount?.toLong(),
                favoriteCount = favoriteCount?.toLong(),
            )
        }
    }

    @JsonClass(generateAdapter = true)
    internal data class RawArtwork(
        @Json(name = "150x150") val s150: String? = null,
        @Json(name = "480x480") val s480: String? = null,
        @Json(name = "1000x1000") val s1000: String? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class RawUser(
        val id: String? = null,
        val name: String? = null,
        val handle: String? = null,
        @Json(name = "profile_picture") val profilePicture: RawArtwork? = null,
    )

    @JsonClass(generateAdapter = true)
    internal data class RawTrackList(
        val data: List<RawTrack> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    internal data class RawTrackEnvelope(
        val data: RawTrack,
    )

    private val trackListAdapter = moshi.adapter(RawTrackList::class.java)
    private val trackEnvelopeAdapter = moshi.adapter(RawTrackEnvelope::class.java)

    /** Public per-page response — same shape as SoundCloudApi.SearchPage so
     *  ViewModels stay agnostic of which provider is wired in. */
    data class SearchPage(
        val tracks: List<TrackInfo>,
        val nextOffset: Int?,
    )

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

        /**
         * Audius track ids are short alphanumeric strings ("zz5YzPp"). We
         * fold them into a stable Long so existing screens that key on
         * `track.id: Long` still work, while keeping the original string
         * available via `providerId` for streaming.
         */
        fun stableIdHash(id: String): Long {
            // FNV-1a 64-bit. Collisions in practice are negligible
            // because we only ever compare ids within a single session.
            var hash = -3750763034362895579L  // 0xcbf29ce484222325
            for (c in id) {
                hash = hash xor c.code.toLong()
                hash *= 1099511628211L         // 0x100000001b3
            }
            // Force positive so we don't surprise anything that assumes
            // ids are non-negative.
            return hash and 0x7FFFFFFFFFFFFFFFL
        }
    }
}
