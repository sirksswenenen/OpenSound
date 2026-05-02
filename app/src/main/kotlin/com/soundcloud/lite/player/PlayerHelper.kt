package com.soundcloud.lite.player

import com.soundcloud.lite.api.TrackInfo

/**
 * Static helpers used by smali-patched bodies inside PlayerManager and its
 * listener. Keeping them in a separate Kotlin object makes the patches small
 * and lets us iterate the logic in real Kotlin instead of hand-written smali.
 *
 * The functions take primitive / stable types only (String, Long, Int) so we
 * do not need media3 on the build classpath here.
 */
object PlayerHelper {

    /**
     * Returns the index in [queue] whose track id matches the supplied
     * media id (decimal string of the track id), or -1 if no match.
     */
    @JvmStatic
    fun findIndexByMediaId(queue: List<TrackInfo>?, mediaId: String?): Int {
        if (queue == null || queue.isEmpty()) return -1
        if (mediaId.isNullOrEmpty()) return -1
        val targetId = mediaId.toLongOrNull() ?: return -1
        for (i in queue.indices) {
            if (queue[i].id == targetId) return i
        }
        return -1
    }

    /**
     * Pick the right index. If the supplied [mediaId] matches a track in the
     * queue, prefer that. Otherwise fall back to the controller's reported
     * index. This is the listener's source of truth for "which queue entry
     * is currently playing".
     */
    @JvmStatic
    fun resolveIndex(queue: List<TrackInfo>?, mediaId: String?, fallbackIndex: Int): Int {
        val byId = findIndexByMediaId(queue, mediaId)
        return if (byId >= 0) byId else fallbackIndex
    }

    /**
     * Format the total duration of [tracks] as a short "Xh Ym" / "Ym Zs" / "0m"
     * label suitable for the playlist subtitle. Accepts a heterogenous list
     * (TrackInfo, StoredTrack, etc.) and pulls the duration via reflection so
     * smali patches can pass any list type without conversion.
     */
    @JvmStatic
    fun formatTotalDuration(tracks: List<Any?>?): String {
        if (tracks == null || tracks.isEmpty()) return "0m"
        var totalMs = 0L
        for (t in tracks) {
            val d = extractDurationMillis(t)
            if (d > 0) totalMs += d
        }
        val totalSec = totalMs / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m"
            seconds > 0 -> "${seconds}s"
            else -> "0m"
        }
    }

    private fun extractDurationMillis(item: Any?): Long {
        if (item == null) return 0L
        if (item is TrackInfo) return item.duration
        return try {
            val m = item.javaClass.getMethod("getDuration")
            val r = m.invoke(item)
            when (r) {
                is Long -> r
                is Int -> r.toLong()
                is Number -> r.toLong()
                else -> 0L
            }
        } catch (_: Throwable) {
            0L
        }
    }
}
