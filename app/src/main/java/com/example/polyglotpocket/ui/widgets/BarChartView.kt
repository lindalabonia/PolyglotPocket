package com.example.polyglotpocket.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.polyglotpocket.R
import kotlin.math.max

/**
 * REQ. 3 (2D Graphics): Custom View rendering a weekly bar chart (Monday to Sunday).
 *
 * Drawn natively on an Android Canvas without third-party libraries:
 *  - Calculates responsive bar widths dynamically based on screen width and padding.
 *  - Normalizes bar heights proportionally against the maximum studied count of the week.
 *  - Empty days show a subtle rounded baseline nub so zero-activity days remain visible.
 *  - Reuses a single RectF instance and pre-allocated Paint objects to ensure 60 FPS rendering.
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // Screen density scaling factor
    private val density = resources.displayMetrics.density
    private val desiredHeight = (118 * density).toInt()

    // Paint for active days (primary purple/indigo brand color)
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pp_primary)
    }

    // Paint for empty/zero-activity days (subtle semi-transparent nub on the baseline)
    private val nubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#18000000")
    }

    // Paint for weekday labels ("M", "T", "W", "T", "F", "S", "S")
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6B82")
        textSize = 10.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }

    // Single reusable RectF to avoid object allocation in onDraw()
    private val rect = RectF()

    // 7 integer values representing card counts for Monday through Sunday
    private var values = IntArray(7)

    init {
        // Sample preview data for Android Studio design editor & Navigation Graph
        if (isInEditMode) {
            values = intArrayOf(5, 12, 8, 15, 20, 10, 18)
        }
    }

    /**
     * Updates the 7 weekly card counts and triggers a view redraw.
     */
    fun setData(values: IntArray) {
        this.values = if (values.size == 7) values else IntArray(7)
        invalidate() // Tells Android to request a fresh onDraw() call
    }

    /**
     * Measures view bounds: expands to full parent width with a fixed 118dp height.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec) else desiredHeight
        setMeasuredDimension(w, h)
    }

    /**
     * Renders the weekly bars and weekday labels directly onto the Canvas.
     */
    override fun onDraw(canvas: Canvas) {
        // 1. Establish drawing boundaries
        val labelH = 18f * density
        val top = paddingTop + 6f * density
        val baseline = height - paddingBottom - labelH
        val plotLeft = paddingLeft.toFloat()
        val plotWidth = width - paddingLeft - paddingRight
        if (baseline <= top || plotWidth <= 0) return

        // 2. Compute dynamic bar width: (available width - 6 gaps) / 7 days
        val gap = 12f * density
        val barWidth = (plotWidth - gap * 6) / 7f

        // 3. Find the maximum count to normalize vertical scaling
        val maxV = max(1, values.maxOrNull() ?: 0)
        val radius = 5f * density

        val names = arrayOf("M", "T", "W", "T", "F", "S", "S")

        // 4. Iterate over the 7 days and draw each bar
        for (i in 0 until 7) {
            val x = plotLeft + i * (barWidth + gap)
            val cx = x + barWidth / 2

            if (values[i] <= 0) {
                // Case A: 0 cards studied -> draw a small 3dp baseline nub
                rect.set(x, baseline - 3f * density, x + barWidth, baseline)
                canvas.drawRoundRect(rect, 1.5f * density, 1.5f * density, nubPaint)
            } else {
                // Case B: Active day -> compute proportional height: (cards / maxV) * available height
                val h = values[i].toFloat() / maxV * (baseline - top)
                rect.set(x, baseline - h, x + barWidth, baseline)
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }

            // 5. Draw the weekday initial centered underneath the bar
            canvas.drawText(names[i], cx, height - paddingBottom - 4f * density, labelPaint)
        }
    }
}
