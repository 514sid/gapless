# Asset scheduling

Filter assets by their own schedule rules in the host. On each `Started` event, decide what plays next, start buffering it, and schedule the transition.

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
    val durationMs: Long,
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
        asset = GaplessAsset(id = "promo-weekday", uri = "...", mimeType = "video/mp4"),
        daysOfWeek = setOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY),
        durationMs = 15_000,
    ),
    ScheduledAsset(
        asset = GaplessAsset(id = "promo-weekend", uri = "...", mimeType = "video/mp4"),
        daysOfWeek = setOf(Calendar.SATURDAY, Calendar.SUNDAY),
        durationMs = 15_000,
    ),
    ScheduledAsset(
        asset = GaplessAsset(id = "lunch-offer", uri = "...", mimeType = "image/jpeg"),
        startHour = 11,
        endHour = 14,
        durationMs = 8_000,
    ),
    ScheduledAsset(
        asset = GaplessAsset(id = "always-on-brand", uri = "...", mimeType = "image/png"),
        durationMs = 5_000,
    ),
)

fun activeAssets(catalogue: List<ScheduledAsset>): List<ScheduledAsset> =
    catalogue.filter { it.isActiveNow() }
```

## Buffering and transitioning on each slot

On each `Started` event, pick the next scheduled asset, start buffering it, then delay for the current asset's duration before calling `play`:

```kotlin
var index = 0
var timerJob: Job? = null

fun nextActive(): ScheduledAsset? {
    val active = activeAssets(catalogue)
    if (active.isEmpty()) return null
    return active[index++ % active.size]
}

val first = nextActive() ?: return  // nothing active at boot
manager.start(first.asset)

lifecycleScope.launch {
    manager.events.collect { event ->
        if (event is GaplessEvent.Started) {
            timerJob?.cancel()
            val next = nextActive()
            if (next != null) {
                manager.prepareNext(next.asset)
                timerJob = launch {
                    delay(event.asset.durationMs ?: return@launch)
                    manager.play(next.asset)
                }
            } else {
                timerJob = launch {
                    delay(event.asset.durationMs ?: return@launch)
                    manager.stop()
                }
            }
        }
    }
}
```

The schedule is re-evaluated on every `Started` event. Assets that expire or become eligible between slots are picked up at the next boundary.

## Resuming after an empty period

When no assets are active, `manager.stop()` halts playback. Poll until assets are available again:

```kotlin
fun scheduleRetry() {
    lifecycleScope.launch {
        delay(60_000)
        val next = nextActive()
        if (next != null) manager.start(next.asset)
        else scheduleRetry()
    }
}
```
