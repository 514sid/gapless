# Gapless

A self-contained gapless media player Composable for Android. Transitions seamlessly between video, image, and web assets by pre-buffering content in dual render slots.

## Features

- **True Gapless Playback** — two internal render slots preload and buffer the next asset before the current one finishes, eliminating black frames between transitions.
- **Multi-Format Support**
  - **Video** — ExoPlayer (MP4, HLS, DASH, RTSP)
  - **Images** — Coil 3 (local files and remote URLs)
  - **Web Content** — Android WebView with JavaScript and DOM storage enabled
- **Smart Scheduling** — assets can be scheduled by date range, specific days of the week, and daily time windows (including midnight-crossing ranges)
- **Responsive Layout** — built-in support for content rotation (0°, 90°, 180°, 270°) without affecting the Composable's own layout bounds
- **Shuffle Mode** — intelligent randomization that ensures the last item played doesn't immediately repeat on a new cycle
- **Customizable States** — provide your own Composables for the empty playlist state (`emptyState`) and for when assets exist but none currently match their schedule (`idleState`)

## Requirements

- Android SDK 26+
- Jetpack Compose

## Installation

### Maven Central

```kotlin
dependencies {
    implementation("io.github.514sid:gapless:0.0.13")
}
```

### GitHub Packages

> Requires a GitHub token even for public packages — prefer Maven Central or JitPack for most cases.

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

dependencies {
    implementation("io.github.514sid:gapless:0.0.13")
}
```

### JitPack

```kotlin
repositories {
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    implementation("com.github.514sid:gapless:v0.0.13")
}
```

## Basic Usage

```kotlin
val assets = listOf(
    GaplessAsset(
        id = "video-1",
        uri = "https://example.com/video.mp4",
        mimeType = "video/mp4",
        durationMs = 15_000
    ),
    GaplessAsset(
        id = "image-1",
        uri = "file:///android_asset/poster.jpg",
        mimeType = "image/jpeg",
        durationMs = 5_000
    )
)

GaplessPlayer(
    assets = assets,
    rotation = 90, // landscape content on a portrait screen
    shuffle = true,
    emptyState = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No content assigned to this device.", color = Color.White)
        }
    },
    idleState = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Sleeping. Scheduled content will resume later.", color = Color.Gray)
        }
    },
    onEvent = { event ->
        when (event) {
            is GaplessEvent.Started -> println("Now playing: ${event.asset.id}")
            is GaplessEvent.Finished -> println("Finished: ${event.asset.id}")
            is GaplessEvent.PlaybackError -> println("Error on ${event.asset.id}: ${event.message}")
            else -> {}
        }
    }
)
```

## API Reference

### `GaplessPlayer`

The main Composable entry point.

| Parameter | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `assets` | `List<GaplessAsset>` | — | Ordered playlist. Passing a new list hot-swaps the playlist while preserving the currently-playing asset when its ID still appears in the new list. |
| `rotation` | `Int` | `0` | Screen rotation in degrees: `0`, `90`, `180`, or `270`. Content is rotated inside its bounds; the Composable's own size is unaffected. |
| `shuffle` | `Boolean` | `false` | Randomizes the playlist each cycle, ensuring the last-played asset is not placed first in the reshuffled order. |
| `config` | `GaplessPlayerConfig` | default | Timing configuration — see [`GaplessPlayerConfig`](#gaplessplayerconfig). |
| `onEvent` | `(GaplessEvent) -> Unit` | `{}` | Invoked on the main thread for each playback event — see [`GaplessEvent`](#gaplessevent). |
| `emptyState` | `@Composable () -> Unit` | black box | Shown when `assets` is empty. |
| `idleState` | `@Composable () -> Unit` | `emptyState` | Shown when `assets` is non-empty but no asset currently meets its scheduling criteria. Defaults to `emptyState` if not provided. |

---

### `GaplessAsset`

The primary data unit describing a single piece of media.

| Property | Type | Default | Description |
| :--- | :--- | :--- | :--- |
| `id` | `String` | — | Stable unique identifier. Used to preserve playback state across playlist hot-swaps. |
| `uri` | `String` | — | Local file path, `content://` URI, or remote URL. Bare paths are automatically prefixed with `file://`. |
| `mimeType` | `String` | — | Determines the renderer. `video/*` and streaming MIME types → ExoPlayer; `image/*` → Coil; anything else → WebView. |
| `durationMs` | `Long` | `10_000` | How long to display the asset, in milliseconds. The engine snaps this value down to the nearest `tickIntervalMs` boundary, so the effective duration is `floor(durationMs / tickIntervalMs) * tickIntervalMs`. |
| `width` | `Int?` | `null` | Optional intrinsic width hint. When both `width` and `height` are set, used to compute the aspect ratio before the first video frame is decoded. |
| `height` | `Int?` | `null` | Optional intrinsic height hint. |
| `startDate` | `Long?` | `null` | Epoch-millisecond timestamp. The asset will not play before this time. |
| `endDate` | `Long?` | `null` | Epoch-millisecond timestamp. The asset will not play after this time. |
| `playDays` | `Set<DayOfWeek>?` | `null` | Allowed days of the week. `null` or empty means every day. |
| `playTimeFrom` | `String?` | `null` | Start of the daily time window in `"HH:mm"` or `"HH:mm:ss"` format. |
| `playTimeTo` | `String?` | `null` | End of the daily time window. Supports midnight-crossing ranges (e.g., `"22:00"` → `"06:00"`). |
| `refreshIntervalMs` | `Long?` | `null` | Web assets only — automatic reload interval in milliseconds. `null` means no auto-refresh. |

