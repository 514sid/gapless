# Gapless

A self-contained gapless media player Composable for Android. It seamlessly transitions between video, image, and web assets by pre-buffering content in dual render slots.

## Features

- **True Gapless Playback**: Uses two internal render slots to preload and buffer the next asset before the current one finishes, eliminating black frames between transitions.
- **Multi-Format Support**:
  - **Video**: ExoPlayer (MP4, HLS, DASH, RTSP).
  - **Images**: Coil (supports local files and remote URLs).
  - **Web Content**: Android WebView with JavaScript and DOM storage enabled.
- **Smart Scheduling**: Assets can be scheduled by date range, specific days of the week, and daily time windows (including midnight-crossing ranges).
- **Responsive Layout**: Built-in support for content rotation (0, 90, 180, 270 degrees) without affecting the Composable's layout bounds.
- **Shuffle Mode**: Intelligent randomization that ensures the last item played doesn't immediately repeat on a new cycle.
- **Customizable States**: Provide your own Composables to display when the playlist is completely empty (`emptyState`) versus when assets exist but are waiting for their scheduled time (`idleState`).

## Installation

Add the dependency to your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("io.github.514sid:gapless:0.0.3")
}
```

## Basic Usage

```kotlin
val assets = listOf(
    GaplessAsset(
        id = "video-1",
        uri = "[https://example.com/video.mp4](https://example.com/video.mp4)",
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
    rotation = 90, // Landscape content on portrait screen
    shuffle = true,
    emptyState = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No playlists assigned to this device.", color = Color.White)
        }
    },
    idleState = {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Sleeping. Scheduled content will resume later.", color = Color.Gray)
        }
    },
    onEvent = { event ->
        when (event) {
            is GaplessEvent.NowPlaying -> println("Playing: ${event.asset.id}")
            is GaplessEvent.PlaybackError -> println("Error on ${event.asset.id}: ${event.message}")
            is GaplessEvent.PlaylistEmpty -> println("No active assets to play")
            else -> {}
        }
    }
)
```

## Core Components

### `GaplessAsset`

The primary data unit for the player.

| Property | Description |
| :--- | :--- |
| `id` | Unique stable identifier. |
| `uri` | Path or URL to the media. |
| `mimeType` | Determines the renderer (Video/Image/Web). |
| `durationMs` | Display duration (defaults to 10s). |
| `startDate` / `endDate` | Epoch timestamps for availability. |
| `playDays` | Set of `java.time.DayOfWeek` indicating allowed days (e.g., `DayOfWeek.MONDAY`). |
| `playTimeFrom` / `playTimeTo` | Daily window (e.g., "08:00", "22:30:00"). Supports midnight crossing. |
| `refreshIntervalMs` | (Web only) Automatic reload interval. |

### `GaplessEvent`

The player communicates state changes via the `onEvent` callback:

- `NowPlaying`: Fired when an asset becomes active.
- `Preloading`: Fired when the next asset starts buffering (default 5s before current ends).
- `PlaybackError`: Fired on renderer failure. The player automatically skips the failing asset.
- `PlaylistEmpty`: Fired when no assets are valid or available according to their schedules.

### `GaplessPlayerConfig`

Tweak playback engine performance:

- `tickIntervalMs`: Accuracy of schedule and timing checks (default 1000ms).
- `preloadThresholdMs`: Buffer lead time for the next asset (default 5000ms).

## Technical Details for AI Tools

- **State Management**: Uses a `GaplessViewModel` to manage the playlist lifecycle. Multiple `GaplessPlayer` calls in the same `ViewModelStoreOwner` will share state unless scoped differently.
- **Rendering**:
  - Videos are rendered via `TextureView` to allow for smooth alpha transitions and rotations.
  - Web content uses `WebView.onResume()`/`onPause()` to manage resource usage when inactive.
  - Image loading uses Coil 3 with `FilterQuality.High`.
- **Scheduling Logic**: Temporal validations are evaluated against the system clock by default (`isActiveNow()`), but expose `isActiveAt(clock: java.time.Clock)` to allow deterministic unit testing of midnight-crossing and day-of-week logic. The temporal check is performed every `tickIntervalMs`. If an asset's schedule expires while playing, the player advances immediately.
- **Asset Updates**: Passing a new list to `assets` performs a hot-swap. The engine attempts to keep the current asset playing if its ID still exists in the new list.