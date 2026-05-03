package com.soundcloud.lite.data

import android.content.Context
import android.content.SharedPreferences
import com.soundcloud.lite.api.Provider
import com.soundcloud.lite.api.TrackInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists playlists (including imported ones) to SharedPreferences as JSON,
 * so they survive app restarts.
 */
class PlaylistRepository(context: Context) {
    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("sclite_playlists_v2", Context.MODE_PRIVATE)

    fun loadPlaylists(): List<Playlist> {
        val json = prefs.getString("playlists", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { playlistFromJson(arr.getJSONObject(i)) }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun savePlaylists(playlists: List<Playlist>) {
        val arr = JSONArray()
        playlists.forEach { arr.put(playlistToJson(it)) }
        prefs.edit().putString("playlists", arr.toString()).apply()
    }

    // ---- Serialization ----

    private fun playlistToJson(pl: Playlist): JSONObject = JSONObject().apply {
        put("id", pl.id)
        put("title", pl.title)
        pl.artistName?.let { put("artistName", it) }
        pl.artworkUrl?.let { put("artworkUrl", it) }
        pl.sourceUrl?.let { put("sourceUrl", it) }
        val tracksArr = JSONArray()
        pl.tracks.forEach { tracksArr.put(trackToJson(it)) }
        put("tracks", tracksArr)
    }

    private fun playlistFromJson(obj: JSONObject): Playlist {
        val tracksArr = obj.optJSONArray("tracks")
        val tracks = if (tracksArr != null) {
            (0 until tracksArr.length()).mapNotNull { i ->
                runCatching { trackFromJson(tracksArr.getJSONObject(i)) }.getOrNull()
            }
        } else emptyList()
        return Playlist(
            id = obj.getString("id"),
            title = obj.getString("title"),
            artistName = obj.optStringOrNull("artistName"),
            artworkUrl = obj.optStringOrNull("artworkUrl"),
            sourceUrl = obj.optStringOrNull("sourceUrl"),
            tracks = tracks,
        )
    }

    private fun trackToJson(t: TrackInfo): JSONObject = JSONObject().apply {
        put("id", t.id)
        put("providerId", t.providerId)
        put("provider", t.provider.name)
        put("title", t.title)
        put("artistName", t.artistName)
        put("artistId", t.artistId)
        t.artworkUrl?.let { put("artworkUrl", it) }
        t.avatarUrl?.let { put("avatarUrl", it) }
        put("duration", t.duration)
        t.permalink?.let { put("permalink", it) }
        t.streamHint?.let { put("streamHint", it) }
        t.genre?.let { put("genre", it) }
        t.playCount?.let { put("playCount", it) }
        t.favoriteCount?.let { put("favoriteCount", it) }
        put("isUnplayable", t.isUnplayable)
    }

    private fun trackFromJson(obj: JSONObject): TrackInfo = TrackInfo(
        id = obj.getLong("id"),
        providerId = obj.optString("providerId", ""),
        provider = runCatching { Provider.valueOf(obj.getString("provider")) }.getOrDefault(Provider.UNKNOWN),
        title = obj.optString("title", ""),
        artistName = obj.optString("artistName", ""),
        artistId = obj.optString("artistId", ""),
        artworkUrl = obj.optStringOrNull("artworkUrl"),
        avatarUrl = obj.optStringOrNull("avatarUrl"),
        duration = obj.optLong("duration", 0L),
        permalink = obj.optStringOrNull("permalink"),
        streamHint = obj.optStringOrNull("streamHint"),
        genre = obj.optStringOrNull("genre"),
        playCount = if (obj.has("playCount") && !obj.isNull("playCount")) obj.getLong("playCount") else null,
        favoriteCount = if (obj.has("favoriteCount") && !obj.isNull("favoriteCount")) obj.getLong("favoriteCount") else null,
        isUnplayable = obj.optBoolean("isUnplayable", false),
    )

    /** Returns null instead of empty string for missing/null optional fields. */
    private fun JSONObject.optStringOrNull(key: String): String? {
        if (!has(key) || isNull(key)) return null
        val v = getString(key)
        return if (v.isEmpty()) null else v
    }
}
