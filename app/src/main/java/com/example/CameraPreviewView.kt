package com.example

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import android.view.WindowManager
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
import androidx.compose.ui.input.pointer.PointerEventPass
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
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

fun captureWithCamera2(
    context: Context,
    targetLogicalId: String,
    targetPhysicalId: String,
    targetFocalLength: Int,
    flashMode: Int,
    onCaptured: (File) -> Unit,
    onError: (Exception) -> Unit
) {
    val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES) ?: context.cacheDir
    val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
    val photoFile = File(directory, "RETRO_IMG_${timeStamp}_${targetFocalLength}mm.jpg")

    val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    val cameraThread = HandlerThread("Camera2Capture").apply { start() }
    val cameraHandler = Handler(cameraThread.looper)

    fun cleanup(reader: ImageReader? = null, device: CameraDevice? = null) {
        try { reader?.close() } catch (_: Exception) {}
        try { device?.close() } catch (_: Exception) {}
        cameraThread.quitSafely()
    }

    try {
        val characteristics = cameraManager.getCameraCharacteristics(targetLogicalId)

        val physicalChars = try {
            cameraManager.getCameraCharacteristics(targetPhysicalId)
        } catch (_: Exception) { characteristics }
        val sensorOrientation = physicalChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

        val configMap = physicalChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val outputSizes = configMap?.getOutputSizes(ImageFormat.JPEG)
        val size = outputSizes?.maxByOrNull { it.width * it.height }
        if (size == null) { cleanup(); onError(RuntimeException("No JPEG output size")); return }

        val imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)

        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val deviceRotation = when (windowManager.defaultDisplay.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
        val jpegOrientation = (sensorOrientation + deviceRotation) % 360

        cameraManager.openCamera(targetLogicalId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                try {
                    val sessionCallback = object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                            requestBuilder.addTarget(imageReader.surface)
                            requestBuilder.set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                            requestBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation)
                            when (flashMode) {
                                0 -> requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                                1 -> requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                                else -> {
                                    requestBuilder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                                    requestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                                }
                            }
                            val request = requestBuilder.build()

                            imageReader.setOnImageAvailableListener({ reader ->
                                try {
                                    val image = reader.acquireLatestImage()
                                    if (image != null) {
                                        val buffer = image.planes[0].buffer
                                        val bytes = ByteArray(buffer.remaining())
                                        buffer.get(bytes)
                                        FileOutputStream(photoFile).use { it.write(bytes) }
                                        image.close()
                                    }
                                } catch (e: Exception) {
                                    Log.e("CameraPreviewView", "Error reading Camera2 image", e)
                                } finally {
                                    cleanup(imageReader, camera)
                                    onCaptured(photoFile)
                                }
                            }, cameraHandler)

                            session.capture(request, object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                                    Log.e("CameraPreviewView", "Camera2 capture failed: ${failure.reason}")
                                    cleanup(imageReader, camera)
                                    onError(RuntimeException("Capture failed"))
                                }
                            }, cameraHandler)
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            Log.e("CameraPreviewView", "Camera2 session configure failed")
                            cleanup(imageReader, camera)
                            onError(RuntimeException("Session configure failed"))
                        }
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val outputConfig = OutputConfiguration(imageReader.surface)
                        outputConfig.setPhysicalCameraId(targetPhysicalId)
                        val executor = java.util.concurrent.Executor { command -> cameraHandler.post(command) }
                        val sessionConfig = SessionConfiguration(SessionConfiguration.SESSION_REGULAR, listOf(outputConfig), executor, sessionCallback)
                        camera.createCaptureSession(sessionConfig)
                    } else {
                        camera.createCaptureSession(listOf(imageReader.surface), sessionCallback, cameraHandler)
                    }
                } catch (e: Exception) {
                    Log.e("CameraPreviewView", "Error creating Camera2 session", e)
                    cleanup(imageReader, camera)
                    onError(e)
                }
            }

            override fun onDisconnected(camera: CameraDevice) {
                Log.e("CameraPreviewView", "Camera2 disconnected")
                cleanup(imageReader, camera)
                onError(RuntimeException("Camera disconnected"))
            }

            override fun onError(camera: CameraDevice, error: Int) {
                Log.e("CameraPreviewView", "Camera2 error: $error")
                cleanup(imageReader, camera)
                onError(RuntimeException("Camera error: $error"))
            }
        }, cameraHandler)
    } catch (e: Exception) {
        Log.e("CameraPreviewView", "Error opening Camera2 device", e)
        cleanup()
        onError(e)
    }
}

