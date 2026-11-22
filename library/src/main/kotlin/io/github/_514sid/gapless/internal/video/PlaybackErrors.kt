package io.github._514sid.gapless.internal.video

import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Tracks
import androidx.media3.datasource.HttpDataSource

/** What a video slot should do about a [PlaybackException]. */
internal enum class ErrorAction {
    /** Likely to clear up on its own (network blip, codec reclaimed, audio device change): retry. */
    RETRY,

    /** A live stream fell behind its window: jump back to the live edge. */
    REJOIN_LIVE,

    /** Retrying won't help (bad URL, unsupported media, DRM): report it. */
    FATAL,
}

internal fun classifyError(error: PlaybackException): ErrorAction =
    classifyError(error.errorCode, error.httpStatus())

/**
 * [httpStatus] is the server's response code for [PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS],
 * when known. Without it a bad status counts as fatal.
 */
internal fun classifyError(errorCode: Int, httpStatus: Int? = null): ErrorAction = when (errorCode) {
    PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> ErrorAction.REJOIN_LIVE

    PlaybackException.ERROR_CODE_UNSPECIFIED,
    PlaybackException.ERROR_CODE_REMOTE_ERROR,
    PlaybackException.ERROR_CODE_TIMEOUT -> ErrorAction.RETRY

    // Timeouts, rate limits and server errors pass; a CDN can return a 503 for one segment.
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
        if (httpStatus != null && (httpStatus == 408 || httpStatus == 429 || httpStatus >= 500)) {
            ErrorAction.RETRY
        } else {
            ErrorAction.FATAL
        }

    // The server answered, and the answer was no.
    PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
    PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
    PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
    PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE -> ErrorAction.FATAL

    // Decoders that exist but could not be opened or were taken away: often a resource limit.
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED,
    PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED -> ErrorAction.RETRY

    else -> when (errorCode / 1000) {
        2 -> ErrorAction.RETRY // remaining IO errors: connection failed, timed out, unspecified
        5 -> ErrorAction.RETRY // audio track errors, e.g. HDMI output switched
        else -> ErrorAction.FATAL
    }
}

/** How many times to retry an error classified as [ErrorAction.RETRY]. */
internal fun retryLimit(errorCode: Int): Int =
    // Damaged media fails at the same spot every time; one retry (on a new player) is enough to
    // rule out a wedged codec.
    if (errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED) 1 else MAX_RETRIES

internal const val MAX_RETRIES = 3

/** The HTTP response code behind this error, if it came from a bad HTTP status. */
internal fun PlaybackException.httpStatus(): Int? {
    var cause: Throwable? = cause
    while (cause != null) {
        if (cause is HttpDataSource.InvalidResponseCodeException) return cause.responseCode
        cause = cause.cause
    }
    return null
}

/**
 * True when the error means the device ran out of decoders rather than that the media is bad,
 * which is what happens when a second hardware decoder can't be opened next to the first.
 */
internal fun isDecoderResourceError(errorCode: Int): Boolean =
    errorCode == PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
        errorCode == PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED

/**
 * Explains why the media's video can't be shown, or returns null if it can. When no decoder on the
 * device handles the video format, ExoPlayer quietly leaves the video track unselected and plays on
 * with no picture and no error, so this has to be checked by hand.
 */
internal fun Tracks.unsupportedVideoMessage(): String? {
    if (!containsType(C.TRACK_TYPE_VIDEO)) return null
    if (isTypeSupported(C.TRACK_TYPE_VIDEO, /* allowExceedsCapabilities = */ true)) return null
    val format = groups.firstOrNull { it.type == C.TRACK_TYPE_VIDEO }?.takeIf { it.length > 0 }?.getTrackFormat(0)
    val codec = listOfNotNull(format?.sampleMimeType, format?.codecs).joinToString(" ").ifEmpty { "unknown format" }
    return "No decoder on this device can play the video ($codec)"
}
