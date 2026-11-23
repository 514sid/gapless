package io.github._514sid.gapless.internal

import io.github._514sid.gapless.GaplessClock

/** A clip's position should be zero at [startAtMs] on [clock]. */
internal class VideoTimeline(val clock: GaplessClock, val startAtMs: Long) {
    /** Where the clip should be now, in milliseconds. */
    fun expectedPositionMs(): Long = clock.nowMs() - startAtMs
}
