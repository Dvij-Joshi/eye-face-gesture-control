package com.example.eyeandfacegesturephonecontrol

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.eyeandfacegesturephonecontrol.common.GestureEventBus
import com.example.eyeandfacegesturephonecontrol.databinding.ActivityMainBinding
import com.example.eyeandfacegesturephonecontrol.service.EyeControlAccessibilityService
import com.example.eyeandfacegesturephonecontrol.service.FaceTrackingService
import com.example.eyeandfacegesturephonecontrol.ui.CalibrationActivity
import com.example.eyeandfacegesturephonecontrol.ui.OnboardingActivity
import com.example.eyeandfacegesturephonecontrol.ui.SettingsActivity
import com.example.eyeandfacegesturephonecontrol.utils.PermissionUtils
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Check if this is first run - if so, launch onboarding
        val prefs = getSharedPreferences("app_prefs", MODE_PRIVATE)
        val hasCompletedOnboarding = prefs.getBoolean("onboarding_complete", false)
        
        if (!hasCompletedOnboarding) {
            startActivity(Intent(this, OnboardingActivity::class.java))
            finish()
            return
        }
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeServiceState()
    }

    override fun onResume() {
        super.onResume()
        checkPermissions()
    }

    private fun setupUI() {
        binding.btnToggleService.setOnClickListener {
            val isRunning = FaceTrackingService.ServiceHelper.isRunning
            if (isRunning) {
                FaceTrackingService.ServiceHelper.stop(this)
            } else {
                if (checkPermissions()) {
                   FaceTrackingService.ServiceHelper.start(this)
                }
            }
        }

        binding.btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.btnCalibrate.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        binding.btnTutorial.setOnClickListener {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }
        
        binding.btnEARCalibration.setOnClickListener {
            startActivity(Intent(this, com.example.eyeandfacegesturephonecontrol.ui.EARCalibrationActivity::class.java))
        }
        
        binding.tvPermissionWarning.setOnClickListener {
             if (!PermissionUtils.hasCameraPermission(this)) {
                PermissionUtils.requestCameraPermission(this)
            } else if (!PermissionUtils.hasOverlayPermission(this)) {
                PermissionUtils.requestOverlayPermission(this)
            } else if (!PermissionUtils.isAccessibilityServiceEnabled(this, EyeControlAccessibilityService::class.java)) {
                PermissionUtils.openAccessibilitySettings(this)
            }
        }
    }

    private fun checkPermissions(): Boolean {
        val hasCamera = PermissionUtils.hasCameraPermission(this)
        val hasOverlay = PermissionUtils.hasOverlayPermission(this)
        val isAccessibilityEnabled = PermissionUtils.isAccessibilityServiceEnabled(
            this,
            EyeControlAccessibilityService::class.java
        )

        val allGranted = hasCamera && hasOverlay && isAccessibilityEnabled

        if (!allGranted) {
            binding.tvPermissionWarning.visibility = View.VISIBLE
            binding.tvPermissionWarning.text = when {
                !hasCamera -> getString(R.string.permission_camera_rationale)
                !hasOverlay -> getString(R.string.permission_overlay_rationale)
                !isAccessibilityEnabled -> getString(R.string.permission_accessibility_message)
                else -> ""
            }
        } else {
            binding.tvPermissionWarning.visibility = View.GONE
        }
        
        return allGranted
    }

    private fun observeServiceState() {
        lifecycleScope.launch {
            GestureEventBus.serviceState.collectLatest { state ->
                updateServiceStatus(state.isRunning)
            }
        }
        
        lifecycleScope.launch {
             GestureEventBus.gestureEvents.collectLatest { event ->
                 binding.tvLastGesture.text = "${getString(R.string.current_gesture_label)} $event"
             }
        }
    }

    private fun updateServiceStatus(isRunning: Boolean) {
        if (isRunning) {
            binding.tvServiceStatus.text = getString(R.string.service_status_running)
            binding.viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_running)
            )
            binding.btnToggleService.text = getString(R.string.btn_stop_service)
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.error)
            )
        } else {
            binding.tvServiceStatus.text = getString(R.string.service_status_stopped)
            binding.viewStatusIndicator.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.status_stopped)
            )
            binding.btnToggleService.text = getString(R.string.btn_start_service)
            binding.btnToggleService.backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, R.color.primary)
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.REQUEST_CAMERA_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                checkPermissions()
            } else {
                Toast.makeText(this, getString(R.string.error_camera_permission_denied), Toast.LENGTH_SHORT).show()
            }
        }
    }
}