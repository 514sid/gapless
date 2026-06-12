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

        var index = 0
        var timerJob: Job? = null
        val manager = GaplessPlaylistManager(scope = lifecycleScope)
        manager.start(assets[index++])

        lifecycleScope.launch {
            manager.events.collect { event ->
                if (event is GaplessEvent.Started) {
                    timerJob?.cancel()
                    val next = assets[index++ % assets.size]
                    manager.prepareNext(next)
                    timerJob = launch {
                        delay(event.asset.durationMs ?: return@launch)
                        manager.play(next)
                    }
                }
            }
        }

        setContent {
            GaplessPlayer(modifier = Modifier.fillMaxSize(), manager = manager)
        }
    }
}
```

---

## API

### `GaplessPlaylistManager`

Owns the playback loop. Create it once, call `start` with a callback that returns the next asset to play.

```kotlin
val manager = GaplessPlaylistManager(
    scope     = lifecycleScope, // cancelled automatically with the scope
    preloadMs = 3_000,          // how early to buffer the next asset
)
```

Call `start` with the first asset. On each `Started` event, call `prepareNext` to begin buffering the next asset and `play` when it is time to transition. The host controls all timing.

```kotlin
manager.start(firstAsset)

lifecycleScope.launch {
    manager.events.collect { event ->
        if (event is GaplessEvent.Started) {
            val next = nextAsset()
            manager.prepareNext(next)
            launch {
                delay(event.asset.durationMs ?: return@launch)
                manager.play(next)
            }
        }
    }
}
```

| Method / Property | Description |
| :---------------- | :---------- |
| `start(asset)` | Begin playback with the given asset. |
| `prepareNext(asset)` | Start buffering the next asset. Call this early — as soon as `Started` fires — so it is ready when `play` is called. |
| `play(asset)` | Transition to the asset. If already preloading, transitions immediately. If not, prepares it first and plays as soon as the renderer is ready. |
| `stop()` | Cancel all coroutines and halt playback. |
| `events: SharedFlow<GaplessEvent>` | Stream of playback events (collect in a coroutine). |
| `currentState: StateFlow<GaplessPlaybackState?>` | Currently-playing asset, playback ID, and start timestamp. |

---

### `GaplessPlayer`

The Compose entry point. Pair it with a `GaplessPlaylistManager`.

```kotlin
GaplessPlayer(
    modifier     = Modifier.fillMaxSize(),
    manager      = manager,
    rotation     = GaplessRotation.Deg90,
    videoConfig  = GaplessVideoConfig(         // optional; shown with non-default values
        enableDecoderFallback               = true,
        minBufferMs                         = 2_000,
        maxBufferMs                         = 8_000,
        bufferForPlaybackMs                 = 1_000,
        bufferForPlaybackAfterRebufferMs    = 2_000,
        repeatMode                          = GaplessVideoRepeatMode.FREEZE,
    ),
    webConfig    = GaplessWebConfig(           // optional; shown with non-default values
        enableChromeDebugging  = true,         // enable for development builds
        allowThirdPartyCookies = true,
        mixedContentMode       = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW,
        userAgent              = null,         // null uses the WebView system default
    ),
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
    durationMs  = 10_000,        // host metadata — read in your Started handler to time the play() call
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
            is GaplessEvent.Started       -> log("Playing: ${event.asset.id} [${event.playbackId}]")
            is GaplessEvent.Ended         -> log("Finished: ${event.asset.id}")
            is GaplessEvent.Preloading    -> log("Buffering next: ${event.asset.id}")
            is GaplessEvent.PlaybackError -> log("Error on ${event.asset.id}: ${event.message}")
            is GaplessEvent.PreloadMissed -> log("Preload not ready for ${event.asset.id}: took ${event.elapsedMs}ms")
        }
    }
}
```

`PlaybackError` stops the loop — the host decides whether to call `start()` again, retry, or stay blank.

---

## Shuffle

Manage the shuffled order in the host and push assets via `prepareNext`.

**Shuffle, reshuffle every cycle:**

```kotlin
val shuffled = assets.shuffled().toMutableList()
var index = 0

fun nextShuffled(): GaplessAsset {
    if (index >= shuffled.size) {
        shuffled.shuffle()
        index = 0
    }
    return shuffled[index++]
}

manager.start(nextShuffled())

lifecycleScope.launch {
    manager.events.collect { event ->
        if (event is GaplessEvent.Started) {
            val next = nextShuffled()
            manager.prepareNext(next)
            launch {
                delay(event.asset.durationMs ?: return@launch)
                manager.play(next)
            }
        }
    }
}
```

**Prevent the last-played asset from appearing first after a reshuffle:**

```kotlin
fun nextShuffled(): GaplessAsset {
    if (index >= shuffled.size) {
        val last = shuffled.last()
        shuffled.shuffle()
        if (shuffled.size > 1 && shuffled.first().id == last.id) Collections.swap(shuffled, 0, 1)
        index = 0
    }
    return shuffled[index++]
}

// Wire it up the same way — prepareNext + delayed play
manager.start(nextShuffled())

lifecycleScope.launch {
    manager.events.collect { event ->
        if (event is GaplessEvent.Started) {
            val next = nextShuffled()
            manager.prepareNext(next)
            launch {
                delay(event.asset.durationMs ?: return@launch)
                manager.play(next)
            }
        }
    }
}

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
        durationMs = 10_000L,
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
