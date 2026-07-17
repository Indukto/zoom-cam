package com.example

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaActionSound
import android.media.ExifInterface
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.zoom.FovMapper
import com.example.zoom.LensCatalog
import com.example.zoom.LensRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ExifData(
    val focalLength: String = "--",
    val shutterSpeed: String = "--",
    val iso: String = "--"
)

class CameraViewModel : ViewModel() {

    // The user-facing target focal length (e.g. 35mm, 50mm, 60mm, 85mm)
    private val _focalLength = MutableStateFlow(35)
    val focalLength: StateFlow<Int> = _focalLength.asStateFlow()

    // The actual physical lens that is selected (largest ≤ target)
    private val _baseFocalLength = MutableStateFlow(24)
    val baseFocalLength: StateFlow<Int> = _baseFocalLength.asStateFlow()

    // Digital zoom factor = targetFocalLength / baseFocalLength
    private val _zoomRatio = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    // Available physical focal lengths reported by the device's back cameras
    private val _availableFocalLengths = MutableStateFlow<List<Float>>(listOf(24f, 77f))
    val availableFocalLengths: StateFlow<List<Float>> = _availableFocalLengths.asStateFlow()

    // Zoom-box scale: fraction of the preview frame covered by the framing box
    // Computed by FovMapper.boxScale(previewFocalMm, targetFocalMm)
    private val _boxScale = MutableStateFlow(1f)
    val boxScale: StateFlow<Float> = _boxScale.asStateFlow()

    // Current preview lens role (determined by FovMapper.previewLens)
    private val _previewLensRole = MutableStateFlow(LensRole.PRIMARY)
    val previewLensRole: StateFlow<LensRole> = _previewLensRole.asStateFlow()

    // Current capture lens role (determined by FovMapper.captureLens with hysteresis)
    private val _captureLensRole = MutableStateFlow(LensRole.PRIMARY)
    val captureLensRole: StateFlow<LensRole> = _captureLensRole.asStateFlow()

    // Lens catalog result from runtime enumeration
    private var lensCatalogResult: LensCatalog.CatalogResult? = null

    private val _exposure = MutableStateFlow(0f) // -3f to +3f
    val exposure: StateFlow<Float> = _exposure.asStateFlow()

    private val _temperature = MutableStateFlow(0f) // -2f to +2f (Warm/Cool)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _flashMode = MutableStateFlow(0) // 0 = Auto, 1 = On, 2 = Off
    val flashMode: StateFlow<Int> = _flashMode.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _capturedPhotos = MutableStateFlow<List<File>>(emptyList())
    val capturedPhotos: StateFlow<List<File>> = _capturedPhotos.asStateFlow()

    private val _selectedPhoto = MutableStateFlow<File?>(null)
    val selectedPhoto: StateFlow<File?> = _selectedPhoto.asStateFlow()

    private val _showTemperatureSlider = MutableStateFlow(false)
    val showTemperatureSlider: StateFlow<Boolean> = _showTemperatureSlider.asStateFlow()

    private val _showExposureSlider = MutableStateFlow(false)
    val showExposureSlider: StateFlow<Boolean> = _showExposureSlider.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val _showGridLines = MutableStateFlow(false)
    val showGridLines: StateFlow<Boolean> = _showGridLines.asStateFlow()

    private val _aspectRatio = MutableStateFlow("4:3") // "4:3" or "1:1"
    val aspectRatio: StateFlow<String> = _aspectRatio.asStateFlow()

    private val shutterSound = MediaActionSound()

    init {
        // Pre-load shutter sound
        try {
            shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error loading shutter sound", e)
        }
    }

    fun loadPhotos(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val files = directory?.listFiles { file ->
                file.isFile && (file.extension.lowercase() == "jpg" || file.extension.lowercase() == "jpeg")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
            _capturedPhotos.value = files
        }
    }

    /**
     * Called by CameraPreviewView once the available back-camera focal lengths
     * are known from the hardware characteristics.
     */
    fun setAvailableFocalLengths(lengths: List<Float>) {
        if (lengths.isNotEmpty() && lengths != _availableFocalLengths.value) {
            _availableFocalLengths.value = lengths
            // Recalculate the lens selection for the current target focal length
            applyLensSelection(_focalLength.value)
        }
    }

