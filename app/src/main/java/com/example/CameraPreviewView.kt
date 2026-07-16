@file:OptIn(ExperimentalCamera2Interop::class)
package com.example

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Environment
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

private fun getBackCameraFocalLengths(context: Context): List<Float> {
    return try {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val lengths = mutableListOf<Float>()
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                if (focalLengths != null) {
                    for (fl in focalLengths) {
                        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        val cropFactor = if (sensorSize != null) {
                            val sensorDiagonal = kotlin.math.sqrt(
                                sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height
                            )
                            (43.27 / sensorDiagonal).toFloat()
                        } else 1.0f
                        lengths.add(fl * cropFactor)
                    }
                }
            }
        }
        lengths.sorted()
    } catch (e: Exception) {
        Log.e("CameraPreviewView", "Error reading camera focal lengths", e)
        listOf(24f, 77f)
    }
}

fun buildCameraSelectorForLens(context: Context, baseFocalLength: Int): CameraSelector {
    return try {
        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        var bestCameraId: String? = null
        var bestDiff = Float.MAX_VALUE
        for (cameraId in cameraManager.cameraIdList) {
            val characteristics = cameraManager.getCameraCharacteristics(cameraId)
            val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
            if (facing == CameraCharacteristics.LENS_FACING_BACK) {
                val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                if (focalLengths != null) {
                    for (fl in focalLengths) {
                        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        val cropFactor = if (sensorSize != null) {
                            val sensorDiagonal = kotlin.math.sqrt(
                                sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height
                            )
                            (43.27 / sensorDiagonal).toFloat()
                        } else 1.0f
                        val equivFocal = fl * cropFactor
                        val diff = kotlin.math.abs(equivFocal - baseFocalLength)
                        if (diff < bestDiff) { bestDiff = diff; bestCameraId = cameraId }
                    }
                }
            }
        }
        if (bestCameraId != null) {
            CameraSelector.Builder().addCameraFilter { cameras ->
                cameras.filter { Camera2CameraInfo.from(it).cameraId == bestCameraId }
            }.build()
        } else CameraSelector.DEFAULT_BACK_CAMERA
    } catch (e: Exception) {
        Log.e("CameraPreviewView", "Error building lens selector", e)
        CameraSelector.DEFAULT_BACK_CAMERA
    }
}

/**
 * Temporarily switches to the target lens, captures one photo at full quality,
 * then rebinds the original wide-angle camera so the preview resumes.
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

    val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.cacheDir
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

    LaunchedEffect(Unit) {
        val lengths = getBackCameraFocalLengths(context)
        onAvailableFocalLengths(lengths)
    }

    LaunchedEffect(isFrontCamera, cameraProviderFuture) {
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            try {
                val cp = cameraProviderFuture.get()
                cameraProviderRef = cp
                val preview = Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) }
                val cs = if (isFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                cp.unbindAll()
                camera = cp.bindToLifecycle(lifecycleOwner, cs, preview, imageCapture)
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
    val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: context.cacheDir
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val photoFile = File(directory, "RETRO_IMG_$timeStamp.jpg")
    val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
    imageCapture.takePicture(outputOptions, executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { onCaptured(photoFile) }
            override fun onError(exception: ImageCaptureException) { onCaptureError(exception) }
        })
}