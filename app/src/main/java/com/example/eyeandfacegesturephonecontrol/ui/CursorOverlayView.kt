package com.example.eyeandfacegesturephonecontrol.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PointF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.example.eyeandfacegesturephonecontrol.R

/**
 * Custom view for drawing the eye control cursor and visual feedback
 */
class CursorOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.cursor_normal)
    }

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = resources.getDimension(R.dimen.cursor_stroke_width)
        color = ContextCompat.getColor(context, R.color.cursor_ring)
    }

    private var cursorPosition = PointF(0f, 0f)
    private var cursorSize = resources.getDimension(R.dimen.cursor_size_default)
    private var ringSize = resources.getDimension(R.dimen.cursor_ring_size_default)
    
    // Smooth movement interpolation
    private var targetPosition = PointF(0f, 0f)
    private val smoothingFactor = 0.3f

    init {
        // Ensure the view is transparent
        setBackgroundColor(ContextCompat.getColor(context, android.R.color.transparent))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        // Interpolate position for smoothness (simple lerp for now, could use Choreographer)
        cursorPosition.x += (targetPosition.x - cursorPosition.x) * smoothingFactor
        cursorPosition.y += (targetPosition.y - cursorPosition.y) * smoothingFactor

        // Draw cursor
        canvas.drawCircle(cursorPosition.x, cursorPosition.y, cursorSize / 2f, cursorPaint)
        canvas.drawCircle(cursorPosition.x, cursorPosition.y, ringSize / 2f, ringPaint)
        
        // Request next frame if still moving significantly
        if (Math.abs(targetPosition.x - cursorPosition.x) > 1f || 
            Math.abs(targetPosition.y - cursorPosition.y) > 1f) {
            invalidate()
        }
    }

    /**
     * Update target cursor position (normalized 0..1)
     */
    fun updatePosition(normalizedX: Float, normalizedY: Float) {
        val screenWidth = resources.displayMetrics.widthPixels.toFloat()
        val screenHeight = resources.displayMetrics.heightPixels.toFloat()
        
        targetPosition.x = normalizedX * screenWidth
        targetPosition.y = normalizedY * screenHeight
        
        invalidate()
    }
    
    /**
     * Update cursor size
     */
    fun setCursorSize(sizeDp: Float) {
         // TODO: Implement resizing logic
    }
    
    /**
     * Show click animation
     */
    fun performClickAnimation() {
        val animator = ValueAnimator.ofFloat(ringSize / 2f, ringSize).apply {
            duration = 300
            addUpdateListener { animation ->
                val animatedValue = animation.animatedValue as Float
                // Draw ripple effect? For now just invalidate
                invalidate()
            }
        }
        animator.start()
    }
}
