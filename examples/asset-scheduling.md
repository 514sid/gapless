# Asset scheduling

Filter assets by their own schedule rules before passing them to the manager. The manager owns playback; the caller owns which assets are currently eligible to play.

## Per-asset schedule rules

Wrap `GaplessAsset` with scheduling metadata:

```kotlin
data class ScheduledAsset(
    val asset: GaplessAsset,
    val daysOfWeek: Set<Int> = setOf(
        Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY,
        Calendar.THURSDAY, Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
    ),
    val startHour: Int = 0,  // inclusive
    val endHour: Int = 24,   // exclusive
) {
    fun isActiveNow(): Boolean {
        val now = Calendar.getInstance()
        val day = now.get(Calendar.DAY_OF_WEEK)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        return day in daysOfWeek && hour >= startHour && hour < endHour
    }
}
```

Define your full asset catalogue with rules:

```kotlin
val catalogue = listOf(
    ScheduledAsset(
        asset = GaplessAsset(id = "promo-weekday", uri = "...", mimeType = "video/mp4", durationMs = 15_000),
        daysOfWeek = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
    ),
    ScheduledAsset(
        asset = GaplessAsset(id = "promo-weekend", uri = "...", mimeType = "video/mp4", durationMs = 15_000),
        daysOfWeek = setOf(Calendar.SATURDAY, Calendar.SUNDAY),
    ),
    ScheduledAsset(
        asset = GaplessAsset(id = "lunch-offer", uri = "...", mimeType = "image/jpeg", durationMs = 8_000),
        startHour = 11,
        endHour = 14,
    ),
    ScheduledAsset(
        asset = GaplessAsset(id = "always-on-brand", uri = "...", mimeType = "image/png", durationMs = 5_000),
        // no restrictions — plays any time, any day
    ),
)

fun activeAssets(catalogue: List<ScheduledAsset>): List<GaplessAsset> =
    catalogue.filter { it.isActiveNow() }.map { it.asset }
```

## Re-evaluating on cycle

Re-filter at the end of each cycle so newly-eligible assets enter the playlist and expired ones leave. `update()` keeps the currently-playing asset running if its id is still present.

```kotlin
manager.start(activeAssets(catalogue))

lifecycleScope.launch {
    manager.events.collect { event ->
        if (event is GaplessEvent.CycleCompleted) {
            manager.update(activeAssets(catalogue))
        }
    }
}
```

Assets that become eligible mid-cycle appear at the next cycle boundary. Assets that expire mid-cycle keep playing until the current slot ends, then are excluded from the next `update()`.

## Handling an empty result

If all assets are scheduled out, `update()` with an empty list stops playback and emits `GaplessEvent.Empty`. Decide in your app whether to show a fallback or a blank screen:

```kotlin
val next = activeAssets(catalogue)
if (next.isNotEmpty()) {
    manager.update(next)
} else {
    manager.update(fallbackAssets) // or manager.stop() for a blank screen
}
```

## Re-evaluating on a timer

For tighter schedule precision (assets that switch mid-cycle at an exact hour), also drive updates from a timer aligned to the next hour boundary:

```kotlin
fun millisUntilNextHour(): Long {
    val now = Calendar.getInstance()
    val minutes = now.get(Calendar.MINUTE)
    val seconds = now.get(Calendar.SECOND)
    return ((60 - minutes) * 60 - seconds) * 1000L
}

fun scheduleHourlyRefresh() {
    lifecycleScope.launch {
        delay(millisUntilNextHour())
        manager.update(activeAssets(catalogue))
        scheduleHourlyRefresh()
    }
}
```

The cycle-boundary and hourly approaches can run together — `update()` is idempotent when the filtered list hasn't changed.
