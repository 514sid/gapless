package io.github.gapless.sample

import io.github._514sid.gapless.GaplessAsset

object SampleAssets {
    private const val PACKAGE_NAME = "io.github.gapless.sample"

    val all = listOf(
        GaplessAsset(
            id = "raw-video",
            uri = "android.resource://$PACKAGE_NAME/raw/jellyfish_30",
            mimeType = "video/mp4",
            durationMs = 10_000L
        ),
    )
}
