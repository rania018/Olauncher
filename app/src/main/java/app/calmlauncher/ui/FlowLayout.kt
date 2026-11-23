package app.calmlauncher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lays its children out in a row and wraps to a new row only when the next one does not fit.
 * Used for chips: session lengths in "How long?" sit on one line, like "2 min  5 min  10 min".
 *
 * A ViewGroup positions its children in two passes: [onMeasure] asks each child how big it wants to
 * be and decides our own size, then [onLayout] places each child. Both passes walk the children
 * the same way. Right-to-left languages get mirrored rows.
 */
class FlowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ViewGroup(context, attrs) {

    var horizontalSpacing: Int = dp(8)
        set(value) { field = value; requestLayout() }

    var verticalSpacing: Int = dp(8)
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthMode = MeasureSpec.getMode(widthMeasureSpec)
        val maxWidth = if (widthMode == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE else {
            MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        }
        var x = 0
        var lineHeight = 0
        var usedWidth = 0
        var height = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            measureChild(child, widthMeasureSpec, heightMeasureSpec)
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (x > 0 && x + w > maxWidth) {
                height += lineHeight + verticalSpacing
                x = 0
                lineHeight = 0
            }
            x += w
            usedWidth = max(usedWidth, x)
            x += horizontalSpacing
            lineHeight = max(lineHeight, h)
        }
        height += lineHeight
        setMeasuredDimension(
            resolveSize(usedWidth + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(height + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxWidth = width - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = 0
        var y = paddingTop
        var lineHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val w = child.measuredWidth
            val h = child.measuredHeight
            if (x > 0 && x + w > maxWidth) {
                y += lineHeight + verticalSpacing
                x = 0
                lineHeight = 0
            }
            val left = if (rtl) width - paddingRight - x - w else paddingLeft + x
            child.layout(left, y, left + w, y + h)
            x += w + horizontalSpacing
            lineHeight = max(lineHeight, h)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()
}
