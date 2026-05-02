# OpenSound

An Android music client for the [Audius](https://audius.org) decentralized
streaming network, written from scratch in Kotlin + Jetpack Compose.

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

## Why Audius (and not SoundCloud)

The earlier prototype hit SoundCloud's `api-v2` directly, which has two
practical problems:

* tracks get region-locked or pulled by rights-holders (so the app
  surfaces a lot of dead links), and
* the public anonymous `client_id` rotates aggressively, which means
  the app spends time scraping the SoundCloud homepage looking for the
  current one.

Audius is a permissionless catalog with millions of tracks (a lot of
dance / hip-hop / electronic / lo-fi), no region locks, and a stable
public read API at `https://api.audius.co`. No client_id, no OAuth, no
geo restrictions. We pass `app_name=OpenSound` so Audius can attribute
the traffic.

## Status (v0.1.2)

| Feature                                    | State        |
| ------------------------------------------ | ------------ |
| Search Audius tracks                       | Working      |
| Stream playback (mp3 over HTTPS)           | Working      |
| Background play + media notification       | Working      |
| Queue screen with drag-to-reorder          | Working      |
| Swipe-to-remove queue items (50% threshold)| Working      |
| Local playlists (in-memory MVP)            | Working      |
| Trending tracks                            | Working      |
| Related tracks (artist-fallback)           | Working      |
| Liquid Glass UI (GPU + StackBlur)          | Inherited    |
| 3 launcher-icon variants (Orange/Cyan/Purple) | Working   |
| Offline downloads                          | Not yet      |
| Waveform display in fullscreen player      | Not yet      |
| Alternative sources (Cobalt / YT fallback) | Not yet      |
| Importing remote playlists by URL          | Not yet      |
| Persisting playlists across runs (Room)    | Not yet      |

## Architecture

```
ui/
  MainActivity.kt          ─ Compose entry point, NavHost, top-level routes
  MainViewModel.kt         ─ business logic, exposes StateFlow's to screens
  screens/                 ─ one Composable per route
  components/              ─ MiniPlayer, TrackRow, GlassSurface, …
api/
  AudiusApi.kt             ─ OkHttp + Moshi wrapper around api.audius.co
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

The Audius `/v1/tracks/{id}/stream` endpoint redirects to a signed CDN
URL on a different host; ExoPlayer's `DefaultHttpDataSource` handles
the redirect with `setAllowCrossProtocolRedirects(true)` set in
`PlaybackService`.

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
