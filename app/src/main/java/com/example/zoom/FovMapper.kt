package com.example.zoom

/**
 * Core math module for the zoom-box camera.
 *
 * All functions are pure — no Android dependencies, fully unit-testable.
 *
 * The zoom-box works by showing a "framing box" on the preview that indicates
 * what portion of the scene will be captured at the target focal length.
 *
 * Box size formula:
 *   boxScale = previewFocalMm / targetFocalMm
 *
 * This is derived from the rectilinear projection assumption:
 *   HFOV(f) = 2 * atan(18 / f)   (18 = half 36mm sensor width)
 *   boxScale = tan(HFOV(target)/2) / tan(HFOV(preview)/2) = preview/target
 *
 * The atan/tan cancel exactly, so boxScale is purely the focal length ratio.
 */
object FovMapper {

    /**
     * Computes the box scale factor for the zoom-box overlay.
     *
     * @param previewFocalMm The equivalent focal length of the current preview lens
     * @param targetFocalMm The target equivalent focal length the user has selected
     * @return A value in (0, 1] representing the fraction of the frame the box covers.
     *         Returns 0f if targetFocalMm is 0 (should not happen in practice).
     */
    fun boxScale(previewFocalMm: Float, targetFocalMm: Float): Float {
        if (targetFocalMm <= 0f || previewFocalMm <= 0f) return 1f
        return (previewFocalMm / targetFocalMm).coerceIn(0f, 1f)
    }

    /**
     * Determines which lens should be used for the live preview.
     *
     * The preview lens is chosen for *context* (showing the whole scene):
     * - Below the Primary's focal length, use the Ultra-wide
     * - At or above the Primary's focal length, use the Primary
     *
     * The Tele lens is never used for preview — it's only switched in at capture time.
     */
    fun previewLens(
        targetFocalMm: Float,
        primaryFocalMm: Float,
        ultraWideFocalMm: Float
    ): LensRole {
        return when {
            targetFocalMm < primaryFocalMm -> LensRole.ULTRA_WIDE
            else -> LensRole.PRIMARY
        }
    }

    /**
     * Determines which lens should be used for image capture.
     *
     * Uses hysteresis around the telephoto threshold to prevent flickering
     * when the user's finger sits right on the boundary.
     *
     * @param targetFocalMm The target equivalent focal length
     * @param currentCaptureLens The lens currently selected for capture (to hold steady in hysteresis band)
     * @param primaryFocalMm The Primary lens's equivalent focal length
     * @param teleFocalMm The Telephoto lens's equivalent focal length
     * @param hysteresisMm The hysteresis margin in mm (default 8f as per spec)
     * @return The LensRole to use for capture
     */
    fun captureLens(
        targetFocalMm: Float,
        currentCaptureLens: LensRole,
        primaryFocalMm: Float,
        teleFocalMm: Float,
        ultraWideFocalMm: Float,
        hysteresisMm: Float = 8f
    ): LensRole {
        return when {
            // Above tele threshold → use Tele
            targetFocalMm >= teleFocalMm -> LensRole.TELE

            // Below tele threshold minus hysteresis → use Primary or Ultra-wide
            targetFocalMm < teleFocalMm - hysteresisMm -> {
                if (targetFocalMm < primaryFocalMm) LensRole.ULTRA_WIDE
                else LensRole.PRIMARY
            }

            // Inside hysteresis band → hold steady on current capture lens
            else -> currentCaptureLens
        }
    }

    /**
     * Determines if the Tele lens should be pre-warmed.
     * Pre-warming is triggered speculatively when the target focal length
     * gets within `triggerDistanceMm` of the telephoto threshold.
     */
    fun shouldPreWarmTele(
        targetFocalMm: Float,
        teleFocalMm: Float,
        triggerDistanceMm: Float = 15f
    ): Boolean {
        return targetFocalMm >= teleFocalMm - triggerDistanceMm
    }

    /**
     * Computes the digital crop factor for the capture.
     *
     * When the target focal length exceeds the native focal length of the
     * capture lens, we need to digitally crop the output.
     *
     * @param captureLensFocalMm The native equivalent focal length of the capture lens
     * @param targetFocalMm The target equivalent focal length
     * @return The crop factor (≥ 1.0). 1.0 means no digital crop.
     */
    fun captureCropFactor(captureLensFocalMm: Float, targetFocalMm: Float): Float {
        if (targetFocalMm <= 0f || captureLensFocalMm <= 0f) return 1f
        return (targetFocalMm / captureLensFocalMm).coerceAtLeast(1f)
    }

    /**
     * Estimates the output megapixels after crop.
     *
     * @param nativeMp The native megapixels of the capture lens
     * @param cropFactor The digital crop factor (≥ 1.0)
     * @return Approximate output megapixels after cropping
     */
    fun outputMegapixels(nativeMp: Int, cropFactor: Float): Float {
        return nativeMp / (cropFactor * cropFactor)
    }
}