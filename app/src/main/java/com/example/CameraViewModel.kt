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
import com.example.zoom.AspectRatio
import com.example.zoom.CaptureExtension
import com.example.zoom.FovMapper
import com.example.zoom.LensCatalog
import com.example.zoom.LensRole
import com.example.zoom.PreviewSessionManager
import com.example.zoom.RawCapture
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

enum class RetroCameraPreset(
    val displayName: String,
    val tempOffset: Float,
    val tintOffset: Float,
    val exposureOffset: Float
) {
    CLASSIC_U("Classic U", 0.6f, 0.2f, 0.0f),
    DQS("DQS", -0.8f, -0.4f, 0.2f),
    INSTA_C("Insta C", 0.4f, 0.6f, 0.4f),
    GR_D("GR D", 0.0f, -0.2f, -0.3f)
}

class CameraViewModel : ViewModel() {

    private val _selectedLensRole = MutableStateFlow(LensRole.PRIMARY)
    val selectedLensRole: StateFlow<LensRole> = _selectedLensRole.asStateFlow()

    private val _previewLensRole = MutableStateFlow(LensRole.PRIMARY)
    val previewLensRole: StateFlow<LensRole> = _previewLensRole.asStateFlow()

    private val _captureLensRole = MutableStateFlow(LensRole.PRIMARY)
    val captureLensRole: StateFlow<LensRole> = _captureLensRole.asStateFlow()

    private val _digitalZoomRatio = MutableStateFlow(1.0f)
    val digitalZoomRatio: StateFlow<Float> = _digitalZoomRatio.asStateFlow()

    private val _effectiveFocalLength = MutableStateFlow(24)
    val effectiveFocalLength: StateFlow<Int> = _effectiveFocalLength.asStateFlow()

    private val _boxScale = MutableStateFlow(1f)
    val boxScale: StateFlow<Float> = _boxScale.asStateFlow()

    private val _availableFocalLengths = MutableStateFlow<List<Float>>(listOf(24f, 77f))
    val availableFocalLengths: StateFlow<List<Float>> = _availableFocalLengths.asStateFlow()

    private var lensCatalogResult: LensCatalog.CatalogResult? = null

    private val _exposure = MutableStateFlow(0f)
    val exposure: StateFlow<Float> = _exposure.asStateFlow()

    private val _temperature = MutableStateFlow(0f)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _tint = MutableStateFlow(0f)
    val tint: StateFlow<Float> = _tint.asStateFlow()

    private val _activePreset = MutableStateFlow(RetroCameraPreset.CLASSIC_U)
    val activePreset: StateFlow<RetroCameraPreset> = _activePreset.asStateFlow()

    private val _flashMode = MutableStateFlow(0)
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

    private val _lensSwitchTrigger = MutableStateFlow(0)
    val lensSwitchTrigger: StateFlow<Int> = _lensSwitchTrigger.asStateFlow()

    private val _showGridLines = MutableStateFlow(false)
    val showGridLines: StateFlow<Boolean> = _showGridLines.asStateFlow()

    private val _aspectRatio = MutableStateFlow(AspectRatio.RATIO_4_3)
    val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    // 0 = Off, 3 = 3 s, 10 = 10 s
    private val _selfTimerMode = MutableStateFlow(0)
    val selfTimerMode: StateFlow<Int> = _selfTimerMode.asStateFlow()

    private val _doubleExposureActive = MutableStateFlow(false)
    val doubleExposureActive: StateFlow<Boolean> = _doubleExposureActive.asStateFlow()

    // ── RAW capture mode ──────────────────────────────────────────────────
    // When true, the shutter routes through RawCapture.captureDng() instead of
    // the JPEG ImageCapture path. Capability-checked per lens via the catalog:
    // RAW is only offered when the currently-selected lens advertises RAW_SENSOR.

    private val _rawModeEnabled = MutableStateFlow(false)
    val rawModeEnabled: StateFlow<Boolean> = _rawModeEnabled.asStateFlow()

