# Playlist scheduling

Switch the active playlist based on a time-of-day or day-of-week schedule. The host re-evaluates which playlist is active on each `Started` event and pushes the next asset accordingly.

## Time-of-day playlists

Define one playlist per time window:

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

fun activePlaylist(schedules: List<ScheduledPlaylist>): ScheduledPlaylist? {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return schedules.firstOrNull { hour >= it.startHour && hour < it.endHour }
}
```

Wire it up — re-evaluate the active playlist on each transition:

```kotlin
class PlayerViewModel : ViewModel() {
    val manager = GaplessPlaylistManager(scope = viewModelScope)

    init {
        val indices = mutableMapOf<String, Int>()

        fun nextAsset(): GaplessAsset? {
            val playlist = activePlaylist(schedules) ?: return null
            val i = indices.getOrDefault(playlist.name, 0)
            indices[playlist.name] = (i + 1) % playlist.assets.size
            return playlist.assets[i]
        }

        val first = nextAsset() ?: return
        manager.start(first)

        viewModelScope.launch {
            manager.events.collect { event ->
                if (event is GaplessEvent.Started) {
                    val next = nextAsset()
                    if (next != null) manager.prepareNext(next)
                    else manager.stop()
                }
            }
        }
    }
}
```

Each call to `nextAsset()` picks the playlist for the current hour. The index per playlist is tracked separately so each playlist resumes where it left off when it becomes active again.

## Day-of-week playlists

Add a `daysOfWeek` field and filter before picking:

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

If no schedule matches, `nextAsset()` returns null and the host calls `manager.stop()`.
