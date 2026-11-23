package io.github._514sid.gapless

/**
 * Marks API that is experimental: it works and is tested, but its shape and behaviour may change
 * in any release, without a deprecation cycle. Today that is synced playback: playing video on a
 * start time ([GaplessController.play] with `startAtMs`) and the [GaplessClock] it is kept to.
 *
 * Opt in with `@OptIn(ExperimentalGaplessApi::class)` where you use it.
 */
@RequiresOptIn(
    message = "Synced playback (start times and GaplessClock) is experimental; it may change in any release.",
    level = RequiresOptIn.Level.ERROR,
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.PROPERTY)
public annotation class ExperimentalGaplessApi
