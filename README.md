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
- **Selectable video strategy** — seamless A/B dual-ExoPlayer by default, or an experimental single-ExoPlayer mode for single-decoder hardware
- **Rotation** — built-in 0/90/180/270 degree content rotation without affecting the composable's layout bounds

---

## Installation

```kotlin
dependencies {
    implementation("io.github.514sid:gapless:0.2.0")
}
```

Also available via **GitHub Packages** and **JitPack** (see [setup notes](#alternative-repositories) below).

---

## Quick Start

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        data class Asset(val spec: GaplessAsset, val durationMs: Long)

        val assets = listOf(
            Asset(GaplessAsset(id = "promo-video", uri = "https://example.com/promo.mp4", mimeType = "video/mp4"), 15_000L),
            Asset(GaplessAsset(id = "poster",      uri = "https://example.com/poster.jpg", mimeType = "image/jpeg"), 8_000L),
        )

        val manager = GaplessController(scope = lifecycleScope)

        // Drive the loop from your own coroutine. Anchor each slot's timer to currentState, so the
        // duration starts when the asset is actually on screen (not when play() was called).
        lifecycleScope.launch {
            var index = 0
            manager.start(assets[index].spec)
            while (isActive) {
                val current = assets[index % assets.size]
                manager.currentState.first { it?.asset?.id == current.spec.id }

                val next = assets[(index + 1) % assets.size]
                manager.prepareNext(next.spec)
                delay(current.durationMs)
                manager.play(next.spec)
                index++
            }
        }

        // Events are for observability only (logging, error handling).
        lifecycleScope.launch {
            manager.events.collect { event -> Log.d("Gapless", "$event") }
        }

        setContent {
            GaplessPlayer(modifier = Modifier.fillMaxSize(), manager = manager)
        }
    }
}
```

> Drive the playlist from a coroutine you own and treat `events` as log-only. The `events` flow has
> `replay = 1`, so a collector that restarts (for example under `repeatOnLifecycle`) re-receives the last
> event; using it to drive transitions can re-fire `prepareNext`/`play`. `currentState` is a conflated
> `StateFlow`, so awaiting it is idempotent.

---

## API

### `GaplessController`

Owns the playback loop. Create it once, call `start` with a callback that returns the next asset to play.

```kotlin
val manager = GaplessController(
    scope     = lifecycleScope, // cancelled automatically with the scope
    preloadMs = 3_000,          // how early to buffer the next asset
)
```

Call `start` with the first asset. From a coroutine you own, wait until the asset is on screen (via
`currentState`), call `prepareNext` to begin buffering the next asset, then `play` when it is time to
transition. The host controls all timing.

```kotlin
val durations = mapOf("promo-video" to 15_000L, "poster" to 8_000L)

