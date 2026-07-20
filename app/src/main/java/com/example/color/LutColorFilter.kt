package com.example.color

import android.graphics.Bitmap

/**
 * Applies a 3D LUT (see [CubeLut]) to a [Bitmap] via trilinear interpolation
 * on the CPU.
 *
 * The LUT is treated as a regular `size`×`size`×`size` lattice of RGB output
 * samples. Each input pixel's normalized (R, G, B) components in [0, 1] are
 * mapped onto the lattice and the 8 surrounding samples are blended by their
 * fractional distance — the standard trilinear scheme.
 *
 * Runs on a post-capture bitmap, so the per-pixel cost (no GPU) is acceptable.
 * Alpha is preserved unchanged.
 */
object LutColorFilter {

    /**
     * Returns a new bitmap with the LUT applied. The source bitmap is not
     * recycled. If [source] has no config, ARGB_8888 is used.
     */
    fun apply(source: Bitmap, lut: CubeLut): Bitmap {
        val w = source.width
        val h = source.height
        val srcPixels = IntArray(w * h)
        source.getPixels(srcPixels, 0, w, 0, 0, w, h)

        val outPixels = IntArray(w * h)
        val data = lut.data
        val n = lut.size
        val maxIndex = n - 1
        // The lattice samples sit at i / (n - 1), so a normalized component in
        // [0, 1] maps to a lattice coordinate in [0, n - 1].
        val scale = maxIndex.toFloat()

        for (i in srcPixels.indices) {
            val c = srcPixels[i]
            val a = c ushr 24 and 0xFF
            val r8 = c ushr 16 and 0xFF
            val g8 = c ushr 8 and 0xFF
            val b8 = c and 0xFF

            // Normalize to [0, 1] then to lattice coordinates [0, maxIndex].
            val rF = (r8 / 255f) * scale
            val gF = (g8 / 255f) * scale
            val bF = (b8 / 255f) * scale

            val r0 = rF.toInt().coerceIn(0, maxIndex)
            val g0 = gF.toInt().coerceIn(0, maxIndex)
            val b0 = bF.toInt().coerceIn(0, maxIndex)
            val r1 = if (r0 < maxIndex) r0 + 1 else maxIndex
            val g1 = if (g0 < maxIndex) g0 + 1 else maxIndex
            val b1 = if (b0 < maxIndex) b0 + 1 else maxIndex

            val dR = rF - r0
            val dG = gF - g0
            val dB = bF - b0

            // Starting indices of the 8 lattice corners (×3 to step over RGB).
            val i000 = lut.indexOf(r0, g0, b0) * 3
            val i100 = lut.indexOf(r1, g0, b0) * 3
            val i010 = lut.indexOf(r0, g1, b0) * 3
            val i110 = lut.indexOf(r1, g1, b0) * 3
            val i001 = lut.indexOf(r0, g0, b1) * 3
            val i101 = lut.indexOf(r1, g0, b1) * 3
            val i011 = lut.indexOf(r0, g1, b1) * 3
            val i111 = lut.indexOf(r1, g1, b1) * 3

            // Trilinear blend of each channel. data[idx + 0|1|2] = R|G|B.
            val outR = triChannel(data, i000, i100, i010, i110, i001, i101, i011, i111, 0, dR, dG, dB)
            val outG = triChannel(data, i000, i100, i010, i110, i001, i101, i011, i111, 1, dR, dG, dB)
            val outB = triChannel(data, i000, i100, i010, i110, i001, i101, i011, i111, 2, dR, dG, dB)

            val or8 = (outR * 255f + 0.5f).toInt().coerceIn(0, 255)
            val og8 = (outG * 255f + 0.5f).toInt().coerceIn(0, 255)
            val ob8 = (outB * 255f + 0.5f).toInt().coerceIn(0, 255)

            outPixels[i] = (a shl 24) or (or8 shl 16) or (og8 shl 8) or ob8
        }

        val output = Bitmap.createBitmap(w, h, source.config ?: Bitmap.Config.ARGB_8888)
        output.setPixels(outPixels, 0, w, 0, 0, w, h)
        return output
    }

    /**
     * Trilinear interpolation of a single channel (0 = R, 1 = G, 2 = B).
     *
     * Each `iXXX` is the starting array index of a lattice corner's RGB triple;
     * [offset] selects which of the three channels to read.
     */
    private fun triChannel(
        data: FloatArray,
        i000: Int, i100: Int, i010: Int, i110: Int,
        i001: Int, i101: Int, i011: Int, i111: Int,
        offset: Int,
        dR: Float, dG: Float, dB: Float
    ): Float {
        // Interpolate along R for both B-slices.
        val i00 = lerp(data[i000 + offset], data[i100 + offset], dR)
        val i10 = lerp(data[i010 + offset], data[i110 + offset], dR)
        val i01 = lerp(data[i001 + offset], data[i101 + offset], dR)
        val i11 = lerp(data[i011 + offset], data[i111 + offset], dR)

        // Interpolate along G.
        val i0 = lerp(i00, i10, dG)
        val i1 = lerp(i01, i11, dG)

        // Interpolate along B.
        return lerp(i0, i1, dB)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
}
