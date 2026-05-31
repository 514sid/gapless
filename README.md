# Gapless

A Jetpack Compose library for seamless, gapless media playback on Android. Designed for digital signage, kiosks, and any display that must never show a black frame between assets.

Plays video, images, and web content in a continuous loop. Transitions are preloaded before the current asset ends, so the switch is instantaneous.

## Tested on

| Device | Result |
| :----- | :----- |
| Amazon Fire Stick 4K | UHD video gapless playback |

---

## Features

- **Zero black frames** — next asset is buffered before the current one finishes
- **Mixed media** — video (MP4, HLS, DASH, RTSP via ExoPlayer), images (Coil), and web pages (WebView) in the same playlist
- **Scheduling** — per-asset date ranges, days of the week, and time-of-day windows with midnight-crossing support
- **Shuffle** — reshuffles each cycle, preventing the last-played asset from appearing first
- **Rotation** — built-in 0/90/180/270 degree content rotation without affecting the composable's layout bounds
- **Custom states** — supply your own composable for empty playlists and idle (scheduled-but-inactive) states

---

## Installation

```kotlin
dependencies {
    implementation("io.github.514sid:gapless:0.0.13")
}
```

Also available via **GitHub Packages** and **JitPack** (see [setup notes](#alternative-repositories) below).

---

## Quick Start

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val assets = listOf(
            GaplessAsset(
                id = "promo-video",
                uri = "https://example.com/promo.mp4",
                mimeType = "video/mp4",
                durationMs = 15_000
            ),
            GaplessAsset(
                id = "poster",
                uri = "https://example.com/poster.jpg",
                mimeType = "image/jpeg",
                durationMs = 8_000
            )
        )

        val manager = GaplessPlaylistManager(scope = lifecycleScope)
        manager.start(assets)

        setContent {
            GaplessPlayer(modifier = Modifier.fillMaxSize(), manager = manager)
        }
    }
}
```

---

## API

### `GaplessPlaylistManager`

Owns the playlist loop. Create it once, then call `start` or `update` as your asset list changes.

```kotlin
val manager = GaplessPlaylistManager(
    scope      = lifecycleScope, // cancelled automatically with the scope
    preloadMs  = 3_000,          // how early to buffer the next asset
    shuffle    = false,
    skipFailedAssets = true
)
```

| Method | Description |
| :----- | :---------- |
| `start(assets)` | Set the initial playlist and begin playing. |
| `update(assets)` | Hot-swap the playlist. The current asset keeps playing if its ID is still present; otherwise the player transitions immediately to the next available asset. |
| `stop()` | Cancel all coroutines and halt playback. |
| `events: SharedFlow<GaplessEvent>` | Stream of playback events (collect in a coroutine). |

---

### `GaplessPlayer`

The Compose entry point. Pair it with a `GaplessPlaylistManager`.

```kotlin
GaplessPlayer(
    modifier  = Modifier.fillMaxSize(),
    manager   = manager,
    rotation  = GaplessRotation.Deg90,   // rotate content without affecting layout
    emptyState = { /* composable shown when playlist is empty */ },
    idleState  = { /* composable shown when assets exist but none match their schedule */ }
)
```

---

### `GaplessAsset`

```kotlin
GaplessAsset(
    id          = "unique-id",   // stable across list updates
    uri         = "https://...",  // local path, content://, or remote URL
    mimeType    = "video/mp4",   // determines the renderer
    durationMs  = 10_000,        // display duration in ms
    width       = 1920,          // optional, used for aspect ratio before first frame
    height      = 1080,

    // Scheduling (all optional, omit to always play)
    startDate   = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(),
    endDate     = Instant.parse("2025-12-31T23:59:59Z").toEpochMilli(),
    playDays    = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    playTimeFrom = "08:00",
    playTimeTo   = "20:00",

    // Web assets only
    refreshIntervalMs = 60_000
)
```

**MIME type to renderer mapping:**

| MIME type | Renderer |
| :-------- | :------- |
| `video/*`, HLS, DASH, RTSP | ExoPlayer |
| `image/*` | Coil |
| anything else | WebView |

---

### `GaplessEvent`

Collect from `manager.events`:

```kotlin
lifecycleScope.launch {
    manager.events.collect { event ->
        when (event) {
            is GaplessEvent.Started      -> log("Playing: ${event.asset.id} [${event.playbackId}]")
            is GaplessEvent.Ended        -> log("Finished: ${event.asset.id}")
            is GaplessEvent.Preloading   -> log("Buffering next: ${event.asset.id}")
            is GaplessEvent.PlaybackError -> log("Error on ${event.asset.id}: ${event.message}")
            is GaplessEvent.Empty        -> log("Playlist is empty")
            is GaplessEvent.Idle         -> log("No assets match current schedule")
        }
    }
}
```

---

## Scheduling

Each asset can carry independent temporal constraints. All fields are optional; omit them to play the asset unconditionally.

```kotlin
// Night-mode ad: plays every night 22:00-06:00 (midnight-crossing window)
GaplessAsset(
    id           = "night-ad",
    uri          = "https://example.com/night.mp4",
    mimeType     = "video/mp4",
    durationMs   = 20_000,
    playTimeFrom = "22:00",
    playTimeTo   = "06:00"
)

// Weekend-only promo with an expiry date
GaplessAsset(
    id       = "weekend-promo",
    uri      = "https://example.com/promo.jpg",
    mimeType = "image/jpeg",
    durationMs = 10_000,
    playDays = setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
    endDate  = Instant.parse("2025-06-30T23:59:59Z").toEpochMilli()
)
```

When no asset matches the current time, `GaplessEvent.Idle` is emitted and `idleState` is displayed.

---

## Sample App

The included sample demonstrates a production-style digital signage setup where **the player runs in a completely separate OS process** from the launcher activity.

```
app process                           :player process
+-----------------------------------------+    +------------------------------+
|  MainActivity (thin launcher)           |    |  PlayerActivity              |
|                                         |    |  GaplessPlayer composable    |
|  WatchdogService (foreground) --- bind -+----+  GaplessPlaylistManager      |
|  detects crash, restarts player         |    |                              |
+-----------------------------------------+    |  PlayerService               |
                                               |  (keepalive stub)            |
                                               +------------------------------+
```

**Why a separate process?**

- A crash in the player (ExoPlayer, WebView, Coil) cannot take down the watchdog.
- The watchdog (`WatchdogService`) is a foreground service in the main process. It holds a Binder to `PlayerService` in `:player`. When `:player` dies, `onServiceDisconnected` fires and the watchdog automatically restarts both the service and the activity.
- `PlayerActivity` uses `singleInstance` launch mode, so only one player instance ever exists.
- `MainActivity` immediately calls `finish()` after starting the watchdog. It has no logic of its own.

**Running the sample:**

Assets are defined in `PlayerActivity.onCreate()`. Swap in your own URIs and MIME types to test different content:

```kotlin
val assets = listOf(
    GaplessAsset(
        id       = "promo",
        uri      = "android.resource://$packageName/raw/promo",
        mimeType = "video/mp4",
        durationMs = 10_000,
        width    = 3840,
        height   = 2160
    )
)
```

Place raw video files under `app/src/main/res/raw/`.

---

## Web Page Support

Web assets use Android WebView and work out of the box for most sites. For **PWAs and service-worker-dependent pages** (common in digital signage platforms), you must configure `ServiceWorkerController` before any `WebView` is created in your process. Add this to your `Activity.onCreate()` or `Application.onCreate()`:

```kotlin
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse

ServiceWorkerController.getInstance().apply {
    serviceWorkerWebSettings.allowContentAccess = true
    serviceWorkerWebSettings.allowFileAccess = true
    setServiceWorkerClient(object : ServiceWorkerClient() {
        override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = null
    })
}
```

Without this, service workers will fail to make network requests and the page will appear to load but never display content.

---

## How It Works

Each media type uses a different strategy to eliminate the gap:

**Video** uses ExoPlayer's native 2-item media queue. When the next video is due, it is added as item[1] while item[0] is still playing. On transition, ExoPlayer seeks to item[1] and drops item[0]. The switch happens inside a single player instance with no surface swap. If the aspect ratio changes between clips, a bitmap snapshot of the last frame is captured and held on screen until the first frame of the next video renders, hiding any resize flicker.

**Images** maintain two Coil slots (A and B) in the Compose hierarchy at all times. While one slot is visible, the other enqueues the next image with Coil in the background. On transition, the active slot index flips. Both slots stay composed so Coil keeps the decoded bitmap warm.

**Web pages** keep two WebView instances (A always-on, B created lazily). While one is visible, the other loads the next URL. On transition, the active slot flips and the previous WebView is either blanked (`about:blank`) or destroyed, so only one view holds live content at a time.

---

## Alternative Repositories

**GitHub Packages** (requires a token even for public packages):

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/514sid/gapless")
        credentials {
            username = System.getenv("GITHUB_ACTOR")
            password = System.getenv("GITHUB_TOKEN")
        }
    }
}
```

**JitPack:**

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.514sid:gapless:v0.0.13")
}
```

---

## License

MIT. See [LICENSE.md](LICENSE.md).
