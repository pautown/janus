<p align="center">
  <img src="assets/janus-logo.jpg" alt="Janus Logo" width="300">
</p>

# Janus

Android companion app for the llizard CarThing UI system. Bridges Android media playback to the Spotify CarThing device via Bluetooth Low Energy (BLE).

## Overview

Janus acts as a BLE GATT server that exposes media playback state, album art, and podcast browsing to [Mercury](https://github.com/pautown/mercury), the BLE client daemon running on the CarThing. It monitors the Android device's active media sessions (Spotify, YouTube Music, etc.) and makes that state available over BLE, while also providing a built-in podcast player with lazy-loading episode browsing.

**Architecture**: Android Phone (Janus/GATT Server) ← BLE → CarThing (Mercury/GATT Client) → Redis → [llizard](https://github.com/pautown/llizard)

## System Overview

Janus is part of a three-component system for bringing media control to the Spotify CarThing:

| Component | Platform | Role |
|-----------|----------|------|
| **Janus** | Android | BLE GATT server exposing media state from phone |
| [**Mercury**](https://github.com/pautown/mercury) | CarThing (Go) | BLE client daemon that bridges phone ↔ Redis |
| [**llizard**](https://github.com/pautown/llizard) | CarThing (C/raylib) | Native GUI that displays media from Redis |

## Features

- **BLE GATT Server**: Advertises as "Janus" and serves media state over BLE
- **Universal Media Control**: Monitors any Android media app via NotificationListenerService
- **Media Channel Selection**: Switch which media app to control (Spotify, YouTube Music, Podcasts, etc.)
- **Podcast Player**: Built-in Media3/ExoPlayer podcast player with subscription management
- **Podcast Browsing**: Lazy-loading podcast browser with A-Z list, recent episodes, and paginated per-podcast episode lists
- **Album Art Transfer**: Optimized binary chunked transfer protocol (WebP, 250x250px)
- **Compact BLE Format**: ~55% reduction in payload size for podcast data
- **Synced Lyrics**: Fetches time-synced lyrics from LRCLIB API with caching
- **Playback Commands**: Bidirectional control (play, pause, seek, volume, skip, toggle)
- **Time Sync**: Syncs phone time to CarThing on connection
- **Foreground Service**: Maintains BLE connection in background

## Requirements

- **Android 8.0+** (API level 26)
- **BLE Hardware** (Bluetooth Low Energy)
- **Notification Listener Permission** (for universal media monitoring)
- **Storage Permission** (for podcast caching)

## Build Instructions

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- JDK 17
- Android SDK 34
- Kotlin 1.9.22

### Building

1. Clone the repository:
```bash
git clone https://github.com/pautown/janus-android.git
cd janus-android
```

2. Open in Android Studio or build via command line:
```bash
./gradlew assembleDebug
```

3. Install to device:
```bash
./gradlew installDebug
```

Or build release APK:
```bash
./gradlew assembleRelease
# APK output: app/build/outputs/apk/release/app-release-unsigned.apk
```

## Architecture

### Layer Structure

```
┌─────────────────────────────────────────────────────────┐
│                    UI Layer (Compose)                   │
│  MainActivity, MainViewModel, PodcastPage, PlayerPage   │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                    Domain Layer                         │
│  MediaState, PlaybackCommand, CompactBleModels          │
│  PodcastInfoResponse, PodcastListResponse               │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                    Data Layer                           │
│  MediaRepository, PodcastRepository                     │
│  MediaSessionListener, PodcastPlayerService             │
│  Room Database (podcasts, episodes)                     │
└─────────────────────────────────────────────────────────┘
                           ↓
┌─────────────────────────────────────────────────────────┐
│                    BLE Layer                            │
│  GattServerService, GattServerManager                   │
│  AlbumArtTransmitter, NotificationThrottler             │
└─────────────────────────────────────────────────────────┘
```

### Key Components

#### BLE Layer (`com.mediadash.android.ble`)
- **GattServerService**: Foreground service hosting the BLE GATT server. Manages the lifecycle, observes media state changes, and coordinates characteristic updates. Uses Hilt for dependency injection.
- **GattServerManager**: Core BLE management - opens GATT server, sets up characteristics, handles advertising, manages device connections, and sends notifications. Singleton scoped.
- **AlbumArtTransmitter**: Handles chunked binary album art transmission with flow control. Tracks in-flight transfers per device and uses notification callbacks for pacing.
- **NotificationThrottler**: Rate-limits BLE notifications with configurable minimum interval (10ms default) to prevent buffer overflow.
- **BleConstants**: Protocol constants including UUIDs, chunk sizes (496 bytes), header size (16 bytes), and image dimensions (250x250).

#### Data Layer (`com.mediadash.android.data`)
- **MediaRepository/MediaRepositoryImpl**: Provides current media state, album art chunks, and processes playback commands. Bridges MediaControllerManager and PodcastRepository.
- **MediaControllerManager**: Manages the active Android MediaController for external apps (Spotify, YouTube Music, etc.). Tracks playback state, processes metadata changes, and prepares album art chunks.
- **MediaSessionListener**: NotificationListenerService that monitors all Android media sessions. Implements auto-switching when a new app starts playing and channel selection.
- **PlaybackSourceTracker**: Tracks which media source (internal podcast vs external app) is active. Enables proper resume functionality and play/pause routing.
- **PodcastRepository**: Manages podcast subscriptions, episodes, and feed parsing using Room database. Supports iTunes API search and RSS feed subscriptions.
- **AlbumArtCache**: LRU cache for prepared album art chunks, keyed by hash.
- **AlbumArtFetcher**: Fetches album art from MediaMetadata or URLs, resizes to 250x250, encodes as WebP.
- **LyricsManager**: Manages lyrics fetching, caching (LRU, 50 entries), and BLE transmission. Converts lyrics to chunked format.
- **SettingsManager**: DataStore-backed settings (e.g., lyrics enabled toggle).

#### Media Layer (`com.mediadash.android.media`)
- **PodcastPlayerService**: Media3 MediaSessionService hosting ExoPlayer for podcast playback. Handles audio focus and background playback.
- **PodcastPlayerManager**: High-level podcast playback API. Manages playlist, playback controls, and syncs state to MediaControllerManager for BLE exposure.
- **EpisodeDownloadManager**: Handles podcast episode downloads for offline playback.

#### Domain Layer (`com.mediadash.android.domain`)
- **MediaState**: Current playback state serialized to JSON (track, artist, album, duration, position, volume, albumArtHash, mediaChannel).
- **PlaybackCommand**: Commands from CarThing with validation. Supports playback controls, podcast browsing, and media channel selection.
- **CompactBleModels**: Optimized data models with short field names for minimal BLE bandwidth. Includes hash generation functions.
- **PodcastInfoResponse**: Legacy full podcast response and new lazy-loading response types (PodcastListResponse, RecentEpisodesResponse, PodcastEpisodesResponse).
- **LyricsState/CompactLyricsResponse**: Lyrics models with timestamps for synced lyrics display.
- **AlbumArtChunk**: Binary chunk model with serialization to 16-byte header + data format.

#### DI Layer (`com.mediadash.android.di`)
- **AppModule**: Provides BluetoothManager, BluetoothAdapter, AudioManager, coroutine dispatchers, and application scope.
- **BleModule**: Provides GattServerManager and related BLE components.
- **MediaModule**: Provides MediaRepository, MediaControllerManager, and related media components.
- **PodcastModule**: Provides Room database, DAOs, PodcastRepository, and RSS parser.

#### UI Layer (`com.mediadash.android.ui`)
- **MainActivity**: Entry point with permission handling for Bluetooth and notifications.
- **MainScreen**: Compose-based main UI with connection status, now playing, and navigation.
- **MainViewModel**: UI state management, service control, and podcast observation.
- **PodcastPage/PodcastViewModel**: Podcast subscription management and browsing.
- **PodcastPlayerPage/PodcastPlayerViewModel**: Podcast playback controls and progress.

## BLE Protocol

### Service UUID
```
0000a0d0-0000-1000-8000-00805f9b34fb  (Janus Service)
```

### Characteristics

| Characteristic | UUID | Properties | Description |
|----------------|------|------------|-------------|
| Media State | `0000a0d1-0000-1000-8000-00805f9b34fb` | Read, Notify | Current media playback state (JSON) |
| Playback Control | `0000a0d2-0000-1000-8000-00805f9b34fb` | Write, Write No Response | Commands from CarThing (JSON) |
| Album Art Request | `0000a0d3-0000-1000-8000-00805f9b34fb` | Write, Write No Response | Request album art by hash (JSON) |
| Album Art Data | `0000a0d4-0000-1000-8000-00805f9b34fb` | Read, Notify | Album art chunks (binary) |
| Podcast Info | `0000a0d5-0000-1000-8000-00805f9b34fb` | Read, Notify | Podcast data (JSON, chunked) |
| Lyrics Request | `0000a0d6-0000-1000-8000-00805f9b34fb` | Write, Write No Response | Request lyrics for track (JSON) |
| Lyrics Data | `0000a0d7-0000-1000-8000-00805f9b34fb` | Read, Notify | Synced lyrics (JSON, chunked) |
| Settings | `0000a0d8-0000-1000-8000-00805f9b34fb` | Read, Notify | Configuration settings (JSON) |
| Time Sync | `0000a0d9-0000-1000-8000-00805f9b34fb` | Read, Notify | Unix timestamp for time sync |

### Media State Characteristic

JSON structure sent to CarThing on media changes:

```json
{
  "isPlaying": true,
  "playbackState": "playing",
  "trackTitle": "Song Title",
  "artist": "Artist Name",
  "album": "Album Name",
  "duration": 240000,
  "position": 45000,
  "volume": 75,
  "albumArtHash": "1234567890",
  "mediaChannel": "Spotify"
}
```

The `mediaChannel` field indicates which app is being controlled (e.g., "Spotify", "YouTube Music", "Podcasts").

### Playback Control Characteristic

Commands sent from CarThing:

```json
// Basic playback controls
{"action": "play"}
{"action": "pause"}
{"action": "toggle"}
{"action": "next"}
{"action": "previous"}
{"action": "stop"}
{"action": "seek", "value": 60000}
{"action": "volume", "value": 80}

// Podcast playback (by episode hash - recommended)
{"action": "play_episode", "episodeHash": "a1b2c3d4"}

// Legacy podcast playback (by index - deprecated)
{"action": "play_podcast_episode", "podcastId": "abc123", "episodeIndex": 5}

// Podcast data requests (lazy loading)
{"action": "request_podcast_list"}
{"action": "request_recent_episodes", "limit": 30}
{"action": "request_podcast_episodes", "podcastId": "abc123", "offset": 0, "limit": 15}

// Media channel selection (switch which app to control)
{"action": "request_media_channels"}
{"action": "select_media_channel", "channel": "Spotify"}
```

### Lyrics Request Characteristic

Request lyrics for current track:

```json
{"action": "get", "artist": "Artist Name", "track": "Track Title"}
{"action": "get", "hash": "abc12345"}
{"action": "clear", "hash": "abc12345"}
```

### Time Sync Characteristic

On client connection, Janus sends a Unix timestamp (seconds since epoch) as a UTF-8 string for CarThing time synchronization.

### Album Art Transfer Protocol

**Binary chunk format** (16-byte header + up to 496 bytes data):

```
Offset  Size   Type      Field
------  ----   ----      -----
0       4      uint32    hash (CRC32 of artist+album, little-endian)
4       2      uint16    chunkIndex (0-based, little-endian)
6       2      uint16    totalChunks (little-endian)
8       2      uint16    dataLength (bytes in this chunk, little-endian)
10      4      uint32    dataCRC32 (CRC32 of chunk data, little-endian)
14      2      uint16    reserved (0)
16+     N      bytes     raw WebP image data (max 496 bytes)
```

**Protocol details:**
- Album art resized to 250x250px, WebP format, quality 75
- Maximum notification size: 512 bytes (16 header + 496 data)
- Chunks sent with 10ms minimum interval between notifications
- CarThing requests art by CRC32 hash to avoid redundant transfers
- Album art hash = CRC32(artist + album) as decimal string
- Request format: `{"hash": "1234567890"}`

### Podcast Info Characteristic

Three response types for lazy-loading podcast browsing:

#### Type 1: Podcast List (A-Z)

Header: `[0x01][chunk_index][total_chunks]` + JSON payload

```json
{
  "p": [
    {"h": "abc12345", "n": "Podcast Name", "c": 150}
  ],
  "np": {"h": "abc12345", "t": "Episode Title", "i": 5}
}
```

#### Type 2: Recent Episodes

Header: `[0x02][chunk_index][total_chunks]` + JSON payload

```json
{
  "e": [
    {"h": "a1b2c3d4", "p": "def67890", "c": "Podcast Name", "t": "Episode Title", "d": 3600, "u": 1704499200, "i": 0}
  ],
  "t": 30
}
```

Fields: `h`=episode hash, `p`=podcast hash, `c`=channel/podcast name, `t`=title, `d`=duration (seconds), `u`=pubDate (unix timestamp seconds), `i`=index (for backward compat)

#### Type 3: Podcast Episodes (Paginated)

Header: `[0x03][chunk_index][total_chunks]` + JSON payload

```json
{
  "h": "abc12345",
  "n": "Podcast Name",
  "t": 150,
  "o": 0,
  "m": true,
  "e": [
    {"h": "a1b2c3d4", "t": "Episode Title", "d": 3600, "u": 1704499200}
  ]
}
```

Fields: `h`=podcast/episode hash, `n`=name, `t`=total count or title, `o`=offset, `m`=has more, `e`=episodes, `d`=duration (seconds), `u`=pubDate (unix timestamp seconds)

#### Type 4: Media Channels

Header: `[0x04][chunk_index][total_chunks]` + binary payload

Binary format for media channel list:
```
2 bytes: uint16 count (big-endian)
For each channel:
  1 byte: length of name
  N bytes: UTF-8 name
```

Example channels: "Spotify", "YouTube Music", "Podcasts"

### Lyrics Data Characteristic

Lyrics are sent in chunked JSON format. Each chunk has a 3-byte header followed by JSON:

Header: `[lyrics_chunk_index][ble_packet_index][total_ble_packets]`

```json
{
  "h": "abc12345",
  "s": true,
  "n": 50,
  "c": 0,
  "m": 3,
  "l": [
    {"t": 15000, "l": "First line of lyrics"},
    {"t": 18500, "l": "Second line of lyrics"}
  ]
}
```

Fields:
- `h`: Hash (CRC32 of artist|track)
- `s`: Synced (true if has timestamps)
- `n`: Total line count
- `c`: Chunk index (0-based)
- `m`: Max chunks (total)
- `l`: Array of lyrics lines
  - `t`: Timestamp in milliseconds (0 if unsynced)
  - `l`: Lyrics text

Clear notification sends empty lines array with `n=0`.

### Settings Characteristic

Settings are broadcast as JSON when they change:

```json
{"lyricsEnabled": true}
```

Clients can read current settings or subscribe to changes via notifications

## Compact BLE Format

To minimize BLE bandwidth usage, podcast data uses a compact JSON format with abbreviated field names and optimized data types.

### Size Reduction Example

**Original Format** (~180 bytes per episode):
```json
{
  "podcastId": "com.example.podcast.feed.123",
  "podcastTitle": "The Example Podcast Show",
  "title": "Episode 42: Understanding the Universe",
  "duration": 3600000,
  "publishDate": "Jan 15, 2024",
  "pubDate": 1705305600000,
  "episodeIndex": 0
}
```

**Compact Format** (~80 bytes per episode, **55% smaller**):
```json
{
  "h": "a1b2c3d4",
  "c": "The Example Podcast Show",
  "t": "Episode 42: Understanding the Universe",
  "d": 3600,
  "i": 0
}
```

### Field Mappings

| Original | Compact | Notes |
|----------|---------|-------|
| `podcastId` + `pubDate` | `h` | CRC32 hash (8 chars) |
| `podcastTitle` | `c` | Channel name |
| `title` | `t` | Episode title |
| `duration` | `d` | Seconds (not ms) |
| `episodeIndex` | `i` | Index for playback |
| `podcastHash` | `h` | Podcast ID hash |
| `name` | `n` | Podcast name |
| `count` | `c` | Episode count |
| `total` | `t` | Total count |
| `offset` | `o` | Pagination offset |
| `more` | `m` | Has more pages |
| `episodes` | `e` | Episode array |
| `podcasts` | `p` | Podcast array |
| `nowPlaying` | `np` | Currently playing |

### Hash Generation

**Episode hash** encodes `feedUrl|pubDate|duration` as CRC32 (uses seconds, not milliseconds):
```kotlin
fun generateEpisodeHash(feedUrl: String, pubDate: Long, duration: Long): String {
    val pubDateSec = pubDate / 1000
    val durationSec = duration / 1000
    val input = "$feedUrl|$pubDateSec|$durationSec"
    val crc = CRC32()
    crc.update(input.toByteArray())
    return String.format("%08x", crc.value)  // "a1b2c3d4"
}
```

**Podcast hash** uses the podcast ID directly if short, otherwise CRC32:
```kotlin
fun generatePodcastHash(podcastId: String): String {
    if (podcastId.length <= 8) return podcastId
    val crc = CRC32()
    crc.update(podcastId.toByteArray())
    return String.format("%08x", crc.value)
}
```

**Album art hash** encodes `artist|album` as CRC32:
```kotlin
fun generateAlbumArtHash(artist: String, album: String): String {
    val input = "$artist|$album"
    val crc = CRC32()
    crc.update(input.toByteArray())
    return crc.value.toString()  // Decimal string: "1234567890"
}
```

**Lyrics hash** encodes `artist|track` (lowercase, trimmed) as CRC32.

## Media Channel Selection

Janus monitors all active Android media sessions and allows the CarThing to switch which app it controls.

### How It Works

1. **MediaSessionListener** monitors Android's MediaSessionManager for active sessions
2. When a new app starts playing, Janus auto-switches to control it
3. CarThing can request the list of available channels via `request_media_channels`
4. CarThing can select a specific channel via `select_media_channel`

### Channel Types

| Channel | Source | Description |
|---------|--------|-------------|
| Spotify | External | Spotify app media session |
| YouTube Music | External | YouTube Music app media session |
| Podcasts | Internal | Janus built-in podcast player |
| (other apps) | External | Any app with active MediaSession |

### Auto-Switch Behavior

When an external app starts playing:
1. MediaSessionListener detects the new playing session
2. If different from current controlled app, auto-switches to it
3. Media state updates to reflect new source
4. `mediaChannel` field in MediaState updates

### Manual Channel Selection

```json
// Request available channels
{"action": "request_media_channels"}

// Response (Type 4 binary on Podcast Info characteristic)
// Channels: ["Spotify", "YouTube Music", "Podcasts"]

// Select specific channel
{"action": "select_media_channel", "channel": "Spotify"}
```

When selecting a channel:
1. Currently playing app is paused (if different from selected)
2. Selected app becomes the active controller
3. Playback commands route to selected app

## Podcast Lazy Loading System

### Three-Layer Browse Experience

1. **A-Z Podcast List** (Type 1 Response)
   - Shows all subscribed podcasts sorted alphabetically
   - Displays: podcast name, episode count
   - No episodes loaded initially → minimal bandwidth

2. **Recent Episodes** (Type 2 Response)
   - Cross-podcast chronological feed
   - Displays: podcast name, episode title, duration
   - Limited to N most recent episodes (default 30)

3. **Per-Podcast Episodes** (Type 3 Response)
   - Episodes for specific podcast, paginated
   - Displays: episode title, duration
   - Loads 15 episodes per page, on-demand

### Request Flow Example

```
CarThing (Mercury)                Janus (Android)
       │                                 │
       ├─ request_podcast_list ─────────>│
       │<─ Type 1: Podcast List ─────────┤
       │  (names only, no episodes)      │
       │                                 │
       ├─ request_podcast_episodes ─────>│
       │  podcastId="abc123"             │
       │  offset=0, limit=15             │
       │<─ Type 3: Episodes 0-14 ────────┤
       │                                 │
       ├─ request_podcast_episodes ─────>│
       │  podcastId="abc123"             │
       │  offset=15, limit=15            │
       │<─ Type 3: Episodes 15-29 ───────┤
```

### Bandwidth Savings

Traditional approach (send all episodes upfront):
- 10 podcasts × 100 episodes × 180 bytes = **180 KB**

Lazy loading approach (send list + on-demand episodes):
- 10 podcasts × 80 bytes = 800 bytes
- 1 podcast × 15 episodes × 80 bytes = 1.2 KB
- **Total: ~2 KB** (99% reduction for initial load)

## Usage

### First Launch

1. Grant **Bluetooth** permissions
2. Grant **Notification Listener** permission (Settings → Apps → Janus → Notification access)
3. Grant **Post Notifications** permission (Android 13+)
4. Tap **Start BLE Service** in the app

### Connecting to CarThing

1. Ensure [Mercury](https://github.com/pautown/mercury) daemon is running on CarThing
2. CarThing will auto-discover and connect to "Janus" BLE advertisement
3. Connection status shows in app UI

### Podcast Subscriptions

1. Navigate to **Podcasts** tab
2. Search for podcasts or add RSS feed URL
3. Tap **Subscribe** to add to library
4. Subscribed podcasts appear in **My Podcasts** section
5. Podcasts are automatically exposed to CarThing via BLE

### Media Playback

- Play media on **any Android app** (Spotify, YouTube Music, etc.)
- Media state automatically syncs to CarThing
- Control playback from CarThing UI
- Album art transfers on-demand when requested

## Dependencies

### Core
- Kotlin 1.9.22
- AndroidX Core KTX 1.12.0
- AndroidX Lifecycle 2.7.0
- Jetpack Compose (BOM 2024.12.01)

### Dependency Injection
- Hilt 2.50

### Networking
- Retrofit 2.9.0
- OkHttp 4.12.0
- Kotlinx Serialization 1.6.2

### Media
- Media3 (ExoPlayer) 1.2.1
- AndroidX Media 1.7.0

### Database
- Room 2.6.1

### Other
- RSS Parser 6.0.7
- Coil (image loading) 2.5.0

## Permissions

```xml
<!-- BLE -->
<uses-permission android:name="android.permission.BLUETOOTH" />
<uses-permission android:name="android.permission.BLUETOOTH_ADMIN" />
<uses-permission android:name="android.permission.BLUETOOTH_ADVERTISE" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />

<!-- Media monitoring -->
<uses-permission android:name="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" />

<!-- Networking (podcast fetching, lyrics) -->
<uses-permission android:name="android.permission.INTERNET" />

<!-- Foreground service -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Integration with Mercury

Janus is designed to work with [Mercury](https://github.com/pautown/mercury), the BLE client daemon on the CarThing. Mercury:

1. Scans for "Janus" BLE advertisement
2. Connects as GATT client
3. Subscribes to Media State, Album Art, and Lyrics characteristics
4. Sends playback commands via Playback Control characteristic
5. Requests podcast data via Podcast Info characteristic
6. Stores all state in Redis for consumption by [llizard](https://github.com/pautown/llizard) UI plugins

## Development Notes

### Testing BLE without CarThing

Use **nRF Connect** app on another Android device to inspect GATT characteristics:
1. Install nRF Connect for Mobile
2. Scan for "Janus" device
3. Connect and explore service `0000a0d0-...`
4. Read/subscribe to characteristics
5. Write commands to Playback Control characteristic

### Debugging

The app uses structured logging with semantic tags for easy filtering:

```bash
# BLE operations
adb logcat -s GattServerManager:V GattServerService:V AlbumArtTransmitter:V

# Album art transfers (detailed)
adb logcat -s ALBUMART:V

# Podcast operations
adb logcat -s PODCAST:I PodcastAudio:D

# Lyrics fetching and transmission
adb logcat -s LYRICS:D LyricsManager:D

# Media channels
adb logcat -s MEDIA_CHANNELS:I

# Media controller and session
adb logcat -s MediaControllerManager:D MediaSessionListener:D

# Playback source tracking
adb logcat -s PlaybackSourceTracker:D

# Combined useful filter
adb logcat -s GattServerManager:D GattServerService:D ALBUMART:I PODCAST:I LYRICS:I MEDIA_CHANNELS:I MediaControllerManager:D
```

Log prefixes used in verbose output:
- `═══` Start/end of major operations
- `───` Section separators
- `📥` Incoming requests
- `📤` Outgoing responses
- `✅` Success
- `⚠️` Warnings
- `❌` Errors
- `📦` Cache operations
- `📡` Network/BLE transmission

### Project Structure

```
app/src/main/kotlin/com/mediadash/android/
├── MediaDashApplication.kt          # Hilt application entry point
├── ble/
│   ├── BleConstants.kt              # UUIDs, sizes, protocol constants
│   ├── GattServerService.kt         # Foreground service (Hilt-injected)
│   ├── GattServerManager.kt         # GATT server lifecycle & operations
│   ├── AlbumArtTransmitter.kt       # Binary chunk transmission
│   └── NotificationThrottler.kt     # Rate limiting
├── data/
│   ├── local/
│   │   ├── PodcastDatabase.kt       # Room database
│   │   ├── PodcastDao.kt            # Podcast DAO
│   │   ├── EpisodeDao.kt            # Episode DAO (in PodcastDao.kt)
│   │   ├── PodcastEntity.kt         # Room entities
│   │   └── SettingsManager.kt       # DataStore preferences
│   ├── media/
│   │   ├── MediaControllerManager.kt  # External app control
│   │   ├── MediaSessionListener.kt    # Session monitoring
│   │   ├── PlaybackSourceTracker.kt   # Active source tracking
│   │   ├── AlbumArtCache.kt           # LRU cache
│   │   ├── AlbumArtFetcher.kt         # Image fetching & processing
│   │   └── LyricsManager.kt           # Lyrics fetch & cache
│   ├── remote/
│   │   ├── ITunesApiService.kt        # iTunes podcast search
│   │   ├── RssFeedParser.kt           # RSS feed parsing
│   │   ├── LyricsApiService.kt        # LRCLIB API client
│   │   └── OPMLParser.kt              # OPML import support
│   └── repository/
│       ├── MediaRepository.kt         # Interface
│       ├── MediaRepositoryImpl.kt     # Implementation
│       └── PodcastRepository.kt       # Podcast data access
├── di/
│   ├── AppModule.kt                   # Core dependencies
│   ├── BleModule.kt                   # BLE dependencies
│   ├── MediaModule.kt                 # Media dependencies
│   └── PodcastModule.kt               # Podcast dependencies
├── domain/
│   ├── model/
│   │   ├── MediaState.kt              # Playback state model
│   │   ├── PlaybackCommand.kt         # Command model
│   │   ├── AlbumArtChunk.kt           # Binary chunk model
│   │   ├── Podcast.kt                 # Podcast & episode models
│   │   ├── PodcastInfoResponse.kt     # BLE response types
│   │   ├── CompactBleModels.kt        # Optimized BLE models
│   │   ├── LyricsState.kt             # Lyrics models
│   │   └── ConnectionStatus.kt        # BLE connection states
│   └── usecase/
│       └── ProcessPlaybackCommandUseCase.kt
├── media/
│   ├── PodcastPlayerService.kt        # Media3 service
│   ├── PodcastPlayerManager.kt        # Playback management
│   └── EpisodeDownloadManager.kt      # Offline downloads
└── ui/
    ├── MainActivity.kt                # Entry point
    ├── MainViewModel.kt               # Main screen state
    ├── theme/Theme.kt                 # Material 3 theme
    ├── composables/                   # Reusable Compose components
    │   ├── MainScreen.kt
    │   ├── NowPlayingCard.kt
    │   ├── ConnectionStatusCard.kt
    │   └── ...
    ├── podcast/
    │   ├── PodcastPage.kt
    │   └── PodcastViewModel.kt
    └── player/
        ├── PodcastPlayerPage.kt
        └── PodcastPlayerViewModel.kt
```

### Adding New Playback Commands

1. Add action constant to `PlaybackCommand.kt`:
   ```kotlin
   const val ACTION_MY_COMMAND = "my_command"
   ```

2. Add to `VALID_ACTIONS` set in the same file

3. Handle in `GattServerService.observeCommands()`:
   ```kotlin
   PlaybackCommand.ACTION_MY_COMMAND -> {
       Log.i("MY_TAG", "Processing my command")
       handleMyCommand(command)
   }
   ```

4. For data requests, implement handler method and use `gattServerManager.notify*()` to respond

5. For playback commands, delegate to `ProcessPlaybackCommandUseCase` which routes to `MediaRepository`

### Adding New BLE Characteristics

1. Add UUID constant to `BleConstants.kt`:
   ```kotlin
   val MY_CHARACTERISTIC_UUID: UUID = UUID.fromString("0000a0da-0000-1000-8000-00805f9b34fb")
   ```

2. Add characteristic property in `GattServerManager.kt`:
   ```kotlin
   private var myCharacteristic: BluetoothGattCharacteristic? = null
   ```

3. Create characteristic in `setupService()`:
   ```kotlin
   myCharacteristic = BluetoothGattCharacteristic(
       BleConstants.MY_CHARACTERISTIC_UUID,
       BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
       BluetoothGattCharacteristic.PERMISSION_READ
   ).apply {
       addDescriptor(createCCCD())
   }
   service.addCharacteristic(myCharacteristic)
   ```

4. Add notify method for sending data:
   ```kotlin
   suspend fun notifyMyData(data: MyData) {
       val characteristic = myCharacteristic ?: return
       val server = gattServer ?: return
       // ... serialize and send
   }
   ```

### Modifying BLE Protocol

**IMPORTANT**: BLE UUIDs and data formats must match [Mercury](https://github.com/pautown/mercury) exactly. Any changes require coordinated updates on both sides.

Protocol changes checklist:
1. Update `BleConstants.kt` (Janus)
2. Update `ble/constants.go` (Mercury)
3. Update binary format handling in both projects
4. Update this README documentation
5. Test with nRF Connect before integration testing

## Related Projects

- [**llizard**](https://github.com/pautown/llizard): Native CarThing GUI (raylib/raygui)
- [**Mercury**](https://github.com/pautown/mercury): CarThing BLE client daemon (bridges Janus ↔ Redis)

## License

See repository for license details.

---

**Note**: Janus requires a Spotify CarThing device running [llizard](https://github.com/pautown/llizard) with [Mercury](https://github.com/pautown/mercury). It will not function as a standalone media player without BLE connectivity to the CarThing.
