@file:OptIn(ExperimentalCamera2Interop::class)
package com.example

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Environment
import android.util.Log
import android.view.Display
import android.view.Surface
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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Queries the device's back-facing cameras and returns their 35mm-equivalent
 * focal lengths sorted ascending.
 */
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
                        // Convert physical focal length to 35mm equivalent.
                        // We approximate by multiplying with a crop factor derived from
                        // the sensor physical size vs 35mm (36x24mm).
                        val sensorSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                        val cropFactor = if (sensorSize != null) {
                            // 35mm diagonal = sqrt(36^2 + 24^2) ≈ 43.27mm
                            val sensorDiagonal = kotlin.math.sqrt(
                                sensorSize.width * sensorSize.width + sensorSize.height * sensorSize.height
                            )
                            (43.27 / sensorDiagonal).toFloat()
                        } else {
                            1.0f
                        }
                        val equivFocal = fl * cropFactor
                        lengths.add(equivFocal)
                    }
                }
            }
        }
        lengths.sorted()
    } catch (e: Exception) {
        Log.e("CameraPreviewView", "Error reading camera focal lengths", e)
        // Fallback to common values if we can't read them
        listOf(24f, 77f)
    }
}

/**
 * Builds a CameraSelector that targets the back camera whose focal length
 * best matches the given baseFocalLength. If no exact match is found,
 * it falls back to the default back camera.
 */
private fun buildLensSelector(context: Context, baseFocalLength: Int): CameraSelector {
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
                        } else {
                            1.0f
                        }
                        val equivFocal = fl * cropFactor
                        val diff = kotlin.math.abs(equivFocal - baseFocalLength)
                        if (diff < bestDiff) {
                            bestDiff = diff
                            bestCameraId = cameraId
                        }
                    }
                }
            }
        }

        if (bestCameraId != null) {
            CameraSelector.Builder().addCameraFilter { cameras ->
                cameras.filter { Camera2CameraInfo.from(it).cameraId == bestCameraId }
            }.build()
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    } catch (e: Exception) {
        Log.e("CameraPreviewView", "Error building lens selector", e)
        CameraSelector.DEFAULT_BACK_CAMERA
    }
}

@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    zoomRatio: Float,
    baseFocalLength: Int,
    exposure: Float,
    flashMode: Int,
    isFrontCamera: Boolean,
    onZoomChanged: (Float) -> Unit,
    onAvailableFocalLengths: (List<Float>) -> Unit,
    imageCaptureProvider: (ImageCapture) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Initialize CameraX processes
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }
    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }

    val imageCapture = remember {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val rotation = windowManager.defaultDisplay.rotation
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()
    }

    // Capture state references
    var camera by remember { mutableStateOf<Camera?>(null) }

    // Keep parent informed of our active ImageCapture instance
    LaunchedEffect(imageCapture) {
        imageCaptureProvider(imageCapture)
    }

    // Read available back-camera focal lengths once and report to ViewModel
    LaunchedEffect(Unit) {
        val lengths = getBackCameraFocalLengths(context)
        onAvailableFocalLengths(lengths)
    }

    // Apply CameraX lifecycle state updates
    // Re-bind when isFrontCamera or baseFocalLength changes
    LaunchedEffect(isFrontCamera, baseFocalLength, cameraProviderFuture) {
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }

                val cameraSelector = if (isFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    // Select the physical lens closest to our base focal length
                    buildLensSelector(context, baseFocalLength)
                }

                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCapture
                )
            } catch (e: Exception) {
                Log.e("CameraPreviewView", "Failed to bind camera lifecycle", e)
            }
        }, executor)
    }

    // Apply the digital zoom ratio to the camera preview
    LaunchedEffect(camera, zoomRatio) {
        val activeCamera = camera ?: return@LaunchedEffect
        try {
            activeCamera.cameraControl.setZoomRatio(zoomRatio)
        } catch (e: Exception) {
            Log.e("CameraPreviewView", "Error setting zoom ratio", e)
        }
    }

    // Dynamically apply Exposure Compensation index
    LaunchedEffect(exposure, camera) {
        val activeCamera = camera ?: return@LaunchedEffect
        val exposureState = activeCamera.cameraInfo.exposureState
        if (exposureState.isExposureCompensationSupported) {
            val min = exposureState.exposureCompensationRange.lower
            val max = exposureState.exposureCompensationRange.upper
            
            // Map exposure range (-3 to +3) to index bounds
            val ratio = (exposure + 3f) / 6f
            val index = (min + ratio * (max - min)).toInt().coerceIn(min, max)
            try {
                activeCamera.cameraControl.setExposureCompensationIndex(index)
            } catch (e: Exception) {
                Log.e("CameraPreviewView", "Error adjusting exposure index", e)
            }
        }
    }

    // Dynamically apply Torch (for flashMode = 1 continuous light)
    LaunchedEffect(flashMode, camera) {
        val activeCamera = camera ?: return@LaunchedEffect
        try {
            // flashMode 1 is "On" (torch enabled), others are auto or off (torch disabled)
            activeCamera.cameraControl.enableTorch(flashMode == 1)
        } catch (e: Exception) {
            Log.e("CameraPreviewView", "Error setting torch mode", e)
        }
        
        // Also map to ImageCapture flash behavior
        imageCapture.flashMode = when (flashMode) {
            0 -> ImageCapture.FLASH_MODE_AUTO
            1 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    // AndroidView wrapper for PreviewView with pinch-to-zoom gestures
    AndroidView(
        factory = { previewView },
        modifier = modifier
            .fillMaxSize()
            .pointerInput(zoomRatio) {
                detectTransformGestures { _, _, zoomFactor, _ ->
                    if (zoomFactor != 1.0f) {
                        val currentZoom = zoomRatio
                        val updatedZoom = (currentZoom * zoomFactor).coerceIn(1.0f, 10.0f)
                        onZoomChanged(updatedZoom)
                    }
                }
            }
    )
}

// Global Capture Trigger implementation
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

    imageCapture.takePicture(
        outputOptions,
        executor,
        object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                onCaptured(photoFile)
            }

            override fun onError(exception: ImageCaptureException) {
                onCaptureError(exception)
            }
        }
    )
}
