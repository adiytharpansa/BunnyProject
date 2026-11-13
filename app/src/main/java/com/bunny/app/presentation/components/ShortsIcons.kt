package com.bunny.app.presentation.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Ikon tab "Shorts" buatan sendiri (Material tidak punya ikon ini):
 * kapsul vertikal bergaya layar ponsel dengan segitiga putar.
 * Filled = terpilih (kapsul padat, segitiga berlubang);
 * Outlined = tidak terpilih (kapsul garis, segitiga padat).
 */
object ShortsIcons {

    val Filled: ImageVector by lazy {
        ImageVector.Builder(
            name = "ShortsFilled",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            // Kapsul luar 14x20, sudut 4.
            moveTo(9f, 2f)
            horizontalLineTo(15f)
            arcTo(4f, 4f, 0f, false, true, 19f, 6f)
            verticalLineTo(18f)
            arcTo(4f, 4f, 0f, false, true, 15f, 22f)
            horizontalLineTo(9f)
            arcTo(4f, 4f, 0f, false, true, 5f, 18f)
            verticalLineTo(6f)
            arcTo(4f, 4f, 0f, false, true, 9f, 2f)
            close()
            // Segitiga putar (lubang).
            moveTo(10f, 8.5f)
            lineTo(16f, 12f)
            lineTo(10f, 15.5f)
            close()
        }.build()
    }

    val Outlined: ImageVector by lazy {
        ImageVector.Builder(
            name = "ShortsOutlined",
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).path(
            fill = SolidColor(Color.Black),
            pathFillType = PathFillType.EvenOdd,
        ) {
            // Kapsul luar.
            moveTo(9f, 2f)
            horizontalLineTo(15f)
            arcTo(4f, 4f, 0f, false, true, 19f, 6f)
            verticalLineTo(18f)
            arcTo(4f, 4f, 0f, false, true, 15f, 22f)
            horizontalLineTo(9f)
            arcTo(4f, 4f, 0f, false, true, 5f, 18f)
            verticalLineTo(6f)
            arcTo(4f, 4f, 0f, false, true, 9f, 2f)
            close()
            // Kapsul dalam (membentuk garis tebal 1.8).
            moveTo(9f, 3.8f)
            horizontalLineTo(15f)
            arcTo(2.2f, 2.2f, 0f, false, true, 17.2f, 6f)
            verticalLineTo(18f)
            arcTo(2.2f, 2.2f, 0f, false, true, 15f, 20.2f)
            horizontalLineTo(9f)
            arcTo(2.2f, 2.2f, 0f, false, true, 6.8f, 18f)
            verticalLineTo(6f)
            arcTo(2.2f, 2.2f, 0f, false, true, 9f, 3.8f)
            close()
            // Segitiga putar padat di dalam.
            moveTo(10.5f, 9f)
            lineTo(15f, 12f)
            lineTo(10.5f, 15f)
            close()
        }.build()
    }
}
