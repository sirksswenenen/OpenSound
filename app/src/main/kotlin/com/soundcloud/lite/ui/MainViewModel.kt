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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private var nextTrendingOffset: Int? = 0

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

    // ── Trending ────────────────────────────────────────────────────────────────

    fun loadTrending() {
        nextTrendingOffset = 0
        loadMoreTrending()
    }

    fun loadMoreTrending() {
        if (_isLoadingTrending.value) return
        // SC's /charts endpoint is one-shot (no paging), so we only fetch once.
        if (nextTrendingOffset == null) return
        viewModelScope.launch {
            _isLoadingTrending.value = true
            try {
                val tracks = withContext(Dispatchers.IO) {
                    val genre = _playlists.value
                        .flatMap { it.tracks }
                        .mapNotNull { it.genre }
                        .groupingBy { it }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key
                    runCatching { soundCloudApi.getTrending(genre = genre, limit = 50) }
                        .getOrDefault(emptyList())
                }
                rememberProviderIds(tracks)
                _trending.update { tracks }
                nextTrendingOffset = null
            } catch (t: Throwable) {
                _toast.value = "Trending failed: ${t.message ?: t::class.java.simpleName}"
            } finally {
                _isLoadingTrending.value = false
            }
        }
    }

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
