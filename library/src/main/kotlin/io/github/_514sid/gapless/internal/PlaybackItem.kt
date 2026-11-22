package io.github._514sid.gapless.internal

import android.net.Uri
import java.util.UUID

internal sealed class PlaybackItem {
    val playbackId: UUID = UUID.randomUUID()
    var preparedAt: Long = 0L
    var startedAt: Long = 0L
    var assetId: String = ""

    /**
     * @param aspectRatioHint Width / height from the asset's declared size, used to lay out the
     * slot before the decoder reports the real video size. Null when the asset declared no size.
     */
    data class Video(
        val uri: Uri,
        val mimeType: String,
        val aspectRatioHint: Float? = null,
        val volume: Float = 0f,
        val durationMs: Long? = null,
    ) : PlaybackItem()

    data class Image(
        val model: Any,
        val width: Int,
        val height: Int
    ) : PlaybackItem()

    data class Web(
        val url: String,
        val refreshIntervalMs: Long = 0L
    ) : PlaybackItem()
}
