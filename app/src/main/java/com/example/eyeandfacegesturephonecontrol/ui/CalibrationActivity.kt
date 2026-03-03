package com.example.eyeandfacegesturephonecontrol.ui

import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.eyeandfacegesturephonecontrol.R
import com.example.eyeandfacegesturephonecontrol.camera.CameraManager
import com.example.eyeandfacegesturephonecontrol.databinding.ActivityCalibrationBinding
import com.example.eyeandfacegesturephonecontrol.gestures.CalibrationData
import com.example.eyeandfacegesturephonecontrol.service.FaceTrackingService
import com.example.eyeandfacegesturephonecontrol.utils.PermissionUtils
import com.example.eyeandfacegesturephonecontrol.vision.FaceMeshDetector
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult
import kotlin.math.*

/**
 * Calibration wizard — 5-step blendshape-verified calibration.
 *
 * Steps:
 *   1. REST      — neutral face, captures head pose + resting blendshape values
 *   2. BROW_UP   — raise eyebrows high, captures browInnerUp peak
 *   3. BROW_DOWN — squint/furrow eyebrows, captures browDownLeft/Right peak
 *   4. WINK_R    — wink right eye (keep left open), captures eyeBlinkRight peak
 *   5. WINK_L    — wink left eye (keep right open), captures eyeBlinkLeft peak
 *
 * Thresholds are calculated at 65% between rest and peak.
 */