    // True when the currently selected lens can actually emit RAW frames.
    private val _rawAvailableForCurrentLens = MutableStateFlow(false)
    val rawAvailableForCurrentLens: StateFlow<Boolean> = _rawAvailableForCurrentLens.asStateFlow()

    // ── OEM extension mode (HDR / Night / Bokeh / Auto) ───────────────────
    // NONE keeps the manual physical-lens routing. Any other value lets the
    // OEM extension own sensor selection. Availability is device-specific and
    // cached after the first probe.

    private val _activeExtension = MutableStateFlow(CaptureExtension.NONE)
    val activeExtension: StateFlow<CaptureExtension> = _activeExtension.asStateFlow()

    private val _availableExtensions = MutableStateFlow<Set<CaptureExtension>>(setOf(CaptureExtension.NONE))
    val availableExtensions: StateFlow<Set<CaptureExtension>> = _availableExtensions.asStateFlow()

    private val _extensionsProbeDone = MutableStateFlow(false)
    val extensionsProbeDone: StateFlow<Boolean> = _extensionsProbeDone.asStateFlow()

    private val shutterSound = MediaActionSound()

    init {
        try {
            shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error loading shutter sound", e)
        }
    }

    fun loadPhotos(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            _capturedPhotos.value = listPhotoFiles(context)
        }
    }

    /**
     * Synchronous directory scan shared by [loadPhotos] (async wrapper) and
     * [deletePhoto] (which needs the post-delete list *now* to auto-advance
     * the photo viewer's selection). Sorted newest-first to match the
     * gallery / filmstrip where index 0 is the most recent capture.
     */
    private fun listPhotoFiles(context: Context): List<File> {
        // Two locations hold our captures:
        //   1. App-private: getExternalFilesDir(DIRECTORY_PICTURES) — working
        //      copies written straight from the capture pipeline.
        //   2. Public-shared MediaStore mirror: Pictures/ZoomBoxCamera/ (plus
        //      its RAW subfolder). After an app reinstall the private copy
        //      is wiped but the MediaStore entries survive — scanning the
        //      public tree is what surfaces the user's old photos at startup.
        val privateDir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        val publicRoot = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
            "ZoomBoxCamera"
        )

        fun isOurPhoto(file: File): Boolean =
            file.isFile && file.extension.lowercase() in listOf("jpg", "jpeg", "dng")

        val privateFiles = privateDir?.listFiles(::isOurPhoto)?.toList() ?: emptyList()
        // runCatching guards against scoped-storage edge cases where the
        // public tree exists but listFiles() refuses to descend it (some
        // OEM ROM builds post-API-29). walkTopDown() also picks up DNGs in
        // ZoomBoxCamera/RAW/ alongside the JPEGs in the root.
        val publicFiles = runCatching {
            publicRoot.walkTopDown().filter(::isOurPhoto).toList()
        }.getOrDefault(emptyList())

        // Public first, then private — distinctBy { it.name } keeps the
        // public entry when both copies exist, so the file we hand to
        // deletePhoto() is the canonical (reinstall-survived) path.
        return (publicFiles + privateFiles)
            .distinctBy { it.name }
            .sortedByDescending { it.lastModified() }
    }

    fun setAvailableFocalLengths(lengths: List<Float>) {
        if (lengths.isNotEmpty() && lengths != _availableFocalLengths.value) {
            _availableFocalLengths.value = lengths
            recalculateState()
        }
    }

    fun setLensCatalogResult(result: LensCatalog.CatalogResult) {
        lensCatalogResult = result
        recalculateState()
        refreshRawAvailabilityForCurrentLens()
    }

    fun setZoom(ratio: Float) {
        if (_selectedLensRole.value != LensRole.PRIMARY) return
        val clampedRatio = ratio.coerceIn(1.0f, 3.0f)
        _digitalZoomRatio.value = clampedRatio
        recalculateState()
    }

    /**
     * Cycle through physical lenses regardless of catalog availability.
     * The preview binding in CameraPreviewView will fallback gracefully if a lens isn't found.
     */
    fun cycleLens() {
        val nextRole = when (_selectedLensRole.value) {
            LensRole.PRIMARY -> LensRole.ULTRA_WIDE
            LensRole.ULTRA_WIDE -> LensRole.TELE
            LensRole.TELE -> LensRole.PRIMARY
        }
        _selectedLensRole.value = nextRole
        _lensSwitchTrigger.value = _lensSwitchTrigger.value + 1
        _digitalZoomRatio.value = 1.0f
        recalculateState()
        refreshRawAvailabilityForCurrentLens()
        // Switching lens invalidates the per-lens extension availability; reset
        // the probe so the UI re-queries against the new logical camera.
        _extensionsProbeDone.value = false
        _availableExtensions.value = setOf(CaptureExtension.NONE)
        _activeExtension.value = CaptureExtension.NONE
    }

    private fun recalculateState() {
        val catalog = lensCatalogResult
        val primaryFocalMm = catalog?.primary?.equivFocalMm ?: 24f
        val ultraWideFocalMm = catalog?.ultraWide?.equivFocalMm ?: 13.4f
        val teleFocalMm = catalog?.tele?.equivFocalMm ?: 116.2f

        val selectedRole = _selectedLensRole.value

        _previewLensRole.value = selectedRole
        _captureLensRole.value = selectedRole

        when (selectedRole) {
            LensRole.PRIMARY -> {
                val nativeFocal = primaryFocalMm
                val digitalZoom = _digitalZoomRatio.value
                val effectiveFocal = (nativeFocal * digitalZoom).toInt()
                _effectiveFocalLength.value = effectiveFocal
                val scale = FovMapper.boxScale(nativeFocal, effectiveFocal.toFloat())
                _boxScale.value = scale
            }
            LensRole.ULTRA_WIDE -> {
                val nativeFocal = ultraWideFocalMm.toInt()
                _effectiveFocalLength.value = nativeFocal
                _boxScale.value = 1f
            }
            LensRole.TELE -> {
                val nativeFocal = teleFocalMm.toInt()
                _effectiveFocalLength.value = nativeFocal
                _boxScale.value = 1f
            }
        }
    }

    fun setExposure(value: Float) { _exposure.value = value.coerceIn(-3.0f, 3.0f) }
    fun setTemperature(value: Float) { _temperature.value = value.coerceIn(-2.0f, 2.0f) }
    fun setTint(value: Float) { _tint.value = value.coerceIn(-2.0f, 2.0f) }
    fun setCameraPreset(preset: RetroCameraPreset) {
        _activePreset.value = preset
        setTemperature(preset.tempOffset)
        setTint(preset.tintOffset)
        setExposure(preset.exposureOffset)
    }
    fun toggleFlash() { _flashMode.value = (_flashMode.value + 1) % 3 }
    fun toggleCamera() { _isFrontCamera.value = !_isFrontCamera.value }
    fun toggleGridLines() { _showGridLines.value = !_showGridLines.value }
    fun cycleSelfTimer() {
        _selfTimerMode.value = when (_selfTimerMode.value) { 0 -> 3; 3 -> 10; else -> 0 }
    }
    fun toggleDoubleExposure() { _doubleExposureActive.value = !_doubleExposureActive.value }
    fun setAspectRatio(ratio: AspectRatio) { _aspectRatio.value = ratio }
    fun setSelectedPhoto(file: File?) { _selectedPhoto.value = file }

    fun toggleTemperatureSlider() {
        _showTemperatureSlider.value = !_showTemperatureSlider.value
        if (_showTemperatureSlider.value) _showExposureSlider.value = false
    }

    fun toggleExposureSlider() {
        _showExposureSlider.value = !_showExposureSlider.value
        if (_showExposureSlider.value) _showTemperatureSlider.value = false
    }

    fun closeSliders() {
        _showTemperatureSlider.value = false
        _showExposureSlider.value = false
    }

    // ── RAW / Extension toggles ───────────────────────────────────────────

    /**
     * Toggle RAW capture mode. Refuses to enable RAW when the current lens
     * doesn't support it (the caller can also check [rawAvailableForCurrentLens]
     * to grey out the control).
     */
    fun toggleRawMode() {
        if (!_rawModeEnabled.value && !_rawAvailableForCurrentLens.value) return
        _rawModeEnabled.value = !_rawModeEnabled.value
        // RAW bypasses OEM extensions by design (extensions produce processed
        // JPEGs); force NONE while RAW is on so the two don't conflict.
        if (_rawModeEnabled.value) _activeExtension.value = CaptureExtension.NONE
    }

    fun setRawModeEnabled(enabled: Boolean) {
        if (enabled && !_rawAvailableForCurrentLens.value) return
        _rawModeEnabled.value = enabled
        if (enabled) _activeExtension.value = CaptureExtension.NONE
    }

    /**
     * Select an OEM extension mode. Falls back to NONE if the mode isn't in
     * [availableExtensions] (probed at runtime).
     */
    fun setExtension(ext: CaptureExtension) {
        if (ext != CaptureExtension.NONE && ext !in _availableExtensions.value) return
        _activeExtension.value = ext
        // Extensions produce processed output, so RAW is mutually exclusive.
        if (ext != CaptureExtension.NONE) _rawModeEnabled.value = false
    }

    fun cycleExtension() {
        val available = CaptureExtension.userSelectable.filter { it in _availableExtensions.value }
        if (available.size <= 1) return
        val idx = available.indexOf(_activeExtension.value)
        _activeExtension.value = available[(idx + 1).mod(available.size)]
        if (_activeExtension.value != CaptureExtension.NONE) _rawModeEnabled.value = false
    }

    /**
     * Refreshes the RAW availability flag based on the currently selected
     * lens. Called whenever the lens role changes or the catalog is refreshed.
     */
    fun refreshRawAvailabilityForCurrentLens() {
        val catalog = lensCatalogResult ?: return
        val currentLens = when (_selectedLensRole.value) {
            LensRole.ULTRA_WIDE -> catalog.ultraWide
            LensRole.PRIMARY -> catalog.primary
            LensRole.TELE -> catalog.tele
        }
        val supported = currentLens?.supportsRaw == true
        _rawAvailableForCurrentLens.value = supported
        // Auto-disable RAW if the user switches to a lens that can't do it.
        if (!supported && _rawModeEnabled.value) _rawModeEnabled.value = false
    }

    /**
     * Probes which OEM extensions the device advertises for the given camera.
     * Result is cached in [availableExtensions] and surfaced via [extensionsProbeDone]
     * so the UI can stop showing the loading affordance.
     */
    fun probeExtensions(
        context: Context,
        cameraProvider: androidx.camera.lifecycle.ProcessCameraProvider,
        logicalCameraId: String,
        isFrontCamera: Boolean,
        lifecycleOwner: androidx.lifecycle.LifecycleOwner
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val manager = PreviewSessionManager(context, lifecycleOwner)
                val available = manager.availableExtensions(cameraProvider, logicalCameraId, isFrontCamera)
                _availableExtensions.value = available
                if (_activeExtension.value !in available) _activeExtension.value = CaptureExtension.NONE
            } catch (e: Exception) {
                Log.e("CameraViewModel", "probeExtensions failed", e)
                _availableExtensions.value = setOf(CaptureExtension.NONE)
            } finally {
                _extensionsProbeDone.value = true
            }
        }
    }

    fun playShutterSound() {
        try { shutterSound.play(MediaActionSound.SHUTTER_CLICK) } catch (e: Exception) { Log.e("CameraViewModel", "Error playing shutter sound", e) }
    }

    /**
     * RAW capture entry point. Routes the shutter through [RawCapture.captureDng]
     * and inserts the resulting .dng into the gallery as image/x-adobe-dng.
     * Skips the JPEG post-processing pipeline (no retro filter / crop).
     */
    fun captureAndSaveRaw(
        context: Context,
        logicalCameraId: String,
        physicalCameraId: String,
        focalLengthMm: Int
    ) {
        _isCapturing.value = true
        RawCapture.captureDng(
            context = context,
            logicalCameraId = logicalCameraId,
            physicalCameraId = physicalCameraId,
            focalLengthMm = focalLengthMm,
            flashMode = _flashMode.value,
            onCaptured = { dngFile ->
                saveDngToGallery(context, dngFile)
                loadPhotos(context)
                android.widget.Toast.makeText(context, "RAW saved: ${dngFile.name}", android.widget.Toast.LENGTH_SHORT).show()
                _isCapturing.value = false
            },
            onError = { e ->
                Log.e("CameraViewModel", "RAW capture failed", e)
                android.widget.Toast.makeText(
                    context,
                    "RAW capture failed: ${e.localizedMessage ?: "unknown error"}",
                    android.widget.Toast.LENGTH_SHORT
                ).show()
                _isCapturing.value = false
            }
        )
    }

    /**
     * Inserts a .dng into MediaStore under Pictures/ZoomBoxCamera/RAW. RAW files
     * are kept separate from JPEGs both by extension and by subfolder so the
     * retro-roll filmstrip (which decodes JPEGs) isn't polluted.
     */
    private fun saveDngToGallery(context: Context, file: File) {
        try {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, file.name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/ZoomBoxCamera/RAW")
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
            val uri = resolver.insert(contentUri, values) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { `in` -> `in`.copyTo(out) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
            }
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error saving DNG to gallery", e)
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
        val currentAspectRatioMultiplier = _aspectRatio.value.heightToWidth
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inMutable = true }
                var bitmap = BitmapFactory.decodeFile(rawFile.absolutePath, options)

                if (bitmap != null) {
                    var originalExposureTime = 0.0
                    var originalIso = 0
                    try {
                        val origExif = ExifInterface(rawFile.absolutePath)
                        originalExposureTime = origExif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                        originalIso = origExif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
                    } catch (e: Exception) { Log.e("CameraViewModel", "Error reading original EXIF", e) }

                    val matrix = Matrix()
                    try {
                        val exif = ExifInterface(rawFile.absolutePath)
                        val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                        when (orientation) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
                            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.postScale(-1f, 1f) }
                        }
                    } catch (e: Exception) { Log.e("CameraViewModel", "Error reading EXIF orientation", e) }

                    if (_isFrontCamera.value) matrix.postScale(-1f, 1f)

                    if (matrix.isIdentity.not()) {
                        val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                        bitmap.recycle()
                        bitmap = rotatedBitmap
                    }

                    // Three-stage crop pipeline. Runs left-to-right:
                    //   1. Lens-based digital zoom (only when shooting beyond the
                    //      capture lens's native focal length, e.g. digital tele on
                    //      the Primary lens). Magnifies the sensor output via a
                    //      centered crop.
                    //   2. Aspect-ratio crop (ALWAYS). Centers the result to the
                    //      target portrait ratio. This is the bugfix for the stale
                    //      4:3 output when the user picked 3:2 or 1:1 in Settings.
                    //   3. Live zoom-box crop (only when zoomed in and no lens crop
                    //      was applied). Layers the editor's framing on top of the
                    //      aspect-ratio base.
                    val lensCropped = if (captureLensNativeFocalMm != null) {
                        val cropFactor = (captureLensNativeFocalMm / captureFocalLength).coerceIn(0f, 1f)
                        if (cropFactor < 0.99f) {
                            val cropW = (bitmap.width * cropFactor).toInt().coerceAtLeast(1)
                            val cropH = (bitmap.height * cropFactor).toInt().coerceAtLeast(1)
                            val cropX = (bitmap.width - cropW) / 2
                            val cropY = (bitmap.height - cropH) / 2
                            try {
                                val cropped = Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH)
                                if (cropped != bitmap) { bitmap.recycle() }
                                cropped
                            } catch (e: Exception) { Log.e("CameraViewModel", "Error center-cropping", e); bitmap }
                        } else { bitmap }
                    } else { bitmap }

                    val arCropped = try {
                        val c = centerCropToAspectRatio(lensCropped, currentAspectRatioMultiplier)
                        if (c != lensCropped && lensCropped !== bitmap) { lensCropped.recycle() }
                        c
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Error aspect-ratio cropping", e); lensCropped
                    }

                    val finalBitmap = if (captureLensNativeFocalMm == null && boxWidthFraction < 0.99f) {
                        try {
                            val zoomed = cropBitmapToZoomBox(arCropped, boxWidthFraction, screenWidth, screenHeight, currentAspectRatioMultiplier)
                            if (zoomed != arCropped) { arCropped.recycle() }
                            zoomed
                        } catch (e: Exception) { Log.e("CameraViewModel", "Error zoom-box cropping", e); arCropped }
                    } else { arCropped }

                    val processedBitmap = if (_temperature.value != 0f || _tint.value != 0f || _exposure.value != 0f) {
                        finalBitmap.applyRetroFilter(_temperature.value, _tint.value, _exposure.value)
                    } else { finalBitmap }

                    FileOutputStream(rawFile).use { out -> processedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out) }
                    processedBitmap.recycle()
                    if (finalBitmap != processedBitmap) { finalBitmap.recycle() }

                    val focalDir = rawFile.parentFile
                    val focalName = rawFile.nameWithoutExtension
                    val focalExt = rawFile.extension
                    val newName = "${focalName}_${captureFocalLength}mm.$focalExt"
                    val renamedFile = File(focalDir, newName)
                    rawFile.renameTo(renamedFile)

                    try {
                        val exifOut = ExifInterface(renamedFile.absolutePath)
                        if (originalExposureTime > 0.0) { exifOut.setAttribute(ExifInterface.TAG_EXPOSURE_TIME, originalExposureTime.toString()) }
                        if (originalIso > 0) { exifOut.setAttribute(ExifInterface.TAG_ISO_SPEED_RATINGS, originalIso.toString()) }
                        exifOut.setAttribute(ExifInterface.TAG_FOCAL_LENGTH, "${captureFocalLength}.0")
                        exifOut.saveAttributes()
                    } catch (e: Exception) { Log.e("CameraViewModel", "Error writing EXIF", e) }

                    savePhotoToGallery(context, renamedFile)
                }
                loadPhotos(context)
            } catch (e: Exception) { Log.e("CameraViewModel", "Error processing photo", e) }
            finally { _isCapturing.value = false }
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
                } else { @Suppress("DEPRECATION") put(MediaStore.Images.Media.DATA, file.absolutePath) }
            }
            val resolver = context.contentResolver
            val contentUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val uri = resolver.insert(contentUri, values)
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { `in` -> `in`.copyTo(out) } }
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }

            }
        } catch (e: Exception) { Log.e("CameraViewModel", "Error saving to gallery", e) }
    }

    fun readExifData(file: File): ExifData {
        return try {
            val exif = ExifInterface(file.absolutePath)
            val exposureTime = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
            val shutterSpeed = if (exposureTime > 0.0) {
                if (exposureTime < 1.0) { val denom = kotlin.math.round(1.0 / exposureTime).toInt(); "1/${denom}s" }
                else { "${kotlin.math.round(exposureTime).toInt()}s" }
            } else "--"
            val isoRaw = exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
            val iso = if (isoRaw > 0) "ISO $isoRaw" else "--"
            val name = file.nameWithoutExtension
            val focalMatch = Regex("""_(\d+)mm$""").find(name)
            val focalLength = focalMatch?.groupValues?.get(1)?.let { "${it}mm" } ?: "--"
            ExifData(focalLength = focalLength, shutterSpeed = shutterSpeed, iso = iso)
        } catch (e: Exception) { Log.e("CameraViewModel", "Error reading EXIF", e); ExifData() }
    }

    private fun cropBitmapToZoomBox(bitmap: Bitmap, boxWidthFraction: Float, screenWidth: Float, screenHeight: Float, aspectRatioMultiplier: Float): Bitmap {
        val wBitmap = bitmap.width.toFloat()
        val hBitmap = bitmap.height.toFloat()
        val scale = kotlin.math.max(screenWidth / wBitmap, screenHeight / hBitmap)
        val wVisible = screenWidth / scale
        val hVisible = screenHeight / scale
        val xVisibleStart = (wBitmap - wVisible) / 2f
        val yVisibleStart = (hBitmap - hVisible) / 2f
        val wBox = screenWidth * boxWidthFraction
        val hBox = wBox * aspectRatioMultiplier
        val xBox = (screenWidth - wBox) / 2f
        val yBox = (screenHeight - hBox) / 2f
        val xCropStart = (xVisibleStart + (xBox / screenWidth) * wVisible).toInt().coerceIn(0, bitmap.width - 1)
        val yCropStart = (yVisibleStart + (yBox / screenHeight) * hVisible).toInt().coerceIn(0, bitmap.height - 1)
        val wCrop = ((wBox / screenWidth) * wVisible).toInt().coerceIn(1, bitmap.width - xCropStart)
        val hCrop = ((hBox / screenHeight) * hVisible).toInt().coerceIn(1, bitmap.height - yCropStart)
        return Bitmap.createBitmap(bitmap, xCropStart, yCropStart, wCrop, hCrop)
    }

    /**
     * Centers [bitmap] to the portrait ratio described by [heightToWidth].
     *
     * Inputs are assumed to be already EXIF-corrected and (if applicable)
     * selfie-mirrored, so the bitmap is in screen-natural orientation. The
     * target ratio is `width/height = 1 / heightToWidth` (e.g. heightToWidth
     * = 1.35 for 4:3 portrait → target w/h = 0.741; heightToWidth = 1.5 for
     * 3:2 portrait → 0.667; heightToWidth = 1.0 for 1:1 → 1.0).
     *
     * If the bitmap's actual width/height ratio is within a 2% tolerance of
     * the target, the bitmap is returned unchanged. Otherwise a centered
     * crop is computed: if the bitmap is wider than target, crop the sides;
     * if it's taller, crop the top/bottom.
     *
     * This is the always-on portion of the crop pipeline \u2014 aspect ratio is
     * honored even when the user is at 1.0\u00d7 zoom with no lens-based crop,
     * which the previous implementation did not do (the bug was that the
     * `else { bitmap }` branch on a non-zoom capture preserved the sensor's
     * native 4:3 ratio regardless of the user's aspect-ratio choice).
     */
    private fun centerCropToAspectRatio(bitmap: Bitmap, heightToWidth: Float): Bitmap {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        if (w <= 0f || h <= 0f || heightToWidth <= 0f) return bitmap
        val actualRatio = w / h                  // bitmap's current width/height
        val targetRatio = 1f / heightToWidth     // desired width/height
        if (kotlin.math.abs(actualRatio - targetRatio) < 0.02f) return bitmap
        return if (actualRatio > targetRatio) {
            // Bitmap is wider than target -> crop sides to match.
            val targetW = (h * targetRatio).toInt().coerceIn(1, bitmap.width)
            val cropX = ((w - targetW) / 2f).toInt().coerceAtLeast(0)
            Bitmap.createBitmap(bitmap, cropX, 0, targetW, bitmap.height)
        } else {
            // Bitmap is taller than target -> crop top/bottom to match.
            val targetH = (w / targetRatio).toInt().coerceIn(1, bitmap.height)
            val cropY = ((h - targetH) / 2f).toInt().coerceAtLeast(0)
            Bitmap.createBitmap(bitmap, 0, cropY, bitmap.width, targetH)
        }
    }

    fun deletePhoto(context: Context, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val wasSelected = _selectedPhoto.value == file
                // Position of the deleted photo in the (newest-first) gallery,
                // captured BEFORE the file disappears so the auto-advance below
                // can land on the photo that fills the deleted slot — the same
                // one the user would have reached by scrolling farther down the
                // filmstrip. -1 is a sentinel meaning "file isn't currently
                // tracked" (e.g. out-of-band deletion); we handle that branch
                // explicitly below instead of coercing it to 0 (which would
                // silently jump the user to the newest photo).
                val insertionIndex = if (wasSelected) {
                    _capturedPhotos.value.indexOf(file)
                } else -1

                // The file passed in is whichever copy listPhotoFiles
                // surfaced (public takes precedence). There may still be a
                // same-name mirror in the app-private dir — delete that too
                // so a subsequent startup scan doesn't re-surface it as a
                // duplicate after the public copy was removed.
                val privateMirror = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?.let { File(it, file.name) }
                listOfNotNull(file, privateMirror).distinct().forEach { candidate ->
                    if (candidate.exists()) candidate.delete()
                }

                // Re-scan synchronously inside this coroutine instead of calling
                // loadPhotos() — loadPhotos fires its own viewModelScope.launch
                // and _capturedPhotos wouldn't be updated by the time we read it
                // for the advance decision. Note: rapid double deletes may
                // interleave (viewModelScope.launch is not serialized), but
                // MutableStateFlow guarantees ordered, conflated emissions, so
                // the eventual UI state is still the desired one.
                val refreshed = listPhotoFiles(context)
                _capturedPhotos.value = refreshed

                // Stay-in-gallery: deleting shouldn't kick the user out of the
                // photo viewer. Same-slot — next photo in filmstrip order
                // (chronologically older since the list is newest-first);
                // tail-stepping — when the deleted photo was the very last
                // entry; sentinel fallback — pick any photo to keep the
                // gallery open if the deleted file wasn't tracked anymore;
                // only close the viewer when there is literally nothing
                // left to show.
                if (wasSelected) {
                    _selectedPhoto.value = when {
                        insertionIndex in refreshed.indices -> refreshed[insertionIndex]
                        refreshed.isNotEmpty() -> refreshed.last()
                        else -> null
                    }
                }
            } catch (e: Exception) { Log.e("CameraViewModel", "Error deleting photo", e) }
        }
    }

    private fun Bitmap.applyRetroFilter(tempVal: Float, tintVal: Float, expVal: Float): Bitmap {
        val w = this.width; val h = this.height
        val output = Bitmap.createBitmap(w, h, this.config ?: Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint()
        canvas.drawBitmap(this, 0f, 0f, paint)
        if (expVal != 0f) {
            val offset = expVal * 20f
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(android.graphics.ColorMatrix(floatArrayOf(1f,0f,0f,0f,offset,0f,1f,0f,0f,offset,0f,0f,1f,0f,offset,0f,0f,0f,1f,0f)))
            canvas.drawBitmap(output, 0f, 0f, paint)
        }
        if (tempVal != 0f) {
            val tintColor = if (tempVal > 0f) android.graphics.Color.argb((tempVal * 25f).toInt().coerceIn(0, 80), 245, 158, 11)
            else android.graphics.Color.argb((-tempVal * 25f).toInt().coerceIn(0, 80), 14, 165, 233)
            val tp = android.graphics.Paint().apply { colorFilter = android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_ATOP) }
            canvas.drawBitmap(output, 0f, 0f, tp)
        }
        if (tintVal != 0f) {
            val tintColor = if (tintVal > 0f) android.graphics.Color.argb((tintVal * 25f).toInt().coerceIn(0, 80), 236, 72, 153)
            else android.graphics.Color.argb((-tintVal * 25f).toInt().coerceIn(0, 80), 34, 197, 94)
            val tp = android.graphics.Paint().apply { colorFilter = android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_ATOP) }
            canvas.drawBitmap(output, 0f, 0f, tp)
        }
        val vp = android.graphics.Paint().apply { isAntiAlias = true; shader = android.graphics.RadialGradient(w/2f, h/2f, kotlin.math.max(w, h)*0.72f, intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(135, 12, 12, 12)), floatArrayOf(0.55f, 1.0f), android.graphics.Shader.TileMode.CLAMP) }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), vp)
        return output
    }

    override fun onCleared() { super.onCleared(); try { shutterSound.release() } catch (_: Exception) {} }
}