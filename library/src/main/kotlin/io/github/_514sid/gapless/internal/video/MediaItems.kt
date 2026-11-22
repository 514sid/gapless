package io.github._514sid.gapless.internal.video

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

/** Media3 only recognizes `application/x-mpegURL` for HLS, so map the Apple alias onto it. */
private fun normalizeMimeType(mimeType: String): String =
    if (mimeType.equals("application/vnd.apple.mpegurl", ignoreCase = true)) MimeTypes.APPLICATION_M3U8
    else mimeType

/** Display aspect ratio of a decoded video, or null while the size is still unknown. */
internal fun VideoSize.displayAspectRatio(): Float? =
    if (width > 0 && height > 0) width * pixelWidthHeightRatio / height else null