class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding
    private lateinit var cameraManager: CameraManager
    private lateinit var faceMeshDetector: FaceMeshDetector

    // State
    private var isServiceWasRunning = false
    private var currentStepIndex = 0
    private var capturedSamples = 0

    // Per-step sample storage
    private val stepSamples = mutableListOf<Map<String, Float>>()

    // Head pose reference
    private var refYawSum = 0f
    private var refPitchSum = 0f
    private var headPoseSamples = 0
    private var refYaw = 0f
    private var refPitch = 0f

    // Rest values (from step 0)
    private var restBrowUp = 0f
    private var restBrowDown = 0f
    private var restBlinkR = 0f
    private var restBlinkL = 0f

    // Peak values (from steps 1-4)
    private var peakBrowUp = 0f
    private var peakBrowDown = 0f
    private var peakBlinkR = 0f
    private var peakBlinkL = 0f

    companion object {
        private const val TAG = "CalibrationActivity"
        private const val SAMPLES_PER_STEP = 30
    }

    data class CalibStep(
        val name: String,
        val instruction: String,
        val subInstruction: String,
        val blendshapeKey: String?,
        val minSignal: Float
    )

    private val steps = listOf(
        CalibStep("REST",      "Look straight ahead",      "Keep a neutral face, stay still",            null,             0f),
        CalibStep("BROW_UP",   "RAISE EYEBROWS high",      "Raise both eyebrows high and hold",          "browInnerUp",    0.08f),
        CalibStep("BROW_DOWN", "SQUINT/FURROW eyebrows",   "Pull eyebrows down as if angry, hold",       "browDownLeft",   0.08f),
        CalibStep("WINK_R",    "WINK RIGHT EYE",           "Close only your RIGHT eye, keep left open",  "eyeBlinkRight",  0.20f),
        CalibStep("WINK_L",    "WINK LEFT EYE",            "Close only your LEFT eye, keep right open",  "eyeBlinkLeft",   0.20f)
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        isServiceWasRunning = FaceTrackingService.ServiceHelper.isRunning
        if (isServiceWasRunning) {
            FaceTrackingService.ServiceHelper.stop(this)
        }

        setupCameraAndDetector()
        setupUI()
        startStep(0)
    }

    private fun setupCameraAndDetector() {
        faceMeshDetector = FaceMeshDetector(
            context = this,
            onResults = { result, _ ->
                binding.faceMeshOverlay.setResults(result, 1600, 1200, 0)
                processCalibrationFrame(result)
            },
            onError = { Log.e(TAG, "Detector error: $it") }
        )
        faceMeshDetector.initialize()

        cameraManager = CameraManager(
            context = this,
            lifecycleOwner = this,
            onFrameAvailable = { imageProxy ->
                if (faceMeshDetector.isReady()) {
                    faceMeshDetector.processFrame(imageProxy)
                } else {
                    imageProxy.close()
                }
            },
            onError = { Log.e(TAG, "Camera error: $it") }
        )

        if (PermissionUtils.hasCameraPermission(this)) {
            binding.viewFinder.post {
                cameraManager.startCamera(binding.viewFinder)
            }
        }
    }

    private fun setupUI() {
        binding.btnBack.setOnClickListener { finish() }
        binding.btnAction.setOnClickListener {
            if (currentStepIndex >= steps.size) {
                saveAndFinish()
            }
        }
    }

    private fun startStep(index: Int) {
        currentStepIndex = index
        capturedSamples = 0
        stepSamples.clear()

        runOnUiThread {
            if (index < steps.size) {
                val step = steps[index]
                binding.tvInstruction.text = step.instruction
                binding.tvSubInstruction.text = step.subInstruction
                binding.tvProgress.text = "Step ${index + 1} of ${steps.size}"
                binding.progressBar.progress = 0
                binding.btnAction.text = "Collecting..."
                binding.btnAction.isEnabled = false
                binding.tvWarning.visibility = View.GONE
                binding.tvLiveFeedback.text = ""

                val crosshairVisible = if (index == 0) View.VISIBLE else View.GONE
                binding.viewCrosshairVertical.visibility = crosshairVisible
                binding.viewCrosshairHorizontal.visibility = crosshairVisible
            } else {
                showComplete()
            }
        }
    }

    private fun showComplete() {
        runOnUiThread {
            binding.tvInstruction.text = getString(R.string.calibration_success)
            binding.tvSubInstruction.text = "Gesture thresholds calibrated."
            binding.tvProgress.text = "Complete"
            binding.progressBar.progress = 100
            binding.btnAction.text = getString(R.string.btn_finish)
            binding.btnAction.isEnabled = true
            binding.viewCrosshairVertical.visibility = View.GONE
            binding.viewCrosshairHorizontal.visibility = View.GONE
            binding.tvWarning.visibility = View.GONE
            binding.tvLiveFeedback.text = String.format(
                "BrowUp: %.3f | BrowDn: %.3f | WinkR: %.3f | WinkL: %.3f",
                computeThreshold(restBrowUp, peakBrowUp),
                computeThreshold(restBrowDown, peakBrowDown),
                computeThreshold(restBlinkR, peakBlinkR),
                computeThreshold(restBlinkL, peakBlinkL)
            )
        }
    }

    private fun processCalibrationFrame(result: FaceLandmarkerResult) {
        if (currentStepIndex >= steps.size) return
        if (result.faceLandmarks().isEmpty()) return

        val step = steps[currentStepIndex]

        val bsOpt = result.faceBlendshapes()
        if (!bsOpt.isPresent || bsOpt.get().isEmpty()) return
        val categories = bsOpt.get()[0]
        val bs = mutableMapOf<String, Float>()
        for (cat in categories) {
            bs[cat.categoryName()] = cat.score()
        }

        val browInnerUp = bs["browInnerUp"]   ?: 0f
        val browDownL   = bs["browDownLeft"]   ?: 0f
        val browDownR   = bs["browDownRight"]  ?: 0f
        val browDown    = (browDownL + browDownR) / 2f
        val blinkR      = bs["eyeBlinkRight"]  ?: 0f
        val blinkL      = bs["eyeBlinkLeft"]   ?: 0f

        var gestureDetected = false
        var warningMsg = ""
        var liveInfo = ""

        when (step.name) {
            "REST" -> {
                gestureDetected = true
                liveInfo = String.format("browUp:%.2f browDn:%.2f blinkR:%.2f blinkL:%.2f",
                    browInnerUp, browDown, blinkR, blinkL)
            }
            "BROW_UP" -> {
                liveInfo = String.format("browInnerUp: %.3f (need > %.2f)", browInnerUp, step.minSignal)
                if (browInnerUp >= step.minSignal) {
                    gestureDetected = true
                } else {
                    warningMsg = "Raise eyebrows higher!"
                }
            }
            "BROW_DOWN" -> {
                liveInfo = String.format("browDown: %.3f (need > %.2f)", browDown, step.minSignal)
                if (browDown >= step.minSignal) {
                    gestureDetected = true
                } else {
                    warningMsg = "Squint/furrow harder!"
                }
            }
            "WINK_R" -> {
                liveInfo = String.format("RIGHT: %.3f  LEFT: %.3f  (right high, left low)", blinkR, blinkL)
                if (blinkR > 0.20f && blinkL < 0.25f) {
                    gestureDetected = true
                } else if (blinkL > 0.20f) {
                    warningMsg = "Wrong eye! Wink your RIGHT eye"
                } else {
                    warningMsg = String.format("No wink detected (right: %.2f)", blinkR)
                }
            }
            "WINK_L" -> {
                liveInfo = String.format("LEFT: %.3f  RIGHT: %.3f  (left high, right low)", blinkL, blinkR)
                if (blinkL > 0.20f && blinkR < 0.25f) {
                    gestureDetected = true
                } else if (blinkR > 0.20f) {
                    warningMsg = "Wrong eye! Wink your LEFT eye"
                } else {
                    warningMsg = String.format("No wink detected (left: %.2f)", blinkL)
                }
            }
        }

        if (gestureDetected) {
            val sample = mapOf(
                "browInnerUp"   to browInnerUp,
                "browDownAvg"   to browDown,
                "eyeBlinkRight" to blinkR,
                "eyeBlinkLeft"  to blinkL
            )
            stepSamples.add(sample)
            capturedSamples++

            // Capture head pose during REST
            if (step.name == "REST") {
                val matOpt = result.facialTransformationMatrixes()
                if (matOpt.isPresent && matOpt.get().isNotEmpty()) {
                    val m: FloatArray = matOpt.get()[0]
                    if (m.size >= 16) {
                        val r02: Float = m[2 * 4 + 0]
                        val r12: Float = m[2 * 4 + 1]
                        val r22: Float = m[2 * 4 + 2]
                        val negR12: Float = -r12
                        val pitch = Math.toDegrees(asin(negR12.toDouble().coerceIn(-1.0, 1.0))).toFloat()
                        val yaw   = Math.toDegrees(atan2(r02.toDouble(), r22.toDouble())).toFloat()
                        refYawSum += yaw
                        refPitchSum += pitch
                        headPoseSamples++
                    }
                }
            }

            if (capturedSamples >= SAMPLES_PER_STEP) {
                finishStep()
                return
            }
        }

        val progress = (capturedSamples * 100) / SAMPLES_PER_STEP
        runOnUiThread {
            binding.progressBar.progress = progress
            binding.tvLiveFeedback.text = liveInfo

            if (warningMsg.isNotEmpty()) {
                binding.tvWarning.visibility = View.VISIBLE
                binding.tvWarning.text = warningMsg
                binding.tvWarning.setTextColor(0xFFFF6644.toInt())
            } else if (gestureDetected && step.name != "REST") {
                binding.tvWarning.visibility = View.VISIBLE
                binding.tvWarning.text = "✓ Gesture detected! Hold it..."
                binding.tvWarning.setTextColor(0xFF44FF44.toInt())
            } else {
                binding.tvWarning.visibility = View.GONE
            }

            binding.btnAction.text = "Collecting... $progress%"
        }
    }

    private fun finishStep() {
        val step = steps[currentStepIndex]
        Log.i(TAG, "Step ${step.name} complete with $capturedSamples samples")

        when (step.name) {
            "REST" -> {
                restBrowUp   = stepSamples.map { it["browInnerUp"]   ?: 0f }.average().toFloat()
                restBrowDown = stepSamples.map { it["browDownAvg"]   ?: 0f }.average().toFloat()
                restBlinkR   = stepSamples.map { it["eyeBlinkRight"] ?: 0f }.average().toFloat()
                restBlinkL   = stepSamples.map { it["eyeBlinkLeft"]  ?: 0f }.average().toFloat()

                if (headPoseSamples > 0) {
                    refYaw   = refYawSum / headPoseSamples
                    refPitch = refPitchSum / headPoseSamples
                }

                Log.i(TAG, "REST baselines: browUp=%.3f browDn=%.3f blinkR=%.3f blinkL=%.3f refYaw=%.1f refPitch=%.1f"
                    .format(restBrowUp, restBrowDown, restBlinkR, restBlinkL, refYaw, refPitch))
            }
            "BROW_UP" -> {
                peakBrowUp = stepSamples.map { it["browInnerUp"] ?: 0f }.average().toFloat()
                Log.i(TAG, "BROW_UP peak: %.3f (rest=%.3f)".format(peakBrowUp, restBrowUp))
            }
            "BROW_DOWN" -> {
                peakBrowDown = stepSamples.map { it["browDownAvg"] ?: 0f }.average().toFloat()
                Log.i(TAG, "BROW_DOWN peak: %.3f (rest=%.3f)".format(peakBrowDown, restBrowDown))
            }
            "WINK_R" -> {
                peakBlinkR = stepSamples.map { it["eyeBlinkRight"] ?: 0f }.average().toFloat()
                Log.i(TAG, "WINK_R peak: %.3f (rest=%.3f)".format(peakBlinkR, restBlinkR))
            }
            "WINK_L" -> {
                peakBlinkL = stepSamples.map { it["eyeBlinkLeft"] ?: 0f }.average().toFloat()
                Log.i(TAG, "WINK_L peak: %.3f (rest=%.3f)".format(peakBlinkL, restBlinkL))
            }
        }

        startStep(currentStepIndex + 1)
    }

    private fun computeThreshold(rest: Float, peak: Float): Float {
        return rest + (peak - rest) * 0.65f
    }

    private fun saveAndFinish() {
        val browUpThresh   = computeThreshold(restBrowUp, peakBrowUp).coerceIn(0.15f, 0.9f)
        val browDownThresh = computeThreshold(restBrowDown, peakBrowDown).coerceIn(0.10f, 0.9f)
        val winkRThresh    = computeThreshold(restBlinkR, peakBlinkR).coerceIn(0.20f, 0.9f)
        val winkLThresh    = computeThreshold(restBlinkL, peakBlinkL).coerceIn(0.20f, 0.9f)

        Log.i(TAG, "Saving: browUp=%.3f browDn=%.3f winkR=%.3f winkL=%.3f refYaw=%.1f refPitch=%.1f"
            .format(browUpThresh, browDownThresh, winkRThresh, winkLThresh, refYaw, refPitch))

        val data = CalibrationData().withCalibrationResults(
            refYaw = refYaw,
            refPitch = refPitch,
            browUpThreshold = browUpThresh,
            browDownThreshold = browDownThresh,
            winkRThreshold = winkRThresh,
            winkLThreshold = winkLThresh
        )
        data.save(this)

        if (isServiceWasRunning) {
            FaceTrackingService.ServiceHelper.start(this)
        }

        Toast.makeText(this, "Calibration Saved!", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraManager.isInitialized) {
            cameraManager.shutdown()
        }
        if (::faceMeshDetector.isInitialized) {
            faceMeshDetector.close()
        }
        if (isServiceWasRunning && !FaceTrackingService.ServiceHelper.isRunning && !isFinishing) {
            FaceTrackingService.ServiceHelper.start(this)
        }
    }
}
