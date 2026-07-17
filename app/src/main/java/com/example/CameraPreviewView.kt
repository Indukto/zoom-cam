@file:OptIn(ExperimentalCamera2Interop::class)
package com.example

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.zoom.LensCatalog
import com.example.zoom.LensRole
import com.example.zoom.PreviewSessionManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * A handle containing the ProcessCameraProvider and Preview.SurfaceProvider
 * needed by captureWithBestLens to temporarily switch lenses for capture.
 */
data class LensCaptureHandle(
    val cameraProvider: ProcessCameraProvider,
    val surfaceProvider: Preview.SurfaceProvider
)

/**
 * Uses LensCatalog to enumerate cameras with proper crop-factor computation,
 * matching the architecture spec §5.1 (LensCatalog) and §9 (physical camera targeting).
 */
private fun getBackCameraFocalLengths(context: Context): List<Float> {
    return try {
        val catalog = LensCatalog(context)
        val result = catalog.enumerate()
        result.allLenses.map { it.equivFocalMm }.sorted()
    } catch (e: Exception) {
        Log.e("CameraPreviewView", "Error reading camera focal lengths via LensCatalog", e)
        listOf(24f, 77f)
    }
}

/**
 * Builds a CameraSelector for a specific physical camera, matching the
 * architecture spec §9: uses Camera2Interop.Extender.setPhysicalCameraId().
 */
fun buildCameraSelectorForLens(context: Context, baseFocalLength: Int): CameraSelector {
    return try {
        val catalog = LensCatalog(context)
        val result = catalog.enumerate()

        // Find the lens whose equivFocalMm is closest to baseFocalLength
        val targetLens = result.allLenses.minByOrNull {
            kotlin.math.abs(it.equivFocalMm - baseFocalLength)
        }

        if (targetLens != null) {
            CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { Camera2CameraInfo.from(it).cameraId == targetLens.logicalCameraId }
                }
                .build()
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    } catch (e: Exception) {
        Log.e("CameraPreviewView", "Error building lens selector", e)
        CameraSelector.DEFAULT_BACK_CAMERA
    }
}

/**
 * Temporarily switches to the target lens, captures one photo at full quality,
 * then rebinds the original wide-angle camera so the preview resumes.
 *
 * Updated to use LensCatalog for proper physical camera targeting.
 */
fun captureWithBestLens(
    context: Context,
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    baseFocalLength: Int,
    targetFocalLength: Int,
    flashMode: Int,
    surfaceProvider: Preview.SurfaceProvider,
    isFrontCamera: Boolean,
    executor: Executor,
    rebindPreview: () -> Unit,
    onCaptured: (File) -> Unit,
    onCaptureError: (ImageCaptureException) -> Unit
) {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val rotation = windowManager.defaultDisplay.rotation

    val cameraSelector = if (!isFrontCamera && targetFocalLength > 0) {
        buildCameraSelectorForLens(context, targetFocalLength)
    } else {
        if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    // Find the target physical lens ID if we're doing a lens-switched capture
    var targetPhysicalId: String? = null
    if (!isFrontCamera && targetFocalLength > 0) {
        val catalog = LensCatalog(context)
        val result = catalog.enumerate()
        val targetLens = result.allLenses.minByOrNull {
            kotlin.math.abs(it.equivFocalMm - targetFocalLength)
        }
        targetPhysicalId = targetLens?.physicalCameraId
    }

    val tempImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setTargetRotation(rotation)
        .apply {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                targetPhysicalId?.let { Camera2Interop.Extender(this).setPhysicalCameraId(it) }
            }
            when (flashMode) {
                0 -> setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                1 -> setFlashMode(ImageCapture.FLASH_MODE_ON)
                else -> setFlashMode(ImageCapture.FLASH_MODE_OFF)
            }
        }
        .build()

    val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.cacheDir
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val photoFile = File(directory, "RETRO_IMG_${timeStamp}_${targetFocalLength}mm.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

    try {
        cameraProvider.unbindAll()
        val previewBuilder = Preview.Builder()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            targetPhysicalId?.let { Camera2Interop.Extender(previewBuilder).setPhysicalCameraId(it) }
        }
        val capturePreview = previewBuilder.build().apply { setSurfaceProvider(surfaceProvider) }
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, capturePreview, tempImageCapture)

        tempImageCapture.takePicture(outputOptions, executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    rebindPreview()
                    onCaptured(photoFile)
                }
                override fun onError(exception: ImageCaptureException) {
                    rebindPreview()
                    onCaptureError(exception)
                }
            })
    } catch (e: Exception) {
        Log.e("CameraPreviewView", "Error capturing with best lens", e)
        rebindPreview()
    }
}

