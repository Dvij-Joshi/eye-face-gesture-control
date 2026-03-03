package com.example.eyeandfacegesturephonecontrol.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.PixelFormat
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import androidx.lifecycle.lifecycleScope
import com.example.eyeandfacegesturephonecontrol.R
import com.example.eyeandfacegesturephonecontrol.common.GestureEventBus
import com.example.eyeandfacegesturephonecontrol.gestures.GestureEvent
import com.example.eyeandfacegesturephonecontrol.gestures.ScrollDirection
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

/**
 * Accessibility service that performs system-level actions based on detected gestures
 * Receives gestures from FaceTrackingService via GestureEventBus
 */
class EyeControlAccessibilityService : AccessibilityService() {
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var windowManager: WindowManager
    private lateinit var displayMetrics: DisplayMetrics
    private var cursorView: View? = null
    
    companion object {
        private const val TAG = "EyeControlAccessibility"
    }
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Accessibility service connected")
        
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        displayMetrics = resources.displayMetrics
        
        // Start listening to gesture events
        startGestureListener()
        
        // Show cursor overlay
        showCursorOverlay()
    }
    
    /**
     * Listen to gesture events from GestureEventBus
     */
    private fun startGestureListener() {
        serviceScope.launch {
            GestureEventBus.gestureEvents.collectLatest { event ->
                handleGesture(event)
            }
        }
        
        // Listen to cursor position updates
        serviceScope.launch {
            GestureEventBus.cursorPosition.collectLatest { position ->
                updateCursorPosition(position.x, position.y)
            }
        }
    }
    
    /**
     * Handle detected gestures
     */
    private fun handleGesture(event: GestureEvent) {
        Log.e("AccessibilityDebug", "🔵 EVENT RECEIVED: ${event.javaClass.simpleName}")
        
        when (event) {
            is GestureEvent.Click -> {
                Log.e("AccessibilityDebug", "🔵 Performing CLICK (Squint)")
                performClick()
            }
            is GestureEvent.LongPress -> {
                Log.e("AccessibilityDebug", "🔵 Performing LONG PRESS")
                performLongPress()
            }
            is GestureEvent.DoubleBlink -> {
                Log.e("AccessibilityDebug", "🔵 Performing RECENT APPS") 
                performRecentApps()
            }
            is GestureEvent.WinkLeft -> {
                Log.e("AccessibilityDebug", "🔵 Performing BACK")
                performBack()
            }
            is GestureEvent.WinkRight -> {
                Log.e("AccessibilityDebug", "🔵 Performing HOME")
                performHome()
            }
            is GestureEvent.Scroll -> {
                Log.e("AccessibilityDebug", "🔵 Performing SCROLL: ${event.direction}")
                performScroll(event.direction, event.velocity)
            }
            is GestureEvent.VoiceInput -> {
                Log.e("AccessibilityDebug", "🔵 Performing VOICE INPUT")
                performVoiceInput()
            }
            is GestureEvent.CursorMove -> {
                // Cursor updates are handled in separate flow listener, no log needed here to avoid spam
            }
        }
    }
    
    /**
     * Perform click at current cursor position
     */
    private fun performClick() {
        Log.d(TAG, "Performing click")
        val cursorPos = GestureEventBus.cursorPosition.value
        val path = Path().apply {
            moveTo(
                cursorPos.x * displayMetrics.widthPixels,
                cursorPos.y * displayMetrics.heightPixels
            )
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
            .build()
        
        dispatchGesture(gesture, null, null)
    }
    
    /**
     * Perform long press at current cursor position
     */
    private fun performLongPress() {
        Log.d(TAG, "Performing long press")
        val cursorPos = GestureEventBus.cursorPosition.value
        val path = Path().apply {
            moveTo(
                cursorPos.x * displayMetrics.widthPixels,
                cursorPos.y * displayMetrics.heightPixels
            )
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 1000))  // 1 second
            .build()
        
        dispatchGesture(gesture, null, null)
    }
    
    /**
     * Navigate back
     */
    private fun performBack() {
        Log.d(TAG, "Performing back")
        performGlobalAction(GLOBAL_ACTION_BACK)
    }
    
    /**
     * Navigate home
     */
    private fun performHome() {
        Log.d(TAG, "Performing home")
        performGlobalAction(GLOBAL_ACTION_HOME)
    }
    
    /**
     * Open recent apps
     */
    private fun performRecentApps() {
        Log.d(TAG, "Performing recent apps")
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }
    
    /**
     * Perform scroll gesture
     */
    /**
     * Perform scroll gesture via swipe
     */
    private fun performScroll(direction: ScrollDirection, velocity: Float) {
        Log.d(TAG, "Performing scroll: $direction, velocity: $velocity")
        
        val centerX = displayMetrics.widthPixels / 2f
        val centerY = displayMetrics.heightPixels / 2f
        val scrollDistance = displayMetrics.heightPixels * 0.3f // Scroll 30% of screen
        
        val path = Path()
        
        // Scroll DOWN (content moves up) -> Swipe Up (Bottom to Top)
        // Scroll UP (content moves down) -> Swipe Down (Top to Bottom)
        // Adjust based on user expectation. Usually "Scroll Down" means "Go to bottom" -> Content moves Up -> Swipe Up.
        
        if (direction == ScrollDirection.DOWN) {
            // Swipe Up
            path.moveTo(centerX, centerY + scrollDistance/2)
            path.lineTo(centerX, centerY - scrollDistance/2)
        } else {
            // Swipe Down
            path.moveTo(centerX, centerY - scrollDistance/2)
            path.lineTo(centerX, centerY + scrollDistance/2)
        }
        
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300)) // 300ms duration
            .build()
            
        dispatchGesture(gesture, null, null)
    }
    
    /**
     * Trigger voice input
     */
    private fun performVoiceInput() {
        Log.d(TAG, "Voice input triggered")
        val intent = android.content.Intent(android.content.Intent.ACTION_VOICE_COMMAND).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch voice command", e)
        }
    }
    
    /**
     * Show cursor overlay
     */
    private fun showCursorOverlay() {
        val layoutParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        
        cursorView = com.example.eyeandfacegesturephonecontrol.ui.CursorOverlayView(this)
        windowManager.addView(cursorView, layoutParams)
    }
    
    /**
     * Update cursor position on screen
     */
    private fun updateCursorPosition(normalizedX: Float, normalizedY: Float) {
        (cursorView as? com.example.eyeandfacegesturephonecontrol.ui.CursorOverlayView)?.updatePosition(normalizedX, normalizedY)
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used in this implementation
    }
    
    override fun onInterrupt() {
        Log.i(TAG, "Service interrupted")
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        
        // Remove cursor overlay
        cursorView?.let { windowManager.removeView(it) }
        
        // Cancel coroutines
        serviceScope.cancel()
    }
}
