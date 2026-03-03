package com.example.eyeandfacegesturephonecontrol.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.view.PreviewView
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Debug camera preview floating window.
 *
 * Shows a small draggable camera preview with:
 *   - Live camera feed (via CameraX PreviewView)
 *   - Face mesh landmark overlay
 *   - Blendshape values + head angles debug HUD
 *
 * Toggled on/off from the FaceTrackingService notification.
 */
class DebugPreviewOverlay(private val context: Context) {

    companion object {
        private const val TAG = "DebugPreview"
        private const val PREVIEW_WIDTH_DP = 160
        private const val PREVIEW_HEIGHT_DP = 220
    }

    private var windowManager: WindowManager? = null
    private var rootView: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var meshOverlay: FaceMeshOverlayView? = null
    private var debugText: TextView? = null
    private var isShowing = false

    // Touch drag state
    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var layoutParams: WindowManager.LayoutParams? = null

    /**
     * Returns the internal PreviewView so CameraManager can bind to it.
     */
    fun getPreviewView(): PreviewView? = previewView

    /**
     * Check if overlay permission is granted.
     */
    fun hasOverlayPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else true
    }

    /**
     * Show the debug preview overlay.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (isShowing) return
        if (!hasOverlayPermission()) {
            Log.w(TAG, "No overlay permission — cannot show debug preview")
            return
        }

        windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val density = context.resources.displayMetrics.density
        val widthPx = (PREVIEW_WIDTH_DP * density).toInt()
        val heightPx = (PREVIEW_HEIGHT_DP * density).toInt()

        // Build the layout programmatically
        rootView = FrameLayout(context).apply {
            setBackgroundColor(Color.argb(200, 0, 0, 0))
        }

        // Camera preview
        previewView = PreviewView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                height = (heightPx * 0.7f).toInt()
            }
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FIT_CENTER
        }
        rootView!!.addView(previewView)

        // Face mesh overlay on top of camera
        meshOverlay = FaceMeshOverlayView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (heightPx * 0.7f).toInt()
            )
        }
        rootView!!.addView(meshOverlay)

        // Debug text below camera
        debugText = TextView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = (heightPx * 0.7f).toInt()
            }
            setTextColor(Color.GREEN)
            textSize = 8f  // sp
            setPadding(6, 4, 6, 4)
            maxLines = 6
            text = "Debug: waiting..."
        }
        rootView!!.addView(debugText)

        // Window layout params — floating, draggable
        layoutParams = WindowManager.LayoutParams(
            widthPx,
            heightPx,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 20
            y = 100
        }

        // Make draggable
        rootView!!.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = this.layoutParams!!.x
                    initialY = this.layoutParams!!.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    this.layoutParams!!.x = initialX + (event.rawX - initialTouchX).toInt()
                    this.layoutParams!!.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(rootView, this.layoutParams)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager?.addView(rootView, this.layoutParams)
            isShowing = true
            Log.i(TAG, "Debug preview overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show debug preview", e)
        }
    }

    /**
     * Hide and remove the debug preview overlay.
     */
    fun hide() {
        if (!isShowing) return
        try {
            rootView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove debug preview", e)
        }
        previewView = null
        meshOverlay = null
        debugText = null
        rootView = null
        isShowing = false
        Log.i(TAG, "Debug preview overlay hidden")
    }

    fun isVisible(): Boolean = isShowing

    /**
     * Update the face mesh overlay with new detection results.
     */
    fun updateFaceResults(result: FaceLandmarkerResult) {
        meshOverlay?.setResults(result, 640, 480, 0)
    }

    /**
     * Update the debug HUD text with current tracking data.
     */
    fun updateDebugInfo(
        yaw: Float,
        pitch: Float,
        jawOpen: Float,
        browInnerUp: Float,
        blinkR: Float,
        blinkL: Float,
        cursorX: Float,
        cursorY: Float
    ) {
        debugText?.post {
            debugText?.text = String.format(
                "Yaw:%.1f Pitch:%.1f\njaw:%.2f brow:%.2f\nblkR:%.2f blkL:%.2f\ncur:%.2f,%.2f",
                yaw, pitch, jawOpen, browInnerUp, blinkR, blinkL, cursorX, cursorY
            )
        }
    }
}
