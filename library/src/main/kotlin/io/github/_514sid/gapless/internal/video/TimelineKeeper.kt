package io.github._514sid.gapless.internal.video

import androidx.media3.common.C
import kotlin.math.abs

/**
 * Decides how to keep a playing clip on its timeline (see [io.github._514sid.gapless.internal.VideoTimeline]).
 *
 * Small errors are closed by playing slightly faster or slower: the speed is proportional to the
 * error, so it eases back to normal as the clip catches up, and is capped so the change can't be
 * seen. Errors too large to close that way in reasonable time are fixed with a seek, at most once
 * per [SEEK_COOLDOWN_MS] so a slow seek can't turn into a loop of them.
 */
internal object TimelineKeeper {

    sealed interface Correction {
        data class Speed(val speed: Float) : Correction
        data class Seek(val positionMs: Long) : Correction
        data object None : Correction
    }

    /** Errors up to this are left alone. */
    const val DEADBAND_MS = 4L

    /** The speed change closes the error over about this long, short enough for short clips. */
    const val CATCH_UP_MS = 2_000L

    /** The furthest the speed goes from 1. A 5% change doesn't show on video; audio keeps its pitch. */
    const val MAX_SPEED_CHANGE = 0.05f

    /** Errors from this size up are fixed with a seek instead. */
    const val SEEK_ERROR_MS = 500L

    /** Seeks this close to the clip's end aren't worth it; it is about to be cut away from. */
    const val SEEK_END_MARGIN_MS = 1_000L

    const val SEEK_COOLDOWN_MS = 5_000L

    /**
     * The most frames a second a speed-up may ask of the decoder. TV decoders are often built for
     * exactly 60, and asking a 60 fps clip for 63 makes it fall behind and stall.
     */
    const val MAX_DECODE_FPS = 60f

    /** Speed changes smaller than this aren't applied. */
    private const val MIN_SPEED_STEP = 0.0005f

    /**
     * @param positionMs Where the clip is.
     * @param expectedMs Where its timeline says it should be.
     * @param durationMs The clip's length, or [C.TIME_UNSET].
     * @param speed The speed it plays at now.
     * @param canSeek False while a recent seek is still cooling down.
     * @param maxSpeedUp How much faster than normal the clip may play; see [maxSpeedUpFor].
     */
    fun correct(
        positionMs: Long,
        expectedMs: Long,
        durationMs: Long,
        speed: Float,
        canSeek: Boolean,
        maxSpeedUp: Float = MAX_SPEED_CHANGE,
    ): Correction {
        // Before its start time the clip should not be playing at all; nothing useful to do.
        if (expectedMs < 0) return speedTo(1f, speed)
        val error = positionMs - expectedMs
        if (abs(error) >= SEEK_ERROR_MS) {
            val fitsInClip = durationMs == C.TIME_UNSET || expectedMs < durationMs - SEEK_END_MARGIN_MS
            if (canSeek && fitsInClip) return Correction.Seek(expectedMs)
        }
        val target = if (abs(error) <= DEADBAND_MS) {
            1f
        } else {
            (1f - error.toFloat() / CATCH_UP_MS).coerceIn(1f - MAX_SPEED_CHANGE, 1f + maxSpeedUp)
        }
        return speedTo(target, speed)
    }

    /**
     * How much faster a clip of [frameRate] frames a second may play without asking the decoder
     * for more than [MAX_DECODE_FPS]. A 60 fps clip can't be sped up at all, only slowed down.
     */
    fun maxSpeedUpFor(frameRate: Float): Float =
        if (frameRate <= 0f) MAX_SPEED_CHANGE else (MAX_DECODE_FPS / frameRate - 1f).coerceIn(0f, MAX_SPEED_CHANGE)

    private fun speedTo(target: Float, speed: Float): Correction =
        if (abs(target - speed) >= MIN_SPEED_STEP || (target == 1f && speed != 1f)) Correction.Speed(target) else Correction.None
}
