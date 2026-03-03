package com.example.eyeandfacegesturephonecontrol.gestures

import android.util.Log
import com.example.eyeandfacegesturephonecontrol.vision.*
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.*

/**
 * Gesture recognition engine — ported from PCV main.py.
 *
 * Uses:
 * - Facial transformation matrix → Euler angles → direct screen mapping
 * - Blendshape scores for gestures (browInnerUp, browDown, eyeBlinkRight/Left)
 * - Double Kalman filters for ultra-smooth cursor (Kalman + EMA)
 * - GestureStateMachine with dwell time to prevent accidental triggers
 * - Gesture exclusion: winking suppresses scroll to avoid cross-trigger
 * - Distance-adaptive sensitivity via face scale measurement
 */
class GestureRecognizer(
    private var calibrationData: CalibrationData
) {

    companion object {
        private const val TAG = "GestureRecognizer"
    }

    /** Debug info exposed for the debug preview overlay. */
    data class DebugInfo(
        val yaw: Float = 0f,
        val pitch: Float = 0f,
        val jawOpen: Float = 0f,
        val browInnerUp: Float = 0f,
        val browDown: Float = 0f,
        val blinkR: Float = 0f,
        val blinkL: Float = 0f,
        val cursorX: Float = 0.5f,
        val cursorY: Float = 0.5f
    )

    var lastDebugInfo: DebugInfo = DebugInfo()
        private set

    // ── Kalman Filter ────────────────────────────
    private class KalmanFilter1D(
        private val processNoise: Float,
        private val measureNoise: Float
    ) {
        private var x = 0f   // position estimate
        private var P = 1f   // error covariance

        fun update(measurement: Float): Float {
            P += processNoise
            val K = P / (P + measureNoise)
            x += K * (measurement - x)
            P *= (1f - K)
            return x
        }

        fun set(value: Float) {
            x = value
            P = 1f
        }

        fun value(): Float = x
    }

    // ── Gesture State Machine ────────────────────
    private class GestureStateMachine(
        val name: String,
        private val dwellMs: Long,
        private val cooldownMs: Long
    ) {
        private var state = State.IDLE
        private var startTime = 0L
        var fired = false; private set

        private enum class State { IDLE, DETECTING, COOLDOWN }

        fun update(triggered: Boolean, nowMs: Long): Boolean {
            fired = false
            when (state) {
                State.IDLE -> {
                    if (triggered) {
                        state = State.DETECTING
                        startTime = nowMs
                    }
                }
                State.DETECTING -> {
                    if (!triggered) {
                        state = State.IDLE
                    } else if (nowMs - startTime >= dwellMs) {
                        state = State.COOLDOWN
                        startTime = nowMs
                        fired = true
                    }
                }
                State.COOLDOWN -> {
                    if (nowMs - startTime >= cooldownMs) {
                        state = State.IDLE
                    }
                }
            }
            return fired
        }

        fun isActive(): Boolean = state == State.DETECTING || state == State.COOLDOWN

        fun reset() {
            state = State.IDLE
            startTime = 0
            fired = false
        }
    }

    // ── Cursor state ─────────────────────────────
    private val kfX = KalmanFilter1D(calibrationData.kalmanProcess, calibrationData.kalmanMeasure)
    private val kfY = KalmanFilter1D(calibrationData.kalmanProcess, calibrationData.kalmanMeasure)
    private var cursorInitialized = false

    // Extra EMA smoothing layer on top of Kalman (reduces micro-jitter)
    private var emaX = 0.5f
    private var emaY = 0.5f
    private val emaAlpha = 0.35f  // 0 = frozen, 1 = no smoothing. 0.35 is a good balance

    // ── Gesture state machines ───────────────────
    // Winks → clicks
    private val winkLFSM = GestureStateMachine("WinkL_Click", calibrationData.dwellTimeMs, calibrationData.cooldownMs)
    private val winkRFSM = GestureStateMachine("WinkR_Click", calibrationData.dwellTimeMs, calibrationData.cooldownMs)
    // Brow → scroll
    private val browUpFSM   = GestureStateMachine("BrowUp_ScrollUp",   calibrationData.dwellTimeMs, calibrationData.cooldownMs)
    private val browDownFSM = GestureStateMachine("BrowDown_ScrollDn", calibrationData.dwellTimeMs, calibrationData.cooldownMs)

    // ── Face scale history for distance adaptation ─
    private val faceScaleHistory = mutableListOf<Float>()

    // ── Monitoring ───────────────────────────────
    private val _lastGesture = MutableStateFlow<GestureEvent?>(null)
    val lastGesture: StateFlow<GestureEvent?> = _lastGesture

    private var frameCount = 0

    // ═══════════════════════════════════════════
    //  PUBLIC API
    // ═══════════════════════════════════════════

    fun updateCalibration(newData: CalibrationData) {
        calibrationData = newData
        resetState()
    }

    /**
     * Process one frame — returns list of gestures detected this frame.
     */
    fun processFrame(result: FaceLandmarkerResult, timestamp: Long): List<GestureEvent> {
        val gestures = mutableListOf<GestureEvent>()

        if (!result.hasFace()) {
            resetState()
            return emptyList()
        }

        val nowMs = System.currentTimeMillis()

        // ── 1. Extract head angles from transformation matrix ─
        val headAngles = extractHeadAngles(result)
        if (headAngles != null) {
            val (yaw, pitch) = headAngles

            // Delta from calibrated neutral
            var deltaYaw   = yaw   - calibrationData.refYaw
            var deltaPitch = pitch - calibrationData.refPitch

            // Dead zone — slightly larger to cut micro-movements
            deltaYaw   = applyDeadZone(deltaYaw,   calibrationData.deadZoneDeg)
            deltaPitch = applyDeadZone(deltaPitch, calibrationData.deadZoneDeg)

            // Distance-adaptive sensitivity
            val distMultiplier = calculateDistanceMultiplier(result)

            // Direct mapping: angle → screen position (0..1)
            val normX = (deltaYaw   / (calibrationData.maxYawDeg   * distMultiplier)).coerceIn(-1f, 1f)
            val normY = (deltaPitch / (calibrationData.maxPitchDeg * distMultiplier)).coerceIn(-1f, 1f)

            val targetX = 0.5f + normX * 0.5f
            val targetY = 0.5f + normY * 0.5f

            // Initialize on first frame
            if (!cursorInitialized) {
                kfX.set(targetX)
                kfY.set(targetY)
                emaX = targetX
                emaY = targetY
                cursorInitialized = true
            }

            // Layer 1: Kalman filter (handles sensor noise)
            val kalmanX = kfX.update(targetX).coerceIn(0f, 1f)
            val kalmanY = kfY.update(targetY).coerceIn(0f, 1f)

            // Layer 2: EMA smoothing (handles remaining micro-jitter)
            emaX = emaX + emaAlpha * (kalmanX - emaX)
            emaY = emaY + emaAlpha * (kalmanY - emaY)

            val smoothX = emaX.coerceIn(0f, 1f)
            val smoothY = emaY.coerceIn(0f, 1f)

            gestures.add(GestureEvent.CursorMove(smoothX, smoothY))

            if (frameCount++ % 30 == 0) {
                Log.d(TAG, "Yaw:%.1f Pitch:%.1f | delta: %.1f,%.1f | cursor: %.2f,%.2f"
                    .format(yaw, pitch, deltaYaw, deltaPitch, smoothX, smoothY))
            }
        }

        // ── 2. Blendshape-based gestures ─────────────
        val blendshapes = extractBlendshapes(result)
        if (blendshapes != null) {
            val eyeBlinkR    = blendshapes["eyeBlinkRight"] ?: 0f
            val eyeBlinkL    = blendshapes["eyeBlinkLeft"]  ?: 0f
            val browInnerUp  = blendshapes["browInnerUp"]   ?: 0f
            val browDownL    = blendshapes["browDownLeft"]   ?: 0f
            val browDownR    = blendshapes["browDownRight"]  ?: 0f
            val browDown     = (browDownL + browDownR) / 2f

            // ── Check winks FIRST (higher priority) ───
            val isWinkingL = eyeBlinkL > calibrationData.winkLThreshold && eyeBlinkR < 0.3f
            val isWinkingR = eyeBlinkR > calibrationData.winkRThreshold && eyeBlinkL < 0.3f
            val anyWinkActive = isWinkingL || isWinkingR || winkLFSM.isActive() || winkRFSM.isActive()

            // Left wink → Left click
            if (winkLFSM.update(isWinkingL, nowMs)) {
                Log.i(TAG, "🔥 LEFT CLICK via left wink (blinkL=$eyeBlinkL)")
                gestures.add(GestureEvent.Click)
                _lastGesture.value = GestureEvent.Click
            }

            // Right wink → Right click
            if (winkRFSM.update(isWinkingR, nowMs)) {
                Log.i(TAG, "🔥 RIGHT CLICK via right wink (blinkR=$eyeBlinkR)")
                gestures.add(GestureEvent.WinkRight)
                _lastGesture.value = GestureEvent.WinkRight
            }

            // ── Brow gestures ONLY if no wink is active ───
            // This prevents scroll triggers when a wink naturally moves the brows
            if (!anyWinkActive) {
                // Eyebrow raise → Scroll Up
                if (browUpFSM.update(browInnerUp > calibrationData.browUpThreshold, nowMs)) {
                    Log.i(TAG, "🔥 SCROLL UP (browInnerUp=$browInnerUp > ${calibrationData.browUpThreshold})")
                    gestures.add(GestureEvent.Scroll(ScrollDirection.UP, 1f))
                    _lastGesture.value = GestureEvent.Scroll(ScrollDirection.UP, 1f)
                }

                // Eyebrow squint/furrow → Scroll Down
                if (browDownFSM.update(browDown > calibrationData.browDownThreshold, nowMs)) {
                    Log.i(TAG, "🔥 SCROLL DOWN (browDown=$browDown > ${calibrationData.browDownThreshold})")
                    gestures.add(GestureEvent.Scroll(ScrollDirection.DOWN, 1f))
                    _lastGesture.value = GestureEvent.Scroll(ScrollDirection.DOWN, 1f)
                }
            } else {
                // Reset brow FSMs to prevent accumulated dwell time from before the wink
                browUpFSM.reset()
                browDownFSM.reset()
            }
        }

        // ── 3. Update debug info ─────────────────────
        val bs = extractBlendshapes(result)
        val curPos = gestures.filterIsInstance<GestureEvent.CursorMove>().lastOrNull()
        lastDebugInfo = DebugInfo(
            yaw = headAngles?.first ?: 0f,
            pitch = headAngles?.second ?: 0f,
            jawOpen = bs?.get("jawOpen") ?: 0f,
            browInnerUp = bs?.get("browInnerUp") ?: 0f,
            browDown = ((bs?.get("browDownLeft") ?: 0f) + (bs?.get("browDownRight") ?: 0f)) / 2f,
            blinkR = bs?.get("eyeBlinkRight") ?: 0f,
            blinkL = bs?.get("eyeBlinkLeft") ?: 0f,
            cursorX = curPos?.x ?: lastDebugInfo.cursorX,
            cursorY = curPos?.y ?: lastDebugInfo.cursorY
        )

        return gestures
    }

    // ═══════════════════════════════════════════
    //  PRIVATE HELPERS
    // ═══════════════════════════════════════════

    /**
     * Extract yaw and pitch from MediaPipe's facial transformation matrix.
     * Returns (yaw, pitch) in degrees, or null if not available.
     */
    private fun extractHeadAngles(result: FaceLandmarkerResult): Pair<Float, Float>? {
        val matrixes = result.facialTransformationMatrixes()
        if (!matrixes.isPresent || matrixes.get().isEmpty()) return null

        val m: FloatArray = matrixes.get()[0]
        if (m.size < 16) return null

        // MediaPipe returns a 4x4 COLUMN-MAJOR transformation matrix
        val r02: Float = m[2 * 4 + 0]
        val r12: Float = m[2 * 4 + 1]
        val r22: Float = m[2 * 4 + 2]

        val negR12: Float = -r12
        val pitch = Math.toDegrees(asin(negR12.toDouble().coerceIn(-1.0, 1.0))).toFloat()
        val yaw   = Math.toDegrees(atan2(r02.toDouble(), r22.toDouble())).toFloat()

        return Pair(yaw, pitch)
    }

    /**
     * Extract blendshape scores as a name→value map.
     */
    private fun extractBlendshapes(result: FaceLandmarkerResult): Map<String, Float>? {
        val blendshapes = result.faceBlendshapes()
        if (!blendshapes.isPresent || blendshapes.get().isEmpty()) return null

        val categories = blendshapes.get()[0]
        val map = mutableMapOf<String, Float>()
        for (cat in categories) {
            map[cat.categoryName()] = cat.score()
        }
        return map
    }

    /**
     * Apply dead zone — set small movements to zero.
     */
    private fun applyDeadZone(value: Float, deadZone: Float): Float {
        return when {
            value > deadZone  -> value - deadZone
            value < -deadZone -> value + deadZone
            else -> 0f
        }
    }

    /**
     * Calculate distance multiplier based on face scale.
     * Larger face (closer) → smaller multiplier → less movement needed.
     */
    private fun calculateDistanceMultiplier(result: FaceLandmarkerResult): Float {
        if (!calibrationData.adaptSensitivity) return 1f

        val leftOuter  = result.getLandmark(LandmarkIndices.LEFT_EYE_OUTER)  ?: return 1f
        val rightOuter = result.getLandmark(LandmarkIndices.RIGHT_EYE_OUTER) ?: return 1f
        val faceScale = sqrt(
            (rightOuter.first - leftOuter.first).pow(2) +
            (rightOuter.second - leftOuter.second).pow(2)
        )

        faceScaleHistory.add(faceScale)
        if (faceScaleHistory.size > 30) faceScaleHistory.removeAt(0)

        val smoothScale = faceScaleHistory.average().toFloat()

        return if (smoothScale > 0.01f) {
            (calibrationData.baseFaceScale / smoothScale).coerceIn(0.4f, 3f)
        } else {
            1f
        }
    }

    /**
     * Reset all state when face is lost.
     */
    fun resetState() {
        cursorInitialized = false
        faceScaleHistory.clear()
        winkLFSM.reset()
        winkRFSM.reset()
        browUpFSM.reset()
        browDownFSM.reset()
        frameCount = 0
    }
}
