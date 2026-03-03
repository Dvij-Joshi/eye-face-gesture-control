package com.example.eyeandfacegesturephonecontrol.ui

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.example.eyeandfacegesturephonecontrol.MainActivity
import com.example.eyeandfacegesturephonecontrol.R
import com.example.eyeandfacegesturephonecontrol.databinding.ActivityOnboardingBinding
import com.example.eyeandfacegesturephonecontrol.service.EyeControlAccessibilityService
import com.example.eyeandfacegesturephonecontrol.utils.PermissionUtils

class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun setupListeners() {
        binding.btnGrantCamera.setOnClickListener {
            if (!PermissionUtils.hasCameraPermission(this)) {
                PermissionUtils.requestCameraPermission(this)
            }
        }

        binding.btnGrantOverlay.setOnClickListener {
            if (!PermissionUtils.hasOverlayPermission(this)) {
                PermissionUtils.requestOverlayPermission(this)
            }
        }

        binding.btnEnableAccessibility.setOnClickListener {
            if (!PermissionUtils.isAccessibilityServiceEnabled(this, EyeControlAccessibilityService::class.java)) {
                PermissionUtils.openAccessibilitySettings(this)
            }
        }

        binding.btnFinish.setOnClickListener {
            // Mark onboarding as complete
            getSharedPreferences("app_prefs", MODE_PRIVATE).edit().apply {
                putBoolean("onboarding_complete", true)
                apply()
            }
            
            // Navigate to MainActivity
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }

    private fun updatePermissionStatus() {
        val hasCamera = PermissionUtils.hasCameraPermission(this)
        val hasOverlay = PermissionUtils.hasOverlayPermission(this)
        val hasAccessibility = PermissionUtils.isAccessibilityServiceEnabled(this, EyeControlAccessibilityService::class.java)

        binding.cbCamera.isChecked = hasCamera
        binding.btnGrantCamera.isEnabled = !hasCamera
        binding.btnGrantCamera.text = if (hasCamera) "Granted" else "Grant"

        binding.cbOverlay.isChecked = hasOverlay
        binding.btnGrantOverlay.isEnabled = !hasOverlay
        binding.btnGrantOverlay.text = if (hasOverlay) "Granted" else "Grant"

        binding.cbAccessibility.isChecked = hasAccessibility
        binding.btnEnableAccessibility.isEnabled = !hasAccessibility
        binding.btnEnableAccessibility.text = if (hasAccessibility) "Enabled" else "Enable"

        binding.btnFinish.isEnabled = hasCamera && hasOverlay && hasAccessibility
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PermissionUtils.REQUEST_CAMERA_PERMISSION) {
            updatePermissionStatus()
            if (grantResults.isEmpty() || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Camera permission required", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
