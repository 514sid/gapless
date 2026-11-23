package io.github._514sid.gapless

/**
 * The clock that [GaplessController.play]'s `startAtMs` is given in. Video that is played with a
 * start time is kept on that time by this clock: a clip that started late catches up, and one
 * that drifts from the clock during a long play is pulled back.
 *
 * Devices playing in step should all use a clock they agree on, such as one synchronized over the
 * network. The default is the device's wall clock.
 *
 * Experimental, like the start times it serves: see [ExperimentalGaplessApi].
 */
@ExperimentalGaplessApi
public fun interface GaplessClock {
    /** The time now, in milliseconds. Must not jump backwards in normal use. */
    public fun nowMs(): Long

    public companion object {
        /** `System.currentTimeMillis()`. */
        public val Wall: GaplessClock = GaplessClock { System.currentTimeMillis() }
    }
}