    /**
     * Called to set the LensCatalog result after camera enumeration.
     */
    fun setLensCatalogResult(result: LensCatalog.CatalogResult) {
        lensCatalogResult = result
        // Recalculate with real lens data
        applyLensSelection(_focalLength.value)
    }

    /**
     * Pinch-to-zoom gesture handler.
     * Maps a zoom ratio to a target focal length based on the current base lens.
     */
    fun setZoom(ratio: Float) {
        val clampedRatio = ratio.coerceIn(1.0f, 10.0f)
        val base = _baseFocalLength.value
        // Map the zoom ratio to a target focal length
        val targetFocal = (base * clampedRatio).toInt().coerceIn(13, 200)
        applyLensSelection(targetFocal)
    }

    /**
     * Cycle through lens presets (35mm → 50mm → 85mm → ...)
     * or select a specific target focal length.
     */
    fun selectLensPreset(lens: Int) {
        applyLensSelection(lens)
    }

    /**
     * Core lens selection algorithm using FovMapper.
     *
     * 1. Find the best physical lens on the device where f_lens ≤ f_target
     * 2. Determine the preview lens (FovMapper.previewLens)
     * 3. Determine the capture lens with hysteresis (FovMapper.captureLens)
     * 4. Calculate box scale = previewFocal / targetFocal
     * 5. Calculate digital zoom factor Z = target / base
     * 6. Update all state flows accordingly
     */
    private fun applyLensSelection(targetFocalLength: Int) {
        val available = _availableFocalLengths.value

        // Step 1: Find the best physical lens (largest focal length ≤ target)
        val bestBase = available
            .filter { it <= targetFocalLength }
            .maxOrNull()
            ?: available.minOrNull() // fallback: widest lens
            ?: 24f

        val baseInt = bestBase.toInt()

        // Step 2: Get catalog data for FovMapper.
        // Prefer real per-lens focals from the catalog; fall back to sane defaults.
        // The preview lens is ALWAYS the Primary (tele is never used for preview per spec §3),
        // so the box scale is computed off the Primary's focal length regardless of capture lens.
        val catalog = lensCatalogResult
        val primaryFocalMm = catalog?.primary?.equivFocalMm ?: 24f
        val ultraWideFocalMm = catalog?.ultraWide?.equivFocalMm ?: 13.4f
        val teleFocalMm = catalog?.tele?.equivFocalMm ?: 116.2f

        // Step 3: Determine preview lens using FovMapper
        val previewRole = FovMapper.previewLens(
            targetFocalMm = targetFocalLength.toFloat(),
            primaryFocalMm = primaryFocalMm,
            ultraWideFocalMm = ultraWideFocalMm
        )
        _previewLensRole.value = previewRole

        // Step 4: Determine capture lens with hysteresis
        val currentCapture = _captureLensRole.value
        val captureRole = FovMapper.captureLens(
            targetFocalMm = targetFocalLength.toFloat(),
            currentCaptureLens = currentCapture,
            primaryFocalMm = primaryFocalMm,
            teleFocalMm = teleFocalMm,
            ultraWideFocalMm = ultraWideFocalMm
        )
        _captureLensRole.value = captureRole

        // Step 5: Compute box scale
        val previewFocal = when (previewRole) {
            LensRole.ULTRA_WIDE -> ultraWideFocalMm
            LensRole.PRIMARY -> primaryFocalMm
            LensRole.TELE -> primaryFocalMm // Tele never used for preview
        }
        val scale = FovMapper.boxScale(previewFocal, targetFocalLength.toFloat())
        _boxScale.value = scale

        // Step 6: Calculate digital zoom factor
        val zoom = targetFocalLength.toFloat() / bestBase

        // Step 7: Update state
        _baseFocalLength.value = baseInt
        _focalLength.value = targetFocalLength
        _zoomRatio.value = zoom
    }

    fun setExposure(value: Float) {
        _exposure.value = value.coerceIn(-3.0f, 3.0f)
    }

    fun setTemperature(value: Float) {
        _temperature.value = value.coerceIn(-2.0f, 2.0f)
    }

