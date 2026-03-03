package com.example.eyeandfacegesturephonecontrol.gestures

/**
 * Sealed class representing all possible gesture events detected by the face tracking system
 */
sealed class GestureEvent {
    /**
     * Cursor movement event with normalized screen coordinates
     * @param x Normalized X coordinate (0.0 to 1.0)
     * @param y Normalized Y coordinate (0.0 to 1.0)
     */
    data class CursorMove(val x: Float, val y: Float) : GestureEvent()
    
    /**
     * Click gesture (quick squint detected)
     */
    data object Click : GestureEvent()
    
    /**
     * Long press gesture (sustained squint >1s)
     */
    data object LongPress : GestureEvent()
    
    /**
     * Double blink gesture (two squints within 500ms)
     */
    data object DoubleBlink : GestureEvent()
    
    /**
     * Left eye wink gesture (back navigation)
     */
    data object WinkLeft : GestureEvent()
    
    /**
     * Right eye wink gesture (home navigation)
     */
    data object WinkRight : GestureEvent()
    
    /**
     * Scroll gesture with direction and velocity
     * @param direction Scroll direction (UP or DOWN)
     * @param velocity Scroll speed multiplier (0.0 to 1.0)
     */
    data class Scroll(val direction: ScrollDirection, val velocity: Float) : GestureEvent()
    
    /**
     * Voice input gesture (mouth open)
     */
    data object VoiceInput : GestureEvent()
}

/**
 * Scroll direction enum
 */
enum class ScrollDirection {
    UP,
    DOWN
}
