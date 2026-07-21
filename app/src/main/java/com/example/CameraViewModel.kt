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
import com.example.color.CubeLut
import com.example.color.CubeLutParser
import com.example.color.LutColorFilter
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

/**
 * A film profile backed by a 3D `.cube` LUT in `assets/`.
 *
 * The LUT defines the base color grade; the default slider values are applied
 * on top of it when the preset is selected and remain user-adjustable.
 */
enum class FilmPreset(
    val displayName: String,
    val assetPath: String,
    val defaultTemp: Float = 0f,
    val defaultTint: Float = 0f,
    val defaultExposure: Float = 0f
) {
    KODAK_PORTRA("Kodak Portra 160", "luts/kodak_portra_160_vc.cube"),
    KODAK_BW("Kodak BW 400 CN", "luts/kodak_bw_400_cn.cube"),
    POLAROID("Polaroid PX-680", "luts/polaroid_px-680.cube"),
    KODAK_ELITE_100_XPRO("Kodak Elite 100 XPro", "luts/kodak_elite_100_xpro.cube"),
    POLAROID_669("Polaroid 669 ++", "luts/polaroid_669_++.cube")
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

    private val _activePreset = MutableStateFlow(FilmPreset.KODAK_PORTRA)
    val activePreset: StateFlow<FilmPreset> = _activePreset.asStateFlow()

    // Lazily-parsed LUTs keyed by asset path. Parsed once on first use and
    // reused for every subsequent capture that selects the same film.
    private val cachedLuts = mutableMapOf<String, CubeLut>()

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
        // The default _selectedLensRole is PRIMARY (24mm-ish). On devices
        // without a primary-class lens the initial preview stays black
        // because the binding fails silently. Auto-correct the initial
        // selection to the first available back-facing lens so the
        // viewfinder lights up at app start even without a 24mm hardware
        // camera. Mirrors the user's bug: "Ich habe keine Kamera '24' —
        // die App switcht beim Start zu dieser Kamera".
        ensureSelectedLensAvailable()
        recalculateState()
        refreshRawAvailabilityForCurrentLens()
    }

    /**
     * Auto-correction for the initial lens selection: if the currently
     * selected role isn't backed by a physical lens on this device, step
     * down the priority ladder PRIMARY → ULTRA_WIDE → TELE and pick the
     * first role the catalog actually has. If the device has no back-facing
     * lenses at all, leave the selection unchanged and let the preview's
     * DEFAULT_BACK_CAMERA fallback path handle the binding.
     */
    private fun ensureSelectedLensAvailable() {
        val catalog = lensCatalogResult ?: return
        val current = _selectedLensRole.value
        val currentAvailable = when (current) {
            LensRole.ULTRA_WIDE -> catalog.ultraWide != null
            LensRole.PRIMARY -> catalog.primary != null
            LensRole.TELE -> catalog.tele != null
        }
        if (currentAvailable) return

        val fallback = when {
            catalog.primary != null -> LensRole.PRIMARY
            catalog.ultraWide != null -> LensRole.ULTRA_WIDE
            catalog.tele != null -> LensRole.TELE
            else -> return  // no back cameras; leave selection for the
                             // preview's DEFAULT_BACK_CAMERA path
        }
        _selectedLensRole.value = fallback
        // Mirror cycleLens(): bump the switch trigger so the CameraPreviewView
        // re-keys and the binding re-fires against the corrected role.
        _lensSwitchTrigger.value = _lensSwitchTrigger.value + 1
        _digitalZoomRatio.value = 1.0f
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
        // Front camera has only one lens — there is nothing to cycle to.
        // Bump out before the state machine runs so the BubbleRow in
        // CameraUi doesn't flicker through 13/24/116 mm while the user is
        // on selfie mode. The UI also gates the click, but guarding here
        // is defense-in-depth in case a future screen subscribes
        // directly to _selectedLensRole.
        if (_isFrontCamera.value) return
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
        // Front camera is single-lens; back-camera focal / box-scale / role
        // state is meaningless there. Short-circuit prevents an earlier
        // rear-camera zoom from leaking into the front preview as the
        // zoom-box overlay + "24mm" label at line ~1421 of CameraUi.kt.
        if (_isFrontCamera.value) return
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
    fun setCameraPreset(preset: FilmPreset) {
        _activePreset.value = preset
        setTemperature(preset.defaultTemp)
        setTint(preset.defaultTint)
        setExposure(preset.defaultExposure)
    }

    /**
     * Returns the parsed LUT for [preset], loading and caching it on first use.
     * Returns null if the asset cannot be read (the pipeline then skips the
     * LUT step and falls back to the manual color filters only).
     */
    fun loadLut(context: Context, preset: FilmPreset): CubeLut? {
        cachedLuts[preset.assetPath]?.let { return it }
        return try {
            val lut = CubeLutParser.parse(preset.assetPath, context)
            cachedLuts[preset.assetPath] = lut
            lut
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Failed to load LUT ${preset.assetPath}", e)
            null
        }
    }
    fun toggleFlash() { _flashMode.value = (_flashMode.value + 1) % 3 }
    fun toggleCamera() {
        val nowFront = !_isFrontCamera.value
        _isFrontCamera.value = nowFront
        if (nowFront) {
            // The front camera is single-lens. Reset zoom-related state so
            // a back-camera zoom (boxScale < 0.99) doesn't carry into the
            // selfie preview as a stale zoom-box overlay. _selectedLensRole
            // is left alone — the user typically flips back-and-forth and
            // we want them to land where they were last on the rear side.
            _digitalZoomRatio.value = 1.0f
            _boxScale.value = 1f
        }
        // Always re-run recalculateState: short-circuits on front (so the
        // reset values above stick), recomputes on rear transition.
        recalculateState()
    }
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
            try {                val originalBitmap = BitmapFactory.decodeFile(
                    rawFile.absolutePath,
                    BitmapFactory.Options().apply { inMutable = true }
                )

                if (originalBitmap != null) {
                    // Read EXIF metadata from the original rawFile before any
                    // rewrite touches it (we re-write the same fields onto the
                    // saved copy).
                    var originalExposureTime = 0.0
                    var originalIso = 0
                    try {
                        val origExif = ExifInterface(rawFile.absolutePath)
                        originalExposureTime = origExif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0)
                        originalIso = origExif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, 0)
                    } catch (e: Exception) { Log.e("CameraViewModel", "Error reading original EXIF", e) }

                    // ─── Stage 0: normalize orientation FIRST ────────────────────
                    // An earlier rewrite deferred the EXIF rotation into the
                    // final Bitmap.createBitmap(..., matrix, true) call while
                    // computing crop coords in pre-rotation source space. That
                    // produced two visible regressions on saved JPEGs:
                    //   1. EXIF double-rotation — pixels were rotated via the
                    //      matrix and the EXIF tag was left pointing at the
                    //      same rotation, so galleries applied it twice (the
                    //      common ROTATE_90 case → 180° flip on display).
                    //   2. Wrong AR-crop axis — curW/curH were the
                    //      pre-rotation dims, so a 90° rotation dropped the
                    //      aspect-ratio crop onto the wrong side of the image
                    //      (a 4:3 sensor with RATIO_4_3 cropped the width to
                    //      2238 px even though the output ends up portrait,
                    //      leaving a noticeably squashed final photo).
                    //
                    // Fix: bake the EXIF rotation + selfie flip into a
                    // display-natural bitmap up front, then run all three
                    // crop stages against post-rotation coords so the math
                    // projects onto the axes the user actually sees. Cost: 1
                    // extra Bitmap alloc (~80 ms at 12 MP), still −2 fewer
                    // allocations than the original pre-rewrite pipeline.
                    val combinedMatrix = Matrix()
                    try {
                        val exif = ExifInterface(rawFile.absolutePath)
                        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> combinedMatrix.postRotate(90f)
                            ExifInterface.ORIENTATION_ROTATE_180 -> combinedMatrix.postRotate(180f)
                            ExifInterface.ORIENTATION_ROTATE_270 -> combinedMatrix.postRotate(270f)
                            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> combinedMatrix.postScale(-1f, 1f)
                            ExifInterface.ORIENTATION_FLIP_VERTICAL -> combinedMatrix.postScale(1f, -1f)
                            ExifInterface.ORIENTATION_TRANSPOSE -> { combinedMatrix.postRotate(90f); combinedMatrix.postScale(-1f, 1f) }
                            ExifInterface.ORIENTATION_TRANSVERSE -> { combinedMatrix.postRotate(270f); combinedMatrix.postScale(-1f, 1f) }
                        }
                    } catch (e: Exception) { Log.e("CameraViewModel", "Error reading EXIF orientation", e) }
                    if (_isFrontCamera.value) combinedMatrix.postScale(-1f, 1f)

                    val normalizedBitmap = try {
                        if (combinedMatrix.isIdentity) {
                            // Fast path for the common rear-camera + EXIF
                            // NORMAL case: no extra alloc, no per-pixel
                            // transform pipeline — just hand back the source.
                            originalBitmap
                        } else {
                            Bitmap.createBitmap(originalBitmap, 0, 0, originalBitmap.width, originalBitmap.height, combinedMatrix, true)
                        }
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Error in normalize+rotate", e)
                        originalBitmap
                    }
                    if (normalizedBitmap !== originalBitmap) originalBitmap.recycle()

                    // ─── Stage 1: lens-based digital zoom (digital tele) ───────
                    // Crop coords are now in post-rotation (display-natural)
                    // space. AspectRatio.heightToWidth is a portrait h/w ratio,
                    // so the post-rotation source dims already match the
                    // user's portrait grip and AR crops project onto the right
                    // axes.
                    var curX = 0
                    var curY = 0
                    var curW = normalizedBitmap.width
                    var curH = normalizedBitmap.height
                    if (captureLensNativeFocalMm != null) {
                        val cropFactor = (captureLensNativeFocalMm / captureFocalLength).coerceIn(0f, 1f)
                        if (cropFactor < 0.99f) {
                            val cropW = (curW * cropFactor).toInt().coerceAtLeast(1)
                            val cropH = (curH * cropFactor).toInt().coerceAtLeast(1)
                            curX = (curW - cropW) / 2
                            curY = (curH - cropH) / 2
                            curW = cropW
                            curH = cropH
                        }
                    }

                    // ─── Stage 2: aspect-ratio crop (ALWAYS) ────────────────────
                    // Trim the longer axis so w/h = 1/currentAspectRatioMultiplier.
                    val arTargetRatio = 1f / currentAspectRatioMultiplier
                    val arActualRatio = curW.toFloat() / curH.toFloat()
                    if (kotlin.math.abs(arActualRatio - arTargetRatio) >= 0.02f) {
                        if (arActualRatio > arTargetRatio) {
                            val targetW = (curH * arTargetRatio).toInt().coerceIn(1, curW)
                            curX += (curW - targetW) / 2
                            curW = targetW
                        } else {
                            val targetH = (curW / arTargetRatio).toInt().coerceIn(1, curH)
                            curY += (curH - targetH) / 2
                            curH = targetH
                        }
                    }

                    // ─── Stage 3: live zoom-box crop ────────────────────────────
                    // Only when no native lens crop AND box is zoomed in. Maps
                    // the viewfinder viewport + box fraction back into
                    // source-coord space.
                    if (captureLensNativeFocalMm == null && boxWidthFraction < 0.99f) {
                        val wScreen = screenWidth
                        val hScreen = screenHeight
                        val arW = curW.toFloat()
                        val arH = curH.toFloat()
                        val scale = kotlin.math.max(wScreen / arW, hScreen / arH)
                        val wVisible = wScreen / scale
                        val hVisible = hScreen / scale
                        val xVisibleStart = (arW - wVisible) / 2f
                        val yVisibleStart = (arH - hVisible) / 2f
                        val wBox = wScreen * boxWidthFraction
                        val hBox = wBox * currentAspectRatioMultiplier
                        val xBox = (wScreen - wBox) / 2f
                        val yBox = (hScreen - hBox) / 2f
                        val xCrop = (xVisibleStart + (xBox / wScreen) * wVisible).toInt().coerceIn(0, curW - 1)
                        val yCrop = (yVisibleStart + (yBox / hScreen) * hVisible).toInt().coerceIn(0, curH - 1)
                        val wCrop = ((wBox / wScreen) * wVisible).toInt().coerceIn(1, curW - xCrop)
                        val hCrop = ((hBox / hScreen) * hVisible).toInt().coerceIn(1, curH - yCrop)
                        curX += xCrop
                        curY += yCrop
                        curW = wCrop
                        curH = hCrop
                    }

                    // ─── Single allocation: rotate + flip + 3 crops at once ───
                    // ─── Final allocation: pure crop on display-natural src ────
                    // normalizedBitmap (produced at Stage 0) is already in
                    // display-natural orientation, so no further transform is
                    // needed — the matrix-less overload below avoids the
                    // per-pixel transform pipeline that would be wasted work.
                    val finalBitmap = try {
                        Bitmap.createBitmap(normalizedBitmap, curX, curY, curW, curH)
                    } catch (e: Exception) {
                        Log.e("CameraViewModel", "Error in final crop", e)
                        normalizedBitmap
                    }
                    if (finalBitmap !== normalizedBitmap) normalizedBitmap.recycle()

                    // In-place LUT + temp + tint + exposure + vignette in a
                    // single pixel loop. Replaces 4\u20135 separate Canvas
                    // re-renders (each fully redrawing the bitmap into a new
                    // backing) plus the LUT's separate outer getPixels/setPixels
                    // round-trip. See [applyRetroFilter].
                    val currentLut = loadLut(context, _activePreset.value)
                    val hasAdjustments = _temperature.value != 0f || _tint.value != 0f || _exposure.value != 0f
                    if (currentLut != null || hasAdjustments) {
                        finalBitmap.applyRetroFilter(_temperature.value, _tint.value, _exposure.value, lut = currentLut)
                    }

                    FileOutputStream(rawFile).use { out -> finalBitmap.compress(Bitmap.CompressFormat.JPEG, 97, out) }
                    finalBitmap.recycle()

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
                        // The pixels already incorporate the EXIF rotation
                        // (we baked it into normalizedBitmap at Stage 0), so
                        // reset ORIENTATION to NORMAL — otherwise the gallery
                        // would re-apply the rotation on top of already-rotated
                        // pixels (a 180° misrender for the common ROTATE_90
                        // case). Setting this unconditionally is safe because
                        // the matrix stage normalized every capture to its
                        // post-rotation form before any crop.
                        exifOut.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
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
    private fun Bitmap.applyRetroFilter(
        tempVal: Float,
        tintVal: Float,
        expVal: Float,
        lut: CubeLut? = null
    ): Bitmap {
        val w = this.width
        val h = this.height
        if (w <= 0 || h <= 0) return this

        val hasExp = expVal != 0f
        val hasTemp = tempVal != 0f
        val hasTint = tintVal != 0f

        val expScale = if (hasExp) java.lang.Math.pow(2.0, (expVal * 0.4).toDouble()).toFloat() else 1f

        val tempIsPos = tempVal > 0f
        val tempR = if (hasTemp && tempIsPos) 245 else if (hasTemp) 14 else 0
        val tempG = if (hasTemp && tempIsPos) 158 else if (hasTemp) 165 else 0
        val tempB = if (hasTemp && tempIsPos) 11 else if (hasTemp) 233 else 0
        val tempA = if (hasTemp) (kotlin.math.abs(tempVal) * 25f).toInt().coerceIn(0, 80) else 0
        val tempInvA = 255 - tempA

        val tintIsPos = tintVal > 0f
        val tintR = if (hasTint && tintIsPos) 236 else if (hasTint) 34 else 0
        val tintG = if (hasTint && tintIsPos) 72 else if (hasTint) 197 else 0
        val tintB = if (hasTint && tintIsPos) 153 else if (hasTint) 94 else 0
        val tintA = if (hasTint) (kotlin.math.abs(tintVal) * 25f).toInt().coerceIn(0, 80) else 0
        val tintInvA = 255 - tintA

        val cx = w * 0.5f
        val cy = h * 0.5f
        val maxRadius = kotlin.math.max(w, h).toFloat() * 0.72f
        val maxRadiusInv = 1f / maxRadius
        val vigInner = 0.55f
        val vigRange = 0.45f
        val vigFadeMax = 135f / 255f
        val cornerRgb = 12f
        val innerRadiusSq = (vigInner * maxRadius) * (vigInner * maxRadius)

        val rowDy2 = FloatArray(h) { y -> (y - cy).let { it * it } }

        val pixels = IntArray(w * h)
        getPixels(pixels, 0, w, 0, 0, w, h)

        var p = 0
        val total = w * h
        while (p < total) {
            val x = p % w
            val y = p / w
            val dx = x - cx

            val c = pixels[p]
            val a = (c ushr 24) and 0xFF
            var r8 = (c ushr 16) and 0xFF
            var g8 = (c ushr 8) and 0xFF
            var b8 = c and 0xFF

            if (hasExp) {
                r8 = (r8 * expScale).toInt().coerceIn(0, 255)
                g8 = (g8 * expScale).toInt().coerceIn(0, 255)
                b8 = (b8 * expScale).toInt().coerceIn(0, 255)
            }

            if (hasTemp) {
                r8 = (r8 * tempInvA + tempR * tempA) / 255
                g8 = (g8 * tempInvA + tempG * tempA) / 255
                b8 = (b8 * tempInvA + tempB * tempA) / 255
            }

            if (hasTint) {
                r8 = (r8 * tintInvA + tintR * tintA) / 255
                g8 = (g8 * tintInvA + tintG * tintA) / 255
                b8 = (b8 * tintInvA + tintB * tintA) / 255
            }

            val distSq = dx * dx + rowDy2[y]
            if (distSq > innerRadiusSq) {
                val dist = kotlin.math.sqrt(distSq)
                val radialT = (dist * maxRadiusInv - vigInner) / vigRange
                if (radialT > 0f) {
                    val clampedT = if (radialT > 1f) 1f else radialT
                    val shaderA = clampedT * vigFadeMax
                    val shaderC = clampedT * cornerRgb
                    val invA = 1f - shaderA
                    val cornerContrib = shaderC * shaderA
                    r8 = (r8 * invA + cornerContrib).toInt().coerceIn(0, 255)
                    g8 = (g8 * invA + cornerContrib).toInt().coerceIn(0, 255)
                    b8 = (b8 * invA + cornerContrib).toInt().coerceIn(0, 255)
                }
            }

            pixels[p] = (a shl 24) or (r8 shl 16) or (g8 shl 8) or b8
            p++
        }

        setPixels(pixels, 0, w, 0, 0, w, h)

        if (lut != null) LutColorFilter.applyInPlace(this, lut)
        return this
    }

    override fun onCleared() { super.onCleared(); try { shutterSound.release() } catch (_: Exception) {} }
}