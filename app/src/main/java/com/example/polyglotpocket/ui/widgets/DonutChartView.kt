package com.example.polyglotpocket.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * REQ. 3 (2D Graphics): Custom View rendering a donut chart for accuracy statistics.
 *
 * Drawn natively on an Android Canvas without any third-party charting libraries:
 *  - Two concentric stroke arcs (base red ring for incorrect answers, green arc for correct answers).
 *  - Centered percentage text and label, vertically aligned via font metrics.
 *  - Pre-allocated Paint and RectF objects to avoid runtime allocations during onDraw (60 FPS smooth).
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // Screen density scaling factor (ensures crisp rendering on all DPIs)
    private val density = resources.displayMetrics.density
    private val stroke = 15f * density

    // Pre-allocated Paint objects (avoids garbage collection lag during onDraw)
    private val greenPaint = ringPaint("#2E9E5B") // Correct answers arc
    private val redPaint = ringPaint("#E5484D")   // Incorrect answers base ring

    // Paint for the large percentage text in the center
    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1B2B")
        textAlign = Paint.Align.CENTER
        textSize = 24f * density
        isFakeBoldText = true
    }

    // Paint for the small "correct" label under the percentage
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6B82")
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
        isFakeBoldText = true
    }

    // Reusable bounding box for drawing arcs
    private val oval = RectF()

    private var correct = 0
    private var wrong = 0

    init {
        // Sample preview data for Android Studio design editor & Navigation Graph
        if (isInEditMode) {
            correct = 42
            wrong = 8
        }
    }

    /**
     * Helper to configure stroke-style ring paints with anti-aliasing.
     */
    private fun ringPaint(hex: String) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE // Draws an open ring outline rather than a filled pie
        strokeWidth = stroke
        color = Color.parseColor(hex)
    }

    /**
     * Updates the chart data from study session statistics and triggers a redraw.
     */
    fun setData(correct: Int, wrong: Int) {
        this.correct = correct
        this.wrong = wrong
        invalidate() // Tells Android to clear the view and schedule a new onDraw() call
    }

    /**
     * Enforces a 1:1 aspect ratio so the donut chart remains perfectly circular.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec) else w
        setMeasuredDimension(w, h)
    }

    /**
     * Renders the donut chart primitives directly onto the Canvas.
     */
    override fun onDraw(canvas: Canvas) {
        // 1. Calculate center and radius based on available bounds
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f - stroke / 2f - 1f
        if (r <= 0) return
        oval.set(cx - r, cy - r, cx + r, cy + r)

        val total = correct + wrong

        // 2. Draw the base ring in red (representing total attempts)
        canvas.drawArc(oval, 0f, 360f, false, redPaint)

        // 3. Draw the green arc on top, proportional to correct answers
        if (total > 0) {
            val sweep = correct.toFloat() / total * 360f
            // -90 degrees starts drawing from the top (12 o'clock position)
            canvas.drawArc(oval, -90f, sweep, false, greenPaint)
        }

        // 4. Calculate accuracy percentage
        val pct = if (total > 0) Math.round(correct * 100.0 / total).toInt() else 0

        // 5. Vertically center the text using font ascent/descent metrics
        val baseY = cy - (bigText.ascent() + bigText.descent()) / 2 - 6f * density
        canvas.drawText("$pct%", cx, baseY, bigText)
        canvas.drawText("correct", cx, baseY + 15f * density, smallText)
    }
}
