package io.github._514sid.gapless.internal

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout

@Composable
internal fun RotatedContainer(
    rotation: Int,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { rotationZ = rotation.toFloat() }
            .layout { measurable, constraints ->
                val isSwapped = rotation == 90 || rotation == 270
                val placeable = measurable.measure(
                    if (isSwapped) constraints.copy(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth
                    ) else constraints
                )
                layout(constraints.maxWidth, constraints.maxHeight) {
                    placeable.placeRelative(
                        (constraints.maxWidth - placeable.width) / 2,
                        (constraints.maxHeight - placeable.height) / 2
                    )
                }
            }
    ) {
        Box(
            modifier = modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
    }
}