@Composable
fun CameraPreviewView(
    modifier: Modifier = Modifier,
    selectedLensRole: LensRole = LensRole.PRIMARY,
    digitalZoomRatio: Float = 1.0f,
    exposure: Float,
    flashMode: Int,
    isFrontCamera: Boolean,
    onZoomChanged: (Float) -> Unit,
    onZoomTick: () -> Unit = {},
    onAvailableFocalLengths: (List<Float>) -> Unit,
    imageCaptureProvider: (ImageCapture) -> Unit,
    onLensCatalogReady: ((LensCatalog.CatalogResult) -> Unit)? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    val previewView = remember {
        PreviewView(context).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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

    // Enumerate cameras
    LaunchedEffect(Unit) {
        val catalog = LensCatalog(context)
        val result = catalog.enumerate()
        onAvailableFocalLengths(result.allLenses.map { it.equivFocalMm }.sorted())
        onLensCatalogReady?.invoke(result)
    }

    // Bind camera — rebinds on lens role or front/back change
    LaunchedEffect(selectedLensRole, isFrontCamera) {
        val cp = try { cameraProviderFuture.get() } catch (e: Exception) { null } ?: return@LaunchedEffect
        val executor = ContextCompat.getMainExecutor(context)

        try {
            if (isFrontCamera) {
                val cs = CameraSelector.DEFAULT_FRONT_CAMERA
                cp.unbindAll()
                camera = cp.bindToLifecycle(lifecycleOwner, cs,
                    Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) },
                    imageCapture)
                activeImageCapture = imageCapture
            } else {
                val catalog = LensCatalog(context)
                val result = catalog.enumerate()
                val targetProfile = when (selectedLensRole) {
                    LensRole.ULTRA_WIDE -> result.ultraWide
                    LensRole.PRIMARY -> result.primary
                    LensRole.TELE -> result.tele
                }

                val previewManager = PreviewSessionManager(context, lifecycleOwner)
                val bound = if (targetProfile != null) {
                    previewManager.bindPreview(
                        cameraProvider = cp,
                        logicalCameraId = targetProfile.logicalCameraId,
                        physicalCameraId = targetProfile.physicalCameraId,
                        surfaceProvider = previewView.surfaceProvider,
                        flashMode = flashMode
                    )
                } else null

                if (bound == null) {
                    val cs = CameraSelector.DEFAULT_BACK_CAMERA
                    cp.unbindAll()
                    camera = cp.bindToLifecycle(lifecycleOwner, cs,
                        Preview.Builder().build().apply { setSurfaceProvider(previewView.surfaceProvider) },
                        imageCapture)
                    activeImageCapture = imageCapture
                } else {
                    camera = bound.camera
                    activeImageCapture = bound.imageCapture
                }
            }
        } catch (e: Exception) {
            Log.e("CameraPreviewView", "Failed to bind camera lifecycle", e)
        }
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
        camera?.let { c -> try { c.cameraControl.enableTorch(flashMode == 1) } catch (e: Exception) {} }
        activeImageCapture.flashMode = when (flashMode) {
            0 -> ImageCapture.FLASH_MODE_AUTO
            1 -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    // Zoom gesture — seed from current digitalZoomRatio
    val currentDigitalZoom by rememberUpdatedState(digitalZoomRatio)
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)
    val currentOnZoomTick by rememberUpdatedState(onZoomTick)

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize().pointerInput(Unit) {
            val heightPx = size.height.toFloat().coerceAtLeast(1f)
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false)
                // Seed from the current VM value so the gesture doesn't jump
                var runningZoom = currentDigitalZoom
                var lastTick = tickIndexOf(runningZoom)
                do {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val pointers = event.changes.filter { it.pressed }
                    if (pointers.isEmpty()) break

                    val newZoom = if (pointers.size >= 2) {
                        val pinchFactor = event.calculateZoom()
                        if (pinchFactor == 1.0f) null
                        else (runningZoom * pinchFactor).coerceIn(1.0f, 3.0f)
                    } else {
                        val dragPx = -event.calculatePan().y
                        if (dragPx == 0f) null
                        else {
                            val fractionalDrag = dragPx / heightPx
                            (runningZoom * kotlin.math.exp(fractionalDrag / 0.7f)).coerceIn(1.0f, 3.0f)
                        }
                    }

                    if (newZoom != null) {
                        runningZoom = newZoom
                        currentOnZoomChanged(newZoom)
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