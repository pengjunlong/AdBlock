package com.pengjunlong.adblock.ui.region

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * 区域标记自定义 View
 *
 * 用户按下手指后开始拖拽，实时绘制半透明矩形选区；
 * 松手后通过 [onRegionSelected] 回调通知外部。
 *
 * 回调参数：矩形的中心点坐标（单位：屏幕像素）
 */
class RegionDrawView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 选区确定后的回调，参数为 (centerX, centerY, rect) */
    var onRegionSelected: ((cx: Float, cy: Float, rect: RectF) -> Unit)? = null

    private var startX = 0f
    private var startY = 0f
    private var endX   = 0f
    private var endY   = 0f
    private var isDragging = false
    private var hasRegion  = false

    /** 当前已选矩形（坐标归一化，left<right, top<bottom） */
    val selectedRect: RectF
        get() = RectF(
            minOf(startX, endX), minOf(startY, endY),
            maxOf(startX, endX), maxOf(startY, endY),
        )

    // ─── 画笔 ───────────────────────────────────────────────────────────────────

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 0, 200, 255)
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 0, 200, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
    }

    private val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 80, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    // ─── Touch ──────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x; startY = event.y
                endX   = event.x; endY   = event.y
                isDragging = true
                hasRegion  = false
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                endX = event.x; endY = event.y
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                endX = event.x; endY = event.y
                isDragging = false
                val rect = selectedRect
                // 最小 10×10 px 才算有效区域
                if (rect.width() >= 10f && rect.height() >= 10f) {
                    hasRegion = true
                    onRegionSelected?.invoke(rect.centerX(), rect.centerY(), rect)
                }
                invalidate()
            }
        }
        return true
    }

    // ─── Draw ───────────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!isDragging && !hasRegion) return

        val rect = selectedRect

        // 半透明填充
        canvas.drawRect(rect, fillPaint)
        // 虚线边框
        canvas.drawRect(rect, borderPaint)

        if (hasRegion) {
            // 中心十字准星
            val cx = rect.centerX()
            val cy = rect.centerY()
            val arm = 16f
            canvas.drawLine(cx - arm, cy, cx + arm, cy, crossPaint)
            canvas.drawLine(cx, cy - arm, cx, cy + arm, crossPaint)
        }
    }

    /** 清除当前选区 */
    fun clearRegion() {
        hasRegion  = false
        isDragging = false
        invalidate()
    }
}

