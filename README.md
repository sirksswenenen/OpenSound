# OpenSound

A native Android music client for SoundCloud. Talks to SoundCloud's
public `api-v2` directly using a rotating anonymous `client_id` (the
same one their own web player uses). Optional `Authorization: OAuth …`
unlocks full-length streams for Go+ subscribers. Written from scratch
in Kotlin + Jetpack Compose, with a liquid-glass UI.

```
git clone https://github.com/sirksswenenen/OpenSound.git
cd OpenSound
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## Why SoundCloud-only

Earlier releases multiplexed Audius + YouTube (via Invidious) + iTunes
metadata into one mosaic catalog. In practice nobody used the other
catalogs, the Invidious instances were unstable, and the cross-source
matching pipeline was the source of half of the bugs. The v0.5 line is
a clean rewrite around exactly one backend so playback reliability is
the only thing to debug.

What that gives you:

* **Search / Trending / Related** — direct calls to
  `api-v2.soundcloud.com/{search,charts,tracks/{id}/related}`.
* **Stream resolution** — `tracks/{id}/streams` is parsed for the
  progressive `http_mp3_128_url` first (ExoPlayer streams it with a
  one-hop CDN redirect) and falls back to HLS `hls_mp3_128_url` when
  progressive is unavailable. Preview-only tracks surface as a 30-second
  clip rather than failing silently.
* **Go+ full-length** — set your OAuth token in Settings (DevTools →
  Network → `Authorization: OAuth …`) and SoundCloud will serve the
  full track instead of the preview.
* **Auto-recovery of client_id** — we ship a snapshot of working
  `client_id`s, probe each one against `/tracks?ids=…`, and fall back
  to scraping `soundcloud.com` (the home page references the JS bundle
  which contains the current `client_id`) if all of them have rotated.

## Playlist import

Paste a SoundCloud playlist URL on the Playlists tab. The importer:

1. Loads the public `/sets/` page.
2. Reads the embedded JSON-LD `MusicPlaylist` block (which is what the
   web player itself does).
3. Resolves the listed track ids in one batch via
   `api-v2.soundcloud.com/tracks?ids=…` so each row already has full
   metadata + artwork by the time it appears on screen.

YouTube / Spotify / Apple Music / Deezer URLs are rejected (with a
clear "SoundCloud-only" message) so the importer never tries to scrape
a service it has no scraper for.

## Status (v0.5.0)

| Feature                                          | State     |
| ------------------------------------------------ | --------- |
| Search SoundCloud tracks                         | Working   |
| Trending tracks (genre charts)                   | Working   |
| Related tracks                                   | Working   |
| Stream playback (progressive + HLS fallback)     | Working   |
| Preview clip fallback for restricted tracks      | Working   |
| Go+ full-length via OAuth                        | Working   |
| Background play + media notification             | Working   |
| Drag-to-reorder queue (rewritten in v0.5)        | Working   |
| Swipe-to-remove queue items (40% threshold)      | Working   |
| Import SoundCloud playlists by URL               | Working   |
| Local playlists (persisted to disk)              | Working   |
| Liquid Glass UI (GPU + StackBlur fallback)       | Working   |
| Glassmorphic bottom navigation                   | Working   |
| 3 launcher-icon variants (Orange/Cyan/Purple)    | Working   |
| Offline downloads (mp3 stream → file)            | Working   |
| Waveform display in fullscreen player            | Not yet   |

## Architecture

```
ui/
  MainActivity.kt          ─ Compose entry point, NavHost, top-level routes
  MainViewModel.kt         ─ business logic, exposes StateFlow's to screens
  screens/                 ─ one Composable per route
  components/              ─ MiniPlayer, TrackRow, GlassSurface, …
api/
  Provider.kt              ─ legacy enum (kept for on-disk back-compat)
  SoundCloudApi.kt         ─ OkHttp wrapper around api-v2.soundcloud.com
  PlaylistImporter.kt      ─ JSON-LD scraper for /sets/ pages
  TrackInfo.kt             ─ track domain model
  WaveformHelper.kt        ─ PNG → Float[] sample extractor
data/
  Settings.kt              ─ AppSettings + SettingsRepository (SharedPreferences)
  Playlist.kt              ─ playlist data class
  PlaylistRepository.kt    ─ JSON-on-disk playlist store
  DownloadRepository.kt    ─ mp3 stream → app-private file cache
player/
  PlayerManager.kt         ─ Compose-side facade in front of Media3
  PlayerState.kt           ─ snapshot data class
  PlaybackService.kt       ─ MediaSessionService hosting ExoPlayer
util/
  CrashLogger.kt           ─ crash log tail helper
```

The Compose UI talks to `PlayerManager` through a
`StateFlow<PlayerState>`. `PlayerManager` itself talks to a
`MediaController` bound to `PlaybackService`, which owns the actual
ExoPlayer instance and exposes it as a `MediaSession` (so the system
media notification + Bluetooth controls Just Work).

`SoundCloudApi.getStreamUrl(trackId)` returns a CDN URL that 302s on
every request (the URL itself is signed and expires in ~5 min), so we
hand the indirect URL to ExoPlayer and let
`DefaultHttpDataSource` follow the redirect — enabled via
`setAllowCrossProtocolRedirects(true)` in `PlaybackService`.

## Building

```
./gradlew :app:assembleRelease
```

The release APK lands at `app/build/outputs/apk/release/app-release.apk`.

The build uses the `sclite.keystore` checked into the repo root by
default. To sign with a different keystore:

```
./gradlew :app:assembleRelease \
  -PSCLITE_KEYSTORE=/abs/path/to/your.keystore \
  -PSCLITE_STOREPASS=your-store-pass \
  -PSCLITE_KEYALIAS=your-alias \
  -PSCLITE_KEYPASS=your-key-pass
```

GitHub Actions builds + publishes signed APKs to Releases on every push
to `main` — see `.github/workflows/build-release.yml`.

## Build environment

- JDK 17
- Android SDK 34 (Build Tools 34.0.0)
- Gradle 8.7 (`./gradlew` will download it on first run)
- Kotlin 1.9.24, AGP 8.5.2

## Disclaimer

Third-party client for SoundCloud's public web-player API. Not
affiliated with or endorsed by SoundCloud Ltd. No login is required to
use the app, but you may paste an `OAuth` token from your own browser
session in Settings if you want full-length playback of Go+ tracks. The
token is stored in app-private `SharedPreferences` only.
