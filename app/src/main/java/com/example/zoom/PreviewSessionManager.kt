package com.example.zoom

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Log
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.TimeUnit

/**
 * Manages the live preview session, handling lens transitions.
 *
 * Uses Camera2Interop.Extender.setPhysicalCameraId() to route Preview and
 * ImageCapture use cases to the specific physical camera (ultra-wide, primary,
 * or tele) within the logical multi-camera device.
 *
 * Also pushes hardware ISP quality keys (EDGE_MODE, NOISE_REDUCTION_MODE,
 * HOT_PIXEL_MODE, LENS_OPTICAL_STABILIZATION_MODE, distortion correction) onto
 * the capture requests so every still benefits from the device's high-quality
 * processing pipeline.
 */
data class BindResult(
    val camera: Camera,
    val imageCapture: ImageCapture
)

/**
 * Which OEM/vendor extension to apply to the camera session. NONE disables
 * extensions and keeps the app's manual physical-lens routing.
 *
 * Extensions take over sensor selection on the OEM side, so when an extension
 * is active the manual physical-lens routing is intentionally bypassed (the
 * extension owns the sensor). Modes unavailable on the device fall back to NONE.
 */
enum class CaptureExtension(val mode: Int) {
    NONE(ExtensionMode.NONE),
    HDR(ExtensionMode.HDR),
    NIGHT(ExtensionMode.NIGHT),
    BOKEH(ExtensionMode.BOKEH),
    FACE_RETOUCH(ExtensionMode.FACE_RETOUCH),
    AUTO(ExtensionMode.AUTO);

    companion object {
        /** Modes surfaced to the user in the UI, in display order. */
        val userSelectable get() = listOf(NONE, HDR, NIGHT, BOKEH, AUTO)
    }
}

