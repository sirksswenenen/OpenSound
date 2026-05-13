package com.soundcloud.lite.data

import android.content.Context
import android.content.SharedPreferences
import com.soundcloud.lite.api.Provider
import com.soundcloud.lite.api.TrackInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

enum class DownloadStatus { PENDING, DOWNLOADING, DONE, FAILED }

data class DownloadedTrack(
    val track: TrackInfo,
    val localPath: String,          // absolute path to the .m4a / .mp3 file
    val status: DownloadStatus = DownloadStatus.DONE,
    val progress: Int = 100,        // 0..100
    val errorMsg: String? = null,
)

/**
 * Manages offline downloads. Files are stored in the app's private music
 * directory (no external-storage permission needed).
 *
 * Stream URL resolution is done by callers (ViewModel) since it requires
 * the provider API clients.
 */
class DownloadRepository(private val context: Context) {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences("sclite_downloads_v1", Context.MODE_PRIVATE)

    private val _downloads = MutableStateFlow<List<DownloadedTrack>>(emptyList())
    val downloads: StateFlow<List<DownloadedTrack>> = _downloads.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    init {
        _downloads.value = loadMeta()
    }

    fun isDownloaded(trackId: Long): Boolean =
        _downloads.value.any { it.track.id == trackId && it.status == DownloadStatus.DONE && File(it.localPath).exists() }

    fun getLocalPath(trackId: Long): String? =
        _downloads.value.firstOrNull { it.track.id == trackId && it.status == DownloadStatus.DONE }
            ?.localPath?.takeIf { File(it).exists() }

    /**
     * Downloads the track from [streamUrl] to local storage.
     * Progress is emitted via [onProgress] (0..100).
     * Returns the local file path on success, null on failure.
     */
    suspend fun download(
        track: TrackInfo,
        streamUrl: String,
        onProgress: (Int) -> Unit = {},
    ): String? = withContext(Dispatchers.IO) {
        // Mark as in-progress
        updateOrAdd(DownloadedTrack(track = track, localPath = "", status = DownloadStatus.DOWNLOADING, progress = 0))

        val dir = File(context.filesDir, "music").also { it.mkdirs() }
        // SoundCloud progressive streams are mp3-128. We keep the legacy
        // "m4a" extension for downloads imported from YouTube on older
        // versions so they still play out of the local cache.
        val ext = if (track.provider == Provider.YOUTUBE) "m4a" else "mp3"
        val file = File(dir, "${track.id}.$ext")

        try {
            val req = Request.Builder()
                .url(streamUrl)
                .header("User-Agent", "OpenSound/1.0 (Android)")
                .build()

            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    updateOrAdd(DownloadedTrack(track = track, localPath = "", status = DownloadStatus.FAILED, progress = 0, errorMsg = "HTTP ${resp.code}"))
                    return@withContext null
                }
                val body = resp.body ?: run {
                    updateOrAdd(DownloadedTrack(track = track, localPath = "", status = DownloadStatus.FAILED, progress = 0, errorMsg = "Empty body"))
                    return@withContext null
                }
                val totalBytes = body.contentLength().takeIf { it > 0 }
                body.byteStream().use { input ->
                    file.outputStream().use { output ->
                        val buf = ByteArray(65_536)
                        var downloaded = 0L
                        var n: Int
                        while (input.read(buf).also { n = it } != -1) {
                            output.write(buf, 0, n)
                            downloaded += n
                            val pct = if (totalBytes != null) ((downloaded * 100) / totalBytes).toInt().coerceIn(0, 99) else -1
                            if (pct >= 0) {
                                onProgress(pct)
                                updateOrAdd(DownloadedTrack(track = track, localPath = file.absolutePath, status = DownloadStatus.DOWNLOADING, progress = pct))
                            }
                        }
                    }
                }
            }

            val done = DownloadedTrack(track = track, localPath = file.absolutePath, status = DownloadStatus.DONE, progress = 100)
            updateOrAdd(done)
            saveMeta()
            onProgress(100)
            file.absolutePath
        } catch (e: Exception) {
            file.delete()
            updateOrAdd(DownloadedTrack(track = track, localPath = "", status = DownloadStatus.FAILED, progress = 0, errorMsg = e.message))
            null
        }
    }

    fun removeDownload(trackId: Long) {
        val current = _downloads.value.firstOrNull { it.track.id == trackId } ?: return
        runCatching { File(current.localPath).delete() }
        _downloads.update { list -> list.filterNot { it.track.id == trackId } }
        saveMeta()
    }

    private fun updateOrAdd(dt: DownloadedTrack) {
        _downloads.update { list ->
            val idx = list.indexOfFirst { it.track.id == dt.track.id }
            if (idx < 0) list + dt else list.toMutableList().also { it[idx] = dt }
        }
    }

    // ---- Persistence ----

    private fun saveMeta() {
        val arr = JSONArray()
        _downloads.value.filter { it.status == DownloadStatus.DONE && File(it.localPath).exists() }.forEach { dt ->
            arr.put(JSONObject().apply {
                put("id", dt.track.id)
                put("providerId", dt.track.providerId)
                put("provider", dt.track.provider.name)
                put("title", dt.track.title)
                put("artistName", dt.track.artistName)
                dt.track.artworkUrl?.let { put("artworkUrl", it) }
                put("duration", dt.track.duration)
                put("localPath", dt.localPath)
            })
        }
        prefs.edit().putString("downloads", arr.toString()).apply()
    }

    private fun loadMeta(): List<DownloadedTrack> {
        val json = prefs.getString("downloads", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val obj = arr.getJSONObject(i)
                    val localPath = obj.getString("localPath")
                    if (!File(localPath).exists()) return@runCatching null
                    DownloadedTrack(
                        track = TrackInfo(
                            id = obj.getLong("id"),
                            providerId = obj.optString("providerId", ""),
                            provider = runCatching { Provider.valueOf(obj.getString("provider")) }.getOrDefault(Provider.UNKNOWN),
                            title = obj.optString("title", ""),
                            artistName = obj.optString("artistName", ""),
                            artworkUrl = if (obj.has("artworkUrl") && !obj.isNull("artworkUrl")) obj.getString("artworkUrl") else null,
                            duration = obj.optLong("duration", 0L),
                        ),
                        localPath = localPath,
                        status = DownloadStatus.DONE,
                    )
                }.getOrNull()
            }
        } catch (_: Exception) { emptyList() }
    }
}
