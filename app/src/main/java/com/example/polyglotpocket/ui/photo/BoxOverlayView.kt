package com.example.polyglotpocket.ui.photo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.polyglotpocket.data.DetectedObject

/**
 * Draws the detected object boxes over the photo and lets the user tap to select
 * them. The boxes come normalized (0..1) over the image, so they are mapped onto
 * the letterboxed rectangle where a fitCenter ImageView actually draws the bitmap.
 */
class BoxOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var objects: List<DetectedObject> = emptyList()
    private var imageWidth = 1
    private var imageHeight = 1
    private val selected = mutableSetOf<Int>()

    // Enable/disable tapping (locked once the user presses OK).
    var selectable = true

    private val density = resources.displayMetrics.density

    private val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * density
        color = Color.parseColor("#FF5722")
    }
    private val selectedBoxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * density
        color = Color.parseColor("#4CAF50")
    }
    private val selectedFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.parseColor("#4C4CAF50") // green, ~30% alpha
    }
    private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CC000000")
    }
    private val labelTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 13 * density
    }

    fun setObjects(objects: List<DetectedObject>, imageWidth: Int, imageHeight: Int) {
        this.objects = objects
        this.imageWidth = imageWidth.coerceAtLeast(1)
        this.imageHeight = imageHeight.coerceAtLeast(1)
        selected.clear()
        invalidate()
    }

    /** English names of the currently selected boxes. */
    fun selectedNames(): List<String> = objects.filterIndexed { i, _ -> i in selected }.map { it.name }

    fun hasSelection(): Boolean = selected.isNotEmpty()

    /** The rectangle where the bitmap is actually drawn (fitCenter letterboxing). */
    private fun imageRect(): RectF {
        val scale = minOf(width.toFloat() / imageWidth, height.toFloat() / imageHeight)
        val drawnW = imageWidth * scale
        val drawnH = imageHeight * scale
        val left = (width - drawnW) / 2f
        val top = (height - drawnH) / 2f
        return RectF(left, top, left + drawnW, top + drawnH)
    }

    private fun boxRect(obj: DetectedObject, area: RectF): RectF {
        val left = area.left + obj.x * area.width()
        val top = area.top + obj.y * area.height()
        return RectF(left, top, left + obj.w * area.width(), top + obj.h * area.height())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val area = imageRect()
        objects.forEachIndexed { i, obj ->
            val rect = boxRect(obj, area)
            val isSelected = i in selected
            if (isSelected) canvas.drawRect(rect, selectedFillPaint)
            canvas.drawRect(rect, if (isSelected) selectedBoxPaint else boxPaint)

            // Label sits just above the box (or inside if it would go off the top).
            val padding = 4 * density
            val textW = labelTextPaint.measureText(obj.name)
            val textH = labelTextPaint.textSize
            var labelTop = rect.top - (textH + padding * 2)
            if (labelTop < area.top) labelTop = rect.top
            canvas.drawRect(
                rect.left, labelTop, rect.left + textW + padding * 2, labelTop + textH + padding * 2,
                labelBgPaint
            )
            canvas.drawText(obj.name, rect.left + padding, labelTop + textH + padding / 2, labelTextPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!selectable) return super.onTouchEvent(event)
        when (event.action) {
            // Consume DOWN so the view keeps receiving the gesture up to UP.
            MotionEvent.ACTION_DOWN -> return true
            MotionEvent.ACTION_UP -> {
                val area = imageRect()
                // Smallest matching box wins, so a box nested inside another is reachable.
                val hit = objects.indices
                    .filter { boxRect(objects[it], area).contains(event.x, event.y) }
                    .minByOrNull { objects[it].w * objects[it].h }
                if (hit != null) {
                    if (hit in selected) selected.remove(hit) else selected.add(hit)
                    invalidate()
                    performClick()
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
