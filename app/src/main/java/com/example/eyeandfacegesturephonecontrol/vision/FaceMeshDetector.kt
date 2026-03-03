package com.example.eyeandfacegesturephonecontrol.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import java.nio.ByteBuffer

import android.graphics.PixelFormat

/**
 * Wrapper class for MediaPipe Face Landmarker.
 * Handles initialization, frame processing, and resource management.
 *
 * IMPORTANT: Uses CPU delegate to avoid native crashes on devices
 * without proper OpenCL/GPU support. Uses monotonically increasing
 * frame counter for timestamps to satisfy detectAsync requirements.
 */
class FaceMeshDetector(
    private val context: Context,
    private val onResults: (FaceLandmarkerResult, Long) -> Unit,
    private val onError: (String) -> Unit
) {
    
    private var faceLandmarker: FaceLandmarker? = null
    private var isInitialized = false

    // Monotonically increasing timestamp for detectAsync
    // MediaPipe requires strictly increasing timestamps — using a frame counter
    // multiplied by a fake interval guarantees monotonicity.
    private var frameTimestamp = 0L
    
    companion object {
        private const val TAG = "FaceMeshDetector"
        private const val MODEL_ASSET_PATH = "face_landmarker.task"
        private const val MIN_DETECTION_CONFIDENCE = 0.3f
        private const val MIN_TRACKING_CONFIDENCE = 0.3f
        private const val MAX_NUM_FACES = 1
    }

    /**
     * Initialize MediaPipe Face Landmarker
     */
    fun initialize() {
        try {
            // Check if model exists
            try {
                context.assets.open(MODEL_ASSET_PATH).close()
                Log.d(TAG, "Model file found: $MODEL_ASSET_PATH")
            } catch (e: Exception) {
                val msg = "Model file '$MODEL_ASSET_PATH' not found in assets!"
                Log.e(TAG, msg)
                onError(msg)
                return
            }

            // Try GPU first, fall back to CPU if it fails
            val landmarker = try {
                Log.i(TAG, "Attempting GPU delegate...")
                createLandmarker(Delegate.GPU)
            } catch (gpuEx: Exception) {
                Log.w(TAG, "GPU delegate failed: ${gpuEx.message}, falling back to CPU")
                try {
                    createLandmarker(Delegate.CPU)
                } catch (cpuEx: Exception) {
                    throw cpuEx
                }
            }

            faceLandmarker = landmarker
            isInitialized = true
            Log.i(TAG, "MediaPipe FaceLandmarker initialized successfully")
            
        } catch (e: Exception) {
            isInitialized = false
            onError("Failed to initialize MediaPipe: ${e.message}")
            Log.e(TAG, "Failed to initialize MediaPipe", e)
        }
    }

    /**
     * Create a FaceLandmarker with the specified delegate
     */
    private fun createLandmarker(delegate: Delegate): FaceLandmarker {
        val baseOptions = BaseOptions.builder()
            .setDelegate(delegate)
            .setModelAssetPath(MODEL_ASSET_PATH)
            .build()
        
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setMinFaceDetectionConfidence(MIN_DETECTION_CONFIDENCE)
            .setMinTrackingConfidence(MIN_TRACKING_CONFIDENCE)
            .setNumFaces(MAX_NUM_FACES)
            .setOutputFaceBlendshapes(true)
            .setOutputFacialTransformationMatrixes(true)
            .setResultListener { result, _ ->
                val faceCount = result.faceLandmarks().size
                if (faceCount > 0) {
                    Log.v(TAG, "Result: $faceCount faces, blendshapes=${result.faceBlendshapes().isPresent}, matrix=${result.facialTransformationMatrixes().isPresent}")
                }
                onResults(result, System.currentTimeMillis())
            }
            .setErrorListener { error ->
                onError("MediaPipe Error: ${error.message}")
                Log.e(TAG, "MediaPipe error", error)
            }
            .build()
        
        return FaceLandmarker.createFromOptions(context, options)
    }
    
    /**
     * Process a camera frame from CameraX ImageProxy.
     * 
     * Uses a monotonically increasing timestamp to satisfy detectAsync requirements.
     * Wraps in try-catch to prevent native crashes from killing the app.
     */
    fun processFrame(imageProxy: ImageProxy) {
        if (!isInitialized || faceLandmarker == null) {
            Log.w(TAG, "FaceLandmarker not initialized, skipping frame")
            imageProxy.close()
            return
        }
        
        try {
            // Convert ImageProxy to MPImage with rotation
            val mpImage = convertImageProxyToMPImage(imageProxy)
            
            // Use monotonically increasing timestamp
            frameTimestamp += 33  // ~30fps interval in ms
            
            faceLandmarker?.detectAsync(mpImage, frameTimestamp)
            
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error: ${e.message}")
            // Don't propagate to avoid crashing the service
            try { imageProxy.close() } catch (_: Exception) {}
        }
    }
    
    /**
     * Convert CameraX ImageProxy to MediaPipe MPImage
     * Handles rotation for front camera based on image info
     */
    private fun convertImageProxyToMPImage(imageProxy: ImageProxy): MPImage {
        val bitmap = imageProxyToBitmap(imageProxy)
        
        val rotation = imageProxy.imageInfo.rotationDegrees.toFloat()
        
        val matrix = Matrix().apply {
            postRotate(rotation)
            // Mirror horizontally for front camera
            postScale(-1f, 1f)
        }
        
        val rotatedBitmap = Bitmap.createBitmap(
            bitmap, 0, 0,
            bitmap.width, bitmap.height,
            matrix, true
        )
        
        if (rotatedBitmap != bitmap) {
            bitmap.recycle()
        }
        
        // Close ImageProxy after conversion
        imageProxy.close()
        
        return BitmapImageBuilder(rotatedBitmap).build()
    }
    
    /**
     * Convert ImageProxy to Bitmap
     * Supports RGBA_8888 (1 plane) and YUV_420_888 (3 planes)
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap {
        if (imageProxy.format == PixelFormat.RGBA_8888 || 
            imageProxy.planes.size == 1) {
            val buffer = imageProxy.planes[0].buffer
            buffer.rewind()
            
            val bitmap = Bitmap.createBitmap(
                imageProxy.width, 
                imageProxy.height, 
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            return bitmap
        }
        
        // Fallback for YUV
        val planes = imageProxy.planes
        val yBuffer: ByteBuffer = planes[0].buffer
        val uBuffer: ByteBuffer = planes[1].buffer
        val vBuffer: ByteBuffer = planes[2].buffer
        
        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()
        
        val nv21 = ByteArray(ySize + uSize + vSize)
        
        yBuffer.get(nv21, 0, ySize)
        vBuffer.get(nv21, ySize, vSize)
        uBuffer.get(nv21, ySize + vSize, uSize)
        
        val yuvImage = android.graphics.YuvImage(
            nv21,
            android.graphics.ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(
            android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height),
            100, out
        )
        val imageBytes = out.toByteArray()
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        
        Log.d(TAG, "YUV to Bitmap: ${bitmap.width}x${bitmap.height}")
        return bitmap
    }
    
    /**
     * Check if detector is ready
     */
    fun isReady(): Boolean = isInitialized && faceLandmarker != null
    
    /**
     * Clean up resources
     */
    fun close() {
        try {
            faceLandmarker?.close()
            faceLandmarker = null
            isInitialized = false
            Log.i(TAG, "FaceMeshDetector closed")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing FaceMeshDetector", e)
        }
    }
}