    fun toggleFlash() {
        _flashMode.value = (_flashMode.value + 1) % 3
    }

    fun toggleCamera() {
        _isFrontCamera.value = !_isFrontCamera.value
    }

    fun toggleGridLines() {
        _showGridLines.value = !_showGridLines.value
    }

    fun setAspectRatio(ratio: String) {
        if (ratio == "4:3" || ratio == "1:1") {
            _aspectRatio.value = ratio
        }
    }

    fun setSelectedPhoto(file: File?) {
        _selectedPhoto.value = file
    }

    fun toggleTemperatureSlider() {
        _showTemperatureSlider.value = !_showTemperatureSlider.value
        if (_showTemperatureSlider.value) {
            _showExposureSlider.value = false
        }
    }

    fun toggleExposureSlider() {
        _showExposureSlider.value = !_showExposureSlider.value
        if (_showExposureSlider.value) {
            _showTemperatureSlider.value = false
        }
    }

    fun closeSliders() {
        _showTemperatureSlider.value = false
        _showExposureSlider.value = false
    }

    fun playShutterSound() {
        try {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error playing shutter sound", e)
        }
    }

    fun processAndSavePhoto(
        context: Context,
        rawFile: File,
        boxWidthFraction: Float,
        screenWidth: Float,
        screenHeight: Float,
        captureFocalLength: Int,
        captureLensNativeFocalMm: Float? = null
    ) {
        _isCapturing.value = true
        val currentAspectRatioMultiplier = if (_aspectRatio.value == "1:1") 1.0f else 1.35f
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Decode captured image
                val options = BitmapFactory.Options().apply {
                    inMutable = true
                }
                var bitmap = BitmapFactory.decodeFile(rawFile.absolutePath, options)

                if (bitmap != null) {
                    // Capture original EXIF values before processing (Bitmap.compress strips them)
                    var originalExposureTime = 0.0
                    var originalIso = 0
                    try {
                        val origExif = ExifInterface(rawFile.absolutePath)
                        originalExposureTime = origExif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                        originalIso = origExif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Error reading original EXIF", e)
                    }

                    // 1. Read EXIF orientation and rotate/flip accordingly
                    val matrix = Matrix()
                    try {
                        val exif = ExifInterface(rawFile.absolutePath)
                        val orientation = exif.getAttributeInt(
                            ExifInterface.TAG_ORIENTATION,
                            ExifInterface.ORIENTATION_NORMAL
                        )
                        when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                            ExifInterface.ORIENTATION_TRANSPOSE -> {
                                matrix.postRotate(90f)
                                matrix.postScale(-1f, 1f)
                            }
                            ExifInterface.ORIENTATION_TRANSVERSE -> {
                                matrix.postRotate(270f)
                                matrix.postScale(-1f, 1f)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Error reading EXIF orientation", e)
                    }

                    // Mirror the image if using front camera
                    if (_isFrontCamera.value) {
                        matrix.postScale(-1f, 1f)
                    }

                    if (matrix.isIdentity.not()) {
                        val rotatedBitmap = Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )
                        bitmap.recycle()
                        bitmap = rotatedBitmap
                    }

                    // 2. Crop the image to match the target focal length.
                    //
                    // When a lens-swap capture occurred (captureLensNativeFocalMm != null),
                    // the image was taken with a different physical lens than the preview.
                    // In that case the on-screen zoom box (computed from previewFocalMm / targetFocalMm)
                    // does NOT represent the correct crop — it would be far too aggressive.
                    // Instead we compute the crop directly from the capture lens's native focal length:
                    //   cropFactor = captureLensNativeFocalMm / captureFocalLength
                    // and apply it as a center-crop on the full-resolution capture.
                    //
                    // For same-lens captures (captureLensNativeFocalMm == null), we keep the
                    // existing screen-coordinate zoom-box crop.
                    val finalBitmap = if (captureLensNativeFocalMm != null) {
                        val cropFactor = (captureLensNativeFocalMm / captureFocalLength).coerceIn(0f, 1f)
                        if (cropFactor < 0.99f) {
                            val cropW = (bitmap.width * cropFactor).toInt().coerceAtLeast(1)
                            val cropH = (bitmap.height * cropFactor).toInt().coerceAtLeast(1)
                            val cropX = (bitmap.width - cropW) / 2
                            val cropY = (bitmap.height - cropH) / 2
                            try {
                                val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
                                if (cropped != bitmap) {
                                    bitmap.recycle()
                                }
                                cropped
                            } catch (e: Exception) {
                                Log.e("CameraViewModel", "Error center-cropping bitmap, fallback to full image", e)
                                bitmap
                            }
                        } else {
                            bitmap
                        }
                    } else if (boxWidthFraction < 0.99f) {
                        val cropped = try {
                            cropBitmapToZoomBox(bitmap, boxWidthFraction, screenWidth, screenHeight, currentAspectRatioMultiplier)
                        } catch (e: Exception) {
                            Log.e("CameraViewModel", "Error cropping bitmap, fallback to full image", e)
                            bitmap
                        }
                        if (cropped != bitmap) {
                            bitmap.recycle()
                        }
                        cropped
                    } else {
                        bitmap
                    }

                    // 3. Apply the retro adjustments only if user has tweaked any slider
                    val processedBitmap = if (_temperature.value != 0f || _exposure.value != 0f) {
                        finalBitmap.applyRetroFilter(_temperature.value, _exposure.value)
                    } else {
                        finalBitmap
                    }

                    // Overwrite the original file with processed retro photo
                    FileOutputStream(rawFile).use { out ->
                        processedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    processedBitmap.recycle()
                    if (finalBitmap != processedBitmap) {
                        finalBitmap.recycle()
                    }

                    // 4. Rename file to embed focal length for later display
                    val focalDir = rawFile.parentFile
                    val focalName = rawFile.nameWithoutExtension
                    val focalExt = rawFile.extension
                    val newName = "${focalName}_${captureFocalLength}mm.$focalExt"
                    val renamedFile = File(focalDir, newName)
                    rawFile.renameTo(renamedFile)

                    // 5. Write back EXIF data (Bitmap.compress strips it)
                    try {
                        val exifOut = ExifInterface(renamedFile.absolutePath)
                        if (originalExposureTime > 0.0) {
                            exifOut.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, originalExposureTime.toString())
                        }
                        if (originalIso > 0) {
                            exifOut.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, originalIso.toString())
                        }
                        exifOut.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${captureFocalLength}.0")
                        exifOut.saveAttributes()
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Error writing EXIF data back", e)
                    }

                    // 6. Save a copy to the public gallery
                    savePhotoToGallery(context, renamedFile)
                }

