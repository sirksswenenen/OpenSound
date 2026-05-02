# OpenSound

An Android music client that pulls audio from multiple free streaming
backends ([Audius](https://audius.org) + YouTube via public Invidious
proxies), with playlist import from SoundCloud / YouTube and metadata
enrichment via the iTunes Search API. Written from scratch in Kotlin +
Jetpack Compose.

This is a ground-up rewrite of an earlier APK-patching project — none of
the code in `app/src/main/kotlin/` depends on a pre-existing APK or
proprietary jar. Cloning the repo and running the included Gradle
wrapper produces a complete, signed, runnable APK with no external
inputs:

```
git clone https://github.com/terrr88599803-alt/OpenSound.git
cd OpenSound
./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

## Why multiple backends

The earlier prototype hit SoundCloud's `api-v2` directly, which has two
practical problems: tracks get region-locked or pulled by rights-holders,
and the anonymous `client_id` rotates often.

We replaced SoundCloud with two complementary public catalogs:

* **Audius** — permissionless catalog (a few million tracks, mostly
  electronic / hip-hop / lo-fi). Stable public read API at
  `https://api.audius.co`. No `client_id`, no OAuth, no geo locks.
* **YouTube** — accessed via the [Invidious](https://docs.invidious.io)
  open API (public instances). The `/latest_version?id=…&itag=140`
  endpoint serves the audio-only adaptive stream, which ExoPlayer can
  consume directly. Used to fill in fan-edits, game OSTs, and anything
  else that's not on Audius.

Search results from both providers are interleaved in the UI, with a
per-row badge identifying the backend.

## Playlist import

Paste a SoundCloud or YouTube playlist URL on the Playlists tab and the
app:

1. Auto-detects the source from the URL host.
2. Scrapes the public playlist (SoundCloud: JSON-LD on the `/sets/`
   page; YouTube: Invidious `/api/v1/playlists/{id}`).
3. For each foreign track, queries the iTunes Search API to get a
   canonical 1:1 cover, clean title, and original artist.
4. Searches our integrated providers (YouTube → Audius) for a playable
   match and copies its stream provider into the imported track.
5. Tracks for which no match exists stay in the playlist as greyed-out
   placeholders so you can see what was in the original.

Spotify, Yandex.Music, Deezer and Apple Music import are scaffolded
(URL detection works) but the actual scrapers aren't implemented yet —
those services need either OAuth client registration or service-specific
workarounds and will be added incrementally.

## Status (v0.2.0)

| Feature                                            | State     |
| -------------------------------------------------- | --------- |
| Search Audius tracks                               | Working   |
| Search YouTube (Invidious) tracks                  | Working   |
| Merged-provider search results with badges         | Working   |
| Stream playback (mp3 from Audius, m4a from YT)     | Working   |
| Background play + media notification               | Working   |
| Queue screen with drag-to-reorder                  | Working   |
| Swipe-to-remove queue items (50% threshold)        | Working   |
| Local playlists (in-memory MVP)                    | Working   |
| Import SoundCloud playlists by URL                 | Working   |
| Import YouTube playlists by URL                    | Working   |
| iTunes metadata enrichment for imported tracks     | Working   |
| Trending tracks                                    | Working   |
| Related tracks (Audius artist-fallback)            | Working   |
| Liquid Glass UI (GPU + StackBlur)                  | Inherited |
| 3 launcher-icon variants (Orange/Cyan/Purple)      | Working   |
| Import Spotify / Yandex.Music / Deezer playlists   | Detected, scraping not impl. |
| Offline downloads                                  | Not yet   |
| Waveform display in fullscreen player              | Not yet   |
| Alternative sources (Cobalt / YT fallback for Audius) | Not yet |
| Persisting playlists across runs (Room)            | Not yet   |

## Architecture

```
ui/
  MainActivity.kt          ─ Compose entry point, NavHost, top-level routes
  MainViewModel.kt         ─ business logic, exposes StateFlow's to screens
  screens/                 ─ one Composable per route
  components/              ─ MiniPlayer, TrackRow, GlassSurface, …
api/
  Provider.kt              ─ enum tagging which backend served a track
  AudiusApi.kt             ─ OkHttp + Moshi wrapper around api.audius.co
  YouTubeApi.kt            ─ wrapper around public Invidious instances
  iTunesApi.kt             ─ free metadata enricher (1:1 covers + clean artist)
  PlaylistImporter.kt      ─ smart-link dispatcher → SoundCloud/YouTube scrapers
  TrackInfo.kt             ─ provider-agnostic track domain model
  AlternativeSource.kt     ─ placeholder for the Cobalt feature
  WaveformHelper.kt        ─ PNG → Float[] sample extractor
data/
  Settings.kt              ─ AppSettings + SettingsRepository (SharedPreferences)
  Playlist.kt              ─ playlist data class
player/
  PlayerManager.kt         ─ Compose-side facade in front of Media3
  PlayerState.kt           ─ snapshot data class
  PlaybackService.kt       ─ MediaSessionService hosting ExoPlayer
util/
  CrashLogger.kt           ─ crash log tail helper
```

The Compose UI talks to PlayerManager through a `StateFlow<PlayerState>`.
PlayerManager itself talks to a `MediaController` bound to the
`PlaybackService`, which owns the actual ExoPlayer instance and exposes
it as a MediaSession (so the system media notification + Bluetooth
controls Just Work).

Both `audius_api.streamUrl(id)` (returns the `/v1/tracks/.../stream`
indirect URL) and `youtube_api.streamUrl(videoId)` (returns the
Invidious `/latest_version?id=…&itag=140` indirect URL) hand back URLs
that 302 to a CDN signed URL on a *different* host. ExoPlayer's
`DefaultHttpDataSource` handles those redirects via
`setAllowCrossProtocolRedirects(true)` — set centrally in
`PlaybackService` so any new provider that uses HTTP-redirects-to-CDN
just works.

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

## Build environment

- JDK 17
- Android SDK 34 (Build Tools 34.0.0)
- Gradle 8.7 (`./gradlew` will download it on first run)
- Kotlin 1.9.24, AGP 8.5.2

## Disclaimer

This project is a third-party client for the public Audius read API. It
is not affiliated with or endorsed by Audius Inc. or SoundCloud Ltd.
The package id is `com.soundcloud.lite` for upgrade-compatibility with
earlier builds; despite the legacy package name, the app no longer
talks to SoundCloud.