lifecycleScope.launch {
    manager.start(firstAsset)
    var current = firstAsset
    while (isActive) {
        manager.currentState.first { it?.asset?.id == current.id }
        val next = nextAsset()
        manager.prepareNext(next)
        delay(durations[current.id] ?: 10_000L)
        manager.play(next)
        current = next
    }
}
```

| Method / Property | Description |
| :---------------- | :---------- |
| `start(asset)` | Begin playback with the given asset. |
| `prepareNext(asset)` | Start buffering the next asset. Call this early — as soon as the current asset is on screen — so it is ready when `play` is called. |
| `play(asset)` | Transition to the asset. If already preloading, transitions immediately. If not, prepares it first and plays as soon as the renderer is ready. |
| `stop()` | Cancel all coroutines and halt playback. |
| `events: SharedFlow<GaplessEvent>` | Stream of playback events (collect in a coroutine). |
| `currentState: StateFlow<GaplessPlaybackState?>` | Currently-playing asset, playback ID, and start timestamp. |

---

### `GaplessPlayer`

The Compose entry point. Pair it with a `GaplessController`.

```kotlin
GaplessPlayer(
    modifier     = Modifier.fillMaxSize(),
    manager      = manager,
    rotation     = GaplessRotation.Deg90,
    videoConfig  = GaplessVideoConfig(         // optional; shown with non-default values
        strategy                            = GaplessVideoStrategy.DUAL_INSTANCE,
        enableDecoderFallback               = true,
        minBufferMs                         = 2_000,
        maxBufferMs                         = 8_000,
        bufferForPlaybackMs                 = 1_000,
        bufferForPlaybackAfterRebufferMs    = 2_000,
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

**Video strategy (`GaplessVideoStrategy`).** Controls how video playback is backed:

| Strategy | Decoders | Behavior |
| :------- | :------- | :------- |
| `DUAL_INSTANCE` (default) | Two during a transition | Two ExoPlayers in an A/B swap. The next clip is staged and its first frame rendered on a hidden player, then the visible slot flips for a seamless cut. |
| `SINGLE_INSTANCE_EXPERIMENTAL` | One | A single ExoPlayer reusing one timeline. Uses one decoder at a time but re-initializes it on each cut. Use only on hardware limited to a single decoder. |

---

### `GaplessAsset`

```kotlin
GaplessAsset(
    id       = "unique-id",  // stable across list updates
    uri      = "https://...", // local path, content://, or remote URL
    mimeType = "video/mp4",  // determines the renderer
    width    = 1920,         // optional, used for aspect ratio before first frame
    height   = 1080,
    volume   = 0f,           // video only: 0.0 (silent) to 1.0 (full)

    // Video only
    durationMs = 10_000,     // optional; clip the video so the next one can preload early

    // Web assets only
    refreshIntervalMs = 60_000
)
```

**`durationMs` (video only).** Clips the video to the given length so it ends at a fixed point instead of
playing its full natural length. The host still drives the transition with `play()`; if a clip reaches its
`durationMs` end before then, it holds on the last frame and never auto-advances. Leave `durationMs` null for
live streams (HLS/DASH/RTSP).

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
            is GaplessEvent.PreloadError  -> log("Preload failed for ${event.asset.id}: ${event.message}")
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
data class Asset(val spec: GaplessAsset, val durationMs: Long)

val all: List<Asset> = listOf(/* ... */)
val shuffled = all.shuffled().toMutableList()
var index = 0

fun nextShuffled(): Asset {
    if (index >= shuffled.size) {
        shuffled.shuffle()
        index = 0
    }
    return shuffled[index++]
}

lifecycleScope.launch {
    var current = nextShuffled()
    manager.start(current.spec)
    while (isActive) {
        manager.currentState.first { it?.asset?.id == current.spec.id }
        val next = nextShuffled()
        manager.prepareNext(next.spec)
        delay(current.durationMs)
        manager.play(next.spec)
        current = next
    }
}
```

**Prevent the last-played asset from appearing first after a reshuffle:**

```kotlin
fun nextShuffled(): Asset {
    if (index >= shuffled.size) {
        val last = shuffled.last()
        shuffled.shuffle()
        if (shuffled.size > 1 && shuffled.first().spec.id == last.spec.id) Collections.swap(shuffled, 0, 1)
        index = 0
    }
    return shuffled[index++]
}
```

Wire it up the same way as above — `prepareNext` + delayed `play`.

---

## Sample App

The included sample demonstrates a production-style digital signage setup where **the player runs in a completely separate OS process** from the launcher activity.

```
app process                           :player process
+-----------------------------------------+    +------------------------------+
|  MainActivity (thin launcher)           |    |  PlayerActivity              |
|                                         |    |  GaplessPlayer composable    |
|  WatchdogService (foreground) --- bind -+----+  GaplessController      |
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
        width    = 3840,
        height   = 2160
    ) to 10_000L  // (asset, durationMs)
)
```

Place raw video files under `app/src/main/res/raw/`.

**Not seeing the `Started`/`Ended`/`Preloading` logs?** Some locked-down devices (e.g. Fire TV) ship with
`persist.log.tag = I`, which raises the global logcat level to `INFO` and drops every `Log.d` call. Playback
still runs; the debug logs are just filtered. Whitelist the tag to see them:

```sh
adb shell setprop log.tag.GaplessPlayer VERBOSE   # app sample tag; lasts until reboot
```

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

**Video** has two strategies, selected via `GaplessVideoConfig.strategy`.

`DUAL_INSTANCE` (default) keeps two ExoPlayers as A/B slots, like the image and web players. While one slot plays, the next clip is prepared on the hidden slot, which buffers and renders its first frame into its own surface. On transition the visible slot flips so the cut is seamless, then the old slot is cleared to free its decoder. Two hardware decoders are alive only during the brief overlap around a transition.

`SINGLE_INSTANCE_EXPERIMENTAL` uses one ExoPlayer with a 2-item queue. The next video is added as item[1] while item[0] plays; on transition the player advances to item[1] and drops item[0]. It uses a single decoder but re-initializes it on each cut, and if the aspect ratio changes a bitmap snapshot of the last frame is held until the next video's first frame renders, hiding the resize flicker.

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
    implementation("com.github.514sid:gapless:v0.2.0")
}
```

---

## License

MIT. See [LICENSE.md](LICENSE.md).