                // Reload photo gallery
                loadPhotos(context)
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error processing captured photo", e)
            } finally {
                _isCapturing.value = false
            }
        }
    }

    private fun savePhotoToGallery(context: Context, file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZoomBoxCamera")
                } else {
                    @Suppress("DEPRECATION")
                    put(MediaStore.Images.Media.DATA, file.absolutePath)
                }
            }

            val resolver = context.contentResolver
            val contentUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }

            val uri = resolver.insert(contentUri, values)
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { `in` ->
                            `in`.copyTo(out)
                        }
                    }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                Log.d("CameraViewModel", "Photo saved to gallery: ${file.name}")
            }
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error saving photo to gallery", e)
        }
    }
    // Read EXIF display data from a saved photo file
    fun readExifData(file: File): ExifData {
        return try {
            val exif = ExifInterface(file.absolutePath)
            
            // Shutter speed from exposure time
            val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            val shutterSpeed = if (exposureTime > 0.0) {
                if (exposureTime < 1.0) {
                    val denom = kotlin.math.round(1.0 / exposureTime).toInt()
                    "1/${denom}s"
                } else {
                    "${kotlin.math.round(exposureTime).toInt()}s"
                }
            } else "--"
            
            // ISO
            val isoRaw = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
            val iso = if (isoRaw > 0) "ISO $isoRaw" else "--"
            
            // Focal length from filename (the zoom box simulated focal length)
            val name = file.nameWithoutExtension
            val focalMatch = Regex("""_(\d+)mm$""").find(name)
            val focalLength = focalMatch?.groupValues?.get(1)?.let { "${it}mm" } ?: "--"
            
            ExifData(
                focalLength = focalLength,
                shutterSpeed = shutterSpeed,
                iso = iso
            )
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error reading EXIF data", e)
            ExifData()
        }
    }

    private fun cropBitmapToZoomBox(
        bitmap: Bitmap,
        boxWidthFraction: Float,
        screenWidth: Float,
        screenHeight: Float,
        aspectRatioMultiplier: Float
    ): Bitmap {
        val wBitmap = bitmap.width.toFloat()
        val hBitmap = bitmap.height.toFloat()

        // Calculate how the PreviewView (FILL_CENTER) is scaled on the screen
        val scale = kotlin.math.max(screenWidth / wBitmap, screenHeight / hBitmap)
        val wVisible = screenWidth / scale
        val hVisible = screenHeight / scale

        val xVisibleStart = (wBitmap - wVisible) / 2f
        val yVisibleStart = (hBitmap - hVisible) / 2f

        // Locate the Zoom Box on the screen
        val wBox = screenWidth * boxWidthFraction
        val hBox = wBox * aspectRatioMultiplier
        val xBox = (screenWidth - wBox) / 2f
        val yBox = (screenHeight - hBox) / 2.3f + 6f

        // Map screen Zoom Box to Bitmap coordinates
        val xCropStart = (xVisibleStart + (xBox / screenWidth) * wVisible).toInt().coerceIn(0, bitmap.width - 1)
        val yCropStart = (yVisibleStart + (yBox / screenHeight) * hVisible).toInt().coerceIn(0, bitmap.height - 1)
        val wCrop = ((wBox / screenWidth) * wVisible).toInt().coerceIn(1, bitmap.width - xCropStart)
        val hCrop = ((hBox / screenHeight) * hVisible).toInt().coerceIn(1, bitmap.height - yCropStart)

        return Bitmap.createBitmap(bitmap, xCropStart, yCropStart, wCrop, hCrop)
    }

    fun deletePhoto(context: Context, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    file.delete()
                }
                if (_selectedPhoto.value == file) {
                    _selectedPhoto.value = null
                }
                loadPhotos(context)
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error deleting photo", e)
            }
        }
    }

    // Helper: Extension on Bitmap to apply retro/vintage film filter in memory
    private fun Bitmap.applyRetroFilter(tempVal: Float, expVal: Float): Bitmap {
        val width = this.width
        val height = this.height
        val output = Bitmap.createBitmap(width, height, this.config ?: Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint()
        canvas.drawBitmap(this, 0f, 0f, paint)

        // 1. Apply Exposure (brightness/contrast offset)
        if (expVal != 0f) {
            val offset = expVal * 20f
            // Brighten/darken RGB channels
            val brightnessMatrix = android.graphics.ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, offset,
                0f, 1f, 0f, 0f, offset,
                0f, 0f, 1f, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(brightnessMatrix)
            canvas.drawBitmap(output, 0f, 0f, paint)
        }

        // 2. Apply Temperature Tint
        if (tempVal != 0f) {
            // Calculate a warm or cool amber/cyan tint
            val tintColor = if (tempVal > 0f) {
                // Retro Amber/Orange Warmth
                val alpha = (tempVal * 25f).toInt().coerceIn(0, 80)
                android.graphics.Color.argb(alpha, 245, 158, 11)
            } else {
                // Retro Cool Blue/Teal
                val alpha = (-tempVal * 25f).toInt().coerceIn(0, 80)
                android.graphics.Color.argb(alpha, 14, 165, 233)
            }
            val tintPaint = android.graphics.Paint().apply {
                colorFilter = android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
            }
            canvas.drawBitmap(output, 0f, 0f, tintPaint)
        }

        // 3. Apply Retro Vignette
        val vignettePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            shader = android.graphics.RadialGradient(
                width / 2f, height / 2f,
                kotlin.math.max(width, height) * 0.72f,
                intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(135, 12, 12, 12)),
                floatArrayOf(0.55f, 1.0f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)

        return output
    }

    override fun onCleared() {
        super.onCleared()
        try {
            shutterSound.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}