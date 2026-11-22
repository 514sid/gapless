package io.github._514sid.gapless

/**
 * Configuration for the two ExoPlayers the library plays video with. One plays while the other
 * loads the next clip, so a cut only swaps which one is on screen.
 *
 * @param enableDecoderFallback When the preferred (usually hardware) decoder fails to open, try the
 * device's other decoders, typically a software one, instead of failing. Off by default: a hardware
 * decoder usually fails to open because the device's decoders are all in use, and a software decoder
 * taking over then decodes on the CPU, which stutters on the weak CPUs of TVs and signage players.
 * Off, a second decoder that can't open makes the player switch to hard cuts instead, which keep
 * every clip on the hardware decoder but hold the last frame briefly at each cut. Formats that only
 * a software decoder supports play in software either way; this only decides what happens when the
 * first choice won't open.
 * @param minBufferMs Minimum duration of media to buffer before playback can start, in milliseconds.
 * @param maxBufferMs Maximum duration of media to buffer at any point, in milliseconds.
 * @param bufferForPlaybackMs Duration of media buffered before playback starts or resumes, in milliseconds.
 * @param bufferForPlaybackAfterRebufferMs Duration of media buffered to resume after a rebuffer, in milliseconds.
 * @param stallTimeoutMs How long the playing video may sit without advancing (stuck buffering a
 * stream, a hung decoder) before the player restarts it, in milliseconds. Repeated stalls end in a
 * [GaplessEvent.PlaybackError]. Zero or negative disables the check.
 * @param targetBufferBytes The most media each player buffers, in bytes, whatever the durations
 * above allow. Buffers are on the Java heap, and there are two players, so this
 * bounds video memory at about twice this value. Defaults to 32 MB, about 5 seconds of 50 Mbps 4K.
 * Zero or negative uses Media3's own limit of up to about 144 MB per player.
 */
public data class GaplessVideoConfig(
    val enableDecoderFallback: Boolean = false,
    val minBufferMs: Int = 1_000,
    val maxBufferMs: Int = 3_000,
    val bufferForPlaybackMs: Int = 500,
    val bufferForPlaybackAfterRebufferMs: Int = 1_000,
    val stallTimeoutMs: Long = 15_000,
    val targetBufferBytes: Int = 32 * 1024 * 1024,
)