@OptIn(ExperimentalCamera2Interop::class)
class PreviewSessionManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "PreviewSessionManager"

        /**
         * Cached ExtensionsManager. Resolved lazily and reused across binds;
         * one manager instance covers all cameras on the device.
         */
        @Volatile private var extensionsManager: ExtensionsManager? = null
        @Volatile private var extensionsProvider: ProcessCameraProvider? = null
    }

    /**
     * Builds a CameraSelector targeting a logical camera by its CameraX ID.
     * Physical sub-cameras live inside the logical camera; they are selected
     * via Camera2Interop.Extender.setPhysicalCameraId() on each use case.
     */
    fun buildSelectorForLogicalCamera(logicalCameraId: String): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { camera ->
                    Camera2CameraInfo.from(camera).cameraId == logicalCameraId
                }
            }
            .build()
    }

    /**
     * Binds a preview + ImageCapture use case to a specific physical camera
     * within a logical multi-camera device.
     *
     * @param cameraProvider The ProcessCameraProvider
     * @param logicalCameraId The parent logical camera ID
     * @param physicalCameraId The target physical camera ID
     * @param surfaceProvider The Preview.SurfaceProvider from the PreviewView
     * @param flashMode Flash mode: 0 = Auto, 1 = On, 2 = Off
     * @param extension OEM extension to apply (NONE = manual physical-lens path)
     * @return A BindResult containing the bound Camera and ImageCapture, or null on failure
     */
    fun bindPreview(
        cameraProvider: ProcessCameraProvider,
        logicalCameraId: String,
        physicalCameraId: String,
        surfaceProvider: Preview.SurfaceProvider,
        flashMode: Int = 0,
        extension: CaptureExtension = CaptureExtension.NONE
    ): BindResult? {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation

        val characteristics = getCharacteristics(physicalCameraId)
            ?: getCharacteristics(logicalCameraId)

        // Extensions own the sensor path; only the non-extension path routes
        // to a specific physical lens (the app's signature behavior).
        //
        // Also skip the physical-lens routing when the IDs are equal: that
        // happens on single-lens devices/emulators (e.g. Pixel 5 AVD with
        // physicalCameraIds == [cameraId]) where `setPhysicalCameraId()` is a
        // documented no-op but in practice the HAL still has to undo the
        // surface config, which on virtualised stacks can intermittently
        // lead to an async `onConfigureFailed`. Letting the logical-camera
        // path bind clean here is strictly safer.
        val usePhysicalLens = extension == CaptureExtension.NONE &&
            physicalCameraId != logicalCameraId

        val preview = Preview.Builder()
            .apply {
                if (usePhysicalLens) {
                    Camera2Interop.Extender(this).setPhysicalCameraId(physicalCameraId)
                }
                applyQualityKeys(this, characteristics, isPreview = true)
            }
            .build()
            .apply { setSurfaceProvider(surfaceProvider) }

        val newImageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .apply {
                if (usePhysicalLens) {
                    Camera2Interop.Extender(this).setPhysicalCameraId(physicalCameraId)
                }
                applyQualityKeys(this, characteristics, isPreview = false)
                when (flashMode) {
                    0 -> setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                    1 -> setFlashMode(ImageCapture.FLASH_MODE_ON)
                    else -> setFlashMode(ImageCapture.FLASH_MODE_OFF)
                }
            }
            .build()

        val selector = if (usePhysicalLens) {
            buildSelectorForLogicalCamera(logicalCameraId)
        } else {
            // Build the base logical selector, then layer the extension on top.
            buildExtensionSelector(cameraProvider, logicalCameraId, extension)
                ?: buildSelectorForLogicalCamera(logicalCameraId)
        }

        return try {
            // unbindAll() lives inside the try so that on a synchronous bind
            // failure we still have whatever the previous invocation bound. If
            // we'd called unbindAll() before catching (old ordering) we'd tear
            // down a working preview before we even know the new bind will
            // succeed, which is how we ended up with the Pixel 5 emulator's
            // black-feed regression on the first failed retry.
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                newImageCapture
            )
            BindResult(camera, newImageCapture)
        } catch (e: Exception) {
            // Catches synchronous bind failures: IllegalStateException (from
            // CameraX sel.validate(...) / registerAggregateSessionConfig),
            // IllegalArgumentException (bad surface or use-case combo), and
            // everything else. Async `onConfigureFailed` is raised later on
            // CameraX's internal SequencerExecutor; that path is filtered
            // upstream in LensCatalog.hasUsablePreviewStream because it
            // cannot be caught here.
            Log.e(
                TAG,
                "Error binding preview to physical=$physicalCameraId logical=$logicalCameraId",
                e
            )
            null
        }
    }

    /**
     * Reports which extensions are actually available for a given logical
     * camera + lens-facing on this device. Use this to grey out unavailable
     * modes in the UI before calling [bindPreview].
     */
    fun availableExtensions(
        cameraProvider: ProcessCameraProvider,
        logicalCameraId: String,
        isFrontCamera: Boolean
    ): Set<CaptureExtension> {
        val supported = mutableSetOf<CaptureExtension>()
        try {
            val manager = getOrInitExtensionsManager(cameraProvider) ?: return emptySet()
            val baseSelector = CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { Camera2CameraInfo.from(it).cameraId == logicalCameraId }
                }
                .build()
            val facingSelector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            CaptureExtension.userSelectable.forEach { ext ->
                if (ext == CaptureExtension.NONE) {
                    supported.add(ext)
                    return@forEach
                }
                try {
                    if (manager.isExtensionAvailable(facingSelector, ext.mode)) {
                        // Also require availability against the specific logical id;
                        // some devices only expose extensions on the main rear cam.
                        if (manager.isExtensionAvailable(baseSelector, ext.mode)) {
                            supported.add(ext)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Extension ${ext.name} probe failed", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "availableExtensions failed", e)
        }
        return supported
    }

    /**
     * Lazily initializes the shared ExtensionsManager. Returns null if the
     * Extensions API isn't usable on this device.
     */
    private fun getOrInitExtensionsManager(provider: ProcessCameraProvider): ExtensionsManager? {
        extensionsManager?.let { return it }
        return try {
            val future = ExtensionsManager.getInstanceAsync(context, provider)
            // Bounded wait — ExtensionsManager init should be near-instant
            // once the provider is up, which it already is by this point.
            val manager = future[5, TimeUnit.SECONDS]
            extensionsManager = manager
            extensionsProvider = provider
            manager
        } catch (e: Exception) {
            Log.w(TAG, "ExtensionsManager unavailable; extension modes disabled", e)
            null
        }
    }

    private fun buildExtensionSelector(
        provider: ProcessCameraProvider,
        logicalCameraId: String,
        extension: CaptureExtension
    ): CameraSelector? {
        val manager = getOrInitExtensionsManager(provider) ?: return null
        val base = CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter { Camera2CameraInfo.from(it).cameraId == logicalCameraId }
            }
            .build()
        return try {
            manager.getExtensionEnabledCameraSelector(base, extension.mode)
        } catch (e: Exception) {
            Log.w(TAG, "getExtensionEnabledCameraSelector(${extension.name}) failed", e)
            null
        }
    }

    /**
     * Applies the ISP quality CaptureRequest keys supported by this sensor to
     * the given Preview builder via Camera2-Interop. Each key is gated by the
     * corresponding AVAILABLE_*_MODES characteristic so unsupported devices
     * are left untouched (no-op).
     */
    private fun applyQualityKeys(
        builder: Preview.Builder,
        characteristics: CameraCharacteristics?,
        isPreview: Boolean
    ) {
        if (characteristics == null) return
        val extender = Camera2Interop.Extender(builder)
        applyEdgeMode(extender, characteristics, isPreview)
        applyNoiseReductionMode(extender, characteristics, isPreview)
        applyHotPixelMode(extender, characteristics)
        applyOisMode(extender, characteristics)
        applyDistortionCorrection(extender, characteristics)
    }

    /**
     * Same as [applyQualityKeys] but for the still-capture ImageCapture builder.
     */
    private fun applyQualityKeys(
        builder: ImageCapture.Builder,
        characteristics: CameraCharacteristics?,
        isPreview: Boolean
    ) {
        if (characteristics == null) return
        val extender = Camera2Interop.Extender(builder)
        applyEdgeMode(extender, characteristics, isPreview)
        applyNoiseReductionMode(extender, characteristics, isPreview)
        applyHotPixelMode(extender, characteristics)
        applyOisMode(extender, characteristics)
        applyDistortionCorrection(extender, characteristics)
    }

    // ---- Per-key helpers. Each is gated by the device's AVAILABLE_*_MODES ----
    // so we never request a mode the HAL can't deliver. `Extender<T : Builder<T>>`
    // is shared between Preview.Builder and ImageCapture.Builder.

    private fun applyEdgeMode(
        extender: Camera2Interop.Extender<*>,
        characteristics: CameraCharacteristics,
        isPreview: Boolean
    ) {
        @Suppress("UNCHECKED_CAST")
        val modes = characteristics.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES) ?: return
        // HIGH_QUALITY is intended for stills; FAST for preview.
        val mode = if (isPreview) CaptureRequest.EDGE_MODE_FAST else CaptureRequest.EDGE_MODE_HIGH_QUALITY
        val chosen = when {
            modes.contains(mode) -> mode
            modes.contains(CaptureRequest.EDGE_MODE_HIGH_QUALITY) -> CaptureRequest.EDGE_MODE_HIGH_QUALITY
            else -> return
        }
        setOptionSafe(extender, CaptureRequest.EDGE_MODE, chosen)
    }

    private fun applyNoiseReductionMode(
        extender: Camera2Interop.Extender<*>,
        characteristics: CameraCharacteristics,
        isPreview: Boolean
    ) {
        val modes = characteristics.get(
            CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES) ?: return
        val preferred = if (isPreview) CaptureRequest.NOISE_REDUCTION_MODE_FAST
        else CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
        val mode = when {
            modes.contains(preferred) -> preferred
            modes.contains(CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY) ->
                CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY
            modes.contains(CaptureRequest.NOISE_REDUCTION_MODE_FAST) ->
                CaptureRequest.NOISE_REDUCTION_MODE_FAST
            else -> return
        }
        setOptionSafe(extender, CaptureRequest.NOISE_REDUCTION_MODE, mode)
    }

    private fun applyHotPixelMode(
        extender: Camera2Interop.Extender<*>,
        characteristics: CameraCharacteristics
    ) {
        val modes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES) ?: return
        if (modes.contains(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)) {
            setOptionSafe(extender, CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
        }
    }

    private fun applyOisMode(
        extender: Camera2Interop.Extender<*>,
        characteristics: CameraCharacteristics
    ) {
        val modes = characteristics.get(
            CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: return
        // Optical image stabilization (sensor-level). Improves sharpness
        // especially at tele focal lengths and in low light.
        if (modes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            setOptionSafe(extender, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
    }

    private fun applyDistortionCorrection(
        extender: Camera2Interop.Extender<*>,
        characteristics: CameraCharacteristics
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        // Distortion correction (API 28+). Most useful on ultra-wide lenses
        // where uncorrected barrel distortion is severe.
        try {
            val modes = characteristics.get(
                CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) ?: return
            if (modes.contains(CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)) {
                setOptionSafe(extender, CaptureRequest.DISTORTION_CORRECTION_MODE,
                    CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY)
            }
        } catch (_: Throwable) {
            // Key not present on this device/API — safe to ignore.
        }
    }

    /**
     * setCaptureRequestOption is invariant on the builder's type parameter; on a
     * star-projected Extender the type system can't prove the assignment. We cast
     * once here rather than duplicating every call site.
     */
    @Suppress("UNCHECKED_CAST")
    private fun <V> setOptionSafe(
        extender: Camera2Interop.Extender<*>,
        key: CaptureRequest.Key<V>,
        value: V
    ) {
        try {
            (extender as Camera2Interop.Extender<Any>).setCaptureRequestOption(
                key as CaptureRequest.Key<Any>, value as Any)
        } catch (e: Exception) {
            Log.w(TAG, "setCaptureRequestOption(${key.name}) failed", e)
        }
    }

    /**
     * Binds the default front or back camera (fallback when no multi-camera setup).
     */
    fun bindDefaultCamera(
        cameraProvider: ProcessCameraProvider,
        surfaceProvider: Preview.SurfaceProvider,
        imageCapture: ImageCapture,
        isFrontCamera: Boolean,
        flashMode: Int = 0
    ): Camera? {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val rotation = windowManager.defaultDisplay.rotation

            val preview = Preview.Builder()
                .build()
                .apply { setSurfaceProvider(surfaceProvider) }

            val updatedImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(rotation)
                .apply {
                    when (flashMode) {
                        0 -> setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                        1 -> setFlashMode(ImageCapture.FLASH_MODE_ON)
                        else -> setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    }
                }
                .build()

            val selector = if (isFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                updatedImageCapture
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error binding default camera", e)
            null
        }
    }

    /**
     * Checks if concurrent camera streaming (e.g. Primary + Tele simultaneously)
     * is supported on this device.
     */
    fun isConcurrentStreamingSupported(): Boolean {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val concurrentIds = cameraManager.concurrentCameraIds
                concurrentIds.isNotEmpty()
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Returns the CameraCharacteristics for a given physical camera ID.
     */
    fun getCharacteristics(physicalCameraId: String): CameraCharacteristics? {
        return try {
            val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            cameraManager.getCameraCharacteristics(physicalCameraId)
        } catch (e: Exception) {
            Log.e(TAG, "Error getting characteristics for $physicalCameraId", e)
            null
        }
    }
}
