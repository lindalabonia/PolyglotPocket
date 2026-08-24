package com.example.polyglotpocket.ui.widgets

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.polyglotpocket.R

/**
 * Accuracy line chart: y-axis is % correct (labelled min/max), x-axis is time
 * (older sessions on the left, latest on the right). Soft area fill and a
 * highlighted last point. 2D graphics for REQ. 3, Canvas-only.
 */
class LineChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val density = resources.displayMetrics.density
    private val desiredHeight = (134 * density).toInt()
    private val leftGutter = 30f * density    // y-axis % labels
    private val bottomGutter = 16f * density   // x-axis labels
    private val topPad = 18f * density         // last-value label
    private val primary = ContextCompat.getColor(context, R.color.pp_primary)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.3f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = primary
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = primary
        alpha = 32
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1f
        color = Color.parseColor("#14000000")
    }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = primary }
    private val dotRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val valueLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = primary
        textSize = 11.5f * density
        textAlign = Paint.Align.RIGHT
        isFakeBoldText = true
    }
    private val axisLabel = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6B82")
        textSize = 9.5f * density
        isFakeBoldText = true
    }

    private val linePath = Path()
    private val fillPath = Path()
    private var values: FloatArray = FloatArray(0)

    fun setData(values: FloatArray) {
        this.values = values
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY)
            MeasureSpec.getSize(heightMeasureSpec) else desiredHeight
        setMeasuredDimension(w, h)
    }

    override fun onDraw(canvas: Canvas) {
        val n = values.size
        if (n == 0) return

        val left = paddingLeft + leftGutter
        val right = width - paddingRight - 6f * density
        val top = paddingTop + topPad
        val bottom = height - paddingBottom - bottomGutter
        if (right <= left || bottom <= top) return

        var minV = values.minOrNull() ?: return
        var maxV = values.maxOrNull() ?: return
        minV = (minV - 5f).coerceAtLeast(0f)
        maxV = (maxV + 5f).coerceAtMost(100f)
        if (maxV - minV < 1f) { minV = (minV - 5f).coerceAtLeast(0f); maxV = minV + 10f }

        fun xAt(i: Int) = if (n == 1) (left + right) / 2 else left + i * (right - left) / (n - 1)
        fun yAt(v: Float) = bottom - (v - minV) / (maxV - minV) * (bottom - top)

        // y-axis: top/bottom gridlines with their % values.
        canvas.drawLine(left, top, right, top, gridPaint)
        canvas.drawLine(left, bottom, right, bottom, gridPaint)
        val yLabelX = paddingLeft + leftGutter - 6f * density
        val half = (axisLabel.descent() + axisLabel.ascent()) / 2
        axisLabel.textAlign = Paint.Align.RIGHT
        canvas.drawText("${Math.round(maxV)}%", yLabelX, top - half, axisLabel)
        canvas.drawText("${Math.round(minV)}%", yLabelX, bottom - half, axisLabel)

        // x-axis labels: older on the left, latest on the right.
        val xLabelY = height - paddingBottom - 3f * density
        axisLabel.textAlign = Paint.Align.LEFT
        canvas.drawText("Older", left, xLabelY, axisLabel)
        axisLabel.textAlign = Paint.Align.RIGHT
        canvas.drawText("Latest", right, xLabelY, axisLabel)

        if (n == 1) {
            val x = xAt(0); val y = yAt(values[0])
            canvas.drawCircle(x, y, 4.5f * density, dotRing)
            canvas.drawCircle(x, y, 3f * density, dotFill)
            return
        }

        linePath.reset()
        fillPath.reset()
        for (i in 0 until n) {
            val x = xAt(i); val y = yAt(values[i])
            if (i == 0) { linePath.moveTo(x, y); fillPath.moveTo(x, bottom); fillPath.lineTo(x, y) }
            else { linePath.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo(xAt(n - 1), bottom)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(linePath, linePaint)

        // A dot per session, so the number of sessions is always visible even
        // when the line is flat.
        for (i in 0 until n) {
            val x = xAt(i); val y = yAt(values[i])
            canvas.drawCircle(x, y, 3.1f * density, dotRing)
            canvas.drawCircle(x, y, 2f * density, dotFill)
        }

        // Emphasize the latest session.
        val lastX = xAt(n - 1); val lastY = yAt(values[n - 1])
        canvas.drawCircle(lastX, lastY, 4.4f * density, dotRing)
        canvas.drawCircle(lastX, lastY, 3f * density, dotFill)
        canvas.drawText("${Math.round(values[n - 1])}%", right, top - 6f * density, valueLabel)
    }
}
