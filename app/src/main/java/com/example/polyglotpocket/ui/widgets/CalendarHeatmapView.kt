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
 * REQ. 3 (2D Graphics): Custom View rendering a GitHub-style calendar contribution heatmap.
 *
 * Drawn natively on an Android Canvas without any third-party charting libraries:
 *  - 2D grid matrix of 7 weekday rows (Monday through Sunday) x N week columns.
 *  - 5-tier intensity color ramp (pp_heat_0 to pp_heat_4) mapping study frequency.
 *  - Dynamic cell size calculation adapting to device width and system padding.
 *  - Day-of-week labels (Mon, Wed, Fri) vertically centered alongside corresponding rows.
 *  - Month transition labels (Jan, Feb, Mar...) dynamically placed along the top gutter.
 *  - Single reusable RectF instance and pre-allocated Paint objects to ensure 60 FPS performance.
 */
class CalendarHeatmapView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : View(context, attrs, defStyle) {

    // 5-level color ramp representing study volume:
    // Level 0 = no activity (faint gray/empty), Levels 1-4 = progressively deeper green hues
    private val ramp = intArrayOf(
        ContextCompat.getColor(context, R.color.pp_heat_0),
        ContextCompat.getColor(context, R.color.pp_heat_1),
        ContextCompat.getColor(context, R.color.pp_heat_2),
        ContextCompat.getColor(context, R.color.pp_heat_3),
        ContextCompat.getColor(context, R.color.pp_heat_4),
    )

    // Screen density scaling factor (ensures crisp rendering across different DPI screens)
    private val density = resources.displayMetrics.density
    private val gap = 3f * density           // Spacing between neighboring heatmap cells
    private val topGutter = 15f * density    // Top margin reserved for drawing month names (Jan, Feb...)
    private val leftGutter = 24f * density   // Left margin reserved for weekday labels (Mon, Wed, Fri)
    private val radius = 2.5f * density      // Rounded corner radius for each heatmap day square

    // Paint for drawing heatmap day rounded squares
    private val cellPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Paint for rendering month labels and weekday text
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6E6B82")
        textSize = 9.5f * density
    }

    // Single reusable RectF to avoid object allocation in onDraw()
    private val rect = RectF()

    // Reusable Calendar instance for calculating month transitions without allocation overhead
    private val cal = Calendar.getInstance()

    // Flattened array of activity intensity levels (0..4) in column-major order (7 rows per week)
    private var levels: IntArray = IntArray(0)
    private var weeks = 0
    private var startMonday = 0L // Epoch timestamp (ms) of the first Monday displayed

    init {
        // Sample preview data for Android Studio design editor & Navigation Graph
        if (isInEditMode) {
            weeks = 12
            levels = IntArray(12 * 7) { (it * 37) % 5 }
            startMonday = System.currentTimeMillis() - 12L * 7 * 86400000L
        }
    }

    /**
     * Updates the activity levels and starting timestamp, then requests layout recalculation
     * and triggers a Canvas redraw.
     *
     * @param levels Flattened array of intensity levels (size must be a multiple of 7).
     * @param startMondayMillis Milliseconds timestamp corresponding to the start of the first week column.
     */
    fun setData(levels: IntArray, startMondayMillis: Long) {
        this.levels = levels
        this.weeks = if (levels.isEmpty()) 0 else levels.size / 7
        this.startMonday = startMondayMillis
        requestLayout()
        invalidate()
    }

    /**
     * Measures the view dimension based on responsive cell sizing.
     * The height is computed strictly from the 7 rows + gutters + padding so there is no wasted space.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val cols = if (weeks > 0) weeks else CalendarWeeks
        val cell = cellSize(width, cols)
        // Total height = top padding + month gutter + (7 rows * cell size) + (6 inter-cell gaps) + bottom padding
        val height = (paddingTop + topGutter + 7 * cell + 6 * gap + paddingBottom).toInt()
        setMeasuredDimension(width, height)
    }

    /**
     * Calculates the width of each square cell so that the entire matrix fits exactly within
     * the available horizontal width minus gutters, padding, and gaps between columns.
     */
    private fun cellSize(width: Int, cols: Int): Float {
        val avail = width - paddingLeft - paddingRight - leftGutter - (cols - 1) * gap
        return (avail / cols).coerceAtLeast(1f)
    }

    override fun onDraw(canvas: Canvas) {
        if (weeks == 0) return
        val cell = cellSize(width, weeks)
        val gridLeft = paddingLeft + leftGutter
        val gridTop = paddingTop + topGutter

        // 1. Draw the 2D grid matrix (weeks columns x 7 day rows)
        for (col in 0 until weeks) {
            val x = gridLeft + col * (cell + gap)
            for (row in 0 until 7) {
                // Fetch level from flattened array; default to 0 and clamp to [0, 4]
                val lvl = levels.getOrElse(col * 7 + row) { 0 }.coerceIn(0, 4)
                cellPaint.color = ramp[lvl]
                val y = gridTop + row * (cell + gap)

                // Update reusable RectF coordinates and render rounded square
                rect.set(x, y, x + cell, y + cell)
                canvas.drawRoundRect(rect, radius, radius, cellPaint)
            }
        }

        // 2. Draw weekday labels (Mon, Wed, Fri only, matching GitHub's clean convention)
        textPaint.textAlign = Paint.Align.LEFT
        val rows = intArrayOf(0, 2, 4) // Indices corresponding to Monday (0), Wednesday (2), and Friday (4)
        val names = arrayOf("Mon", "Wed", "Fri")
        for (i in rows.indices) {
            // Vertically center text with respect to its corresponding cell using font metrics (ascent/descent)
            val y = gridTop + rows[i] * (cell + gap) + cell / 2 -
                (textPaint.ascent() + textPaint.descent()) / 2
            canvas.drawText(names[i], paddingLeft.toFloat(), y, textPaint)
        }

        // 3. Draw month labels along the top gutter whenever a week column crosses into a new month
        var lastMonth = -1
        val labelY = gridTop - 4f * density // Positioned just above the first row of cells
        for (col in 0 until weeks) {
            cal.timeInMillis = startMonday
            cal.add(Calendar.DAY_OF_YEAR, col * 7)
            val month = cal.get(Calendar.MONTH)
            // Only draw the month name when transitioning to a new month
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
