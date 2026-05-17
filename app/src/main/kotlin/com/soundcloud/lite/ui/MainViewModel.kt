package com.soundcloud.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundcloud.lite.api.PlaylistImporter
import com.soundcloud.lite.api.Provider
import com.soundcloud.lite.api.SoundCloudApi
import com.soundcloud.lite.api.TrackInfo
import com.soundcloud.lite.data.AppSettings
import com.soundcloud.lite.data.DownloadRepository
import com.soundcloud.lite.data.Playlist
import com.soundcloud.lite.data.PlaylistRepository
import com.soundcloud.lite.data.SettingsRepository
import com.soundcloud.lite.player.PlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * UI-facing ViewModel. As of v0.5.0 OpenSound only talks to SoundCloud,
 * so this layer is essentially a thin coroutine wrapper around
 * [SoundCloudApi] + [PlayerManager] + persistent repos.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    private val playlistRepo = PlaylistRepository(application)
    val downloadRepo = DownloadRepository(application)
    val downloads = downloadRepo.downloads

    val soundCloudApi: SoundCloudApi = SoundCloudApi()
    private val playlistImporter: PlaylistImporter = PlaylistImporter(soundCloudApi)
    val playerManager: PlayerManager = PlayerManager(application, soundCloudApi)

    /** Long id → opaque providerId map, kept in memory so screens can
     *  pass Long ids through NavHost arguments and we can still reach
     *  back to the SC track id when needed (e.g. for related tracks). */
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
    /** When non-null the legacy `/charts?kind=trending` one-shot is still
     *  in play (e.g. user has no library and we're falling back to charts).
     *  Set to null after the one-shot completes so we stop hammering it. */
    private var nextTrendingOffset: Int? = 0
    /** Seed pool for the "For You" recommendation engine, built from the
     *  user's library (downloaded tracks + tracks across all playlists).
     *  Rebuilt on every [loadTrending] call so newly downloaded or added
     *  tracks feed back in immediately. */
    private var recoSeeds: List<TrackInfo> = emptyList()
    /** Round-robin cursor into [recoSeeds]. */
    private var nextSeedIndex: Int = 0
    /** Ids of tracks already surfaced in the current recommendation
     *  stream, so each `loadMoreTrending()` page returns fresh material. */
    private val seenRecoIds: MutableSet<Long> = mutableSetOf()
    /** Provider ids the user already has locally - we deliberately exclude
     *  these from recommendations because there's no point recommending a
     *  track the user already saved. */
    private val ownedProviderIds: MutableSet<String> = mutableSetOf()

    private val _related = MutableStateFlow<List<TrackInfo>>(emptyList())
    val related: StateFlow<List<TrackInfo>> = _related.asStateFlow()

    private val _playlists = MutableStateFlow<List<Playlist>>(emptyList())
    val playlists: StateFlow<List<Playlist>> = _playlists.asStateFlow()

    private val _toast = MutableStateFlow<String?>(null)
    val toast: StateFlow<String?> = _toast.asStateFlow()

    private val _importInProgress = MutableStateFlow(false)
    val importInProgress: StateFlow<Boolean> = _importInProgress.asStateFlow()

    init {
        // Load persisted playlists, filtering out tracks from legacy
        // providers (Audius / YouTube) that we no longer support.
        _playlists.value = playlistRepo.loadPlaylists().map { p ->
            p.copy(tracks = p.tracks.filter { it.provider == Provider.SOUNDCLOUD })
        }
        viewModelScope.launch(Dispatchers.IO) {
            _playlists.collect { playlistRepo.savePlaylists(it) }
        }
        playerManager.downloadRepo = downloadRepo
        viewModelScope.launch {
            settings.collect { s ->
                soundCloudApi.oauthToken = s.soundCloudOAuthToken
                soundCloudApi.forcedClientId = s.soundCloudClientId
            }
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        settingsRepo.update(transform)
    }

    private fun rememberProviderIds(tracks: Collection<TrackInfo>) {
        for (t in tracks) if (t.providerId.isNotBlank()) providerIdByNumeric[t.id] = t.providerId
    }

    // ── Search ──────────────────────────────────────────────────────────────────

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
        if (q.isBlank() || _isSearching.value) return
        viewModelScope.launch { doSearch(q, append = true, offset = off) }
    }

    private suspend fun doSearch(query: String, append: Boolean, offset: Int = 0) {
        _isSearching.value = true
        try {
            val results = withContext(Dispatchers.IO) {
                runCatching { soundCloudApi.search(query, limit = 30, offset = offset) }
                    .getOrDefault(emptyList())
            }
            rememberProviderIds(results)
            _searchResults.update { if (append) it + results else results }
            nextSearchOffset = (offset + 30).takeIf { results.isNotEmpty() }
        } catch (t: Throwable) {
            _toast.value = "Search failed: ${t.message ?: t::class.java.simpleName}"
        } finally {
            _isSearching.value = false
        }
    }

    // ── Trending / "For You" ────────────────────────────────────────────────────
    //
    // Behaviour:
    //  * If the user has downloaded tracks or any playlist tracks we treat
    //    those as "seeds" and the Home feed becomes a personalised
    //    recommendation stream: for each page we cycle through seeds and
    //    call SoundCloud's /tracks/{id}/related endpoint. The list grows
    //    indefinitely as the user scrolls (round-robin over seeds).
    //  * If the user has no library yet we fall back to the legacy
    //    /charts?kind=trending one-shot fetch (50 tracks) so first-run
    //    still shows content.

    fun loadTrending() {
        // Reset all reco state and rebuild the seed pool from the
        // user's current library snapshot.
        nextTrendingOffset = 0
        nextSeedIndex = 0
        seenRecoIds.clear()
        ownedProviderIds.clear()
        _trending.value = emptyList()

        val downloadedTracks = downloads.value.map { it.track }
        val playlistTracks = _playlists.value.flatMap { it.tracks }
        val library = (downloadedTracks + playlistTracks)
            .filter { it.provider == Provider.SOUNDCLOUD && it.providerId.isNotBlank() }
            .distinctBy { it.providerId }
        library.forEach { ownedProviderIds.add(it.providerId) }
        // Bias the seed order so each session is different but the
        // newest additions surface first - downloaded tracks lead,
        // playlist tracks fill in, then we shuffle within those tiers.
        recoSeeds = (
            downloadedTracks.shuffled() + playlistTracks.shuffled()
        )
            .filter { it.provider == Provider.SOUNDCLOUD && it.providerId.isNotBlank() }
            .distinctBy { it.providerId }

        loadMoreTrending()
    }

    fun loadMoreTrending() {
        if (_isLoadingTrending.value) return
        if (recoSeeds.isEmpty() && nextTrendingOffset == null) return
        viewModelScope.launch {
            _isLoadingTrending.value = true
            try {
                val tracks = withContext(Dispatchers.IO) {
                    if (recoSeeds.isNotEmpty()) {
                        fetchRecommendationPage()
                    } else {
                        // No library yet: fall back to /charts trending.
                        val genre = _playlists.value
                            .flatMap { it.tracks }
                            .mapNotNull { it.genre }
                            .groupingBy { it }
                            .eachCount()
                            .maxByOrNull { it.value }
                            ?.key
                        runCatching {
                            soundCloudApi.getTrending(genre = genre, limit = 50)
                        }.getOrDefault(emptyList())
                    }
                }
                rememberProviderIds(tracks)
                _trending.update { existing ->
                    // Append while preserving order; dedup by numeric id
                    // so the LazyColumn key { } doesn't crash on dupes.
                    val seen = existing.mapTo(mutableSetOf()) { it.id }
                    existing + tracks.filter { seen.add(it.id) }
                }
                if (recoSeeds.isEmpty()) {
                    // Legacy charts is one-shot: don't refetch.
                    nextTrendingOffset = null
                }
            } catch (t: Throwable) {
                _toast.value = "Trending failed: ${t.message ?: t::class.java.simpleName}"
            } finally {
                _isLoadingTrending.value = false
            }
        }
    }

    /**
     * Pull the next ~3 seeds off the round-robin and call SoundCloud's
     * `/related` for each. Dedup against already-seen tracks AND against
     * the user's library so we never recommend something they already
     * downloaded / added to a playlist.
     */
    private suspend fun fetchRecommendationPage(): List<TrackInfo> {
        if (recoSeeds.isEmpty()) return emptyList()
        val seedsThisPage = 3
        val collected = mutableListOf<TrackInfo>()
        repeat(seedsThisPage) {
            if (recoSeeds.isEmpty()) return@repeat
            val seed = recoSeeds[nextSeedIndex % recoSeeds.size]
            nextSeedIndex++
            val related = runCatching {
                soundCloudApi.getRelated(seed.providerId, limit = 20)
            }.getOrDefault(emptyList())
            for (t in related) {
                if (t.id in seenRecoIds) continue
                if (t.providerId.isBlank()) continue
                if (t.providerId in ownedProviderIds) continue
                seenRecoIds.add(t.id)
                collected.add(t)
            }
        }
        return collected
    }

    /** True when the user's library is non-empty - HomeScreen uses this to
     *  switch the section title from "Trending" to "For You". */
    val hasRecommendationSeeds: StateFlow<Boolean> = combine(
        _playlists,
        downloads,
    ) { pl, dl ->
        pl.any { it.tracks.isNotEmpty() } || dl.isNotEmpty()
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = false,
    )

    // ── Related ─────────────────────────────────────────────────────────────────

    fun loadRelated(trackId: Long) {
        val providerId = providerIdByNumeric[trackId] ?: run {
            _toast.value = "No related tracks for this entry"
            return
        }
        viewModelScope.launch {
            _related.value = emptyList()
            try {
                val list = withContext(Dispatchers.IO) {
                    runCatching { soundCloudApi.getRelated(providerId) }.getOrDefault(emptyList())
                }
                rememberProviderIds(list)
                _related.value = list
            } catch (t: Throwable) {
                _toast.value = "Related failed: ${t.message ?: t::class.java.simpleName}"
            }
        }
    }

    fun clearRelated() { _related.value = emptyList() }

    // ── Playback ────────────────────────────────────────────────────────────────

    fun playTrack(track: TrackInfo, queue: List<TrackInfo>) {
        rememberProviderIds(queue + track)
        playerManager.play(track, queue.ifEmpty { listOf(track) })
    }

    fun playSearchResult(track: TrackInfo) {
        rememberProviderIds(_searchResults.value + track)
        playerManager.play(track, _searchResults.value.ifEmpty { listOf(track) })
    }

    // ── Playlists ───────────────────────────────────────────────────────────────

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

    /** Imports a SoundCloud playlist URL. Other services are not supported. */
    fun importPlaylistFromUrl(url: String) {
        if (_importInProgress.value) return
        _importInProgress.value = true
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) { playlistImporter.import(url) }
                when (result) {
                    is PlaylistImporter.ImportResult.UnsupportedUrl -> {
                        _toast.value = "Only SoundCloud playlist URLs are supported."
                    }
                    is PlaylistImporter.ImportResult.Error -> {
                        _toast.value = "Import failed: ${result.message}"
                    }
                    is PlaylistImporter.ImportResult.Success -> {
                        val tracks = result.tracks.filter { it.provider == Provider.SOUNDCLOUD }
                        rememberProviderIds(tracks)
                        val pl = Playlist(
                            id = "import:" + java.util.UUID.randomUUID().toString().take(8),
                            title = result.title,
                            tracks = tracks,
                            artworkUrl = result.artworkUrl,
                            sourceUrl = url,
                        )
                        _playlists.update { it + pl }
                        _toast.value = "Imported '${result.title}' (${tracks.size} tracks)"
                    }
                }
            } catch (t: Throwable) {
                _toast.value = "Import failed: ${t.message ?: t::class.java.simpleName}"
            } finally {
                _importInProgress.value = false
            }
        }
    }

    // ── Downloads ───────────────────────────────────────────────────────────────

    fun downloadTrack(track: TrackInfo) {
        if (track.isUnplayable) { _toast.value = "Track has no playable source."; return }
        if (downloadRepo.isDownloaded(track.id)) { _toast.value = "Already downloaded."; return }
        viewModelScope.launch {
            _toast.value = "Downloading: ${track.title}"
            val url = withContext(Dispatchers.IO) { resolveDownloadUrl(track) }
            if (url == null) {
                _toast.value = "Download failed: no stream for ${track.title}"
            } else {
                val path = downloadRepo.download(track, url)
                if (path != null) _toast.value = "Downloaded: ${track.title}"
                else _toast.value = "Download failed: ${track.title}"
            }
        }
    }

    fun downloadPlaylist(playlistId: String) {
        val pl = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val toDownload = pl.tracks.filter { !it.isUnplayable && !downloadRepo.isDownloaded(it.id) }
        if (toDownload.isEmpty()) { _toast.value = "All tracks already downloaded."; return }
        _toast.value = "Downloading ${toDownload.size} tracks…"
        viewModelScope.launch {
            toDownload.forEach { track ->
                launch(Dispatchers.IO) {
                    val url = resolveDownloadUrl(track) ?: return@launch
                    downloadRepo.download(track, url)
                }
            }
        }
    }

    private suspend fun resolveDownloadUrl(track: TrackInfo): String? = withContext(Dispatchers.IO) {
        if (track.provider != Provider.SOUNDCLOUD) return@withContext null
        runCatching {
            soundCloudApi.getStreamUrl(track.providerId, track.permalink)?.url
        }.getOrNull()
    }

    fun removeDownload(trackId: Long) {
        downloadRepo.removeDownload(trackId)
        _toast.value = "Download removed."
    }

    fun consumeToast() { _toast.value = null }

    override fun onCleared() {
        super.onCleared()
        playerManager.release()
    }
}
