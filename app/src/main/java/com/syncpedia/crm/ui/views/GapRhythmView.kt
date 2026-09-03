package com.syncpedia.crm.ui.views

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.syncpedia.crm.R
import com.syncpedia.crm.models.GapSegment
import com.syncpedia.crm.models.GapSegmentType

class GapRhythmView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var segments: List<GapSegment> = emptyList()
    private var rangeStart = 0L
    private var rangeEnd = 1L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setData(segments: List<GapSegment>, startMs: Long, endMs: Long) {
        this.segments = segments
        rangeStart = startMs
        rangeEnd = endMs.coerceAtLeast(startMs + 1)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        val radius = h / 2f
        val span = (rangeEnd - rangeStart).toFloat().coerceAtLeast(1f)

        paint.color = ContextCompat.getColor(context, R.color.bg_card2)
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, radius, radius, paint)

        for (seg in segments) {
            val left = ((seg.startMs - rangeStart).toFloat() / span) * w
            val right = ((seg.endMs - rangeStart).toFloat() / span) * w
            paint.color = colorFor(seg.type)
            rect.set(left.coerceIn(0f, w), 0f, right.coerceIn(0f, w), h)
            if (rect.width() > 0.5f) {
                canvas.drawRect(rect, paint)
            }
        }
    }

    private fun colorFor(type: GapSegmentType): Int = ContextCompat.getColor(
        context,
        when (type) {
            GapSegmentType.SHORT_GAP -> R.color.divider
            GapSegmentType.MEDIUM_GAP -> R.color.status_contacted
            GapSegmentType.LONG_GAP -> R.color.syncpedia_orange
            GapSegmentType.TALKED -> R.color.syncpedia_teal
        }
    )
}
