package io.github._514sid.gapless

/**
 * Configuration for the ExoPlayer instance created by the library.
 *
 * @param enableDecoderFallback Allow falling back to a software decoder when a hardware decoder
 * fails. Off by default. Enable on weak hardware to avoid a [GaplessEvent.PlaybackError] at the
 * cost of higher CPU usage.
 * @param minBufferMs Minimum duration of media to buffer before playback can start, in milliseconds.
 * @param maxBufferMs Maximum duration of media to buffer at any point, in milliseconds.
 * @param bufferForPlaybackMs Duration of media buffered before playback starts or resumes, in milliseconds.
 * @param bufferForPlaybackAfterRebufferMs Duration of media buffered to resume after a rebuffer, in milliseconds.
 */
data class GaplessVideoConfig(
    val enableDecoderFallback: Boolean = false,
    val minBufferMs: Int = 1_000,
    val maxBufferMs: Int = 3_000,
    val bufferForPlaybackMs: Int = 500,
    val bufferForPlaybackAfterRebufferMs: Int = 1_000,
)
