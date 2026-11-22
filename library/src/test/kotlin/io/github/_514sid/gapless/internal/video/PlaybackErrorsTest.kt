package io.github._514sid.gapless.internal.video

import androidx.media3.common.PlaybackException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaybackErrorsTest {

    @Test
    fun `transient failures are retried`() {
        listOf(
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
        ).forEach { assertEquals(ErrorAction.RETRY, classifyError(it), "code $it") }
    }

    @Test
    fun `failures retrying can't fix are reported at once`() {
        listOf(
            PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_VIDEO_FRAME_PROCESSOR_INIT_FAILED,
        ).forEach { assertEquals(ErrorAction.FATAL, classifyError(it), "code $it") }
    }

    @Test
    fun `server errors and rate limits are retried, other bad statuses are not`() {
        val code = PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        listOf(408, 429, 500, 502, 503, 504).forEach {
            assertEquals(ErrorAction.RETRY, classifyError(code, it), "status $it")
        }
        listOf(400, 401, 403, 404, 410).forEach {
            assertEquals(ErrorAction.FATAL, classifyError(code, it), "status $it")
        }
        assertEquals(ErrorAction.FATAL, classifyError(code, null))
    }

    @Test
    fun `damaged media gets a single retry`() {
        assertEquals(1, retryLimit(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(MAX_RETRIES, retryLimit(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertEquals(MAX_RETRIES, retryLimit(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    @Test
    fun `a live stream behind its window rejoins the live edge`() {
        assertEquals(ErrorAction.REJOIN_LIVE, classifyError(PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW))
    }

    @Test
    fun `only decoder exhaustion counts as a resource error`() {
        assertTrue(isDecoderResourceError(PlaybackException.ERROR_CODE_DECODER_INIT_FAILED))
        assertTrue(isDecoderResourceError(PlaybackException.ERROR_CODE_DECODING_RESOURCES_RECLAIMED))
        assertFalse(isDecoderResourceError(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertFalse(isDecoderResourceError(PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
    }
}
