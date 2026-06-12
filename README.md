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
- **Rotation** — built-in 0/90/180/270 degree content rotation without affecting the composable's layout bounds

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
)
```

When the asset list is empty, the player renders nothing (transparent/black).

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
    volume      = 0f,            // video only: 0.0 (silent) to 1.0 (full)

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
        }
    }
}
```

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
