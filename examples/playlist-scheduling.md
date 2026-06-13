# Playlist scheduling

Switch the active playlist based on a time-of-day or day-of-week schedule. On each `Started` event the host re-evaluates which playlist is active, starts buffering the next asset, and schedules the transition.

## Time-of-day playlists

Define one playlist per time window:

```kotlin
data class ScheduledPlaylist(
    val name: String,
    val startHour: Int, // inclusive, 0-23
    val endHour: Int,   // exclusive, 1-24
    val assets: List<Pair<GaplessAsset, Long>>,  // asset + durationMs
)

val schedules = listOf(
    ScheduledPlaylist(
        name = "morning",
        startHour = 6,
        endHour = 12,
        assets = listOf(
            GaplessAsset(id = "morning-1", uri = "...", mimeType = "video/mp4") to 15_000L,
            GaplessAsset(id = "morning-2", uri = "...", mimeType = "image/jpeg") to 8_000L,
        )
    ),
    ScheduledPlaylist(
        name = "afternoon",
        startHour = 12,
        endHour = 18,
        assets = listOf(
            GaplessAsset(id = "afternoon-1", uri = "...", mimeType = "video/mp4") to 20_000L,
        )
    ),
    ScheduledPlaylist(
        name = "evening",
        startHour = 18,
        endHour = 24,
        assets = listOf(
            GaplessAsset(id = "evening-1", uri = "...", mimeType = "video/mp4") to 12_000L,
        )
    ),
    ScheduledPlaylist(
        name = "overnight",
        startHour = 0,
        endHour = 6,
        assets = listOf(
            GaplessAsset(id = "overnight-1", uri = "...", mimeType = "image/png") to 30_000L,
        )
    ),
)

fun activePlaylist(schedules: List<ScheduledPlaylist>): ScheduledPlaylist? {
    val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return schedules.firstOrNull { hour >= it.startHour && hour < it.endHour }
}
```

Wire it up — buffer next and schedule transition on each `Started` event:

```kotlin
class PlayerViewModel : ViewModel() {
    val manager = GaplessPlaylistManager(scope = viewModelScope)

    init {
        val indices = mutableMapOf<String, Int>()
        var timerJob: Job? = null

        fun nextAsset(): Pair<GaplessAsset, Long>? {
            val playlist = activePlaylist(schedules) ?: return null
            val i = indices.getOrDefault(playlist.name, 0)
            indices[playlist.name] = (i + 1) % playlist.assets.size
            return playlist.assets[i]
        }

        val (first, firstDuration) = nextAsset() ?: return
        var currentDuration = firstDuration
        manager.start(first)

        viewModelScope.launch {
            manager.events.collect { event ->
                if (event is GaplessEvent.Started) {
                    timerJob?.cancel()
                    val duration = currentDuration
                    val next = nextAsset()
                    if (next != null) {
                        val (nextAsset, nextDuration) = next
                        currentDuration = nextDuration
                        manager.prepareNext(nextAsset)
                        timerJob = launch {
                            delay(duration)
                            manager.play(nextAsset)
                        }
                    } else {
                        timerJob = launch {
                            delay(duration)
                            manager.stop()
                        }
                    }
                }
            }
        }
    }
}
```

Each `Started` event re-evaluates the active playlist. The index per playlist is tracked separately so each playlist resumes where it left off.

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
    val assets: List<Pair<GaplessAsset, Long>>,
)

fun activePlaylist(schedules: List<ScheduledPlaylist>): ScheduledPlaylist? {
    val now = Calendar.getInstance()
    val hour = now.get(Calendar.HOUR_OF_DAY)
    val day = now.get(Calendar.DAY_OF_WEEK)
    return schedules.firstOrNull { day in it.daysOfWeek && hour >= it.startHour && hour < it.endHour }
}
```

If no schedule matches, `nextAsset()` returns null and the host calls `manager.stop()`.
