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

        data class Asset(val spec: GaplessAsset, val durationMs: Long)

        val assets = listOf(
            Asset(GaplessAsset(id = "promo-video", uri = "https://example.com/promo.mp4", mimeType = "video/mp4"), 15_000L),
            Asset(GaplessAsset(id = "poster",      uri = "https://example.com/poster.jpg", mimeType = "image/jpeg"), 8_000L),
        )

        var index = 0
        var timerJob: Job? = null
        val manager = GaplessController(scope = lifecycleScope)
        manager.start(assets[index++].spec)

        lifecycleScope.launch {
            manager.events.collect { event ->
                if (event is GaplessEvent.Started) {
                    timerJob?.cancel()
                    val current = assets.first { it.spec.id == event.asset.id }
                    val next = assets[index++ % assets.size]
                    manager.prepareNext(next.spec)
                    // Started can arrive a little after the clip began; time from its timestamp.
                    val elapsed = System.currentTimeMillis() - event.timestamp
                    timerJob = launch {
                        delay(current.durationMs - elapsed)
                        manager.play(next.spec)
                    }
                }
            }
        }

        setContent {
            GaplessPlayer(modifier = Modifier.fillMaxSize(), controller = manager)
        }
    }
}
```

---

## API

### `GaplessController`

Owns the playback state. Create it once; the host decides what plays next and when.

```kotlin
val manager = GaplessController(
    scope     = lifecycleScope, // cancelled automatically with the scope
    preloadMs = 3_000,          // how early to buffer the next asset
)
```

Call `start` with the first asset. On each `Started` event, call `prepareNext` to begin buffering the next asset and `play` when it is time to transition. The host controls all timing.

`Started` fires once the asset is really on screen, and for a video once its frames are moving, which with audio on can be a few hundred milliseconds after the cut. Its `timestamp` is when the clip's position was zero, so time each slot from `event.timestamp` rather than from when the event arrives. `Ended` for the previous asset fires just before it.

```kotlin
val durations = mapOf("promo-video" to 15_000L, "poster" to 8_000L)
manager.start(firstAsset)

