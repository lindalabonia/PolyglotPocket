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
import java.util.Calendar

/**
 * GitHub-style contribution grid: 7 weekday rows (Mon-Sun) x N week columns.
 * Draws short month labels along the top and Mon/Wed/Fri labels on the left.
 * 2D graphics for REQ. 3, drawn on a Canvas with no external library.
 */
class CalendarHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    private val ramp = intArrayOf(
        ContextCompat.getColor(context, R.color.pp_heat_0),
        ContextCompat.getColor(context, R.color.pp_heat_1),
        ContextCompat.getColor(context, R.color.pp_heat_2),
        ContextCompat.getColor(context, R.color.pp_heat_3),
        ContextCompat.getColor(context, R.color.pp_heat_4),
    )

    private val density = resources.displayMetrics.density
    private val gap = 3f * density
    private val topGutter = 15f * density    // room for month labels
    private val leftGutter = 24f * density   // room for weekday labels
    private val radius = 2.5f * density

    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6B82")
        textSize = 9.5f * density
    }
    private val rect = RectF()
    private val cal = Calendar.getInstance()

    private var levels: IntArray = IntArray(0)
    private var weeks = 0
    private var startMonday = 0L

    init {
        if (isInEditMode) {
            weeks = 12
            levels = IntArray(12 * 7) { (it * 37) % 5 }
            startMonday = System.currentTimeMillis() - 12L * 7 * 86400000L
        }
    }

    fun setData(levels: IntArray, startMondayMillis: Long) {
        this.levels = levels
        this.weeks = if (levels.isEmpty()) 0 else levels.size / 7
        this.startMonday = startMondayMillis
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cols = if (weeks > 0) weeks else CalendarWeeks
        val cell = cellSize(width, cols)
        val height = (paddingTop + topGutter + 7 * cell + 6 * gap + paddingBottom).toInt()
        setMeasuredDimension(width, height)
    }

    private fun cellSize(width: Int, cols: Int): Float {
        val avail = width - paddingLeft - paddingRight - leftGutter - (cols - 1) * gap
        return (avail / cols).coerceAtLeast(1f)
    }

    override fun onDraw(canvas: Canvas) {
        if (weeks == 0) return
        val cell = cellSize(width, weeks)
        val gridLeft = paddingLeft + leftGutter
        val gridTop = paddingTop + topGutter

        for (col in 0 until weeks) {
            val x = gridLeft + col * (cell + gap)
            for (row in 0 until 7) {
                val lvl = levels.getOrElse(col * 7 + row) { 0 }.coerceIn(0, 4)
                cellPaint.color = ramp[lvl]
                val y = gridTop + row * (cell + gap)
                rect.set(x, y, x + cell, y + cell)
                canvas.drawRoundRect(rect, radius, radius, cellPaint)
            }
        }

        // Weekday labels (only Mon / Wed / Fri, like GitHub).
        textPaint.textAlign = Paint.Align.LEFT
        val rows = intArrayOf(0, 2, 4)
        val names = arrayOf("Mon", "Wed", "Fri")
        for (i in rows.indices) {
            val y = gridTop + rows[i] * (cell + gap) + cell / 2 -
                (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(names[i], paddingLeft.toFloat(), y, textPaint)
        }

        // Month labels along the top, drawn when the month changes.
        var lastMonth = -1
        val labelY = gridTop - 4f * density
        for (col in 0 until weeks) {
            cal.timeInMillis = startMonday
            cal.add(Calendar.DAY_OF_YEAR, col * 7)
            val month = cal.get(Calendar.MONTH)
            if (month != lastMonth) {
                lastMonth = month
                canvas.drawText(MONTHS[month], gridLeft + col * (cell + gap), labelY, textPaint)
            }
        }
    }

    companion object {
        private const val CalendarWeeks = 16
        private val MONTHS = arrayOf(
            "Jan", "Feb", "Mar", "Apr", "May", "Jun",
            "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
        )
    }
}
