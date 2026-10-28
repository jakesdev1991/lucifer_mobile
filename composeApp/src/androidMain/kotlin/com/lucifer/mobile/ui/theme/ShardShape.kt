package com.lucifer.mobile.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The obsidian shard: an octagon whose corners are cut at 45°.
 *
 * Every glass panel, card, and dialog in the app uses this shape, which is why the interface reads
 * as one material rather than a set of rounded rectangles. The cut is proportional to the shorter
 * side, so a 40dp chip and a full-height card belong to the same family.
 */
class ShardShape(
    private val cut: Dp = Morningstar.Cut,
    private val spec: ChamferSpec? = null
) : Shape {

    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        if (size.width <= 0f || size.height <= 0f) return Outline.Rectangle(Rect(Offset.Zero, size))
        val points = if (spec != null) {
            Chamfer.outline(size.width, size.height, spec)
        } else {
            val px = with(density) { cut.toPx() }
            Chamfer.outline(size.width, size.height, Chamfer.all(px))
        }
        if (points.isEmpty()) return Outline.Rectangle(Rect(Offset.Zero, size))
        return Outline.Generic(points.toShardPath())
    }

    override fun toString(): String = "ShardShape(cut=$cut)"
}

fun shard(cut: Dp = Morningstar.Cut): Shape = ShardShape(cut)

/** Zero-cut shard: a hard rectangle, used where a corner would waste space. */
fun hardEdge(): Shape = RoundedCornerShape(0.dp)

fun List<Point>.toShardPath(): Path = Path().apply {
    if (isEmpty()) return@apply
    moveTo(this@toShardPath[0].x, this@toShardPath[0].y)
    for (index in 1 until size) lineTo(this@toShardPath[index].x, this@toShardPath[index].y)
    close()
}
