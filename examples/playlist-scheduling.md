# Playlist scheduling

Switch the active playlist based on a time-of-day or day-of-week schedule. The manager's `update()` call is the only mechanism needed — it hot-swaps the list with no black frame on the currently-playing asset.

## Time-of-day playlists

Define one playlist per time window, then calculate which one is active now and delay until the next boundary to switch.

```kotlin
data class ScheduledPlaylist(
    val name: String,
    val startHour: Int, // inclusive, 0-23
    val endHour: Int,   // exclusive, 1-24
    val assets: List<GaplessAsset>,
)

val schedules = listOf(
    ScheduledPlaylist(
        name = "morning",
        startHour = 6,
        endHour = 12,
        assets = listOf(
            GaplessAsset(id = "morning-1", uri = "...", mimeType = "video/mp4", durationMs = 15_000),
            GaplessAsset(id = "morning-2", uri = "...", mimeType = "image/jpeg", durationMs = 8_000),
        )
    ),
    ScheduledPlaylist(
        name = "afternoon",
        startHour = 12,
        endHour = 18,
        assets = listOf(
            GaplessAsset(id = "afternoon-1", uri = "...", mimeType = "video/mp4", durationMs = 20_000),
        )
    ),
    ScheduledPlaylist(
        name = "evening",
        startHour = 18,
        endHour = 24,
        assets = listOf(
            GaplessAsset(id = "evening-1", uri = "...", mimeType = "video/mp4", durationMs = 12_000),
        )
    ),
    ScheduledPlaylist(
        name = "overnight",
        startHour = 0,
        endHour = 6,
        assets = listOf(
            GaplessAsset(id = "overnight-1", uri = "...", mimeType = "image/png", durationMs = 30_000),
        )
    ),
)

fun activePlaylist(schedules: List<ScheduledPlaylist>): ScheduledPlaylist {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return schedules.first { hour >= it.startHour && hour < it.endHour }
}

fun millisUntilNextBoundary(schedules: List<ScheduledPlaylist>): Long {
    val now = Calendar.getInstance()
    val hour = now.get(Calendar.HOUR_OF_DAY)
    val minute = now.get(Calendar.MINUTE)
    val second = now.get(Calendar.SECOND)

    val nextStartHour = schedules
        .map { it.startHour }
        .filter { it > hour }
        .minOrNull()
        ?: (schedules.minOf { it.startHour } + 24) // wrap to next day

    val minutesUntil = (nextStartHour - hour) * 60 - minute
    val secondsUntil = minutesUntil * 60 - second
    return secondsUntil * 1000L
}
```

Wire it up in your Activity or ViewModel:

```kotlin
class PlayerViewModel : ViewModel() {
    val manager = GaplessPlaylistManager(scope = viewModelScope)

    init {
        val current = activePlaylist(schedules)
        manager.start(current.assets)
        scheduleNextSwitch()
    }

    private fun scheduleNextSwitch() {
        viewModelScope.launch {
            delay(millisUntilNextBoundary(schedules))
            val next = activePlaylist(schedules)
            manager.update(next.assets)
            scheduleNextSwitch() // reschedule for the boundary after that
        }
    }
}
```

## Day-of-week playlists

The same pattern works for weekly schedules. Add a `daysOfWeek` field and filter before picking:

```kotlin
data class ScheduledPlaylist(
    val name: String,
    val startHour: Int,
    val endHour: Int,
    val daysOfWeek: Set<Int> = setOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
        Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    ),
    val assets: List<GaplessAsset>,
)

fun activePlaylist(schedules: List<ScheduledPlaylist>): ScheduledPlaylist? {
    val now = Calendar.getInstance()
    val hour = now.get(Calendar.HOUR_OF_DAY)
    val day = now.get(Calendar.DAY_OF_WEEK)
    return schedules.firstOrNull { day in it.daysOfWeek && hour >= it.startHour && hour < it.endHour }
}
```

If no schedule matches (a gap in coverage), pass an empty list to show nothing, or keep the previous list running:

```kotlin
val next = activePlaylist(schedules)
if (next != null) {
    manager.update(next.assets)
} // else: keep playing current playlist until next boundary
```

## Handling immediate start

On cold start the device may boot mid-schedule. Compute the active playlist from current time before calling `start()` — no special handling needed:

```kotlin
val current = activePlaylist(schedules) ?: fallbackPlaylist
manager.start(current.assets)
```
