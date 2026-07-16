@file:OptIn(ExperimentalCamera2Interop::class)
package com.example

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
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
                    cameras.filter { Camera2CameraInfo.from(it).cameraId == targetLens.physicalCameraId }
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

    val cameraSelector = if (!isFrontCamera && baseFocalLength > 0) {
        buildCameraSelectorForLens(context, baseFocalLength)
    } else {
        if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    }

    val tempImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setTargetRotation(rotation)
        .apply {
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
        val capturePreview = Preview.Builder().build().apply { setSurfaceProvider(surfaceProvider) }
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
    cameraProvider: ProcessCameraProvider,
    lifecycleOwner: LifecycleOwner,
    surfaceProvider: Preview.SurfaceProvider,
    isFrontCamera: Boolean,
    imageCapture: ImageCapture
) {
    val preview = Preview.Builder().build().apply { setSurfaceProvider(surfaceProvider) }
    val cameraSelector = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
    try {
        cameraProvider.unbindAll()
        cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageCapture)
    } catch (e: Exception) {
        Log.e("CameraPreviewView", "Error rebinding default camera", e)
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
    onAvailableFocalLengths: (List<Float>) -> Unit,
    imageCaptureProvider: (ImageCapture) -> Unit,
    onLensCaptureReady: ((LensCaptureHandle) -> Unit)? = null
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
            .build()
    }

    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(imageCapture) { imageCaptureProvider(imageCapture) }

    // Expose the camera provider + surface provider as a LensCaptureHandle
    LaunchedEffect(cameraProviderRef) {
        val cp = cameraProviderRef
        if (cp != null) {
            onLensCaptureReady?.invoke(LensCaptureHandle(cp, previewView.surfaceProvider))
        }
    }

    // Enumerate cameras using LensCatalog for proper physical camera IDs
    LaunchedEffect(Unit) {
        val lengths = getBackCameraFocalLengths(context)
        onAvailableFocalLengths(lengths)
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
                } else {
                    // Use PreviewSessionManager for back camera to enable physical camera targeting
                    // Enumerate to find the primary back camera
                    val catalog = LensCatalog(context)
                    val result = catalog.enumerate()
                    val primaryLens = result.primary
                    if (primaryLens != null) {
                        camera = previewManager.bindPreview(
                            cameraProvider = cp,
                            physicalCameraId = primaryLens.physicalCameraId,
                            surfaceProvider = previewView.surfaceProvider,
                            imageCapture = imageCapture,
                            flashMode = flashMode
                        )
                    } else {
                        val cs = CameraSelector.DEFAULT_BACK_CAMERA
                        cp.unbindAll()
                        camera = cp.bindToLifecycle(lifecycleOwner, cs, preview, imageCapture)
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
        imageCapture.flashMode = when (flashMode) {
            0 -> ImageCapture.FLASH_MODE_AUTO
            1 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize().pointerInput(zoomRatio) {
            detectTransformGestures { _, _, zoomFactor, _ ->
                if (zoomFactor != 1.0f) {
                    onZoomChanged((zoomRatio * zoomFactor).coerceIn(1.0f, 10.0f))
                }
            }
        }
    )
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