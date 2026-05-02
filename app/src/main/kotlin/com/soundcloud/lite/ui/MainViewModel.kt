package com.soundcloud.lite.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.soundcloud.lite.api.AlternativeSource
import com.soundcloud.lite.api.AudiusApi
import com.soundcloud.lite.api.TrackInfo
import com.soundcloud.lite.data.AppSettings
import com.soundcloud.lite.data.Playlist
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

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    val api: AudiusApi = AudiusApi()
    val playerManager: PlayerManager = PlayerManager(application, api)

    /** Long id → Audius alphanumeric id, populated lazily as we observe
     *  tracks coming back from the API. Lets screens keep using Long
     *  identifiers in NavHost arguments. */
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
            val page = withContext(Dispatchers.IO) { api.search(query, offset) }
            rememberProviderIds(page.tracks)
            _searchResults.update { if (append) it + page.tracks else page.tracks }
            nextSearchOffset = page.nextOffset
        } catch (t: Throwable) {
            _toast.value = "Search failed: ${t.message ?: t::class.java.simpleName}"
        } finally {
            _isSearching.value = false
        }
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
                val page = withContext(Dispatchers.IO) { api.getTrending(offset = off) }
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
                val list = withContext(Dispatchers.IO) { api.getRelatedTracks(providerId) }
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
