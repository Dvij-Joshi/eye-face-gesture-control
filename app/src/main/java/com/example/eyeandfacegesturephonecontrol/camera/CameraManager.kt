package com.example.eyeandfacegesturephonecontrol.camera

import android.content.Context
import android.util.Log
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Manager class for CameraX operations
 * Handles camera initialization, configuration, and frame analysis
 */
class CameraManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner,
    private val onFrameAvailable: (ImageProxy) -> Unit,
    private val onError: (String) -> Unit
) {
    
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    
    companion object {
        private const val TAG = "CameraManager"
        private const val CAMERA_SELECTOR = CameraSelector.LENS_FACING_FRONT
    }
    
    /**
     * Initialize and start camera
     */
    fun startCamera(previewView: PreviewView? = null) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(previewView)
            } catch (e: Exception) {
                onError("Camera initialization failed: ${e.message}")
                Log.e(TAG, "Camera init error", e)
            }
        }, ContextCompat.getMainExecutor(context))
    }
    
    /**
     * Bind camera use cases (preview and image analysis)
     */
    private fun bindCameraUseCases(previewView: PreviewView?) {
        val provider = cameraProvider ?: return
        
        // Unbind everything before rebinding
        provider.unbindAll()
        
        // Camera selector (front camera for face tracking)
        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(CAMERA_SELECTOR)
            .build()
        
        // Preview use case (optional - only if PreviewView is provided)
        val preview = previewView?.let {
            Preview.Builder()
                .build()
                .also { preview ->
                    preview.setSurfaceProvider(it.surfaceProvider)
                }
        }
        
        // ImageAnalysis use case (for MediaPipe processing)
       imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()
            .also { analysis ->
                analysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    processFrame(imageProxy)
                }
            }
        
        try {
            // Bind use cases to camera
            val useCases = mutableListOf<UseCase>(imageAnalysis!!)
            preview?.let { useCases.add(it) }
            
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                *useCases.toTypedArray()
            )
            
            Log.i(TAG, "Camera started successfully")
            
        } catch (e: Exception) {
            onError("Camera binding failed: ${e.message}")
            Log.e(TAG, "Camera binding error", e)
        }
    }
    
    /**
     * Process camera frame
     */
    /**
     * Process camera frame
     */
    private fun processFrame(imageProxy: ImageProxy) {
        try {
            // Pass frame to callback (will be handled by consumers)
            // consumer is responsible for closing the imageProxy
            onFrameAvailable(imageProxy)
        } catch (e: Exception) {
            Log.e(TAG, "Frame processing error", e)
            imageProxy.close() // Close only on error/exception in callback dispatch
        }
    }
    
    /**
     * Stop camera and release resources
     */
    fun stopCamera() {
        try {
            cameraProvider?.unbindAll()
            camera = null
            imageAnalysis = null
            Log.i(TAG, "Camera stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera", e)
        }
    }
    
    /**
     * Release all resources
     */
    fun shutdown() {
        stopCamera()
        cameraExecutor.shutdown()
    }
}
