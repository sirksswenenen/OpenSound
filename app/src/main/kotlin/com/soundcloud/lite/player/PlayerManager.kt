package com.soundcloud.lite.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.soundcloud.lite.api.Provider
import com.soundcloud.lite.api.SoundCloudApi
import com.soundcloud.lite.api.TrackInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Wraps a Media3 ExoPlayer (running inside a foreground service) and
 * exposes its state as a Compose-friendly StateFlow.
 *
 * Designed so the rest of the app talks to PlayerState only — never to
 * ExoPlayer directly. That makes the player easy to swap or stub.
 */
class PlayerManager(
    private val context: Context,
    private val soundCloud: SoundCloudApi,
) {

    /** Injected by MainActivity/ViewModel after construction. */
    var downloadRepo: com.soundcloud.lite.data.DownloadRepository? = null

    private suspend fun resolveStreamUrl(track: TrackInfo): String? =
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            // Use Uri.fromFile so any odd characters in the path are
            // properly encoded — bare "file://$path" can produce
            // unparseable URIs that ExoPlayer rejects with no obvious
            // error from the UI side.
            downloadRepo?.getLocalPath(track.id)?.let { localPath ->
                return@withContext android.net.Uri.fromFile(java.io.File(localPath)).toString()
            }
            if (track.provider != Provider.SOUNDCLOUD) {
                android.util.Log.w(
                    "PlayerManager",
                    "Skipping legacy ${track.provider} track ${track.title}"
                )
                return@withContext null
            }
            if (track.providerId.isBlank()) {
                android.util.Log.w("PlayerManager", "Track ${track.title} has blank providerId")
                return@withContext null
            }
            val res = soundCloud.getStreamUrl(track.providerId, track.permalink)
            android.util.Log.d(
                "PlayerManager",
                "resolveStreamUrl id=${track.providerId} -> ${res?.url} preview=${res?.isPreview}"
            )
            res?.url
        }
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private var controller: MediaController? = null
    private var pendingTrack: TrackInfo? = null
    private var pendingQueue: List<TrackInfo> = emptyList()
    private var positionPoller: Job? = null
    private var connectFuture: ListenableFuture<MediaController>? = null

    init { connect() }

    private fun connect() {
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, PlaybackService::class.java),
        )
        connectFuture = MediaController.Builder(context.applicationContext, token).buildAsync().also { fut ->
            fut.addListener({
                val ctl = fut.get()
                controller = ctl
                ctl.addListener(object : Player.Listener {
                    override fun onIsPlayingChanged(isPlaying: Boolean) { syncState() }
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        syncState()
                        if (playbackState == Player.STATE_READY) startPositionPolling()
                        if (playbackState == Player.STATE_ENDED) {
                            stopPositionPolling()
                            // Auto-advance to next queue item if any.
                            skipNext()
                        }
                    }
                    override fun onPositionDiscontinuity(
                        oldPosition: Player.PositionInfo,
                        newPosition: Player.PositionInfo,
                        reason: Int,
                    ) { syncState() }
                })
                pendingTrack?.let { play(it, pendingQueue) }
                pendingTrack = null
            }, context.mainExecutor)
        }
    }

    fun release() {
        scope.cancel()
        controller?.release()
        controller = null
    }

    /** Queue a track + optional surrounding queue and start playback. */
    fun play(track: TrackInfo, queue: List<TrackInfo>) {
        val ctl = controller
        if (ctl == null) {
            pendingTrack = track
            pendingQueue = queue
            return
        }
        scope.launch {
            try {
                val resolvedQueue = if (queue.isEmpty()) listOf(track) else queue
                val startIndex = resolvedQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

                if (track.isUnplayable) {
                    _state.update { it.copy(error = "This track has no playable source yet.") }
                    return@launch
                }
                // Both Audius (/stream) and Invidious (/latest_version)
                // serve a short-lived 302 to a CDN URL on every request,
                // so we hand the indirect URL to ExoPlayer and let it
                // follow redirects + range-request the audio.
                val streamUrl = resolveStreamUrl(track) ?: run {
                    _state.update { it.copy(error = "Couldn't resolve stream for ${track.title}") }
                    return@launch
                }

                // Only enqueue the current track in the player; we'll
                // re-prepare the next one in onMediaItemTransition. Putting
                // empty URLs in the player's queue makes ExoPlayer choke on
                // transition.
                ctl.setMediaItems(listOf(mediaItem(track, streamUrl)), 0, 0)
                ctl.prepare()
                ctl.play()
                _state.update {
                    it.copy(
                        currentTrack = track,
                        queue = resolvedQueue,
                        queueIndex = startIndex,
                        duration = if (track.duration > 0) track.duration else 0,
                    )
                }
                startPositionPolling()
            } catch (t: Throwable) {
                _state.update { it.copy(error = "Failed to play: ${t.message ?: t::class.java.simpleName}") }
            }
        }
    }

    fun togglePlayPause() {
        val ctl = controller ?: return
        if (ctl.isPlaying) ctl.pause() else ctl.play()
    }

    fun skipNext() {
        val cur = _state.value
        val nextIdx = cur.queueIndex + 1
        val track = cur.queue.getOrNull(nextIdx) ?: return
        playAtIndex(track, nextIdx)
    }

    fun skipPrevious() {
        val ctl = controller ?: return
        val cur = _state.value
        if (ctl.currentPosition > 4_000) {
            ctl.seekTo(0)
            return
        }
        val prevIdx = cur.queueIndex - 1
        val track = cur.queue.getOrNull(prevIdx) ?: run {
            ctl.seekTo(0); return
        }
        playAtIndex(track, prevIdx)
    }

    private fun playAtIndex(track: TrackInfo, index: Int) {
        val ctl = controller ?: return
        scope.launch {
            try {
                if (track.isUnplayable) {
                    _state.update { it.copy(error = "This track has no playable source yet.") }
                    return@launch
                }
                val url = resolveStreamUrl(track) ?: run {
                    _state.update { it.copy(error = "Couldn't resolve stream for ${track.title}") }
                    return@launch
                }
                ctl.setMediaItem(mediaItem(track, url), 0)
                ctl.prepare()
                ctl.play()
                _state.update {
                    it.copy(
                        currentTrack = track,
                        queueIndex = index,
                        duration = if (track.duration > 0) track.duration else 0,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(error = "Failed to load track: ${t.message ?: t::class.java.simpleName}") }
            }
        }
    }

    fun seekTo(positionMs: Long) { controller?.seekTo(positionMs) }

    fun toggleShuffle() {
        val ctl = controller ?: return
        ctl.shuffleModeEnabled = !ctl.shuffleModeEnabled
        _state.update { it.copy(shuffle = ctl.shuffleModeEnabled) }
    }

    fun cycleRepeat() {
        val ctl = controller ?: return
        val next = when (ctl.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        ctl.repeatMode = next
        _state.update { it.copy(repeatMode = next) }
    }

    /** Public: replace queue (used by Queue screen drag-reorder + delete). */
    fun setQueue(newQueue: List<TrackInfo>, currentIndex: Int) {
        // The player itself only ever holds one MediaItem; we just update
        // the conceptual queue + index here. Actual playback continues
        // unaffected unless the current track was removed.
        val safeIdx = currentIndex.coerceIn(0, (newQueue.size - 1).coerceAtLeast(0))
        _state.update { it.copy(queue = newQueue, queueIndex = safeIdx) }
    }

    /**
     * Remove the currently-playing track from the queue.
     * If a next track exists, it starts playing immediately.
     * If the queue becomes empty, playback stops and the player state is cleared
     * (which hides the mini-player in the UI).
     */
    fun stopAndRemoveCurrent() {
        val ctl = controller ?: return
        val cur = _state.value
        val idx = cur.queueIndex
        val queue = cur.queue.toMutableList()
        if (idx < 0 || idx >= queue.size) return
        queue.removeAt(idx)

        if (queue.isEmpty()) {
            ctl.stop()
            ctl.clearMediaItems()
            stopPositionPolling()
            _state.value = PlayerState()
        } else {
            val nextIdx = idx.coerceAtMost(queue.size - 1)
            val nextTrack = queue[nextIdx]
            _state.update { it.copy(queue = queue, queueIndex = nextIdx) }
            playAtIndex(nextTrack, nextIdx)
        }
    }

    /**
     * Shuffle all tracks in the queue in-place, keeping the currently
     * playing track pinned at its current position so playback continues
     * uninterrupted. A second call restores the original order (acts as
     * toggle: shuffle → unshuffle → shuffle …).
     */
    fun shuffleQueue() {
        val cur = _state.value
        val queue = cur.queue
        if (queue.size < 2) return
        val curIdx = cur.queueIndex

        if (cur.shuffledOrder != null) {
            // Restore original order
            val restored = cur.shuffledOrder
            val newCurIdx = restored.indexOfFirst { it.id == queue.getOrNull(curIdx)?.id }
                .takeIf { it >= 0 } ?: curIdx
            _state.update { it.copy(queue = restored, queueIndex = newCurIdx, shuffledOrder = null) }
        } else {
            // Save original, shuffle everything except current track
            val currentTrack = queue.getOrNull(curIdx)
            val others = queue.toMutableList().also { it.removeAt(curIdx) }
            others.shuffle()
            val shuffled = others.toMutableList().also {
                if (currentTrack != null) it.add(0, currentTrack)
            }
            _state.update { it.copy(queue = shuffled, queueIndex = if (currentTrack != null) 0 else 0, shuffledOrder = queue) }
        }
    }

    private fun mediaItem(track: TrackInfo, url: String): MediaItem {
        val md = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artistName)
            .setArtworkUri(track.artworkUrl?.let { android.net.Uri.parse(it) })
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id.toString())
            .setUri(url)
            .setMediaMetadata(md)
            .build()
    }

    private fun syncState() {
        val ctl = controller ?: return
        val isPlaying = ctl.isPlaying
        val pos = ctl.currentPosition.coerceAtLeast(0)
        val dur = ctl.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
        _state.update {
            it.copy(
                isPlaying = isPlaying,
                position = pos,
                duration = if (dur > 0) dur else it.duration,
            )
        }
    }

    private fun startPositionPolling() {
        positionPoller?.cancel()
        positionPoller = scope.launch {
            while (true) {
                delay(500)
                val ctl = controller ?: continue
                val pos = ctl.currentPosition.coerceAtLeast(0)
                val dur = ctl.duration.takeIf { it != C.TIME_UNSET }?.coerceAtLeast(0L) ?: 0L
                _state.update { it.copy(position = pos, duration = if (dur > 0) dur else it.duration) }
            }
        }
    }

    private fun stopPositionPolling() {
        positionPoller?.cancel(); positionPoller = null
    }

    private fun CoroutineScope.cancel() = (this.coroutineContext[Job] as? Job)?.cancel()
}
