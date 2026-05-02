package com.soundcloud.lite.api

import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Free metadata enricher backed by the iTunes Search API
 * (`https://itunes.apple.com/search`). No auth, no key. Used to clean
 * up YouTube video titles into a proper artist/title pair and to fetch
 * a 1:1 high-resolution album cover.
 *
 * The API is forgiving — for "RAUWALEJANDRO - TOUCHING THE SKY (Official
 * Music Video)" it'll still return the canonical "Touching the Sky" by
 * "Rauw Alejandro" if Apple has it.
 */
class iTunesApi(
    private val http: OkHttpClient = defaultHttp(),
    private val moshi: Moshi = defaultMoshi(),
) {
    /** Returns null if iTunes has no good match. */
    suspend fun enrich(query: String): Match? {
        val q = percentEncode(cleanQuery(query))
        if (q.isBlank()) return null
        val url = "https://itunes.apple.com/search?term=$q&media=music&entity=song&limit=1"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "OpenSound/0.1 (Android)")
            .build()
        val body = http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            resp.body?.string() ?: return null
        }
        val parsed = adapter.fromJson(body) ?: return null
        val best = parsed.results.firstOrNull() ?: return null
        return Match(
            title = best.trackName ?: return null,
            artist = best.artistName ?: return null,
            album = best.collectionName,
            artworkUrl1000 = best.artworkUrl100?.let(::upgradeArtwork),
            genre = best.primaryGenreName,
        )
    }

    /** Strip noise like "(Official Video)", "[HD]" before searching. */
    private fun cleanQuery(input: String): String {
        var s = input
        for (re in NOISE_RES) s = s.replace(re, " ")
        s = s.replace(MULTI_SPACE, " ").trim()
        return s
    }

    private fun percentEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")

    @JsonClass(generateAdapter = true)
    internal data class RawResponse(
        val resultCount: Int = 0,
        val results: List<RawTrack> = emptyList(),
    )

    @JsonClass(generateAdapter = true)
    internal data class RawTrack(
        val trackName: String? = null,
        val artistName: String? = null,
        val collectionName: String? = null,
        val artworkUrl100: String? = null,
        val primaryGenreName: String? = null,
    )

    private val adapter = moshi.adapter(RawResponse::class.java)

    data class Match(
        val title: String,
        val artist: String,
        val album: String?,
        val artworkUrl1000: String?,
        val genre: String?,
    )

    companion object {
        fun defaultHttp(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        fun defaultMoshi(): Moshi = Moshi.Builder()
            .add(KotlinJsonAdapterFactory())
            .build()

        /** Apple Music thumbnails come back as `…/100x100bb.jpg`; we
         *  swap that to `1000x1000bb.jpg` for sharper artwork. The CDN
         *  recomputes on the fly. */
        fun upgradeArtwork(url: String): String =
            url.replace("/100x100bb.", "/1000x1000bb.")

        private val NOISE_RES = listOf(
            Regex("""\((official|lyric|hd|hq|m/v|mv|audio|remastered)[^)]*\)""", RegexOption.IGNORE_CASE),
            Regex("""\[(official|lyric|hd|hq|m/v|mv|audio|remastered)[^\]]*\]""", RegexOption.IGNORE_CASE),
            Regex("""\bofficial\s+(music\s+)?video\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(audio|lyrics?|m/v|mv|hd|hq|4k|8k)\b""", RegexOption.IGNORE_CASE),
        )
        private val MULTI_SPACE = Regex("""\s+""")
    }
}
