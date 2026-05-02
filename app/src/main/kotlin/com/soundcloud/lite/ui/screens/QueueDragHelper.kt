package com.soundcloud.lite.ui.screens

import com.soundcloud.lite.api.TrackInfo

/**
 * Resolves the *current* index of a track in the working queue by id.
 * Used by the smali patch in `QueueScreen$2$1$3$2$4$1.invoke-k-4lQ0M` so
 * that on long-press start we look up the row's live index instead of
 * relying on a captured `visualIndex` that goes stale every time the
 * user reorders the queue.
 */
object QueueDragHelper {
    @JvmStatic
    fun resolveIndex(workingQueue: List<TrackInfo>?, trackId: Long, fallback: Int): Int {
        if (workingQueue == null) return fallback
        for (i in workingQueue.indices) {
            if (workingQueue[i].id == trackId) return i
        }
        return fallback
    }
}
