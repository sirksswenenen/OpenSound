# OpenSC

A SoundCloud-like Android client written from scratch in Kotlin + Jetpack
Compose. This is a ground-up MVP rewrite of an earlier APK-patching
project — none of the code in `app/src/main/kotlin/` depends on a
pre-existing APK or proprietary jar. Building with the included Gradle
wrapper produces a complete, signed, runnable APK.

## Status (v0.1.0)

| Feature                                    | State        |
| ------------------------------------------ | ------------ |
| Search SoundCloud tracks                   | Working      |
| Stream playback (HLS / progressive)        | Working      |
| Background play + media notification       | Working      |
| Queue screen with drag-to-reorder          | Working      |
| Swipe-to-remove queue items (50% threshold)| Working      |
| Local playlists (in-memory MVP)            | Working      |
| Trending tracks (Charts API)               | Working      |
| Related tracks                             | Working      |
| Settings (theme, OAuth token, glass)       | Working      |
| Liquid Glass UI (GPU + StackBlur)          | Inherited    |
| Offline downloads                          | Not yet      |
| Waveform display in fullscreen player      | Not yet      |
| Alternative sources (Cobalt / YT fallback) | Not yet      |
| Importing remote playlists by URL          | Not yet      |
| Persisting playlists across runs (Room)    | Not yet      |

The "not yet" items are tracked for the next iteration; the architecture
already has room for them (the PlayerManager and the SoundCloudApi can
be plugged into a Room-backed download index without touching the UI).

## Architecture

```
ui/
  MainActivity.kt          ─ Compose entry point, NavHost, top-level routes
  MainViewModel.kt         ─ business logic, exposes StateFlow's to screens
  screens/                 ─ one Composable per route
  components/              ─ MiniPlayer, TrackRow, GlassSurface, …
api/
  SoundCloudApi.kt         ─ OkHttp + Moshi wrapper around api-v2.soundcloud.com
  TrackInfo.kt             ─ track-domain data class
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

## Getting a SoundCloud `client_id`

The `api-v2.soundcloud.com` endpoints require a `client_id`. We pull it
straight from the public web app at runtime:

1. `GET https://soundcloud.com/`
2. Walk every `<script src="…">` from the response.
3. In each script body, regex-match `client_id\s*[:=]\s*"([a-zA-Z0-9]{32})"`.
4. The first match is cached for the process lifetime.

If SoundCloud rotates the id, kill the app and reopen — `ensureClientId`
will fetch a fresh one. If you have an OAuth token (DevTools → Network
→ any api-v2 request → `Authorization: OAuth …`), paste it in
**Settings → SoundCloud OAuth token** to play tracks that are
geo-locked or preview-only for anonymous users.

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

This project is a personal client for SoundCloud's public web API. It
is not affiliated with or endorsed by SoundCloud Ltd. Use a real OAuth
token from your own account if you need authenticated access.
