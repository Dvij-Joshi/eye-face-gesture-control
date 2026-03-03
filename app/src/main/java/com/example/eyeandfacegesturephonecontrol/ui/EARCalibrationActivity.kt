package com.example.eyeandfacegesturephonecontrol.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.eyeandfacegesturephonecontrol.camera.CameraManager
import com.example.eyeandfacegesturephonecontrol.databinding.ActivityEarCalibrationBinding
import com.example.eyeandfacegesturephonecontrol.gestures.CalibrationData
import com.example.eyeandfacegesturephonecontrol.vision.FaceMeshDetector
import com.example.eyeandfacegesturephonecontrol.vision.calculateLeftEyeEAR
import com.example.eyeandfacegesturephonecontrol.vision.calculateRightEyeEAR
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Calibration step enum
private enum class CalibrationStep {
    EYES_WIDE_OPEN,
    EYES_NORMAL,
    EYES_CLOSED,
    RESULTS
}

// EAR sample data
private data class EARSample(
    val leftEAR: Float,
    val rightEAR: Float,
    val avgEAR: Float,
    val step: CalibrationStep
)

/**
 * Activity for calibrating Eye Aspect Ratio (EAR) thresholds
 * Guides user through measuring their eyes open/normal/closed states
 */
class EARCalibrationActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityEarCalibrationBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var faceMeshDetector: FaceMeshDetector
    
    // Calibration state
    private var currentStep = CalibrationStep.EYES_WIDE_OPEN
    private val earSamples = mutableListOf<EARSample>()
    private var isCollectingSamples = false
    
    // Sample collection parameters
    private val SAMPLES_PER_STEP = 60  // ~2 seconds at 30fps
    private val SAMPLE_DELAY_MS = 33L  // ~30fps
    
    // Camera permission launcher
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            initializeCamera()
        } else {
            Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEarCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        // Check camera permission
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) 
            == PackageManager.PERMISSION_GRANTED) {
            initializeCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        
        setupUI()
    }
    
    private fun initializeCamera() {
        try {
            // Initialize FaceMeshDetector with callbacks
            faceMeshDetector = FaceMeshDetector(
                context = this,
                onResults = { result, timestamp ->
                    // Process the result  
                    if (result.faceLandmarks().isNotEmpty()) {
                        // Calculate EAR values
                        val leftEAR = result.calculateLeftEyeEAR()
                        val rightEAR = result.calculateRightEyeEAR()
                        val avgEAR = (leftEAR + rightEAR) / 2f
                        
                        // Update UI with current EAR
                        runOnUiThread {
                            binding.earValueText.text = String.format("EAR: %.3f", avgEAR)
                        }
                        
                        // Collect sample if we're in collection mode
                        if (isCollectingSamples) {
                            earSamples.add(EARSample(leftEAR, rightEAR, avgEAR, currentStep))
                        }
                    }
                },
                onError = { error ->
                    Log.e(TAG, "FaceMeshDetector Error: $error")
                    runOnUiThread {
                        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    }
                }
            )
            faceMeshDetector.initialize()
            
            // Initialize CameraManager with callbacks
            cameraManager = CameraManager(
                context = this,
                lifecycleOwner = this,
                onFrameAvailable = { imageProxy ->
                    faceMeshDetector.processFrame(imageProxy)
                },
                onError = { error ->
                    Log.e(TAG, "CameraManager Error: $error")
                    runOnUiThread {
                        Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    }
                }
            )
            
            cameraManager.startCamera(binding.cameraPreview)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize camera", e)
            Toast.makeText(this, "Camera initialization failed", Toast.LENGTH_SHORT).show()
            finish()
        }
    }
    
    private fun setupUI() {
        updateStepUI()
        
        binding.nextStepButton.setOnClickListener {
            when (currentStep) {
                CalibrationStep.EYES_WIDE_OPEN,
                CalibrationStep.EYES_NORMAL,
                CalibrationStep.EYES_CLOSED -> startSampleCollection()
                CalibrationStep.RESULTS -> {}  // Handled by save button
            }
        }
        
        binding.saveButton.setOnClickListener {
            saveCalibration()
        }
        
        binding.recalibrateButton.setOnClickListener {
            restartCalibration()
        }
    }
    
    private fun updateStepUI() {
        when (currentStep) {
            CalibrationStep.EYES_WIDE_OPEN -> {
                binding.stepIndicator.text = "Step 1 of 3"
                binding.instructionText.text = "Open your eyes as WIDE as possible"
                binding.nextStepButton.text = "Start"
                binding.nextStepButton.isEnabled = true
                binding.sampleProgress.progress = 0
            }
            CalibrationStep.EYES_NORMAL -> {
                binding.stepIndicator.text = "Step 2 of 3"
                binding.instructionText.text = "Look normally at the screen (relaxed)"
                binding.nextStepButton.text = "Start"
                binding.nextStepButton.isEnabled = true
                binding.sampleProgress.progress = 0
            }
            CalibrationStep.EYES_CLOSED -> {
                binding.stepIndicator.text = "Step 3 of 3"
                binding.instructionText.text = "Close your eyes completely"
                binding.nextStepButton.text = "Start"
                binding.nextStepButton.isEnabled = true
                binding.sampleProgress.progress = 0
            }
            CalibrationStep.RESULTS -> {
                showResults()
            }
        }
    }
    
    private fun startSampleCollection() {
        isCollectingSamples = true
        binding.nextStepButton.isEnabled = false
        binding.nextStepButton.text = "Collecting..."
        
        lifecycleScope.launch {
            val stepSamples = mutableListOf<EARSample>()
            
            for (i in 0 until SAMPLES_PER_STEP) {
                delay(SAMPLE_DELAY_MS)
                
                // Update progress
                val progress = ((i + 1) * 100) / SAMPLES_PER_STEP
                binding.sampleProgress.progress = progress
                
                // Note: Samples are collected in onCameraFrame callback
                // We just wait here for timing
            }
            
            isCollectingSamples = false
            nextStep()
        }
    }
    
    private fun nextStep() {
        currentStep = when (currentStep) {
            CalibrationStep.EYES_WIDE_OPEN -> CalibrationStep.EYES_NORMAL
            CalibrationStep.EYES_NORMAL -> CalibrationStep.EYES_CLOSED
            CalibrationStep.EYES_CLOSED -> CalibrationStep.RESULTS
            CalibrationStep.RESULTS -> CalibrationStep.RESULTS
        }
        
        updateStepUI()
    }
    
    private fun showResults() {
        // Hide instruction panel, show results view
        binding.instructionPanel.visibility = View.GONE
        binding.resultsView.visibility = View.VISIBLE
        
        // Calculate averages for each step
        val wideOpenSamples = earSamples.filter { it.step == CalibrationStep.EYES_WIDE_OPEN }
        val normalSamples = earSamples.filter { it.step == CalibrationStep.EYES_NORMAL }
        val closedSamples = earSamples.filter { it.step == CalibrationStep.EYES_CLOSED }
        
        val avgWideOpen = wideOpenSamples.map { it.avgEAR }.average().toFloat()
        val avgNormal = normalSamples.map { it.avgEAR }.average().toFloat()
        val avgClosed = closedSamples.map { it.avgEAR }.average().toFloat()
        
        // Calculate thresholds
        val eyeClosedThreshold = (avgClosed + avgNormal) / 2f
        val eyeOpenThreshold = avgNormal * 0.85f
        
        // Update UI
        binding.resultsEyesOpen.text = String.format("Eyes Open: %.3f", avgNormal)
        binding.resultsEyesClosed.text = String.format("Eyes Closed: %.3f", avgClosed)
        binding.resultsThresholds.text = String.format(
            "Closed Threshold: %.3f\nOpen Threshold: %.3f",
            eyeClosedThreshold,
            eyeOpenThreshold
        )
        
        Log.i(TAG, "Calibration Results:")
        Log.i(TAG, "  Wide Open: $avgWideOpen")
        Log.i(TAG, "  Normal: $avgNormal")
        Log.i(TAG, "  Closed: $avgClosed")
        Log.i(TAG, "  Threshold (Closed): $eyeClosedThreshold")
        Log.i(TAG, "  Threshold (Open): $eyeOpenThreshold")
    }
    
    private fun saveCalibration() {
        // NOTE: EAR calibration is now legacy — the new CalibrationActivity
        // uses blendshape-based calibration. This just saves without EAR fields.
        val currentData = CalibrationData.load(this)
        currentData.save(this)
        
        Toast.makeText(this, "Calibration saved!", Toast.LENGTH_SHORT).show()
        finish()
    }
    
    private fun restartCalibration() {
        // Clear samples and restart
        earSamples.clear()
        currentStep = CalibrationStep.EYES_WIDE_OPEN
        
        binding.instructionPanel.visibility = View.VISIBLE
        binding.resultsView.visibility = View.GONE
        
        updateStepUI()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
        if (::faceMeshDetector.isInitialized) {
            faceMeshDetector.close()
        }
    }
    
    companion object {
        private const val TAG = "EARCalibration"
    }
}
