package io.github._514sid.gapless

/** Selects how video playback is backed. */
public enum class GaplessVideoStrategy {
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
 * @param enableDecoderFallback When the preferred (usually hardware) decoder fails to open, try the
 * device's other decoders, typically a software one, instead of failing. On by default. It costs
 * nothing while the preferred decoder works; a software decoder that does take over uses more CPU.
 * @param minBufferMs Minimum duration of media to buffer before playback can start, in milliseconds.
 * @param maxBufferMs Maximum duration of media to buffer at any point, in milliseconds.
 * @param bufferForPlaybackMs Duration of media buffered before playback starts or resumes, in milliseconds.
 * @param bufferForPlaybackAfterRebufferMs Duration of media buffered to resume after a rebuffer, in milliseconds.
 * @param stallTimeoutMs How long the playing video may sit without advancing (stuck buffering a
 * stream, a hung decoder) before the player restarts it, in milliseconds. Repeated stalls end in a
 * [GaplessEvent.PlaybackError]. Zero or negative disables the check. Applies to
 * [GaplessVideoStrategy.DUAL_INSTANCE] only.
 */
public data class GaplessVideoConfig(
    val strategy: GaplessVideoStrategy = GaplessVideoStrategy.DUAL_INSTANCE,
    val enableDecoderFallback: Boolean = true,
    val minBufferMs: Int = 1_000,
    val maxBufferMs: Int = 3_000,
    val bufferForPlaybackMs: Int = 500,
    val bufferForPlaybackAfterRebufferMs: Int = 1_000,
    val stallTimeoutMs: Long = 15_000,
)
