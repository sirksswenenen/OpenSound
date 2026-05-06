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
import com.soundcloud.lite.data.DownloadRepository
import com.soundcloud.lite.data.Playlist
import com.soundcloud.lite.data.PlaylistRepository
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
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val settingsRepo = SettingsRepository(application)
    val settings: StateFlow<AppSettings> = settingsRepo.settings

    private val playlistRepo = PlaylistRepository(application)
    val downloadRepo = DownloadRepository(application)
    val downloads = downloadRepo.downloads

    val audiusApi: AudiusApi = AudiusApi()
    val youTubeApi: YouTubeApi = YouTubeApi()
    val soundCloudApi: SoundCloudApi = SoundCloudApi()
    val iTunes: iTunesApi = iTunesApi()
    val playlistImporter: PlaylistImporter = PlaylistImporter(youTubeApi)
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

    init {
        // Load persisted playlists on startup
        _playlists.value = playlistRepo.loadPlaylists()
        // Auto-save whenever playlists change
        viewModelScope.launch(Dispatchers.IO) {
            _playlists.collect { playlists ->
                playlistRepo.savePlaylists(playlists)
            }
        }
        // Give the player access to local downloads so it uses file:// URLs
        playerManager.downloadRepo = downloadRepo
        // Keep SoundCloud settings in sync
        viewModelScope.launch {
            settings.collect { s ->
                soundCloudApi.oauthToken     = s.soundCloudOAuthToken
                soundCloudApi.cobaltBaseUrl  = s.cobaltUrl
                soundCloudApi.forcedClientId = s.soundCloudClientId
            }
        }
    }

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
        nextTrendingOffset = 0
        loadMoreTrending()
    }

    fun loadMoreTrending() {
        val off = nextTrendingOffset ?: return
        if (_isLoadingTrending.value) return
        viewModelScope.launch {
            _isLoadingTrending.value = true
            try {
                val tracks = withContext(Dispatchers.IO) {
                    // Derive a genre hint from the user's playlists if possible
                    val genre = _playlists.value
                        .flatMap { it.tracks }
                        .mapNotNull { it.genre }
                        .groupingBy { it }
                        .eachCount()
                        .maxByOrNull { it.value }
                        ?.key

                    val sc = runCatching { soundCloudApi.getTrending(genre = genre, limit = 50) }.getOrDefault(emptyList())
                    if (sc.isNotEmpty()) sc else audiusApi.getTrending(offset = off).tracks
                }
                rememberProviderIds(tracks)
                _trending.update { if (off == 0) tracks else it + tracks }
                // SC trending is one-shot (no paging), Audius has pages
                nextTrendingOffset = null
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

    /** Imports a playlist from a foreign-service URL. Tracks that don't
     *  have a playable provider yet (e.g. SoundCloud-imported tracks)
     *  are matched against YouTube/Audius as a best-effort. */
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
                        // SC tracks stream directly — no need to resolve against Audius/YouTube.
                        // Only non-SC unplayable tracks go through the slow resolve step.
                        val scTracks = result.tracks.filter { it.provider == Provider.SOUNDCLOUD }
                            .map { it.copy(isUnplayable = false) }
                        val otherTracks = result.tracks.filter { it.provider != Provider.SOUNDCLOUD }
                        val resolvedOthers = if (otherTracks.any { it.isUnplayable }) {
                            withContext(Dispatchers.IO) { resolveImportedTracks(otherTracks) }
                        } else otherTracks

                        val allTracks = (scTracks + resolvedOthers)
                            .sortedBy { result.tracks.indexOfFirst { t -> t.id == it.id } }
                        val deduplicated = deduplicateByLongId(allTracks)
                        rememberProviderIds(deduplicated)
                        val pl = Playlist(
                            id = "import:" + java.util.UUID.randomUUID().toString().take(8),
                            title = result.title,
                            tracks = deduplicated,
                            artworkUrl = result.artworkUrl,
                        )
                        _playlists.update { it + pl }
                        val matched = deduplicated.count { !it.isUnplayable }
                        _toast.value = "Imported '${result.title}' ($matched/${deduplicated.size} playable)"
                    }
                }
            } catch (t: Throwable) {
                _toast.value = "Import failed: ${t.message ?: t::class.java.simpleName}"
            } finally {
                _importInProgress.value = false
            }
        }
    }

    /**
     * Deduplicates a track list by `id` (Long). When two tracks collide
     * (e.g. both SC tracks resolved to the same Audius track), the second
     * one gets a slight XOR offset so LazyColumn never sees the same key twice.
     */
    private fun deduplicateByLongId(tracks: List<TrackInfo>): List<TrackInfo> {
        val seen = mutableSetOf<Long>()
        return tracks.mapIndexed { idx, t ->
            var id = t.id
            while (!seen.add(id)) id = id xor ((idx + 1L) * -7046029254386353131L)
            if (id == t.id) t else t.copy(id = id)
        }
    }

    /**
     * Resolves all unplayable placeholders to actual tracks in parallel.
     * All coroutines are launched at once; a Semaphore caps simultaneous
     * API calls at 20 so we don't overwhelm Audius/Invidious.
     * A per-track timeout of 12 s prevents one slow call from blocking everything.
     */
    private suspend fun resolveImportedTracks(tracks: List<TrackInfo>): List<TrackInfo> = coroutineScope {
        val sem = kotlinx.coroutines.sync.Semaphore(20)
        tracks.map { t ->
            async(Dispatchers.IO) {
                if (!t.isUnplayable) return@async t
                if (t.title.isBlank()) return@async t
                sem.withPermit {
                    kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                        resolveOneTrack(t)
                    } ?: t
                }
            }
        }.awaitAll()
    }

    private suspend fun resolveOneTrack(t: TrackInfo): TrackInfo {
        val expectedMs = t.duration.takeIf { it > 5_000L }
        val titleLower = t.title.lowercase()
        val artistLower = t.artistName.lowercase()
        val badTags = listOf("remix", "cover", "karaoke", "live", "acoustic",
            "instrumental", "tribute", "mashup", "bootleg")
        val originalHasTags = badTags.filter { titleLower.contains(it) }.toSet()

        fun score(c: TrackInfo): Int {
            var s = 0
            val ct = c.title.lowercase()
            val ca = c.artistName.lowercase()
            if (ct.contains(titleLower) || titleLower.contains(ct)) s += 30
            if (artistLower.isNotBlank() &&
                (ca.contains(artistLower) || artistLower.contains(ca))) s += 20
            for (tag in badTags) if (ct.contains(tag) && tag !in originalHasTags) s -= 25
            if (expectedMs != null && c.duration > 0L) {
                val pct = kotlin.math.abs(c.duration - expectedMs).toDouble() / expectedMs
                s += when { pct < 0.05 -> 20; pct < 0.15 -> 10; pct < 0.30 -> 5; pct < 0.50 -> 0; else -> -15 }
            }
            return s
        }

        fun bestMatch(candidates: List<TrackInfo>): TrackInfo? {
            if (candidates.isEmpty()) return null
            val best = candidates.maxByOrNull { score(it) } ?: return null
            return if (score(best) > -20) best else null
        }

        val queries = buildList {
            if (t.artistName.isNotBlank()) add("${t.artistName} ${t.title}")
            add(t.title)
        }.distinct()

        for (query in queries) {
            // 1. Audius — fast, no proxy
            val audius = runCatching { audiusApi.search(query, limit = 8).tracks }.getOrDefault(emptyList())
            bestMatch(audius)?.let { found ->
                return found.copy(
                    title = t.title.ifBlank { found.title },
                    artistName = t.artistName.ifBlank { found.artistName },
                    artworkUrl = found.artworkUrl ?: t.artworkUrl,
                    duration = if (expectedMs != null && expectedMs > 0) expectedMs else found.duration,
                    isUnplayable = false,
                )
            }
            // 2. YouTube/Invidious — fallback
            val yt = runCatching { youTubeApi.search(query, limit = 8) }.getOrDefault(emptyList())
            bestMatch(yt)?.let { found ->
                return found.copy(
                    title = t.title.ifBlank { found.title },
                    artistName = t.artistName.ifBlank { found.artistName },
                    artworkUrl = found.artworkUrl ?: t.artworkUrl,
                    duration = if (expectedMs != null && expectedMs > 0) expectedMs else found.duration,
                    isUnplayable = false,
                )
            }
        }
        return t
    }
    // ---- Downloads ----

    /** Download a single track. SC previews (≤31s) are replaced by a YouTube alternative. */
    fun downloadTrack(track: TrackInfo) {
        if (track.isUnplayable) { _toast.value = "Track has no playable source."; return }
        if (downloadRepo.isDownloaded(track.id)) { _toast.value = "Already downloaded."; return }
        viewModelScope.launch {
            _toast.value = "Downloading: ${track.title}"
            val result = withContext(Dispatchers.IO) { resolveDownloadUrl(track) }
            when (result) {
                null -> _toast.value = "Download failed: no stream for ${track.title}"
                else -> {
                    val path = downloadRepo.download(result.first, result.second)
                    if (path != null) _toast.value = "Downloaded: ${track.title}"
                    else _toast.value = "Download failed: ${track.title}"
                }
            }
        }
    }

    /** Download all playable tracks in a playlist that aren't already downloaded. */
    fun downloadPlaylist(playlistId: String) {
        val pl = _playlists.value.firstOrNull { it.id == playlistId } ?: return
        val toDownload = pl.tracks.filter { !it.isUnplayable && !downloadRepo.isDownloaded(it.id) }
        if (toDownload.isEmpty()) { _toast.value = "All tracks already downloaded."; return }
        _toast.value = "Downloading ${toDownload.size} tracks…"
        viewModelScope.launch {
            toDownload.forEach { track ->
                launch(Dispatchers.IO) {
                    val result = resolveDownloadUrl(track) ?: return@launch
                    downloadRepo.download(result.first, result.second)
                }
            }
        }
    }

    /**
     * Resolves the best download URL for a track.
     * For SC tracks: uses SC stream API. If the stream is a preview (≤31 s),
     * substitutes the best YouTube match instead.
     * Returns Pair(effectiveTrackInfo, streamUrl) or null on failure.
     */
    private suspend fun resolveDownloadUrl(track: TrackInfo): Pair<TrackInfo, String>? {
        return when (track.provider) {
            Provider.SOUNDCLOUD -> {
                val scResult = runCatching {
                    soundCloudApi.getStreamUrl(
                        trackId = track.providerId,
                        trackPermalink = track.permalink,
                    )
                }.getOrNull()

                if (scResult == null) return null

                if (scResult.isPreview || track.duration in 1L..31_000L) {
                    // Preview — find YouTube alternative
                    val ytMatch = runCatching {
                        youTubeApi.search("${track.artistName} ${track.title}", limit = 5)
                            .firstOrNull { it.duration > 31_000L }
                    }.getOrNull()
                    if (ytMatch != null) {
                        val ytUrl = youTubeApi.streamUrl(ytMatch.providerId)
                        Pair(track.copy(provider = Provider.YOUTUBE, providerId = ytMatch.providerId), ytUrl)
                    } else {
                        Pair(track, scResult.url)
                    }
                } else {
                    Pair(track, scResult.url)
                }
            }
            Provider.AUDIUS -> {
                val url = playerManager.resolveStreamUrlPublic(track) ?: return null
                Pair(track, url)
            }
            Provider.YOUTUBE -> {
                val url = playerManager.resolveStreamUrlPublic(track) ?: return null
                Pair(track, url)
            }
            Provider.UNKNOWN -> null
        }
    }

    private fun youtube() = youTubeApi

    fun removeDownload(trackId: Long) {
        downloadRepo.removeDownload(trackId)
        _toast.value = "Download removed."
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
