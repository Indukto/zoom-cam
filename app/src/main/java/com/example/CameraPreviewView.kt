package com.example

import android.content.Context
import android.os.Environment
import android.util.Log
import android.view.ViewGroup
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
import androidx.compose.runtime.remember
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

@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    zoomRatio: Float,
    exposure: Float,
    flashMode: Int,
    isFrontCamera: Boolean,
    onZoomChanged: (Float) -> Unit,
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
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
    }

    // Capture state references
    var camera: Camera? = remember { null }

    // Keep parent informed of our active ImageCapture instance
    LaunchedEffect(imageCapture) {
        imageCaptureProvider(imageCapture)
    }

    // Apply CameraX lifecycle state updates
    LaunchedEffect(isFrontCamera, cameraProviderFuture) {
        val cameraProvider = cameraProviderFuture.get()
        val preview = Preview.Builder().build().apply {
            setSurfaceProvider(previewView.surfaceProvider)
        }

        val cameraSelector = if (isFrontCamera) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
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
    }

    // Dynamically apply Zoom controls
    LaunchedEffect(zoomRatio, camera) {
        val activeCamera = camera ?: return@LaunchedEffect
        try {
            activeCamera.cameraControl.setZoomRatio(zoomRatio)
        } catch (e: Exception) {
            Log.e("CameraPreviewView", "Error adjusting zoom", e)
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
