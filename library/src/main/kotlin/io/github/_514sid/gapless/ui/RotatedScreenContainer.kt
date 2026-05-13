package io.github._514sid.gapless.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints

@Composable
internal fun RotatedScreenContainer(rotation: Int, content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .graphicsLayer { rotationZ = rotation.toFloat() },
    ) { measurables, constraints ->
        val isRotated = rotation == 90 || rotation == 270

        val adjustedConstraints = if (isRotated) {
            Constraints(
                minWidth = constraints.minHeight,
                maxWidth = constraints.maxHeight,
                minHeight = constraints.minWidth,
                maxHeight = constraints.maxWidth,
            )
        } else constraints

        val placeables = measurables.map { it.measure(adjustedConstraints) }

        layout(constraints.maxWidth, constraints.maxHeight) {
            placeables.forEach { p ->
                p.placeRelative(
                    x = (constraints.maxWidth - p.width) / 2,
                    y = (constraints.maxHeight - p.height) / 2,
                )
            }
        }
    }
}