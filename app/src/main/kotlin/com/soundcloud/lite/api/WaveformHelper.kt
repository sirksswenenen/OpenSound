package com.soundcloud.lite.api

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Helper that loads SoundCloud waveform samples for a track URL with two
 * caches (in-memory LRU + disk) and a PNG fallback if the JSON endpoint is
 * unavailable. Used by the smali patch in
 * `SoundCloudApi$fetchWaveformSamples$2.invokeSuspend`.
 *
 * The original implementation only tried `<url>.json` and silently returned
 * an empty list on any network/parse error, which left the full-screen
 * player showing flat 60-height bars on most tracks. This helper:
 *  - reads in-memory cache first
 *  - then disk cache (~30 KB per track, persists between sessions)
 *  - then fetches the JSON sibling of the waveform URL
 *  - finally falls back to decoding the PNG bitmap and reading the column
 *    heights as samples
 */
object WaveformHelper {

    private const val TAG = "SCLiteWaveform"
    private const val MAX_MEMORY_ENTRIES = 64
    private const val TARGET_SAMPLE_COUNT = 200
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    private val memoryCache = object : LinkedHashMap<String, List<Int>>(MAX_MEMORY_ENTRIES, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<Int>>?): Boolean {
            return size > MAX_MEMORY_ENTRIES
        }
    }

    @Volatile
    private var diskCacheDir: File? = null

    @JvmStatic
    fun init(context: Context) {
        if (diskCacheDir != null) return
        try {
            val dir = File(context.cacheDir, "waveforms")
            if (!dir.exists()) dir.mkdirs()
            diskCacheDir = dir
        } catch (_: Throwable) {
            // best-effort: in-memory cache still works without disk
        }
    }

    /**
     * Fetch / cache / decode the samples for [url]. Returns an empty list
     * when [url] is null/blank or every fetch path fails (drawer falls back
     * to flat-bar placeholder, same as the legacy implementation).
     */
    @JvmStatic
    fun loadSamplesBlocking(url: String?): List<Int> {
        if (url.isNullOrBlank()) {
            Log.d(TAG, "url is null/blank")
            return emptyList()
        }
        synchronized(memoryCache) {
            memoryCache[url]?.let { return it }
        }
        val diskKey = sha1(url)
        val diskFile = diskCacheDir?.let { File(it, "$diskKey.bin") }
        if (diskFile != null && diskFile.isFile) {
            val cached = readDiskSamples(diskFile)
            if (cached.isNotEmpty()) {
                Log.d(TAG, "disk hit n=${cached.size} url=$url")
                rememberInMemory(url, cached)
                return cached
            }
        }

        val candidates = buildCandidateUrls(url)
        Log.d(TAG, "candidates=$candidates url=$url")

        var samples: List<Int> = emptyList()
        for (candidate in candidates) {
            samples = if (candidate.endsWith(".json", ignoreCase = true))
                fetchJsonSamples(candidate)
            else
                fetchPngSamples(candidate)
            if (samples.isNotEmpty()) {
                Log.d(TAG, "hit candidate=$candidate n=${samples.size}")
                break
            }
        }

        if (samples.isNotEmpty()) {
            rememberInMemory(url, samples)
            if (diskFile != null) writeDiskSamples(diskFile, samples)
        } else {
            Log.w(TAG, "all candidates failed for url=$url")
        }
        return samples
    }

    /**
     * SoundCloud's waveform endpoints have a few historical formats. Try each
     * sibling so that we cover both `.png` and `.json` hosts even if the
     * track JSON returned only one variant.
     */
    private fun buildCandidateUrls(url: String): List<String> {
        val seen = LinkedHashSet<String>()
        seen.add(url)
        val lower = url.lowercase()
        if (lower.endsWith(".png")) {
            seen.add(url.dropLast(4) + ".json")
        }
        if (lower.endsWith(".json")) {
            seen.add(url.dropLast(5) + ".png")
        }
        // historical: w1.sndcdn.com hosts older PNGs, wis.sndcdn.com newer JSON
        if (lower.contains("w1.sndcdn.com")) {
            seen.add(url.replace("w1.sndcdn.com", "wis.sndcdn.com"))
        }
        if (lower.contains("wis.sndcdn.com")) {
            seen.add(url.replace("wis.sndcdn.com", "w1.sndcdn.com"))
        }
        return seen.toList()
    }

    private fun rememberInMemory(url: String, samples: List<Int>) {
        synchronized(memoryCache) { memoryCache[url] = samples }
    }

    private fun fetchJsonSamples(url: String): List<Int> {
        return try {
            val body = httpGetText(url) ?: return emptyList()
            val obj = JSONObject(body)
            val arr = obj.optJSONArray("samples") ?: return emptyList()
            val out = ArrayList<Int>(arr.length())
            for (i in 0 until arr.length()) out.add(arr.optInt(i, 0))
            out
        } catch (t: Throwable) {
            Log.d(TAG, "fetchJsonSamples failed: ${t.message}")
            emptyList()
        }
    }

    private fun fetchPngSamples(url: String): List<Int> {
        return try {
            val bytes = httpGetBytes(url) ?: return emptyList()
            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return emptyList()
            val w = bmp.width
            val h = bmp.height
            if (w <= 0 || h <= 0) return emptyList()
            val sampleCount = TARGET_SAMPLE_COUNT.coerceAtMost(w)
            val colW = w.toFloat() / sampleCount

            // Pass 1: alpha-based amplitude. Works when the PNG has a
            // transparent background (the historical SoundCloud format),
            // where the wave area is fully opaque and the rest is fully
            // transparent.
            val alphaSamples = ArrayList<Int>(sampleCount)
            for (i in 0 until sampleCount) {
                val cx = (i * colW + colW * 0.5f).toInt().coerceIn(0, w - 1)
                var top = h
                var y = 0
                while (y < h) {
                    val px = bmp.getPixel(cx, y)
                    val alpha = (px ushr 24) and 0xff
                    if (alpha > 32) { top = y; break }
                    y++
                }
                alphaSamples.add((h - top).coerceIn(0, h))
            }

            val alphaUseful = isUsefulAmplitudeProfile(alphaSamples, h)

            val finalSamples = if (alphaUseful) {
                alphaSamples
            } else {
                // Pass 2: luma-based amplitude. New SoundCloud waveform PNGs
                // ship with a fully opaque background (same colour as the
                // app's surface) and the wave drawn on top in a brighter
                // hue. With the alpha pass returning `h` for every column
                // we end up with a flat rectangle. Sample a 4-corner
                // average to estimate the background luma, then for each
                // column scan top-down for the first pixel whose luma
                // differs from the background by more than a threshold.
                val bgLuma = estimateBackgroundLuma(bmp, w, h)
                val lumaSamples = ArrayList<Int>(sampleCount)
                for (i in 0 until sampleCount) {
                    val cx = (i * colW + colW * 0.5f).toInt().coerceIn(0, w - 1)
                    var top = h
                    var y = 0
                    while (y < h) {
                        val px = bmp.getPixel(cx, y)
                        val l = lumaOf(px)
                        if (kotlin.math.abs(l - bgLuma) > LUMA_DIFF_THRESHOLD) {
                            top = y; break
                        }
                        y++
                    }
                    lumaSamples.add((h - top).coerceIn(0, h))
                }
                Log.d(
                    TAG,
                    "alpha-pass flat (bgLuma=$bgLuma); luma fallback produced n=${lumaSamples.size} url=$url"
                )
                lumaSamples
            }

            bmp.recycle()
            finalSamples
        } catch (t: Throwable) {
            Log.d(TAG, "fetchPngSamples exception: ${t.message} url=$url")
            emptyList()
        }
    }

    /** Threshold (0..255) above which a pixel is considered "wave" rather
     *  than background in the luma-fallback pass. */
    private const val LUMA_DIFF_THRESHOLD = 24

    /** A profile of column heights is "useful" if it contains real
     *  variation. A solid-background PNG produces every column at
     *  amplitude=h, which is what we want to detect. */
    private fun isUsefulAmplitudeProfile(samples: List<Int>, h: Int): Boolean {
        if (samples.isEmpty()) return false
        val min = samples.min()
        val max = samples.max()
        // If every column is the same height (within 1px) then the alpha
        // pass effectively gave us no information — fall back to luma.
        if (max - min <= 1) return false
        // If every column is at full height, that's also a flat rectangle.
        if (min >= h - 1) return false
        return true
    }

    /** Estimate the dominant background luma by averaging the four corner
     *  pixels. Works well for both the historical transparent PNGs (where
     *  the result is ~0 because the corners are transparent / black after
     *  alpha-premultiply) and the newer solid-background variants. */
    private fun estimateBackgroundLuma(
        bmp: android.graphics.Bitmap,
        w: Int,
        h: Int
    ): Int {
        val corners = intArrayOf(
            bmp.getPixel(0, 0),
            bmp.getPixel(w - 1, 0),
            bmp.getPixel(0, h - 1),
            bmp.getPixel(w - 1, h - 1)
        )
        var sum = 0
        for (px in corners) sum += lumaOf(px)
        return sum / corners.size
    }

    private fun lumaOf(px: Int): Int {
        val r = (px ushr 16) and 0xff
        val g = (px ushr 8) and 0xff
        val b = px and 0xff
        // Rec. 601 luma: 0.299 R + 0.587 G + 0.114 B, fixed-point in 0..255.
        return (r * 299 + g * 587 + b * 114) / 1000
    }

    private fun httpGetText(url: String): String? {
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.connectTimeout = 5000
            conn.readTimeout = 8000
            conn.requestMethod = "GET"
            conn.doInput = true
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "httpGetText non-2xx code=$code url=$url")
                return null
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            Log.d(TAG, "httpGetText exception: ${t.message} url=$url")
            null
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun httpGetBytes(url: String): ByteArray? {
        val conn = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            conn.connectTimeout = 5000
            conn.readTimeout = 10000
            conn.requestMethod = "GET"
            conn.doInput = true
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "image/*,*/*;q=0.5")
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.d(TAG, "httpGetBytes non-2xx code=$code url=$url")
                return null
            }
            conn.inputStream.use { it.readBytes() }
        } catch (t: Throwable) {
            Log.d(TAG, "httpGetBytes exception: ${t.message} url=$url")
            null
        } finally {
            try { conn.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun sha1(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val out = md.digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(out.size * 2)
        for (b in out) {
            sb.append(((b.toInt() ushr 4) and 0xf).toString(16))
            sb.append((b.toInt() and 0xf).toString(16))
        }
        return sb.toString()
    }

    private fun writeDiskSamples(file: File, samples: List<Int>) {
        try {
            DataOutputStream(file.outputStream().buffered()).use { dos ->
                dos.writeInt(samples.size)
                for (s in samples) dos.writeInt(s)
            }
        } catch (_: Throwable) {
            try { file.delete() } catch (_: Throwable) {}
        }
    }

    private fun readDiskSamples(file: File): List<Int> {
        return try {
            DataInputStream(file.inputStream().buffered()).use { dis ->
                val n = dis.readInt()
                if (n <= 0 || n > 4096) return emptyList()
                val out = ArrayList<Int>(n)
                for (i in 0 until n) out.add(dis.readInt())
                out
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
}