/**
 * Rebinds the camera with the existing ImageCapture and Preview.
 * This is used to resume the wide-angle preview after a lens-switched capture.
 */
fun rebindDefaultCamera(
    context: Context,
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    surfaceProvider: Preview.SurfaceProvider,
    isFrontCamera: Boolean,
    imageCapture: ImageCapture,
    flashMode: Int = 0,
    onActiveImageCaptureChanged: (ImageCapture) -> Unit = {}
) {
    if (isFrontCamera) {
        val preview = Preview.Builder().build().apply { setSurfaceProvider(surfaceProvider) }
        val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
            onActiveImageCaptureChanged(imageCapture)
        } catch (e: Exception) {
            Log.e("CameraPreviewView", "Error rebinding default camera", e)
        }
    } else {
        val previewManager = PreviewSessionManager(context, lifecycleOwner)
        val catalog = LensCatalog(context)
        val result = catalog.enumerate()
        val primaryLens = result.primary
        val bound = if (primaryLens != null) {
            previewManager.bindPreview(
                cameraProvider = cameraProvider,
                physicalCameraId = primaryLens.physicalCameraId,
                surfaceProvider = surfaceProvider,
                flashMode = flashMode
            )
        } else null
        if (bound == null) {
            val preview = Preview.Builder().build().apply { setSurfaceProvider(surfaceProvider) }
            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
                onActiveImageCaptureChanged(imageCapture)
            } catch (e: Exception) {
                Log.e("CameraPreviewView", "Error rebinding default camera", e)
            }
        } else {
            onActiveImageCaptureChanged(bound.imageCapture)
        }
    }
}

