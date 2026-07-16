package com.example.zoom

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.SizeF

/**
 * Discovers and catalogs the physical camera lenses on the device at runtime.
 *
 * Uses Camera2 characteristics to identify which physical camera ID corresponds
 * to Ultra-wide, Primary, and Telephoto lenses. Never hardcodes camera ID indices
 * as they are not stable across OS versions.
 */
class LensCatalog(private val context: Context) {

    companion object {
        private const val TAG = "LensCatalog"

        // 35mm full-frame sensor diagonal in mm
        private const val FULL_FRAME_DIAGONAL_MM = 43.27f

        // Thresholds for identifying lens roles based on equivalent focal length
        private const val ULTRA_WIDE_THRESHOLD_MM = 20f
        private const val TELE_THRESHOLD_MM = 70f
    }

    data class CatalogResult(
        val ultraWide: LensProfile?,
        val primary: LensProfile?,
        val tele: LensProfile?,
        val allLenses: List<LensProfile>
    )

    /**
     * Enumerates all back-facing cameras and classifies them by role.
     * Call this once at app start and cache the result.
     */
    fun enumerate(): CatalogResult {
        val profiles = mutableListOf<LensProfile>()

        try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

            for (cameraId in cameraManager.cameraIdList) {
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)
                val facing = characteristics.get(CameraCharacteristics.LENS_FACING)

                // Only consider back-facing cameras
                if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)

                if (focalLengths == null || focalLengths.isEmpty()) continue

                // Use the widest (smallest) focal length as the lens's native focal length
                val nativeFocalMm = focalLengths[0]
                val cropFactor = computeCropFactor(physicalSize)
                val equivFocalMm = nativeFocalMm * cropFactor

                // Estimate megapixels from sensor size
                val megapixels = estimateMegapixels(characteristics)

                val hasOIS = hasOpticalStabilization(characteristics)
                val maxAperture = estimateMaxAperture(characteristics)

                val role = classifyLens(equivFocalMm)

                profiles.add(
                    LensProfile(
                        role = role,
                        physicalCameraId = cameraId,
                        equivFocalMm = equivFocalMm,
                        nativeMegapixels = megapixels,
                        maxApertureF = maxAperture,
                        hasOIS = hasOIS
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error enumerating cameras", e)
        }

        // Sort by focal length ascending (UW first, Tele last)
        profiles.sortBy { it.equivFocalMm }

        val ultraWide = profiles.find { it.role == LensRole.ULTRA_WIDE }
        val primary = profiles.find { it.role == LensRole.PRIMARY }
        val tele = profiles.find { it.role == LensRole.TELE }

        return CatalogResult(
            ultraWide = ultraWide,
            primary = primary,
            tele = tele,
            allLenses = profiles
        )
    }

    /**
     * Computes the 35mm crop factor from the sensor's physical dimensions.
     */
    private fun computeCropFactor(physicalSize: SizeF?): Float {
        if (physicalSize == null) return 1.0f
        val sensorDiagonal = kotlin.math.sqrt(
            physicalSize.width * physicalSize.width + physicalSize.height * physicalSize.height
        )
        return FULL_FRAME_DIAGONAL_MM / sensorDiagonal
    }

    /**
     * Estimates megapixels from the sensor's active array size.
     */
    private fun estimateMegapixels(characteristics: CameraCharacteristics): Int {
        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        if (sensorSize != null) {
            val mp = (sensorSize.width().toLong() * sensorSize.height().toLong()) / 1_000_000
            return mp.toInt().coerceAtLeast(1)
        }
        return 12 // fallback
    }

    /**
     * Checks if the lens has optical image stabilization.
     */
    private fun hasOpticalStabilization(characteristics: CameraCharacteristics): Boolean {
        val availableOisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
        return availableOisModes?.any { it != CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_OFF } == true
    }

    /**
     * Estimates the maximum aperture from available aperture values.
     */
    private fun estimateMaxAperture(characteristics: CameraCharacteristics): Float {
        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        return apertures?.minOrNull() ?: 2.0f
    }

    /**
     * Classifies a lens by its 35mm-equivalent focal length.
     */
    private fun classifyLens(equivFocalMm: Float): LensRole {
        return when {
            equivFocalMm < ULTRA_WIDE_THRESHOLD_MM -> LensRole.ULTRA_WIDE
            equivFocalMm > TELE_THRESHOLD_MM -> LensRole.TELE
            else -> LensRole.PRIMARY
        }
    }
}