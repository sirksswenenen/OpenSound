package com.soundcloud.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundcloud.lite.api.AlternativeSource
import com.soundcloud.lite.api.AudiusApi
import com.soundcloud.lite.api.PlaylistImporter
import com.soundcloud.lite.api.Provider
import com.soundcloud.lite.api.SoundCloudApi
import com.soundcloud.lite.api.TrackInfo
import com.soundcloud.lite.api.YouTubeApi
import com.soundcloud.lite.api.iTunesApi
import com.soundcloud.lite.data.AppSettings
import com.soundcloud.lite.data.Playlist
import com.soundcloud.lite.data.SettingsRepository
import com.soundcloud.lite.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    val audiusApi: AudiusApi = AudiusApi()
    val youTubeApi: YouTubeApi = YouTubeApi()
    val soundCloudApi: SoundCloudApi = SoundCloudApi()
    val iTunes: iTunesApi = iTunesApi()
    val playlistImporter: PlaylistImporter = PlaylistImporter(youTubeApi, soundCloudApi)
    val playerManager: PlayerManager = PlayerManager(application, audiusApi, youTubeApi, soundCloudApi)

    /** Long id → opaque providerId. Lets screens keep using Long
     *  identifiers in NavHost arguments. Populated lazily as we
     *  observe tracks coming back from the API. */
    private val providerIdByNumeric = mutableMapOf<Long, String>()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<TrackInfo>>(emptyList())
    val searchResults: StateFlow<List<TrackInfo>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private var nextSearchOffset: Int? = null
    private var searchJob: Job? = null

    private val _trending = MutableStateFlow<List<TrackInfo>>(emptyList())
    val trending: StateFlow<List<TrackInfo>> = _trending.asStateFlow()
    private val _isLoadingTrending = MutableStateFlow(false)
    val isLoadingTrending: StateFlow<Boolean> = _isLoadingTrending.asStateFlow()
    private var nextTrendingOffset: Int? = 0

    private val _related = MutableStateFlow<List<TrackInfo>>(emptyList())
    val related: StateFlow<List<TrackInfo>> = _related.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _altSourceState = MutableStateFlow<AltSourceState?>(null)
    val altSourceState: StateFlow<AltSourceState?> = _altSourceState.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _importInProgress = MutableStateFlow(false)
    val importInProgress: StateFlow<Boolean> = _importInProgress.asStateFlow()

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsRepo.update(transform)
    }

    private fun rememberProviderIds(tracks: Collection<TrackInfo>) {
        for (t in tracks) if (t.providerId.isNotBlank()) providerIdByNumeric[t.id] = t.providerId
    }

    // ---- Search ----

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            nextSearchOffset = null
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            doSearch(query, append = false)
        }
    }

    fun searchNow() {
        val q = _searchQuery.value.trim()
        if (q.isBlank()) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch { doSearch(q, append = false) }
    }

    fun loadMoreSearch() {
        val q = _searchQuery.value.trim()
        val off = nextSearchOffset ?: return
        if (q.isBlank()) return
        if (_isSearching.value) return
        viewModelScope.launch { doSearch(q, append = true, offset = off) }
    }

    private suspend fun doSearch(query: String, append: Boolean, offset: Int = 0) {
        _isSearching.value = true
        try {
            // Fan out to both providers in parallel; whichever returns
            // first contributes to the result set, the other backfills.
            val results: List<TrackInfo> = withContext(Dispatchers.IO) {
                coroutineScope {
                    val audiusDef = async {
                        runCatching { audiusApi.search(query, offset).tracks }.getOrDefault(emptyList())
                    }
                    val ytDef = async {
                        if (offset == 0) runCatching { youTubeApi.search(query) }.getOrDefault(emptyList())
                        else emptyList()  // YouTube via Invidious doesn't paginate, only first page
                    }
                    val (a, y) = listOf(audiusDef, ytDef).awaitAll()
                    interleave(a, y)
                }
            }
            rememberProviderIds(results)
            _searchResults.update { if (append) it + results else results }
            // Audius drives pagination; treat YouTube as page-1 only.
            nextSearchOffset = (offset + 50).takeIf { results.isNotEmpty() }
        } catch (t: Throwable) {
            _toast.value = "Search failed: ${t.message ?: t::class.java.simpleName}"
        } finally {
            _isSearching.value = false
        }
    }

    private fun interleave(a: List<TrackInfo>, b: List<TrackInfo>): List<TrackInfo> {
        val out = ArrayList<TrackInfo>(a.size + b.size)
        val iA = a.iterator(); val iB = b.iterator()
        while (iA.hasNext() || iB.hasNext()) {
            if (iA.hasNext()) out += iA.next()
            if (iB.hasNext()) out += iB.next()
        }
        return out
    }

    // ---- Trending ----

    fun loadTrending() {
        if (_trending.value.isNotEmpty()) return
        loadMoreTrending()
    }

    fun loadMoreTrending() {
        val off = nextTrendingOffset ?: return
        if (_isLoadingTrending.value) return
        viewModelScope.launch {
            _isLoadingTrending.value = true
            try {
                val page = withContext(Dispatchers.IO) { audiusApi.getTrending(offset = off) }
                rememberProviderIds(page.tracks)
                _trending.update { it + page.tracks }
                nextTrendingOffset = page.nextOffset
            } catch (t: Throwable) {
                _toast.value = "Trending failed: ${t.message ?: t::class.java.simpleName}"
            } finally {
                _isLoadingTrending.value = false
            }
        }
    }

    // ---- Related ----

    fun loadRelated(trackId: Long) {
        val providerId = providerIdByNumeric[trackId] ?: run {
            _toast.value = "No related: track id missing"
            return
        }
        viewModelScope.launch {
            _related.value = emptyList()
            try {
                val list = withContext(Dispatchers.IO) {
                    runCatching { audiusApi.getRelatedTracks(providerId) }.getOrDefault(emptyList())
                }
                rememberProviderIds(list)
                _related.value = list
            } catch (t: Throwable) {
                _toast.value = "Related failed: ${t.message ?: t::class.java.simpleName}"
            }
        }
    }

    fun clearRelated() { _related.value = emptyList() }

    // ---- Playback ----

    fun playTrack(track: TrackInfo, queue: List<TrackInfo>) {
        rememberProviderIds(queue + track)
        playerManager.play(track, queue.ifEmpty { listOf(track) })
    }

    fun playSearchResult(track: TrackInfo) {
        rememberProviderIds(_searchResults.value + track)
        playerManager.play(track, _searchResults.value.ifEmpty { listOf(track) })
    }

    // ---- Playlists ----

    fun createPlaylist(title: String) {
        if (title.isBlank()) return
        val pl = Playlist(
            id = "local:" + java.util.UUID.randomUUID().toString().take(8),
            title = title.trim(),
            tracks = emptyList(),
        )
        _playlists.update { it + pl }
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.update { list -> list.filterNot { it.id == playlistId } }
    }

    fun addToPlaylist(playlistId: String, track: TrackInfo) {
        rememberProviderIds(listOf(track))
        _playlists.update { list ->
            list.map { p ->
                if (p.id == playlistId && p.tracks.none { it.id == track.id }) {
                    p.copy(tracks = p.tracks + track)
                } else p
            }
        }
    }

    fun removeFromPlaylist(playlistId: String, trackId: Long) {
        _playlists.update { list ->
            list.map { p ->
                if (p.id == playlistId) p.copy(tracks = p.tracks.filterNot { it.id == trackId }) else p
            }
        }
    }

    fun playlistById(id: String): Playlist? = _playlists.value.firstOrNull { it.id == id }

    /** Imports a playlist from a foreign-service URL.
     *
     *  - SoundCloud playlists come back fully-resolved with native SC
     *    streams; no further matching needed.
     *  - YouTube playlists come back with YT streams.
     *  - Anything else (e.g. an imagined Spotify importer in the
     *    future) returns placeholder tracks that we then try to match
     *    against YouTube and Audius (with iTunes-cleaned queries) so
     *    that they have *something* playable. We constrain matches to
     *    ±15% of the original duration to avoid Extended Mix
     *    versions getting picked over the canonical track. */
    fun importPlaylistFromUrl(url: String) {
        if (_importInProgress.value) return
        _importInProgress.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { playlistImporter.import(url) }
                when (result) {
                    is PlaylistImporter.ImportResult.UnsupportedUrl -> {
                        _toast.value = "Unsupported URL. Try SoundCloud or YouTube."
                    }
                    is PlaylistImporter.ImportResult.Error -> {
                        _toast.value = "Import failed (${result.sourceName}): ${result.message}"
                    }
                    is PlaylistImporter.ImportResult.Success -> {
                        // SoundCloud / YouTube come back already playable;
                        // unplayable entries (region-locks, deleted videos)
                        // are skipped over by [resolveImportedTracks] for
                        // foreign sources only.
                        val resolvedTracks = withContext(Dispatchers.IO) { resolveImportedTracks(result.tracks) }
                        rememberProviderIds(resolvedTracks)
                        val pl = Playlist(
                            id = "import:" + java.util.UUID.randomUUID().toString().take(8),
                            title = result.title,
                            tracks = resolvedTracks,
                            artworkUrl = result.artworkUrl,
                        )
                        _playlists.update { it + pl }
                        val matched = resolvedTracks.count { !it.isUnplayable }
                        val msg = buildString {
                            append("Imported '${result.title}' (${matched}/${resolvedTracks.size} playable)")
                            result.warning?.let { append(". ").append(it) }
                        }
                        _toast.value = msg
                    }
                }
            } catch (t: Throwable) {
                _toast.value = "Import failed: ${t.message ?: t::class.java.simpleName}"
            } finally {
                _importInProgress.value = false
            }
        }
    }

    /** For each placeholder track (unplayable + provider == UNKNOWN),
     *  search YouTube/Audius for a duration-matched playable version.
     *  Tracks that already have a native provider stream attached to
     *  them are returned as-is. */
    private suspend fun resolveImportedTracks(tracks: List<TrackInfo>): List<TrackInfo> = coroutineScope {
        tracks.map { t ->
            async {
                if (t.provider != Provider.UNKNOWN) return@async t
                if (!t.isUnplayable) return@async t
                val query = t.title.ifBlank { return@async t }
                val match = runCatching { iTunes.enrich(query) }.getOrNull()
                val fullQuery = if (match != null) "${match.artist} ${match.title}" else query
                val targetMs = t.duration
                fun durationOk(d: Long) =
                    targetMs <= 0L || d <= 0L || kotlin.math.abs(d - targetMs) <= (targetMs * 15 / 100)
                val yt = runCatching { youTubeApi.search(fullQuery, limit = 5) }.getOrDefault(emptyList())
                    .firstOrNull { durationOk(it.duration) }
                val playable = yt ?: runCatching {
                    audiusApi.search(fullQuery, limit = 5).tracks.firstOrNull { durationOk(it.duration) }
                }.getOrNull()
                if (playable != null) {
                    playable.copy(
                        title = match?.title ?: playable.title,
                        artistName = match?.artist ?: playable.artistName,
                        artworkUrl = match?.artworkUrl1000 ?: playable.artworkUrl,
                        genre = match?.genre ?: playable.genre,
                    )
                } else {
                    t.copy(
                        title = match?.title ?: t.title,
                        artistName = match?.artist ?: t.artistName,
                        artworkUrl = match?.artworkUrl1000 ?: t.artworkUrl,
                        isUnplayable = true,
                    )
                }
            }
        }.awaitAll()
    }

    // ---- Alt sources (placeholder; UI shows a "not available in MVP" dialog) ----

    fun openAlternativeSourcesFor(track: TrackInfo, currentPlaylistId: String? = null) {
        _altSourceState.value = AltSourceState(originalTrack = track, currentPlaylistId = currentPlaylistId)
    }

    fun closeAlternativeSources() { _altSourceState.value = null }

    fun playAlternative(track: TrackInfo, alt: AlternativeSource) {
        _toast.value = "Alternative sources are not available yet"
        _altSourceState.value = null
    }

    fun replaceWithAlternative(playlistId: String, trackId: Long, alt: AlternativeSource) {
        _toast.value = "Alternative sources are not available yet"
        _altSourceState.value = null
    }

    fun addAlternativeToPlaylist(playlistId: String, alt: AlternativeSource) {
        _toast.value = "Alternative sources are not available yet"
        _altSourceState.value = null
    }

    fun consumeToast() { _toast.value = null }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}
