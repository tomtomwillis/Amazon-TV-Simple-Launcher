package xyz.bodyparts.home

import android.content.Context
import android.graphics.Rect
import android.widget.HorizontalScrollView

/**
 * A horizontally scrolling row that keeps the selected tile inside the row's side
 * padding. Android's own rule only scrolls until a tile is visible, so the first tile
 * could end up flush with the screen edge (out of line with the row below) and the
 * last tile, grown by its focus animation, could be cut off.
 */
class RowScroller(context: Context) : HorizontalScrollView(context) {

    init {
        isHorizontalScrollBarEnabled = false
        clipChildren = false
        clipToPadding = false
    }

    override fun computeScrollDeltaToGetChildRectOnScreen(rect: Rect): Int {
        val row = getChildAt(0) ?: return 0
        val start = row.paddingStart
        val end = row.paddingEnd
        val target = when {
            rect.left - start < scrollX -> rect.left - start
            rect.right + end > scrollX + width -> rect.right + end - width
            else -> scrollX
        }
        return target.coerceIn(0, maxOf(0, row.width - width)) - scrollX
    }
}
