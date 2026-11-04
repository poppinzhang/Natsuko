package com.poppinzhang.natsuko.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Simple outline glyphs for editing actions missing from the core icon set; tinted by Icon(). */
internal object AdjustmentIcons {
    val Undo: ImageVector by lazy {
        outline("Undo") {
            moveTo(9f, 5f); lineTo(4f, 10f); lineTo(9f, 15f)
            moveTo(4f, 10f); horizontalLineTo(15f); arcTo(5f, 5f, 0f, false, true, 15f, 20f); horizontalLineTo(11f)
        }
    }

    val Redo: ImageVector by lazy {
        outline("Redo") {
            moveTo(15f, 5f); lineTo(20f, 10f); lineTo(15f, 15f)
            moveTo(20f, 10f); horizontalLineTo(9f); arcTo(5f, 5f, 0f, false, false, 9f, 20f); horizontalLineTo(13f)
        }
    }

    val Crop: ImageVector by lazy {
        outline("Crop") {
            moveTo(7f, 3f); verticalLineTo(17f); horizontalLineTo(21f)
            moveTo(3f, 7f); horizontalLineTo(17f); verticalLineTo(21f)
        }
    }

    val Straighten: ImageVector by lazy {
        outline("Straighten") {
            moveTo(4f, 18f); horizontalLineTo(20f)
            moveTo(4f, 18f); lineTo(16f, 6f)
            moveTo(11f, 18f); arcTo(7f, 7f, 0f, false, false, 8.95f, 13.05f)
        }
    }

    val RotateLeft: ImageVector by lazy {
        outline("RotateLeft") {
            moveTo(12f, 20f); arcTo(7f, 7f, 0f, true, false, 5f, 13f)
            moveTo(2f, 10f); lineTo(5f, 13f); lineTo(8f, 10f)
        }
    }

    val RotateRight: ImageVector by lazy {
        outline("RotateRight") {
            moveTo(12f, 20f); arcTo(7f, 7f, 0f, true, true, 19f, 13f)
            moveTo(16f, 10f); lineTo(19f, 13f); lineTo(22f, 10f)
        }
    }

    val FlipHorizontal: ImageVector by lazy {
        outline("FlipHorizontal") {
            moveTo(12f, 3f); verticalLineTo(21f)
            moveTo(9f, 7f); lineTo(3f, 17f); horizontalLineTo(9f); close()
            moveTo(15f, 7f); lineTo(21f, 17f); horizontalLineTo(15f); close()
        }
    }

    val FlipVertical: ImageVector by lazy {
        outline("FlipVertical") {
            moveTo(3f, 12f); horizontalLineTo(21f)
            moveTo(7f, 9f); lineTo(17f, 3f); verticalLineTo(9f); close()
            moveTo(7f, 15f); lineTo(17f, 21f); verticalLineTo(15f); close()
        }
    }

    val Resize: ImageVector by lazy {
        outline("Resize") {
            moveTo(4f, 4f); horizontalLineTo(20f); verticalLineTo(20f); horizontalLineTo(4f); close()
            moveTo(4f, 13f); horizontalLineTo(11f); verticalLineTo(20f)
            moveTo(19f, 5f); lineTo(13f, 11f)
            moveTo(13f, 7f); verticalLineTo(11f); horizontalLineTo(17f)
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
