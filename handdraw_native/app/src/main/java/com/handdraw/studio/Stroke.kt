package com.handdraw.studio

import android.graphics.PointF

/**
 * One committed (or in-progress) stroke on the canvas.
 * `alpha` is 0..255, matching the original app's brush definitions.
 */
data class Stroke(
    val points: MutableList<PointF>,
    var color: Int,
    var widthPx: Float,
    var alpha: Int,
    var brush: String
) {
    fun deepCopy(): Stroke = Stroke(
        points = points.map { PointF(it.x, it.y) }.toMutableList(),
        color = color,
        widthPx = widthPx,
        alpha = alpha,
        brush = brush
    )
}

data class BrushPreset(val widthDp: Float, val alpha: Int)

object Brushes {
    val PRESETS = linkedMapOf(
        "Pen" to BrushPreset(6f, 255),
        "Fine" to BrushPreset(2f, 255),
        "Pencil" to BrushPreset(4f, 180),
        "Marker" to BrushPreset(15f, 120),
        "Brush" to BrushPreset(25f, 90)
    )
    const val ERASER_WIDTH = 32f
    val ORDER = PRESETS.keys.toList()
}

object Palette {
    // name to ARGB color int, filled in at runtime from colors.xml via Context
}
