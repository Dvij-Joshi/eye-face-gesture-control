package com.example.eyeandfacegesturephonecontrol.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.eyeandfacegesturephonecontrol.common.GestureEventBus
import com.example.eyeandfacegesturephonecontrol.databinding.ActivitySettingsBinding
import com.example.eyeandfacegesturephonecontrol.gestures.CalibrationData
import com.example.eyeandfacegesturephonecontrol.service.FaceTrackingService

class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var calibrationData: CalibrationData

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        calibrationData = CalibrationData.load(this)
        
        setupUI()
    }

    private fun setupUI() {
        binding.toolbar.setNavigationOnClickListener { finish() }

        // Snap helper for slider step sizes
        fun snapToStep(value: Float, step: Float = 0.1f): Float {
             return (Math.round(value / step) * step).coerceIn(0.1f, 20.0f)
        }

        // Map new CalibrationData fields to sliders:
        //   sliderSensitivityX  → maxYawDeg (lower = more sensitive)
        //   sliderSensitivityY  → maxPitchDeg
        //   sliderSmoothing     → kalmanMeasure (higher = smoother)
        binding.sliderSensitivityX.value = snapToStep(calibrationData.maxYawDeg)
            .coerceIn(binding.sliderSensitivityX.valueFrom, binding.sliderSensitivityX.valueTo)
        binding.sliderSensitivityY.value = snapToStep(calibrationData.maxPitchDeg)
            .coerceIn(binding.sliderSensitivityY.valueFrom, binding.sliderSensitivityY.valueTo)
        binding.sliderSmoothing.value = snapToStep(calibrationData.kalmanMeasure)
            .coerceIn(binding.sliderSmoothing.valueFrom, binding.sliderSmoothing.valueTo)

        binding.sliderSensitivityX.addOnChangeListener { _, value, _ ->
            calibrationData = calibrationData.copy(maxYawDeg = value)
            calibrationData.save(this)
            notifyServiceConfigChange()
        }

        binding.sliderSensitivityY.addOnChangeListener { _, value, _ ->
            calibrationData = calibrationData.copy(maxPitchDeg = value)
            calibrationData.save(this)
            notifyServiceConfigChange()
        }
        
        binding.sliderSmoothing.addOnChangeListener { _, value, _ ->
            calibrationData = calibrationData.copy(kalmanMeasure = value)
            calibrationData.save(this)
            notifyServiceConfigChange()
        }

        binding.btnResetDefaults.setOnClickListener {
            calibrationData = CalibrationData.getDefaults()
            calibrationData.save(this)
            notifyServiceConfigChange()
            
            binding.sliderSensitivityX.value = snapToStep(calibrationData.maxYawDeg)
                .coerceIn(binding.sliderSensitivityX.valueFrom, binding.sliderSensitivityX.valueTo)
            binding.sliderSensitivityY.value = snapToStep(calibrationData.maxPitchDeg)
                .coerceIn(binding.sliderSensitivityY.valueFrom, binding.sliderSensitivityY.valueTo)
            binding.sliderSmoothing.value = snapToStep(calibrationData.kalmanMeasure)
                .coerceIn(binding.sliderSmoothing.valueFrom, binding.sliderSmoothing.valueTo)
            
            Toast.makeText(this, "Settings Reset", Toast.LENGTH_SHORT).show()
        }
    }
    
    private fun notifyServiceConfigChange() {
        if (FaceTrackingService.ServiceHelper.isRunning) {
            val intent = android.content.Intent(this, FaceTrackingService::class.java).apply {
                action = FaceTrackingService.ACTION_UPDATE_CONFIG
            }
            startService(intent)
        }
    }
}