**Scheduling example** — weekday business hours only:

```kotlin
GaplessAsset(
    id = "promo",
    uri = "https://example.com/promo.mp4",
    mimeType = "video/mp4",
    durationMs = 30_000,
    playDays = setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                     DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
    playTimeFrom = "09:00",
    playTimeTo = "17:00"
)
```

---

### `GaplessEvent`

Events emitted via the `onEvent` callback:

| Event | Properties | Description |
| :--- | :--- | :--- |
| `Started` | `asset`, `playbackId` | Fired when an asset starts playing. `playbackId` is a unique UUID per play instance. |
| `Finished` | `asset`, `playbackId` | Fired when an asset finishes (reaches its `durationMs`). |
| `Preloading` | `asset` (`GaplessAsset?`) | Fired when the next asset begins buffering, typically ~5 s before the current one ends. `asset` is `null` when there is nothing to preload (e.g. single-item playlist). |
| `PlaybackError` | `asset`, `message` | Fired on renderer failure. The player automatically skips the failing asset for the current session. |
| `Idle` | — | Fired when there is nothing to play — either the playlist is empty, or no asset currently meets its scheduling criteria. |

---

### `GaplessPlayerConfig`

Fine-tune playback engine timing:

| Property | Default | Description |
| :--- | :--- | :--- |
| `tickIntervalMs` | `1_000` | How often the engine checks elapsed time and schedule validity, in milliseconds. Lower values are more precise but consume more CPU. Also determines the snapping granularity for asset durations — each asset plays for the largest multiple of `tickIntervalMs` that fits within its `durationMs`. |
| `preloadThresholdMs` | `5_000` | How many milliseconds before the current asset ends the next one starts buffering. Increase on slow storage or network. |

```kotlin
GaplessPlayer(
    assets = assets,
    config = GaplessPlayerConfig(
        tickIntervalMs = 500,
        preloadThresholdMs = 8_000
    )
)
```

## How It Works

```
┌─────────────────────────────────────────┐
│              GaplessPlayer              │
│                                         │
│  ┌─────────────┐   ┌─────────────────┐  │
│  │  Slot A     │   │  Slot B         │  │
│  │  (active)   │   │  (preloading)   │  │
│  │  video/img/ │   │  video/img/web  │  │
│  │  web        │   │  alpha = 0      │  │
│  └─────────────┘   └─────────────────┘  │
│         ▲                  ▲            │
│         └──── swap ────────┘            │
│                                         │
│  GaplessViewModel → PlaylistManager     │
│  - tick loop (default 1 s)              │
│  - schedule validation                  │
│  - advance / preload logic              │
└─────────────────────────────────────────┘
```

1. `PlaylistManager` runs a coroutine tick loop. Each tick it increments elapsed time and checks schedule validity.
2. When remaining time drops below `preloadThresholdMs`, the next asset is assigned to the inactive slot and starts buffering.
3. When the current asset's snapped duration elapses (or its schedule expires), the slots swap: the preloaded slot becomes active and the old one is released. The snapped duration is `floor(durationMs / tickIntervalMs) * tickIntervalMs`, ensuring advancement always lands on an exact tick boundary.
4. Slot visibility is controlled via `graphicsLayer { alpha }` — both slots exist in the composition at all times, avoiding recomposition on every transition.
5. `GaplessViewModel` bridges `PlaylistManager` state flows to Compose state and forwards `Started`/`Finished`/`Preloading` events to the caller.

### Renderer notes

- **Video** — `TextureView` inside `AndroidView`. ExoPlayer is created and released per slot lifecycle. Volume is muted by default (`volume = 0f`). Aspect ratio is applied once `VideoSize` is reported; an optional `width`/`height` hint on the asset provides an earlier ratio before the first frame decodes.
- **Image** — Coil 3 `AsyncImage` with `FilterQuality.High` and crossfade enabled.
- **Web** — `WebView` with JavaScript, DOM storage, and autoplay enabled. `onResume()`/`onPause()` manage resource usage for the inactive slot.
- **Rotation** — `RotatedScreenContainer` applies `rotationZ` via `graphicsLayer` and swaps the layout constraints (width ↔ height) for 90°/270°, so the content fills the rotated area correctly without affecting the parent layout.

## Testing

Unit tests cover `PlaylistManager` scheduling and advancement logic using JUnit 5:

```bash
./gradlew :library:test
```

`GaplessAsset.isActiveAt(clock)` accepts an injected `java.time.Clock`, making schedule logic fully deterministic in tests without mocking system time globally.

## Publishing

Releases are published automatically to Maven Central and GitHub Packages via the `publish` GitHub Actions workflow when a GitHub Release is created. Signing requires `GPG_SIGNING_KEY` and `GPG_SIGNING_PASSWORD` repository secrets.

## License

MIT — see [LICENSE.md](LICENSE.md).
