package io.github._514sid.gapless.internal

import android.net.Uri
import java.util.UUID

internal sealed class PlaybackItem {
    val playbackId: UUID = UUID.randomUUID()
    var preparedAt: Long = 0L
    var startedAt: Long = 0L
    var assetId: String = ""

    abstract val width: Int
    abstract val height: Int

    val targetAspectRatio: Float
        get() = if (height > 0) width.toFloat() / height.toFloat() else 16f / 9f

    data class Video(
        val uri: Uri,
        override val width: Int,
        override val height: Int,
        val volume: Float = 0f,
        val durationMs: Long? = null,
    ) : PlaybackItem()

    data class Image(
        val model: Any,
        override val width: Int,
        override val height: Int
    ) : PlaybackItem()

    data class Web(
        val url: String,
        val refreshIntervalMs: Long = 0L
    ) : PlaybackItem() {
        override val width = 0
        override val height = 0
    }
}