package io.github._514sid.gapless.internal.video

import androidx.media3.common.C
import io.github._514sid.gapless.internal.video.TimelineKeeper.Correction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimelineKeeperTest {

    private fun correct(position: Long, expected: Long, speed: Float = 1f, duration: Long = 60_000, canSeek: Boolean = true) =
        TimelineKeeper.correct(position, expected, duration, speed, canSeek)

    private fun speedOf(c: Correction): Float = (c as Correction.Speed).speed

    @Test
    fun `on time needs nothing`() {
        assertEquals(Correction.None, correct(position = 10_000, expected = 10_000))
        assertEquals(Correction.None, correct(position = 10_003, expected = 10_000))
    }

    @Test
    fun `behind plays faster and ahead plays slower, in proportion`() {
        val behind40 = speedOf(correct(position = 9_960, expected = 10_000))
        val behind80 = speedOf(correct(position = 9_920, expected = 10_000))
        val ahead40 = speedOf(correct(position = 10_040, expected = 10_000))
        assertEquals(1.02f, behind40, 0.0001f)
        assertTrue(behind80 > behind40)
        assertEquals(0.98f, ahead40, 0.0001f)
    }

    @Test
    fun `the speed change is capped`() {
        assertEquals(1f + TimelineKeeper.MAX_SPEED_CHANGE, speedOf(correct(position = 9_600, expected = 10_000)))
        assertEquals(1f - TimelineKeeper.MAX_SPEED_CHANGE, speedOf(correct(position = 10_400, expected = 10_000)))
    }

    @Test
    fun `back on time returns to normal speed`() {
        assertEquals(1f, speedOf(correct(position = 10_000, expected = 10_000, speed = 1.01f)))
    }

    @Test
    fun `a tiny change in the target speed is not applied`() {
        assertEquals(Correction.None, correct(position = 9_960, expected = 10_000, speed = 1.0199f))
    }

    @Test
    fun `half a second off seeks to where the clip should be`() {
        assertEquals(Correction.Seek(10_000), correct(position = 9_400, expected = 10_000))
        assertEquals(Correction.Seek(10_000), correct(position = 10_600, expected = 10_000))
    }

    @Test
    fun `while a seek cools down, a large error is closed with the capped speed`() {
        assertEquals(1f + TimelineKeeper.MAX_SPEED_CHANGE, speedOf(correct(position = 9_000, expected = 10_000, canSeek = false)))
    }

    @Test
    fun `no seek near the end of the clip`() {
        val c = correct(position = 58_000, expected = 59_500, duration = 60_000)
        assertEquals(1f + TimelineKeeper.MAX_SPEED_CHANGE, speedOf(c))
    }

    @Test
    fun `a clip of unknown length can still seek`() {
        assertEquals(Correction.Seek(10_000), correct(position = 9_000, expected = 10_000, duration = C.TIME_UNSET))
    }

    @Test
    fun `a clip that can't be sped up only slows down, and waits for a seek when behind`() {
        assertEquals(Correction.None, TimelineKeeper.correct(9_900, 10_000, 60_000, 1f, true, maxSpeedUp = 0f))
        assertEquals(0.95f, speedOf(TimelineKeeper.correct(10_100, 10_000, 60_000, 1f, true, maxSpeedUp = 0f)))
        assertEquals(Correction.Seek(10_000), TimelineKeeper.correct(9_000, 10_000, 60_000, 1f, true, maxSpeedUp = 0f))
    }

    @Test
    fun `the speed-up is capped by what the decoder can take at the clip's frame rate`() {
        assertEquals(0f, TimelineKeeper.maxSpeedUpFor(60f))
        assertEquals(60f / 59.94f - 1f, TimelineKeeper.maxSpeedUpFor(59.94f), 0.0001f) // up to 60 fps
        assertEquals(TimelineKeeper.MAX_SPEED_CHANGE, TimelineKeeper.maxSpeedUpFor(30f))
        assertEquals(TimelineKeeper.MAX_SPEED_CHANGE, TimelineKeeper.maxSpeedUpFor(25f))
        assertEquals(60f / 58f - 1f, TimelineKeeper.maxSpeedUpFor(58f), 0.0001f)
        assertEquals(TimelineKeeper.MAX_SPEED_CHANGE, TimelineKeeper.maxSpeedUpFor(0f)) // unknown
    }

    @Test
    fun `before its start time the clip is left at normal speed`() {
        assertEquals(Correction.None, correct(position = 0, expected = -200))
        assertEquals(1f, speedOf(correct(position = 0, expected = -200, speed = 1.02f)))
    }
}
