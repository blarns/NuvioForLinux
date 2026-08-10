package com.nuvio.app.core.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope

// Desktop uses Skia through Compose's own draw scope rather than a platform-specific
// fast path, so the budget is conservative and the renderer is a straight loop.
internal actual val ashSwarmMaxGrainBudget: Int = 2_000

internal actual class AshSwarmRenderer actual constructor(private val maxGrains: Int) {
    actual fun draw(
        scope: DrawScope,
        count: Int,
        centersX: FloatArray,
        centersY: FloatArray,
        halfWidths: FloatArray,
        halfHeights: FloatArray,
        colors: IntArray,
    ) {
        val n = minOf(count, maxGrains, centersX.size, centersY.size, halfWidths.size, halfHeights.size, colors.size)
        for (i in 0 until n) {
            val hw = halfWidths[i]
            val hh = halfHeights[i]
            if (hw <= 0f || hh <= 0f) continue
            scope.drawRect(
                color = Color(colors[i]),
                topLeft = Offset(centersX[i] - hw, centersY[i] - hh),
                size = Size(hw * 2f, hh * 2f),
            )
        }
    }
}
