package com.example.zoom

/**
 * The role of a physical camera lens on the device.
 */
enum class LensRole {
    ULTRA_WIDE,
    PRIMARY,
    TELE
}

/**
 * Describes a single physical camera lens discovered at runtime.
 *
 * @property role Which logical role this lens serves (UW / Primary / Tele)
 * @property logicalCameraId The Camera2 logical camera ID string (e.g. "0")
 * @property physicalCameraId The Camera2 physical camera ID string (not stable across OS versions)
 * @property equivFocalMm The 35mm-equivalent focal length in mm (e.g. 13.4, 24, 116.2)
 * @property nativeMegapixels The full-resolution megapixel count of the sensor
 * @property maxApertureF The maximum aperture (e.g. 1.85, 2.2, 3.5)
 * @property hasOIS Whether this lens has optical image stabilisation
 */
data class LensProfile(
    val role: LensRole,
    val logicalCameraId: String,
    val physicalCameraId: String,
    val equivFocalMm: Float,
    val nativeMegapixels: Int,
    val maxApertureF: Float,
    val hasOIS: Boolean
)
