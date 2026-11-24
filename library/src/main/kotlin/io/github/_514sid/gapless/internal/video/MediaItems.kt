package io.github._514sid.gapless.internal.video

import io.github._514sid.gapless.internal.ContentSize
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoSize
import io.github._514sid.gapless.internal.PlaybackItem

/**
 * Builds the [MediaItem] for a video item. The MIME type is passed through so adaptive streams
 * (HLS, DASH, SmoothStreaming, RTSP) are routed to the right media source even when the URL has
 * no telling extension.
 */
internal fun buildMediaItem(item: PlaybackItem.Video): MediaItem =
    MediaItem.Builder()
        .setUri(item.uri)
        .setMimeType(normalizeMimeType(item.mimeType))
        .apply {
            val clipMs = item.durationMs
            if (clipMs != null && clipMs > 0L) {
                setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setEndPositionMs(clipMs)
                        .build()
                )
            }
        }
        .build()

/**
 * Media3 picks the media source by exact match on its own constants (for example
 * `application/x-mpegURL`), so map case variants and aliases onto them.
 */
private fun normalizeMimeType(mimeType: String): String =
    when (val base = mimeType.substringBefore(';').trim().lowercase()) {
        "application/x-mpegurl", "application/vnd.apple.mpegurl" -> MimeTypes.APPLICATION_M3U8
        "application/dash+xml" -> MimeTypes.APPLICATION_MPD
        "application/vnd.ms-sstr+xml" -> MimeTypes.APPLICATION_SS
        "application/x-rtsp" -> MimeTypes.APPLICATION_RTSP
        else -> base
    }

/** Display size of a decoded video, or null while the size is still unknown. */
internal fun VideoSize.displaySize(): ContentSize? = ContentSize.of(width * pixelWidthHeightRatio, height)
