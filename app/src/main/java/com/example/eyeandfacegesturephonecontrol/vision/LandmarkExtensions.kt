package com.example.eyeandfacegesturephonecontrol.vision

import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.sqrt

/**
 * MediaPipe Face Landmark Indices
 * These are the specific landmark points used for gesture detection
 */
object LandmarkIndices {
    // Nose
    const val NOSE_TIP = 1
    
    // Left Eye (6 points for EAR calculation)
    val LEFT_EYE = intArrayOf(33, 160, 158, 133, 153, 144)
    const val LEFT_EYE_OUTER = 33
    const val LEFT_EYE_INNER = 133
    const val LEFT_EYE_TOP_1 = 160
    const val LEFT_EYE_TOP_2 = 158
    const val LEFT_EYE_BOTTOM_1 = 153
    const val LEFT_EYE_BOTTOM_2 = 144
    
    // Right Eye (6 points for EAR calculation)
    val RIGHT_EYE = intArrayOf(362, 385, 387, 263, 373, 380)
    const val RIGHT_EYE_OUTER = 362
    const val RIGHT_EYE_INNER = 263
    const val RIGHT_EYE_TOP_1 = 385
    const val RIGHT_EYE_TOP_2 = 387
    const val RIGHT_EYE_BOTTOM_1 = 373
    const val RIGHT_EYE_BOTTOM_2 = 380
    
    // Eyebrows
    val LEFT_EYEBROW = intArrayOf(70, 63, 105, 66, 107)
    const val LEFT_EYEBROW_CENTER = 105
    
    val RIGHT_EYEBROW = intArrayOf(336, 296, 334, 293, 300)
    const val RIGHT_EYEBROW_CENTER = 334
    
    // Lips
    const val UPPER_LIP = 13
    const val LOWER_LIP = 14
    
    // Head reference points
    const val UPPER_HEAD = 10
    const val LOWER_HEAD = 152
}

/**
 * Extension functions for MediaPipe NormalizedLandmark
 */

/**
 * Calculate distance between two landmarks
 */
fun FaceLandmarkerResult.distance(index1: Int, index2: Int, faceIndex: Int = 0): Float {
    val landmarks = this.faceLandmarks()
    if (faceIndex >= landmarks.size || landmarks.isEmpty()) return 0f
    
    val face = landmarks[faceIndex]
    if (index1 >= face.size || index2 >= face.size) return 0f
    
    val p1 = face[index1]
    val p2 = face[index2]
    
    val dx = p2.x() - p1.x()
    val dy = p2.y() - p1.y()
    return sqrt(dx * dx + dy * dy)
}

/**
 * Get a specific landmark from the result
 */
fun FaceLandmarkerResult.getLandmark(index: Int, faceIndex: Int = 0): Triple<Float, Float, Float>? {
    val landmarks = this.faceLandmarks()
    if (faceIndex >= landmarks.size || landmarks.isEmpty()) return null
    
    val face = landmarks[faceIndex]
    if (index >= face.size) return null
    
    val point = face[index]
    return Triple(point.x(), point.y(), point.z())
}

/**
 * Calculate average position of multiple landmarks
 */
fun FaceLandmarkerResult.averagePosition(indices: IntArray, faceIndex: Int = 0): Triple<Float, Float, Float>? {
    val landmarks = this.faceLandmarks()
    if (faceIndex >= landmarks.size || landmarks.isEmpty()) return null
    
    val face = landmarks[faceIndex]
    var sumX = 0f
    var sumY = 0f
    var sumZ = 0f
    var count = 0
    
    for (index in indices) {
        if (index < face.size) {
            val point = face[index]
            sumX += point.x()
            sumY += point.y()
            sumZ += point.z()
            count++
        }
    }
    
    return if (count > 0) {
        Triple(sumX / count, sumY / count, sumZ / count)
    } else {
        null
    }
}

/**
 * Calculate Eye Aspect Ratio (EAR) for an eye
 * EAR = (vertical_dist1 + vertical_dist2) / (2 * horizontal_dist)
 */
fun FaceLandmarkerResult.calculateEAR(
    outer: Int,
    inner: Int,
    top1: Int,
    top2: Int,
    bottom1: Int,
    bottom2: Int,
    faceIndex: Int = 0
): Float {
    val verticalDist1 = distance(top1, bottom1, faceIndex)
    val verticalDist2 = distance(top2, bottom2, faceIndex)
    val horizontalDist = distance(outer, inner, faceIndex)
    
    return if (horizontalDist > 0) {
        (verticalDist1 + verticalDist2) / (2f * horizontalDist)
    } else {
        0f
    }
}

/**
 * Calculate EAR for left eye
 */
fun FaceLandmarkerResult.calculateLeftEyeEAR(faceIndex: Int = 0): Float {
    return calculateEAR(
        LandmarkIndices.LEFT_EYE_OUTER,
        LandmarkIndices.LEFT_EYE_INNER,
        LandmarkIndices.LEFT_EYE_TOP_1,
        LandmarkIndices.LEFT_EYE_TOP_2,
        LandmarkIndices.LEFT_EYE_BOTTOM_1,
        LandmarkIndices.LEFT_EYE_BOTTOM_2,
        faceIndex
    )
}

/**
 * Calculate EAR for right eye
 */
fun FaceLandmarkerResult.calculateRightEyeEAR(faceIndex: Int = 0): Float {
    return calculateEAR(
        LandmarkIndices.RIGHT_EYE_OUTER,
        LandmarkIndices.RIGHT_EYE_INNER,
        LandmarkIndices.RIGHT_EYE_TOP_1,
        LandmarkIndices.RIGHT_EYE_TOP_2,
        LandmarkIndices.RIGHT_EYE_BOTTOM_1,
        LandmarkIndices.RIGHT_EYE_BOTTOM_2,
        faceIndex
    )
}

/**
 * Get number of faces detected
 */
fun FaceLandmarkerResult.faceCount(): Int {
    return this.faceLandmarks().size
}

/**
 * Check if face detection is successful
 */
fun FaceLandmarkerResult.hasFace(): Boolean {
    return this.faceLandmarks().isNotEmpty()
}
