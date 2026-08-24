package com.example.polyglotpocket.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Correct-vs-wrong donut with the correct share written in the middle.
 * 2D graphics for REQ. 3, Canvas-only.
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val stroke = 15f * density

    private val greenPaint = ringPaint("#2E9E5B")
    private val redPaint = ringPaint("#E5484D")

    private val bigText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1C1B2B")
        textAlign = Paint.Align.CENTER
        textSize = 24f * density
        isFakeBoldText = true
    }
    private val smallText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6B82")
        textAlign = Paint.Align.CENTER
        textSize = 10.5f * density
        isFakeBoldText = true
    }
    private val oval = RectF()

    private var correct = 0
    private var wrong = 0

    private fun ringPaint(hex: String) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = Color.parseColor(hex)
    }

    fun setData(correct: Int, wrong: Int) {
        this.correct = correct
        this.wrong = wrong
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec) else w
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val size = minOf(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        val r = size / 2f - stroke / 2f - 1f
        if (r <= 0) return
        oval.set(cx - r, cy - r, cx + r, cy + r)

        val total = correct + wrong
        // Base ring is the "wrong" colour; the correct arc is drawn on top.
        canvas.drawArc(oval, 0f, 360f, false, redPaint)
        if (total > 0) {
            val sweep = correct.toFloat() / total * 360f
            canvas.drawArc(oval, -90f, sweep, false, greenPaint)
        }

        val pct = if (total > 0) Math.round(correct * 100.0 / total).toInt() else 0
        val baseY = cy - (bigText.ascent() + bigText.descent()) / 2 - 6f * density
        canvas.drawText("$pct%", cx, baseY, bigText)
        canvas.drawText("correct", cx, baseY + 15f * density, smallText)
    }
}
