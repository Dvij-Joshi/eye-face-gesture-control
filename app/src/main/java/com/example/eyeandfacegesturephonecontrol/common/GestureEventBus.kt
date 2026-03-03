package com.example.eyeandfacegesturephonecontrol.common

import com.example.eyeandfacegesturephonecontrol.gestures.GestureEvent
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Singleton EventBus for communication between FaceTrackingService and EyeControlAccessibilityService
 * Replaces deprecated LocalBroadcastManager with Kotlin Flow
 */
object GestureEventBus {
    
    // SharedFlow for gesture events (hot stream, multiple collectors)
    private val _gestureEvents = MutableSharedFlow<GestureEvent>(
        replay = 0,  // Don't replay old events
        extraBufferCapacity = 10  // Buffer up to 10 events
    )
    val gestureEvents: SharedFlow<GestureEvent> = _gestureEvents.asSharedFlow()
    
    // StateFlow for cursor position (always has latest value)
    private val _cursorPosition = MutableStateFlow(CursorPosition(0.5f, 0.5f))
    val cursorPosition: StateFlow<CursorPosition> = _cursorPosition.asStateFlow()
    
    // ServiceState tracking
    private val _serviceState = MutableStateFlow(ServiceState())
    val serviceState: StateFlow<ServiceState> = _serviceState.asStateFlow()
    
    /**
     * Publish a gesture event from FaceTrackingService
     * Thread-safe method
     */
    suspend fun publishGesture(event: GestureEvent) {
        _gestureEvents.emit(event)
    }
    
    /**
     * Non-suspending version for Java/legacy code
     */
    fun publishGestureNonSuspending(event: GestureEvent) {
        _gestureEvents.tryEmit(event)
    }
    
    /**
     * Update cursor position (high frequency updates)
     */
    fun updateCursorPosition(x: Float, y: Float) {
        _cursorPosition.value = CursorPosition(x, y)
    }
    
    /**
     * Update service state
     */
    fun updateServiceState(state: ServiceState) {
        _serviceState.value = state
    }
}

/**
 * Data class for cursor position
 */
data class CursorPosition(
    val x: Float,  // Normalized 0.0 to 1.0
    val y: Float   // Normalized 0.0 to 1.0
)

/**
 * Data class for global service state
 */
data class ServiceState(
    val isRunning: Boolean = false,
    val isPaused: Boolean = false,  // Paused due to no face, face down, or fatigue
    val lastActiveTime: Long = System.currentTimeMillis(),
    val pauseReason: PauseReason = PauseReason.NONE
)

/**
 * Enum for pause reasons
 */
enum class PauseReason {
    NONE,
    NO_FACE_DETECTED,
    PHONE_FACE_DOWN,
    FATIGUE_PREVENTION,
    USER_PAUSED
}
