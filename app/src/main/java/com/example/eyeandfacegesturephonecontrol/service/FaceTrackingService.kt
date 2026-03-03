package com.example.eyeandfacegesturephonecontrol.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.example.eyeandfacegesturephonecontrol.R
import com.example.eyeandfacegesturephonecontrol.camera.CameraManager
import com.example.eyeandfacegesturephonecontrol.common.GestureEventBus
import com.example.eyeandfacegesturephonecontrol.gestures.CalibrationData
import com.example.eyeandfacegesturephonecontrol.gestures.GestureRecognizer
import com.example.eyeandfacegesturephonecontrol.ui.DebugPreviewOverlay
import com.example.eyeandfacegesturephonecontrol.vision.FaceMeshDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.*

/**
 * Foreground service for face tracking and gesture detection
 * Runs continuously in the background processing camera frames
 */
class FaceTrackingService : LifecycleService() {
    
    private lateinit var cameraManager: CameraManager
    private lateinit var faceMeshDetector: FaceMeshDetector
    private lateinit var gestureRecognizer: GestureRecognizer
    private lateinit var calibrationData: CalibrationData
    
    // Debug preview overlay
    private var debugOverlay: DebugPreviewOverlay? = null
    private var debugFrameCount = 0
    
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    companion object {
        private const val TAG = "FaceTrackingService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "eye_control_service_channel"
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_UPDATE_CONFIG = "ACTION_UPDATE_CONFIG"
        const val ACTION_TOGGLE_DEBUG = "ACTION_TOGGLE_DEBUG"
    }
    
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "Service created")
        
        // Load calibration data
        calibrationData = CalibrationData.load(this)
        
        // Initialize MediaPipe face detector
        faceMeshDetector = FaceMeshDetector(
            context = this,
            onResults = { result, timestamp -> handleFaceLandmarks(result, timestamp) },
            onError = { error -> handleError(error) }
        )
        faceMeshDetector.initialize()
        
        // Initialize gesture recognizer
        gestureRecognizer = GestureRecognizer(calibrationData)
        
        // Initialize camera
        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            onFrameAvailable = { imageProxy -> processFrame(imageProxy) },
            onError = { error -> handleError(error) }
        )
        
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        when (intent?.action) {
            ACTION_START -> {
                startForegroundService()
                startTracking()
            }
            ACTION_STOP -> {
                stopTracking()
                stopForeground(true)
                stopSelf()
            }
            ACTION_UPDATE_CONFIG -> {
                Log.i(TAG, "Reloading configuration")
                calibrationData = CalibrationData.load(this)
                gestureRecognizer = GestureRecognizer(calibrationData)
            }
            ACTION_TOGGLE_DEBUG -> {
                toggleDebugPreview()
            }
        }
        
        return START_STICKY
    }
    
    /**
     * Toggle the debug camera preview overlay on/off
     */
    private fun toggleDebugPreview() {
        if (debugOverlay?.isVisible() == true) {
            debugOverlay?.hide()
            debugOverlay = null
            Log.i(TAG, "Debug preview OFF")
        } else {
            debugOverlay = DebugPreviewOverlay(this)
            if (debugOverlay!!.hasOverlayPermission()) {
                debugOverlay!!.show()
                
                // Rebind camera with the debug preview surface
                restartCameraWithPreview()
                
                Log.i(TAG, "Debug preview ON")
            } else {
                Log.w(TAG, "No overlay permission for debug preview")
                debugOverlay = null
            }
        }
        // Update notification to reflect toggle state
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, createNotification())
    }
    
    /**
     * Restart camera binding to include the debug preview surface
     */
    private fun restartCameraWithPreview() {
        cameraManager.stopCamera()
        val previewView = debugOverlay?.getPreviewView()
        cameraManager.startCamera(previewView)
    }
    
    /**
     * Start the foreground service with notification
     */
    private fun startForegroundService() {
        val notification = createNotification()
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        
        // Update service state in EventBus
        GestureEventBus.updateServiceState(
            com.example.eyeandfacegesturephonecontrol.common.ServiceState(isRunning = true)
        )
    }
    
    /**
     * Start tracking (camera and detection)
     */
    private fun startTracking() {
        cameraManager.startCamera(previewView = null)  // No preview in background service
    }
    
    /**
     * Stop tracking
     */
    private fun stopTracking() {
        cameraManager.stopCamera()
        debugOverlay?.hide()
        debugOverlay = null
        GestureEventBus.updateServiceState(
            com.example.eyeandfacegesturephonecontrol.common.ServiceState(isRunning = false)
        )
    }
    
    /**
     * Process camera frame
     */
    private fun processFrame(imageProxy: ImageProxy) {
        if (faceMeshDetector.isReady()) {
            faceMeshDetector.processFrame(imageProxy)
        } else {
            imageProxy.close()
        }
    }
    
    /**
     * Handle face landmark results from MediaPipe
     */
    private fun handleFaceLandmarks(result: FaceLandmarkerResult, timestamp: Long) {
        serviceScope.launch(Dispatchers.Default) {
            // Process landmarks and detect gestures
            val gestures = gestureRecognizer.processFrame(result, timestamp)
            
            // Publish gestures to EventBus
            for (gesture in gestures) {
                GestureEventBus.publishGesture(gesture)
                
                // Update cursor position separately
                if (gesture is com.example.eyeandfacegesturephonecontrol.gestures.GestureEvent.CursorMove) {
                    GestureEventBus.updateCursorPosition(gesture.x, gesture.y)
                }
            }
            
            // Update debug overlay (throttle to every 3rd frame)
            if (debugOverlay?.isVisible() == true) {
                debugFrameCount++
                if (debugFrameCount % 3 == 0) {
                    debugOverlay?.updateFaceResults(result)
                    val d = gestureRecognizer.lastDebugInfo
                    debugOverlay?.updateDebugInfo(
                        d.yaw, d.pitch, d.jawOpen, d.browInnerUp,
                        d.blinkR, d.blinkL, d.cursorX, d.cursorY
                    )
                }
            }
        }
    }
    
    /**
     * Handle errors
     */
    private fun handleError(error: String) {
        Log.e(TAG, "Error: $error")
    }
    
    /**
     * Create notification channel for Android 8.0+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_desc)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }
    
    /**
     * Create foreground service notification with debug toggle action
     */
    private fun createNotification(): Notification {
        // Stop action
        val stopIntent = Intent(this, FaceTrackingService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        // Debug toggle action
        val debugIntent = Intent(this, FaceTrackingService::class.java).apply {
            action = ACTION_TOGGLE_DEBUG
        }
        val debugPendingIntent = PendingIntent.getService(
            this, 1, debugIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        
        val debugLabel = if (debugOverlay?.isVisible() == true) "Hide Debug" else "Show Debug"
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                getString(R.string.notification_action_stop),
                stopPendingIntent
            )
            .addAction(
                android.R.drawable.ic_menu_view,
                debugLabel,
                debugPendingIntent
            )
            .build()
    }
    
    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Service destroyed")
        
        stopTracking()
        faceMeshDetector.close()
        cameraManager.shutdown()
        serviceScope.cancel()
    }
    
    /**
     * Helper methods to start/stop service from outside
     */
    object ServiceHelper {
        var isRunning = false
        
        fun start(context: Context) {
            val intent = Intent(context, FaceTrackingService::class.java).apply {
                action = ACTION_START
            }
            androidx.core.content.ContextCompat.startForegroundService(context, intent)
            isRunning = true
        }
        
        fun stop(context: Context) {
            val intent = Intent(context, FaceTrackingService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
            isRunning = false
        }
    }
}
