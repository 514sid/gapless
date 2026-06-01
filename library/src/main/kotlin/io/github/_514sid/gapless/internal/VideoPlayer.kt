package io.github._514sid.gapless.internal

import android.graphics.Bitmap
import android.view.TextureView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.viewinterop.AndroidView

internal data class VideoPlayerState(
    val aspectRatio: Float? = null,
    val snapshot: Bitmap? = null
)

@Composable
internal fun VideoPlayer(
    state: VideoPlayerState,
    onTextureViewCreated: (TextureView) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {

        Box(
            modifier = Modifier
                .then(
                    if (state.aspectRatio != null)
                        Modifier.aspectRatio(state.aspectRatio)
                    else
                        Modifier.fillMaxSize()
                )
                .clipToBounds()
        ) {
            AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        onTextureViewCreated(this)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
        }

        state.snapshot?.let { bitmap ->
            DisposableEffect(bitmap) {
                onDispose {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.aspectRatio(bitmap.width.toFloat() / bitmap.height)
                )
            }
        }
    }
}