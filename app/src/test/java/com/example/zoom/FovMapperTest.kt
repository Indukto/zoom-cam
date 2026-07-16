package com.example.zoom

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for FovMapper — the pure math module.
 * No Android dependencies needed, runs on JVM.
 *
 * Tests the acceptance criteria from the architecture spec §10:
 * 1. At 24mm, box scale = 1.0 (full-frame).
 * 2. At 85mm, box scale ≈ 0.282, preview lens still Primary.
 * 3. At exactly 116.2mm, capture silently switches to Tele; no visible preview lens change.
 * 4. Zooming past 116.2mm and back below ~108mm (hysteresis band) does not flicker the capture lens.
 * 5. Box aspect ratio matches sensor crop AR regardless of device orientation or display AR.
 */
class FovMapperTest {

    // Pixel 7 Pro reference focal lengths from the spec
    private val UW_FOCAL_MM = 13.4f
    private val PRIMARY_FOCAL_MM = 24f
    private val TELE_FOCAL_MM = 116.2f
    private val HYSTERESIS_MM = 8f

    // --- boxScale tests ---

    @Test
    fun `boxScale at primary focal length returns 1`() {
        // At 24mm with Primary preview (24mm), box should fill the frame
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, PRIMARY_FOCAL_MM)
        assertEquals("At native focal length, boxScale should be 1.0", 1.0f, scale, 0.001f)
    }

    @Test
    fun `boxScale at 85mm with Primary preview returns approx 0dot282`() {
        // At 85mm target with Primary preview (24mm): boxScale = 24/85 ≈ 0.282
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, 85f)
        val expected = 24f / 85f
        assertEquals("Box scale at 85mm should be 24/85", expected, scale, 0.001f)
    }

    @Test
    fun `boxScale at 50mm with Primary preview returns 0dot48`() {
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, 50f)
        assertEquals("Box scale at 50mm should be 24/50 = 0.48", 0.48f, scale, 0.001f)
    }

    @Test
    fun `boxScale at 116dot2mm with Primary preview returns 24 over 116dot2`() {
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, TELE_FOCAL_MM)
        val expected = PRIMARY_FOCAL_MM / TELE_FOCAL_MM
        assertEquals("Box scale at 116.2mm should be 24/116.2", expected, scale, 0.001f)
    }

    @Test
    fun `boxScale with ultraWide preview at 13dot4mm returns 1`() {
        val scale = FovMapper.boxScale(UW_FOCAL_MM, UW_FOCAL_MM)
        assertEquals("At UW native focal length, boxScale should be 1.0", 1.0f, scale, 0.001f)
    }

    @Test
    fun `boxScale with ultraWide preview at 18mm returns correctly`() {
        val scale = FovMapper.boxScale(UW_FOCAL_MM, 18f)
        val expected = UW_FOCAL_MM / 18f
        assertEquals("Box scale at 18mm with UW preview", expected, scale, 0.001f)
    }

    @Test
    fun `boxScale clamps values above 1 to 1`() {
        val scale = FovMapper.boxScale(50f, 30f)
        assertEquals("When target < preview focal, boxScale should clamp to 1.0", 1.0f, scale, 0.001f)
    }

    @Test
    fun `boxScale with zero target returns 1`() {
        val scale = FovMapper.boxScale(24f, 0f)
        assertEquals("Zero target should return 1.0", 1.0f, scale, 0.001f)
    }

    @Test
    fun `boxScale with negative values returns 1`() {
        val scale = FovMapper.boxScale(-24f, 50f)
        assertEquals("Negative preview focal should return 1.0", 1.0f, scale, 0.001f)
    }

    // --- previewLens tests ---

    @Test
    fun `previewLens below primary focal uses ultraWide`() {
        val lens = FovMapper.previewLens(
            targetFocalMm = 15f,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("Below 24mm, preview should use ULTRA_WIDE", LensRole.ULTRA_WIDE, lens)
    }

    @Test
    fun `previewLens at primary focal uses Primary`() {
        val lens = FovMapper.previewLens(
            targetFocalMm = PRIMARY_FOCAL_MM,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 24mm, preview should use PRIMARY", LensRole.PRIMARY, lens)
    }

    @Test
    fun `previewLens at 50mm uses Primary`() {
        val lens = FovMapper.previewLens(
            targetFocalMm = 50f,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 50mm, preview should use PRIMARY", LensRole.PRIMARY, lens)
    }

    @Test
    fun `previewLens at 85mm uses Primary`() {
        val lens = FovMapper.previewLens(
            targetFocalMm = 85f,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 85mm, preview should use PRIMARY", LensRole.PRIMARY, lens)
    }

    @Test
    fun `previewLens at 116dot2mm uses Primary (Tele never used for preview)`() {
        val lens = FovMapper.previewLens(
            targetFocalMm = TELE_FOCAL_MM,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 116.2mm, preview should still be PRIMARY", LensRole.PRIMARY, lens)
    }

    @Test
    fun `previewLens at 200mm uses Primary`() {
        val lens = FovMapper.previewLens(
            targetFocalMm = 200f,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 200mm, preview should still be PRIMARY", LensRole.PRIMARY, lens)
    }

    // --- captureLens tests ---

    @Test
    fun `captureLens at 24mm uses Primary`() {
        val lens = FovMapper.captureLens(
            targetFocalMm = PRIMARY_FOCAL_MM,
            currentCaptureLens = LensRole.PRIMARY,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 24mm, capture should use PRIMARY", LensRole.PRIMARY, lens)
    }

    @Test
    fun `captureLens at 50mm uses Primary`() {
        val lens = FovMapper.captureLens(
            targetFocalMm = 50f,
            currentCaptureLens = LensRole.PRIMARY,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 50mm, capture should use PRIMARY", LensRole.PRIMARY, lens)
    }

    @Test
    fun `captureLens at 85mm uses Primary`() {
        val lens = FovMapper.captureLens(
            targetFocalMm = 85f,
            currentCaptureLens = LensRole.PRIMARY,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 85mm, capture should use PRIMARY", LensRole.PRIMARY, lens)
    }

    @Test
    fun `captureLens at exactly 116dot2mm switches to Tele`() {
        val lens = FovMapper.captureLens(
            targetFocalMm = TELE_FOCAL_MM,
            currentCaptureLens = LensRole.PRIMARY,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At exactly 116.2mm, capture should switch to TELE", LensRole.TELE, lens)
    }

    @Test
    fun `captureLens at 120mm uses Tele`() {
        val lens = FovMapper.captureLens(
            targetFocalMm = 120f,
            currentCaptureLens = LensRole.PRIMARY,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 120mm, capture should use TELE", LensRole.TELE, lens)
    }

    @Test
    fun `captureLens at 200mm uses Tele`() {
        val lens = FovMapper.captureLens(
            targetFocalMm = 200f,
            currentCaptureLens = LensRole.PRIMARY,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 200mm, capture should use TELE", LensRole.TELE, lens)
    }

    // --- Hysteresis tests ---

    @Test
    fun `captureLens inside hysteresis band below tele holds steady on Primary`() {
        // Start at 100mm (below tele threshold of 116.2), current capture is Primary
        val lens = FovMapper.captureLens(
            targetFocalMm = 112f,
            currentCaptureLens = LensRole.PRIMARY,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM,
            hysteresisMm = HYSTERESIS_MM
        )
        // 112mm is inside hysteresis: 116.2 - 8 = 108.2 < 112 < 116.2
        assertEquals("Inside hysteresis band, should hold PRIMARY", LensRole.PRIMARY, lens)
    }

    @Test
    fun `captureLens inside hysteresis band after tele holds steady on Tele`() {
        // Start at 120mm (above tele), current capture is Tele, then zoom back to 112mm
        val lens = FovMapper.captureLens(
            targetFocalMm = 112f,
            currentCaptureLens = LensRole.TELE,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM,
            hysteresisMm = HYSTERESIS_MM
        )
        // 112mm is inside hysteresis band, and current is Tele → hold Tele
        assertEquals("Inside hysteresis band returning from Tele, should hold TELE", LensRole.TELE, lens)
    }

    @Test
    fun `captureLens below hysteresis band after tele switches back to Primary`() {
        // After zooming far back below the hysteresis band (e.g., 100mm, below 108.2)
        val lens = FovMapper.captureLens(
            targetFocalMm = 100f,
            currentCaptureLens = LensRole.TELE,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM,
            hysteresisMm = HYSTERESIS_MM
        )
        // 100mm is below hysteresis band (108.2) → switch to Primary
        assertEquals("Below hysteresis band, should switch to PRIMARY", LensRole.PRIMARY, lens)
    }

    @Test
    fun `captureLens below ultraWide threshold uses UltraWide`() {
        val lens = FovMapper.captureLens(
            targetFocalMm = 18f,
            currentCaptureLens = LensRole.PRIMARY,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("At 18mm, capture should use ULTRA_WIDE", LensRole.ULTRA_WIDE, lens)
    }

    // --- shouldPreWarmTele tests ---

    @Test
    fun `shouldPreWarmTele at 100mm is false`() {
        val should = FovMapper.shouldPreWarmTele(
            targetFocalMm = 100f,
            teleFocalMm = TELE_FOCAL_MM
        )
        assertTrue("At 100mm, should not pre-warm Tele (far from 116.2)", !should)
    }

    @Test
    fun `shouldPreWarmTele at 105mm is true within 15mm threshold`() {
        val should = FovMapper.shouldPreWarmTele(
            targetFocalMm = 105f,
            teleFocalMm = TELE_FOCAL_MM,
            triggerDistanceMm = 15f
        )
        // 105 >= 116.2 - 15 = 101.2
        assertTrue("At 105mm, should pre-warm Tele", should)
    }

    @Test
    fun `shouldPreWarmTele at 116dot2mm is true`() {
        val should = FovMapper.shouldPreWarmTele(
            targetFocalMm = TELE_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM
        )
        assertTrue("At 116.2mm, should pre-warm Tele", should)
    }

    // --- captureCropFactor tests ---

    @Test
    fun `captureCropFactor at native focal length returns 1`() {
        val crop = FovMapper.captureCropFactor(24f, 24f)
        assertEquals("Crop factor at native focal should be 1.0", 1.0f, crop, 0.001f)
    }

    @Test
    fun `captureCropFactor at 50mm with Primary capture returns 50 over 24`() {
        val crop = FovMapper.captureCropFactor(24f, 50f)
        val expected = 50f / 24f
        assertEquals("Crop factor at 50mm with Primary", expected, crop, 0.001f)
    }

    @Test
    fun `captureCropFactor at 85mm with Primary capture returns 85 over 24`() {
        val crop = FovMapper.captureCropFactor(24f, 85f)
        val expected = 85f / 24f
        assertEquals("Crop factor at 85mm with Primary", expected, crop, 0.001f)
    }

    @Test
    fun `captureCropFactor at 116dot2mm with Tele capture returns 1`() {
        val crop = FovMapper.captureCropFactor(TELE_FOCAL_MM, TELE_FOCAL_MM)
        assertEquals("Crop factor at native Tele should be 1.0", 1.0f, crop, 0.001f)
    }

    @Test
    fun `captureCropFactor at 120mm with Tele capture returns 120 over 116dot2`() {
        val crop = FovMapper.captureCropFactor(TELE_FOCAL_MM, 120f)
        val expected = 120f / TELE_FOCAL_MM
        assertEquals("Crop factor at 120mm with Tele", expected, crop, 0.001f)
    }

    // --- outputMegapixels tests ---

    @Test
    fun `outputMegapixels at native focal length equals native MP`() {
        val mp = FovMapper.outputMegapixels(50, 1.0f)
        assertEquals("At 1x crop, output should be 50MP", 50.0f, mp, 0.5f)
    }

    @Test
    fun `outputMegapixels at 2x crop reduces by 4x`() {
        val mp = FovMapper.outputMegapixels(50, 2.0f)
        assertEquals("At 2x crop, output should be ~12.5MP", 12.5f, mp, 0.5f)
    }

    @Test
    fun `outputMegapixels at tele native 48MP with 1x crop`() {
        val mp = FovMapper.outputMegapixels(48, 1.0f)
        assertEquals("Tele native 48MP at 1x", 48.0f, mp, 0.5f)
    }

    // --- Integration: Acceptance Criteria from Spec §10 ---

    @Test
    fun `acceptance criteria 1 - at 24mm box scale is 1`() {
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, PRIMARY_FOCAL_MM)
        assertEquals("AC1: At 24mm, box scale = 1.0", 1.0f, scale, 0.001f)
    }

    @Test
    fun `acceptance criteria 2 - at 85mm box scale is approx 0dot282, preview is Primary`() {
        val scale = FovMapper.boxScale(PRIMARY_FOCAL_MM, 85f)
        val expected = PRIMARY_FOCAL_MM / 85f
        assertEquals("AC2: At 85mm, box scale ≈ 0.282", expected, scale, 0.001f)

        val previewLens = FovMapper.previewLens(
            targetFocalMm = 85f,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("AC2: Preview lens should still be Primary", LensRole.PRIMARY, previewLens)
    }

    @Test
    fun `acceptance criteria 3 - at exactly 116dot2mm capture switches to Tele, preview unchanged`() {
        val captureLens = FovMapper.captureLens(
            targetFocalMm = TELE_FOCAL_MM,
            currentCaptureLens = LensRole.PRIMARY,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("AC3: At 116.2mm, capture switches to TELE", LensRole.TELE, captureLens)

        val previewLens = FovMapper.previewLens(
            targetFocalMm = TELE_FOCAL_MM,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("AC3: Preview lens remains Primary", LensRole.PRIMARY, previewLens)
    }

    @Test
    fun `acceptance criteria 4 - hysteresis band prevents flicker`() {
        // Start above tele (120mm) → Tele
        val enteringTele = FovMapper.captureLens(
            targetFocalMm = 120f,
            currentCaptureLens = LensRole.PRIMARY,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM
        )
        assertEquals("Entering 120mm should use TELE", LensRole.TELE, enteringTele)

        // Zoom back to 112mm (inside hysteresis band) → should hold Tele
        val holdingTele = FovMapper.captureLens(
            targetFocalMm = 112f,
            currentCaptureLens = LensRole.TELE,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM,
            hysteresisMm = HYSTERESIS_MM
        )
        assertEquals("Inside hysteresis band, should hold TELE", LensRole.TELE, holdingTele)

        // Zoom further back to 100mm (below hysteresis band) → should switch to Primary
        val switchingBack = FovMapper.captureLens(
            targetFocalMm = 100f,
            currentCaptureLens = LensRole.TELE,
            primaryFocalMm = PRIMARY_FOCAL_MM,
            teleFocalMm = TELE_FOCAL_MM,
            ultraWideFocalMm = UW_FOCAL_MM,
            hysteresisMm = HYSTERESIS_MM
        )
        assertEquals("Below hysteresis band, should switch to PRIMARY", LensRole.PRIMARY, switchingBack)
    }
}