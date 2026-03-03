package com.example.eyeandfacegesturephonecontrol.ui

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

        // Set slider values from calibration data, clamped to slider ranges
        binding.sliderSensitivityX.value = calibrationData.maxYawDeg
            .coerceIn(binding.sliderSensitivityX.valueFrom, binding.sliderSensitivityX.valueTo)
        binding.sliderSensitivityY.value = calibrationData.maxPitchDeg
            .coerceIn(binding.sliderSensitivityY.valueFrom, binding.sliderSensitivityY.valueTo)
        binding.sliderSmoothing.value = calibrationData.kalmanMeasure
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
            
            binding.sliderSensitivityX.value = calibrationData.maxYawDeg
                .coerceIn(binding.sliderSensitivityX.valueFrom, binding.sliderSensitivityX.valueTo)
            binding.sliderSensitivityY.value = calibrationData.maxPitchDeg
                .coerceIn(binding.sliderSensitivityY.valueFrom, binding.sliderSensitivityY.valueTo)
            binding.sliderSmoothing.value = calibrationData.kalmanMeasure
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
