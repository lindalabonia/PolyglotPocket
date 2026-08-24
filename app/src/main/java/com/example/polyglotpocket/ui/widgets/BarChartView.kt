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
 * Weekly bar chart (Mon-Sun). Empty days show a faint baseline nub so the day
 * is still visible. 2D graphics for REQ. 3, Canvas-only.
 */
class BarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val desiredHeight = (118 * density).toInt()

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.pp_primary)
    }
    private val nubPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#18000000")
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6B82")
        textSize = 10.5f * density
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val rect = RectF()

    private var values = IntArray(7)

    fun setData(values: IntArray) {
        this.values = if (values.size == 7) values else IntArray(7)
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec) else desiredHeight
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val labelH = 18f * density
        val top = paddingTop + 6f * density
        val baseline = height - paddingBottom - labelH
        val plotLeft = paddingLeft.toFloat()
        val plotWidth = width - paddingLeft - paddingRight
        if (baseline <= top || plotWidth <= 0) return

        val gap = 12f * density
        val barWidth = (plotWidth - gap * 6) / 7f
        val maxV = max(1, values.maxOrNull() ?: 0)
        val radius = 5f * density

        val names = arrayOf("M", "T", "W", "T", "F", "S", "S")
        for (i in 0 until 7) {
            val x = plotLeft + i * (barWidth + gap)
            val cx = x + barWidth / 2
            if (values[i] <= 0) {
                rect.set(x, baseline - 3f * density, x + barWidth, baseline)
                canvas.drawRoundRect(rect, 1.5f * density, 1.5f * density, nubPaint)
            } else {
                val h = values[i].toFloat() / maxV * (baseline - top)
                rect.set(x, baseline - h, x + barWidth, baseline)
                canvas.drawRoundRect(rect, radius, radius, barPaint)
            }
            canvas.drawText(names[i], cx, height - paddingBottom - 4f * density, labelPaint)
        }
    }
}
