package io.github._514sid.gapless

/** Selects how video playback is backed. */
enum class GaplessVideoStrategy {
    /** Two ExoPlayers in an A/B swap. Seamless cuts; uses two decoders during a transition. Default. */
    DUAL_INSTANCE,

    /** Single ExoPlayer reusing one timeline. One decoder, re-initialized per cut. Experimental. */
    SINGLE_INSTANCE_EXPERIMENTAL,
}

/**
 * Configuration for the ExoPlayer instance(s) created by the library.
 *
 * @param strategy How video playback is backed. See [GaplessVideoStrategy]. Defaults to
 * [GaplessVideoStrategy.DUAL_INSTANCE].
 * @param enableDecoderFallback Allow falling back to a software decoder when a hardware decoder
 * fails. Off by default. Enable on weak hardware to avoid a [GaplessEvent.PlaybackError] at the
 * cost of higher CPU usage.
 * @param minBufferMs Minimum duration of media to buffer before playback can start, in milliseconds.
 * @param maxBufferMs Maximum duration of media to buffer at any point, in milliseconds.
 * @param bufferForPlaybackMs Duration of media buffered before playback starts or resumes, in milliseconds.
 * @param bufferForPlaybackAfterRebufferMs Duration of media buffered to resume after a rebuffer, in milliseconds.
 */
data class GaplessVideoConfig(
    val strategy: GaplessVideoStrategy = GaplessVideoStrategy.DUAL_INSTANCE,
    val enableDecoderFallback: Boolean = false,
    val minBufferMs: Int = 1_000,
    val maxBufferMs: Int = 3_000,
    val bufferForPlaybackMs: Int = 500,
    val bufferForPlaybackAfterRebufferMs: Int = 1_000,
)
