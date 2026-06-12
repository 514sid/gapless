# Asset scheduling

Filter assets by their own schedule rules when the manager asks for the next one. The host decides what plays — the manager handles the preload and transition.

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

## Pushing next on each transition

Re-evaluate the active set on every `Started` event. The schedule is re-checked at each slot boundary, so assets that expire or become eligible mid-cycle are picked up immediately:

```kotlin
var index = 0

fun nextActive(): GaplessAsset? {
    val active = activeAssets(catalogue)
    if (active.isEmpty()) return null
    return active[index++ % active.size]
}

val first = nextActive() ?: return  // nothing active at boot — handle in your app
manager.start(first)

lifecycleScope.launch {
    manager.events.collect { event ->
        if (event is GaplessEvent.Started) {
            val next = nextActive()
            if (next != null) manager.prepareNext(next)
            else manager.stop()
        }
    }
}
```

## Handling an empty result

When no assets are active, call `manager.stop()` to halt. To resume when assets become available again, re-evaluate on a timer:

```kotlin
fun scheduleRetry() {
    lifecycleScope.launch {
        delay(60_000)
        val next = nextActive()
        if (next != null) manager.start(next)
        else scheduleRetry()
    }
}
```
