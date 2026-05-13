package io.github._514sid.gapless.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import io.github._514sid.gapless.GaplessAsset
import java.io.File

@Composable
internal fun ImageSlot(
    asset: GaplessAsset,
    onError: (GaplessAsset, String) -> Unit,
) {
    val context = LocalContext.current

    val model = remember(asset.id, asset.uri) {
        ImageRequest.Builder(context)
            .data(if (asset.uri.startsWith("/")) File(asset.uri) else asset.uri)
            .crossfade(true)
            .listener(onError = { _, result ->
                onError(asset, result.throwable.message ?: "Image loading failed")
            })
            .build()
    }

    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        filterQuality = FilterQuality.High,
        modifier = Modifier.fillMaxSize(),
    )
}