@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    zoomRatio: Float,
    exposure: Float,
    flashMode: Int,
    isFrontCamera: Boolean,
    onZoomChanged: (Float) -> Unit,
    onZoomTick: () -> Unit = {},
    onAvailableFocalLengths: (List<Float>) -> Unit,
    imageCaptureProvider: (ImageCapture) -> Unit,
    onLensCaptureReady: ((LensCaptureHandle) -> Unit)? = null,
    onLensCatalogReady: ((LensCatalog.CatalogResult) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val imageCapture = remember {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(windowManager.defaultDisplay.rotation)
            .setFlashMode(ImageCapture.FLASH_MODE_AUTO)
            .build()
    }

    var camera by remember { mutableStateOf<Camera?>(null) }
    var activeImageCapture by remember { mutableStateOf(imageCapture) }

    LaunchedEffect(activeImageCapture) { imageCaptureProvider(activeImageCapture) }

    // Expose the camera provider + surface provider as a LensCaptureHandle
    LaunchedEffect(cameraProviderRef) {
        val cp = cameraProviderRef
        if (cp != null) {
            onLensCaptureReady?.invoke(LensCaptureHandle(cp, previewView.surfaceProvider))
        }
    }

    // Enumerate cameras using LensCatalog for proper physical camera IDs
    LaunchedEffect(Unit) {
        val catalog = LensCatalog(context)
        val result = catalog.enumerate()
        onAvailableFocalLengths(result.allLenses.map { it.equivFocalMm }.sorted())
        onLensCatalogReady?.invoke(result)
    }

    // Bind camera with physical camera targeting support
    LaunchedEffect(isFrontCamera, cameraProviderFuture) {
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            try {
                val cp = cameraProviderFuture.get()
                cameraProviderRef = cp

                val previewManager = PreviewSessionManager(context, lifecycleOwner)
                val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }

                if (isFrontCamera) {
                    val cs = CameraSelector.DEFAULT_FRONT_CAMERA
                    cp.unbindAll()
                    camera = cp.bindToLifecycle(lifecycleOwner, cs, preview, imageCapture)
                    activeImageCapture = imageCapture
                } else {
                    // For the back camera, target the Primary physical lens directly so that
                    // capture-time lens swaps (to Tele/UW) hit the right physical sub-camera.
                    // Fall back to the default back camera if physical targeting isn't available.
                    val catalog = LensCatalog(context)
                    val result = catalog.enumerate()
                    val primaryLens = result.primary
                    val bound = if (primaryLens != null) {
                        previewManager.bindPreview(
                            cameraProvider = cp,
                            physicalCameraId = primaryLens.physicalCameraId,
                            surfaceProvider = previewView.surfaceProvider,
                            flashMode = flashMode
                        )
                    } else null
                    if (bound == null) {
                        val cs = CameraSelector.DEFAULT_BACK_CAMERA
                        cp.unbindAll()
                        camera = cp.bindToLifecycle(lifecycleOwner, cs, preview, imageCapture)
                        activeImageCapture = imageCapture
                    } else {
                        camera = bound.camera
                        activeImageCapture = bound.imageCapture
                    }
                }
            } catch (e: Exception) {
                Log.e("CameraPreviewView", "Failed to bind camera lifecycle", e)
            }
        }, executor)
    }

    // Keep preview at 1.0x zoom
    LaunchedEffect(camera) {
        camera?.let { c ->
            try { c.cameraControl.setZoomRatio(1.0f) }
            catch (e: Exception) { Log.e("CameraPreviewView", "Error resetting zoom", e) }
        }
    }

    LaunchedEffect(exposure, camera) {
        camera?.let { c ->
            val es = c.cameraInfo.exposureState
            if (es.isExposureCompensationSupported) {
                val min = es.exposureCompensationRange.lower
                val max = es.exposureCompensationRange.upper
                val ratio = (exposure + 3f) / 6f
                val index = (min + ratio * (max - min)).toInt().coerceIn(min, max)
                try { c.cameraControl.setExposureCompensationIndex(index) }
                catch (e: Exception) { Log.e("CameraPreviewView", "Error adjusting exposure", e) }
            }
        }
    }

    LaunchedEffect(flashMode, camera) {
        camera?.let { c ->
            try { c.cameraControl.enableTorch(flashMode == 1) } catch (e: Exception) {}
        }
        activeImageCapture.flashMode = when (flashMode) {
            0 -> ImageCapture.FLASH_MODE_AUTO
            1 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    // Zoom gesture handling. Supports two input modes:
    //  - Pinch (multi-touch): relative zoom, unchanged from before.
    //  - Vertical drag (single touch): swipe up = zoom in, swipe down = zoom out.
    //    Uses a low-sensitivity exponential mapping so the whole 1x-10x range is reachable
    //    without a huge swipe, but fine control near 1x stays smooth.
    //
    // `pointerInput(Unit)` + rememberUpdatedState keeps the gesture alive across zoom updates
    // (the old `pointerInput(zoomRatio)` restarted on every tick, interrupting mid-drag).
    val currentZoomRatio by rememberUpdatedState(zoomRatio)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val currentOnZoomTick by rememberUpdatedState(onZoomTick)

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize().pointerInput(Unit) {
            val heightPx = size.height.toFloat().coerceAtLeast(1f)
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                // Seed the gesture-local zoom from the latest VM value so per-event
                // accumulation doesn't suffer from state round-trip lag.
                var runningZoom = currentZoomRatio
                var lastTick = tickIndexOf(runningZoom)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pointers = event.changes.filter { it.pressed }
                    if (pointers.isEmpty()) break

                    val newZoom = if (pointers.size >= 2) {
                        // Multi-touch pinch
                        val pinchFactor = event.calculateZoom()
                        if (pinchFactor == 1.0f) null
                        else (runningZoom * pinchFactor).coerceIn(1.0f, 10.0f)
                    } else {
                        // Single-touch vertical drag → zoom (up = zoom in).
                        val dragPx = -event.calculatePan().y
                        if (dragPx == 0f) null
                        else {
                            val fractionalDrag = dragPx / heightPx
                            // Low sensitivity: ~0.7 of the screen height is one e-fold of zoom.
                            (runningZoom * kotlin.math.exp(fractionalDrag / 0.7f))
                                .coerceIn(1.0f, 10.0f)
                        }
                    }

                    if (newZoom != null) {
                        runningZoom = newZoom
                        currentOnZoomChanged(newZoom)
                        // Haptic "notch" in log-space: one tick each time zoom crosses ~8%.
                        val tick = tickIndexOf(newZoom)
                        if (tick != lastTick) {
                            lastTick = tick
                            currentOnZoomTick()
                        }
                    }
                } while (event.changes.any { it.pressed })
            }
        }
    )
}

/**
 * Groups zoom values into discrete haptic notches in log-space.
 * One notch per ~8% relative change → ~28 ticks across the 1x-10x range.
 */
private fun tickIndexOf(zoom: Float): Int {
    if (zoom <= 0f) return 0
    return (kotlin.math.ln(zoom) / kotlin.math.ln(1.08f)).toInt()
}

fun triggerImageCapture(
    context: Context,
    imageCapture: ImageCapture,
    executor: Executor,
    onCaptured: (File) -> Unit,
    onCaptureError: (ImageCaptureException) -> Unit
) {
    val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.cacheDir
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val photoFile = File(directory, "RETRO_IMG_$timeStamp.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
    imageCapture.takePicture(outputOptions, executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { onCaptured(photoFile) }
            override fun onError(exception: ImageCaptureException) { onCaptureError(exception) }
        })
}