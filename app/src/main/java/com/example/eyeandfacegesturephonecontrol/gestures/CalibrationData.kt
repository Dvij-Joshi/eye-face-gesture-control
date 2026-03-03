package com.example.eyeandfacegesturephonecontrol.gestures

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName

/**
 * Calibration data — stores blendshape-based gesture thresholds and head pose reference.
 * Replaces old EAR-based thresholds with MediaPipe blendshape scores.
 */
data class CalibrationData(
    // Head pose reference (neutral position from calibration)
    @SerializedName("ref_yaw")
    val refYaw: Float = 0f,

    @SerializedName("ref_pitch")
    val refPitch: Float = 0f,

    // Blendshape gesture thresholds (0.0 to 1.0)
    @SerializedName("brow_up_threshold")
    val browUpThreshold: Float = 0.4f,

    @SerializedName("brow_down_threshold")
    val browDownThreshold: Float = 0.4f,

    @SerializedName("wink_r_threshold")
    val winkRThreshold: Float = 0.5f,

    @SerializedName("wink_l_threshold")
    val winkLThreshold: Float = 0.5f,

    // Head angle mapping
    @SerializedName("max_yaw_deg")
    val maxYawDeg: Float = 8f,

    @SerializedName("max_pitch_deg")
    val maxPitchDeg: Float = 6f,

    // Dead zone in degrees
    @SerializedName("dead_zone_deg")
    val deadZoneDeg: Float = 2.5f,

    // Kalman filter tuning
    @SerializedName("kalman_process")
    val kalmanProcess: Float = 0.003f,

    @SerializedName("kalman_measure")
    val kalmanMeasure: Float = 0.8f,

    // Gesture timing
    @SerializedName("dwell_time_ms")
    val dwellTimeMs: Long = 350L,

    @SerializedName("cooldown_ms")
    val cooldownMs: Long = 800L,

    // Distance-adaptive sensitivity
    @SerializedName("base_face_scale")
    val baseFaceScale: Float = 0.30f,

    @SerializedName("adapt_sensitivity")
    val adaptSensitivity: Boolean = true,

    // Calibration metadata
    @SerializedName("is_calibrated")
    val isCalibrated: Boolean = false,

    @SerializedName("calibration_timestamp")
    val calibrationTimestamp: Long = 0L
) {

    companion object {
        private const val PREF_NAME = "eye_control_calibration"
        private const val KEY_CALIBRATION_DATA = "calibration_data_v3"
        private val gson = Gson()

        /**
         * Load calibration data from SharedPreferences
         */
        fun load(context: Context): CalibrationData {
            val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString(KEY_CALIBRATION_DATA, null)
            return if (json != null) {
                try {
                    gson.fromJson(json, CalibrationData::class.java)
                } catch (e: Exception) {
                    CalibrationData()
                }
            } else {
                CalibrationData()
            }
        }

        fun getDefaults(): CalibrationData = CalibrationData()
    }

    /**
     * Save calibration data to SharedPreferences
     */
    fun save(context: Context) {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val json = gson.toJson(this)
        prefs.edit().putString(KEY_CALIBRATION_DATA, json).apply()
    }

    /**
     * Create a copy with calibration results
     */
    fun withCalibrationResults(
        refYaw: Float,
        refPitch: Float,
        browUpThreshold: Float,
        browDownThreshold: Float,
        winkRThreshold: Float,
        winkLThreshold: Float
    ): CalibrationData {
        return copy(
            refYaw = refYaw,
            refPitch = refPitch,
            browUpThreshold = browUpThreshold,
            browDownThreshold = browDownThreshold,
            winkRThreshold = winkRThreshold,
            winkLThreshold = winkLThreshold,
            isCalibrated = true,
            calibrationTimestamp = System.currentTimeMillis()
        )
    }
}
