package com.poppinzhang.natsuko.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Outline icons for the Image Split workflow; tinted by Icon(). */
internal object SplitIcons {
    val Split: ImageVector by lazy {
        outline("Split") {
            // Left slice frame
            moveTo(3f, 5f); horizontalLineTo(9f); verticalLineTo(19f); horizontalLineTo(3f); close()
            // Right slice frame
            moveTo(15f, 5f); horizontalLineTo(21f); verticalLineTo(19f); horizontalLineTo(15f); close()
            // Center cut line
            moveTo(12f, 2.5f); verticalLineTo(21.5f)
        }
    }

    val Fullscreen: ImageVector by lazy {
        outline("Fullscreen") {
            moveTo(4f, 9f); verticalLineTo(4f); horizontalLineTo(9f)
            moveTo(15f, 4f); horizontalLineTo(20f); verticalLineTo(9f)
            moveTo(20f, 15f); verticalLineTo(20f); horizontalLineTo(15f)
            moveTo(9f, 20f); horizontalLineTo(4f); verticalLineTo(15f)
        }
    }

    val FullscreenExit: ImageVector by lazy {
        outline("FullscreenExit") {
            moveTo(9f, 4f); verticalLineTo(9f); horizontalLineTo(4f)
            moveTo(15f, 4f); verticalLineTo(9f); horizontalLineTo(20f)
            moveTo(20f, 15f); horizontalLineTo(15f); verticalLineTo(20f)
            moveTo(4f, 15f); horizontalLineTo(9f); verticalLineTo(20f)
        }
    }

    val Preview: ImageVector by lazy {
        outline("SplitPreview") {
            moveTo(3f, 5f); horizontalLineTo(7f); verticalLineTo(19f); horizontalLineTo(3f); close()
            moveTo(10f, 5f); horizontalLineTo(14f); verticalLineTo(19f); horizontalLineTo(10f); close()
            moveTo(17f, 5f); horizontalLineTo(21f); verticalLineTo(19f); horizontalLineTo(17f); close()
        }
    }

    private fun outline(name: String, pathData: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = pathData,
        ).build()
}
