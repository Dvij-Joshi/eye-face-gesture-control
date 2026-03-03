package com.example.eyeandfacegesturephonecontrol.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult

/**
 * Custom view to overlay face mesh visualization on camera preview
 */
class FaceMeshOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var faceLandmarkerResult: FaceLandmarkerResult? = null
    private var imageWidth: Int = 0
    private var imageHeight: Int = 0
    private var rotation: Int = 0

    private val landmarkPaint = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.FILL
        strokeWidth = 4f
    }

    private val connectionPaint = Paint().apply {
        color = Color.argb(180, 0, 255, 0)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    /**
     * Update the face detection results and trigger redraw
     */
    fun setResults(
        result: FaceLandmarkerResult?,
        imageWidth: Int,
        imageHeight: Int,
        rotation: Int = 0
    ) {
        this.faceLandmarkerResult = result
        this.imageWidth = imageWidth
        this.imageHeight = imageHeight
        this.rotation = rotation
        invalidate()
    }

    /**
     * Clear the overlay
     */
    fun clear() {
        faceLandmarkerResult = null
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val result = faceLandmarkerResult ?: return
        if (result.faceLandmarks().isEmpty()) return

        // Calculate scale factors
        val scaleX = width.toFloat() / imageWidth
        val scaleY = height.toFloat() / imageHeight

        // Draw landmarks for each detected face
        result.faceLandmarks().forEach { landmarks ->
            // Draw all landmark points
            landmarks.forEach { landmark ->
                val x = landmark.x() * imageWidth * scaleX
                val y = landmark.y() * imageHeight * scaleY
                canvas.drawCircle(x, y, 3f, landmarkPaint)
            }

            // Draw connections for key facial features
            drawFaceContour(canvas, landmarks, scaleX, scaleY)
        }
    }

    /**
     * Draw face contour lines connecting key landmarks
     */
    private fun drawFaceContour(
        canvas: Canvas,
        landmarks: List<com.google.mediapipe.tasks.components.containers.NormalizedLandmark>,
        scaleX: Float,
        scaleY: Float
    ) {
        // Draw face oval (simplified - just key outline points)
        val faceOvalIndices = listOf(
            10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288,
            397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136,
            172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109, 10
        )

        for (i in 0 until faceOvalIndices.size - 1) {
            val start = landmarks.getOrNull(faceOvalIndices[i]) ?: continue
            val end = landmarks.getOrNull(faceOvalIndices[i + 1]) ?: continue

            val x1 = start.x() * imageWidth * scaleX
            val y1 = start.y() * imageHeight * scaleY
            val x2 = end.x() * imageWidth * scaleX
            val y2 = end.y() * imageHeight * scaleY

            canvas.drawLine(x1, y1, x2, y2, connectionPaint)
        }
    }
}