lifecycleScope.launch {
    manager.events.collect { event ->
        if (event is GaplessEvent.Started) {
            val next = nextAsset()
            manager.prepareNext(next)
            val elapsed = System.currentTimeMillis() - event.timestamp
            launch {
                delay((durations[event.asset.id] ?: return@launch) - elapsed)
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
| `play(asset, startAtMs)` | Experimental. Like `play`, and keeps a video on a timeline while it plays; see [Synced playback](#synced-playback-experimental). |
| `stop()` | Halt playback and forget the current and staged assets. `start` resumes. |
| `close()` | Stop and shut the controller down for good. |
| `events: SharedFlow<GaplessEvent>` | Stream of playback events (collect in a coroutine). |
| `currentState: StateFlow<GaplessPlaybackState?>` | Currently-playing asset, playback ID, and start timestamp. |

---

### `GaplessPlayer`

The Compose entry point. Pair it with a `GaplessController`.

```kotlin
GaplessPlayer(
    modifier     = Modifier.fillMaxSize(),
    controller   = manager,
    rotation     = GaplessRotation.Deg90,
    videoConfig  = GaplessVideoConfig(         // optional; shown with non-default values
        enableDecoderFallback               = true,
        stallTimeoutMs                      = 30_000,
        minBufferMs                         = 2_000,
        maxBufferMs                         = 8_000,
        bufferForPlaybackMs                 = 1_000,
        bufferForPlaybackAfterRebufferMs    = 2_000,
        targetBufferBytes                   = 64 * 1024 * 1024,
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

`mimeType` picks the renderer: `video/*` and HLS, DASH, SmoothStreaming or RTSP stream types play in
ExoPlayer, `image/*` in Coil, and `text/html` or `application/xhtml+xml` in a WebView. Matching ignores case
and parameters. Any other type, an out-of-range `volume`, or a non-positive `durationMs` or `refreshIntervalMs`
makes `start`, `prepareNext` and `play` throw `IllegalArgumentException`.

**`durationMs` (video only).** Without it, a clip plays its full natural length and ExoPlayer only begins
buffering the next clip in the final `maxBufferMs` window, so a long clip can transition with a rebuffer.
Setting `durationMs` clips the video to that length, which lets ExoPlayer start loading the next clip far
earlier. For the largest benefit, pair it with a [`GaplessVideoConfig.maxBufferMs`](#gaplessplayer) at least as
large as the clip:

```kotlin
GaplessPlayer(
    controller  = manager,
    videoConfig = GaplessVideoConfig(maxBufferMs = 12_000), // >= the clip length
)
```

The host still drives the transition with `play()`. If a clip reaches its `durationMs` end before then, it holds
on the last frame and never auto-advances. Leave `durationMs` null for live streams (HLS/DASH/RTSP).

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
            is GaplessEvent.Ended         -> log("Replaced: ${event.asset.id}")
            is GaplessEvent.Completed     -> log("Played to its end: ${event.asset.id}")
            is GaplessEvent.Preloading    -> log("Buffering next: ${event.asset.id}")
            is GaplessEvent.PlaybackError -> log("Error on ${event.asset.id}: ${event.message}")
            is GaplessEvent.PreloadError  -> log("Could not load ${event.asset.id}: ${event.message}")
            is GaplessEvent.PreloadMissed -> log("Preload not ready for ${event.asset.id}: took ${event.elapsedMs}ms")
        }
    }
}
```

Every `play(asset)` ends in `Started` for that asset, in `PreloadError` for it, or in `PlaybackError` if it
fails after the cut but before it shows, unless a later call supersedes it. A host whose schedule runs off `Started` must also move on after `PlaybackError` and after a
`PreloadError` for the asset it just asked to play, or the schedule stops there. The sample app's
`PlayerActivity` shows the pattern.

`Ended` means the asset was replaced on screen. `Completed` means a video played to its end, a clip
reaching its last frame or a stream that finished, while it is still on screen: its last frame
stays up until the next `play()`, so a host can cut to the next asset at once instead of waiting for
its own timer. That matters for streams of unknown length and clips shorter than their slot. A live
stream that simply stops arriving doesn't complete; it stalls, is retried and ends in
`PlaybackError`. Images and web pages have no end, so they never complete.

### Failure handling

The library is built so that a bad asset or a flaky device costs one asset, not the app.

Video failures are retried inside the player before you hear about them:

- Network errors, HTTP timeouts, rate limits and 5xx responses, decoders that fail to open or are
  reclaimed, and audio output errors are retried up to three times with a growing delay. The last retry
  rebuilds that slot's ExoPlayer. Other HTTP errors, such as 404, are reported at once.
- A decoding error mid-stream, which usually means a damaged file, gets one retry on a new player before
  it is reported.
- A live stream that falls behind its window jumps back to the live edge.
- A clip that stops advancing for `stallTimeoutMs` (15 seconds by default) is restarted.
- Each video player buffers at most `targetBufferBytes` (32 MB by default), whatever the buffer durations
  allow. There are two players, so video buffers take at most about twice that.
- `enableDecoderFallback` is off by default, so a hardware decoder that won't open is not replaced by a
  software one. It usually won't open because the device's decoders are all in use, and decoding on the CPU
  instead stutters on TV-class hardware. A second decoder that won't open means hard cuts (below), and a clip
  that gets no decoder at all is retried and then reported. Turn it on to let a software decoder take over
  instead. Formats that only a software decoder supports play in software either way.
- Muted clips (`volume = 0`) don't select an audio track at all, so they need no audio decoder and an audio
  output failure can't stop them.
- Some devices can't run two hardware decoders at once, which seamless cuts need. When the second decoder fails to open, the player switches to hard cuts: the old clip
  holds its last frame while the new one starts, instead of failing. If the new clip's decoder still won't
  open once the old one is released, the clip was the problem: it is reported and seamless cuts resume.

What still reaches you:

- `PlaybackError` when retries run out or the video can't play on this device at all, including video in a
  format no decoder on the device handles (which ExoPlayer would otherwise play with no picture and no
  error). A failed video stays frozen on its last frame. An image that decoded during preload but fails to
  decode on screen (out of memory, for example) also ends in `PlaybackError`, instead of a silent black screen.
- `PreloadError` for an asset that failed to load. For web assets this includes a device with no usable
  WebView, and a WebView whose renderer process crashed; the library replaces the crashed WebView instead
  of letting Android kill the app. A page that doesn't paint within 5 seconds gets a quick script check:
  if the renderer doesn't run it either, it has hung, so the library kills it, starts both web slots on a
  fresh one and reports `PlaybackError` for the page instead of showing it. A hung renderer otherwise
  stops painting every page while its memory keeps growing.

Native crashes inside a vendor codec, and ANRs, can't be caught in-process. Run the player in its own
process with a watchdog, as the sample app does, if that matters for your deployment.

### Synced playback (experimental)

Several devices can show the same content in step, for a video wall or screens side by side. This
API is experimental: it works and is tested, but it may change in any release, so using it needs
an explicit opt-in with `@OptIn(ExperimentalGaplessApi::class)`. Everything else in the library
works the same without it.

The host supplies two things: a clock all devices agree on, and a schedule worked out from it.
Give the clock to the controller and play each asset at its slot's start time:

```kotlin
@OptIn(ExperimentalGaplessApi::class)
val manager = GaplessController(lifecycleScope, clock = GaplessClock { sharedClock.nowMs() })

// At each slot boundary on the shared clock:
manager.play(next, startAtMs = slotStart)
```

The cut happens when `play` is called, so call it at the slot start. A video played with a start
time is then kept on that timeline while it plays:

- A clip that started late plays up to 5% faster until it has caught up, over about two seconds.
- A clip that drifts from the shared clock is sped up or slowed down by a fraction of a percent.
- A clip off by half a second or more seeks, at most once every five seconds. A seek briefly
  freezes the picture.
- A speed-up never asks the decoder for more than 60 frames a second, so a 60 fps clip is only
  ever slowed down; a clip that stalls while sped up goes back to normal speed for the rest of it.

Images and web pages only need the cut on time. Clips played without a start time, and every
clip on a controller without a clock, play exactly as before.

The library doesn't synchronize clocks or carry the schedule between devices. The sample app
has a complete example over UDP on the LAN, with a script that measures how far apart two
devices are: see `samples/README.md`. On two identical TVs it kept the pictures within one frame
(33 ms) of each other, with a median gap of about 5 ms. Muted playback was tested; with audio on,
its start-up delay differs between devices and isn't compensated yet.

---

## Shuffle

Manage the shuffled order in the host and push assets via `prepareNext`.

**Shuffle, reshuffle every cycle:**

```kotlin
data class Asset(val spec: GaplessAsset, val durationMs: Long)

val all: List<Asset> = listOf(/* ... */)
val shuffled = all.shuffled().toMutableList()
var index = 0
val durations = all.associate { it.spec.id to it.durationMs }

fun nextShuffled(): Asset {
    if (index >= shuffled.size) {
        shuffled.shuffle()
        index = 0
    }
    return shuffled[index++]
}

manager.start(nextShuffled().spec)

lifecycleScope.launch {
    manager.events.collect { event ->
        if (event is GaplessEvent.Started) {
            val next = nextShuffled()
            manager.prepareNext(next.spec)
            val elapsed = System.currentTimeMillis() - event.timestamp
            launch {
                delay((durations[event.asset.id] ?: return@launch) - elapsed)
                manager.play(next.spec)
            }
        }
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
|  WatchdogService (foreground) --- bind -+----+  GaplessController           |
|  detects crash, restarts player         |    |                              |
+-----------------------------------------+    |  PlayerService               |
                                               |  (keepalive stub)            |
                                               +------------------------------+
```

**Why a separate process?**

- A crash in the player (ExoPlayer, WebView, Coil) cannot take down the watchdog.
- The watchdog (`WatchdogService`) is a foreground service in the main process. It holds a Binder to `PlayerService` in `:player`. When `:player` dies, `onServiceDisconnected` fires and the watchdog automatically restarts both the service and the activity.
- Android 10 and later block activity starts from the background, so the watchdog can only reopen the player if the app may draw over other apps: `adb shell appops set io.github.gapless.sample SYSTEM_ALERT_WINDOW allow`. Without it, tapping the watchdog's notification brings the player back.
- When the system kills the main process for memory, it restarts the watchdog with its original launch extras, so the player comes back on the same playlist. Android delays that restart, by up to several minutes after repeated kills, and the main process ranks below the player when memory runs out. On a device that runs out of memory often, make the player the home app instead, which Android restarts at once.
- `PlayerActivity` uses `singleInstance` launch mode, so only one player instance ever exists.
- `MainActivity` immediately calls `finish()` after starting the watchdog. It has no logic of its own.

**Running the sample:**

The app plays generated test media from `app/src/main/assets/samples/`: clips with an on-screen timer, frame counter and first and last frame markers, still images, a web page, and deliberately broken files. Pick a playlist with a launch extra:

```sh
adb shell am start -S -n io.github.gapless.sample/.MainActivity --es playlist codecs
```

`samples/README.md` lists every asset and playlist, the other extras (volume, device clock overlay), and how to regenerate the media.

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

**Video** uses two ExoPlayers, each drawing to its own `TextureView`. While one plays, the other loads the next clip and waits, paused, on its first frame, already sized to the clip's shape. The cut swaps which view is visible in one UI frame and clears the old player, so nothing is copied or resized on screen. On a device that can't open a second decoder, the player switches to hard cuts: the outgoing clip's last frame stays up while the next clip's decoder opens.

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
