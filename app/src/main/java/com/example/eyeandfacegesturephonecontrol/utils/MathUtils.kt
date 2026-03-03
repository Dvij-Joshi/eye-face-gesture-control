package com.example.eyeandfacegesturephonecontrol.utils

import kotlin.math.sqrt

/**
 * Mathematical utility functions for gesture recognition
 */
object MathUtils {
    
    /**
     * Apply exponential smoothing to reduce jitter
     * @param previousValue Previously smoothed value
     * @param newValue New raw value
     * @param smoothingFactor Weight of new value (0.0 = all old, 1.0 = all new)
     * @return Smoothed value
     */
    fun exponentialSmoothing(
        previousValue: Float,
        newValue: Float,
        smoothingFactor: Float
    ): Float {
        return previousValue * (1f - smoothingFactor) + newValue * smoothingFactor
    }
    
    /**
     * Linear interpolation between two values
     */
    fun lerp(start: Float, end: Float, t: Float): Float {
        return start + (end - start) * t
    }
    
    /**
     * Clamp a value between min and max
     */
    fun clamp(value: Float, min: Float, max: Float): Float {
        return when {
            value < min -> min
            value > max -> max
            else -> value
        }
    }
    
    /**
     * Map a value from one range to another
     * @param value Value in input range
     * @param inMin Input range minimum
     * @param inMax Input range maximum
     * @param outMin Output range minimum
     * @param outMax Output range maximum
     * @return Mapped value in output range
     */
    fun map(
        value: Float,
        inMin: Float,
        inMax: Float,
        outMin: Float,
        outMax: Float
    ): Float {
        val normalized = (value - inMin) / (inMax - inMin)
        return outMin + normalized * (outMax - outMin)
    }
    
    /**
     * Calculate Euclidean distance between two 2D points
     */
    fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        return sqrt(dx * dx + dy * dy)
    }
    
    /**
     * Calculate Euclidean distance between two 3D points
     */
    fun distance3D(x1: Float, y1: Float, z1: Float, x2: Float, y2: Float, z2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val dz = z2 - z1
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
}
