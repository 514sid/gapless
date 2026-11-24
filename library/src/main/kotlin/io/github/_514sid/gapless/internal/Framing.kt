package io.github._514sid.gapless.internal

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import io.github._514sid.gapless.GaplessFit
import kotlin.math.roundToInt

/** How an asset is fitted to the player, and the colour around it. */
internal data class Framing(
    val fit: GaplessFit = GaplessFit.Contain,
    val backgroundColor: Int = BLACK,
) {
    val contentScale: ContentScale
        get() = when (fit) {
            GaplessFit.Contain -> ContentScale.Fit
            GaplessFit.Cover -> ContentScale.Crop
            GaplessFit.Stretch -> ContentScale.FillBounds
            GaplessFit.Original -> ContentScale.None
        }

    companion object {
        const val BLACK: Int = 0xFF000000.toInt()
    }
}

/** The size of a video's picture in pixels, after any non-square pixels are made square. */
internal data class ContentSize(val width: Float, val height: Float) {
    val aspectRatio: Float get() = width / height

    companion object {
        /** Null unless both sides are known and positive. */
        fun of(width: Number?, height: Number?): ContentSize? {
            val w = width?.toFloat() ?: return null
            val h = height?.toFloat() ?: return null
            return if (w > 0f && h > 0f) ContentSize(w, h) else null
        }
    }
}

/**
 * The size content of [aspectRatio] (width / height) takes in a [width] x [height] box: the largest
 * that fits inside it, or with [cover] the smallest that covers it.
 */
internal fun fittedSize(width: Int, height: Int, aspectRatio: Float, cover: Boolean): Pair<Int, Int> {
    // The box is narrower than the content, so fitting inside it is limited by its width.
    val widthBound = width.toFloat() / height <= aspectRatio
    return if (widthBound != cover) {
        width to (width / aspectRatio).roundToInt()
    } else {
        (height * aspectRatio).roundToInt() to height
    }
}

/**
 * The size content of [size] is laid out at in a [width] x [height] box as [fit] says, or null when
 * it simply fills the box: stretched, or of unknown size.
 */
internal fun contentBox(width: Int, height: Int, size: ContentSize?, fit: GaplessFit): Pair<Int, Int>? = when {
    size == null || fit == GaplessFit.Stretch -> null
    fit == GaplessFit.Original -> size.width.roundToInt() to size.height.roundToInt()
    else -> fittedSize(width, height, size.aspectRatio, cover = fit == GaplessFit.Cover)
}

/**
 * Lays content of [size] out in the available space as [contentBox] says, centred. Covered content
 * and original-size content larger than the space spill over it, so the parent must clip it.
 */
internal fun Modifier.fitted(size: ContentSize?, fit: GaplessFit): Modifier =
    if (size == null || fit == GaplessFit.Stretch) {
        fillMaxSize()
    } else {
        layout { measurable, constraints ->
            val box = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight && constraints.maxHeight > 0) {
                contentBox(constraints.maxWidth, constraints.maxHeight, size, fit)
            } else {
                null
            }
            if (box == null) {
                val placeable = measurable.measure(constraints)
                return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
            }
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val (w, h) = box
            val placeable = measurable.measure(Constraints.fixed(w.coerceAtLeast(1), h.coerceAtLeast(1)))
            layout(width, height) { placeable.place((width - w) / 2, (height - h) / 2) }
        }
    }
