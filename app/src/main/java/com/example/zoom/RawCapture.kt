package com.example.zoom

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Captures a single RAW bayer frame and writes it to a DNG file.
 *
 * Replaces the legacy (dead) `captureWithCamera2()` JPEG path with a real
 * RAW pipeline:
 *   1. Open the logical camera and configure a RAW_SENSOR ImageReader.
 *   2. For multi-camera devices, target the requested physical lens via
 *      `OutputConfiguration.setPhysicalCameraId` (API 28+).
 *   3. Run a precapture 3A convergence so exposure/AF have settled.
 *   4. Submit a single TEMPLATE_STILL_CAPTURE RAW capture.
 *   5. Pair the returned Image + TotalCaptureResult and emit a DNG via
 *      [DngCreator], oriented to the device rotation.
 *
 * The result is a `.dng` saved to `Pictures/` in external files dir, mirroring
 * the storage convention of the JPEG pipeline. The JPEG post-processing
 * (retro filter, crop, gallery insert) is NOT applied to DNGs — RAW is a
 * parallel "Pro" output.
 */
object RawCapture {

    private const val TAG = "RawCapture"

    /**
     * @param context Activity/application context
     * @param logicalCameraId The logical Camera2 id (e.g. "0")
     * @param physicalCameraId The target physical lens id (may equal logical
     *        if the device has no logical multi-camera)
     * @param focalLengthMm Native focal length, used only to label the filename
     * @param flashMode 0 = auto, 1 = on, 2 = off
     * @param onCaptured Invoked with the written .dng file
     * @param onError Invoked on any failure (capability, open, session, capture)
     */
    @SuppressLint("MissingPermission")
    fun captureDng(
        context: Context,
        logicalCameraId: String,
        physicalCameraId: String,
        focalLengthMm: Int,
        flashMode: Int,
        onCaptured: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: context.cacheDir
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val photoFile = File(directory, "RETRO_RAW_${timeStamp}_${focalLengthMm}mm.dng")

        val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val cameraThread = HandlerThread("RawCapture").apply { start() }
        val cameraHandler = Handler(cameraThread.looper)

        var imageReader: ImageReader? = null
        var camera: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val finished = java.util.concurrent.atomic.AtomicBoolean(false)

        fun cleanup() {
            try { imageReader?.close() } catch (_: Exception) {}
            try { camera?.close() } catch (_: Exception) {}
            cameraThread.quitSafely()
        }

        fun failOnce(error: Exception) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                onError(error)
            }
        }

        fun succeedOnce(file: File) {
            if (finished.compareAndSet(false, true)) {
                cleanup()
                onCaptured(file)
            }
        }

        try {
            if (!isRawSupported(cameraManager, logicalCameraId, physicalCameraId)) {
                failOnce(IllegalStateException("RAW not supported on this lens"))
                return
            }

            val characteristics = cameraManager.getCameraCharacteristics(logicalCameraId)
            val physicalChars = try {
                cameraManager.getCameraCharacteristics(physicalCameraId)
            } catch (_: Exception) { characteristics }

            val configMap = physicalChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = configMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val size = rawSizes?.maxByOrNull { it.width * it.height }
            if (size == null) {
                failOnce(IllegalStateException("No RAW_SENSOR output size"))
                return
            }

            imageReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2)

            val sensorOrientation = physicalChars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
            val deviceRotation = when (
                (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.rotation
            ) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
            // DNG orientation: rotate sensor image into device-natural, then by display rotation.
            val dngRotation = (sensorOrientation + deviceRotation + 360) % 360

            // Pair the Image and TotalCaptureResult before writing the DNG.
            // Either may arrive first; both are required by DngCreator.
            var pendingImage: Image? = null
            var pendingResult: TotalCaptureResult? = null

            fun tryWriteDng() {
                val img = pendingImage ?: return
                val res = pendingResult ?: return
                try {
                    val dng = DngCreator(characteristics, res)
                    dng.setDescription("ZoomBox Camera RAW capture")
                    dng.setOrientation(dngRotation)
                    FileOutputStream(photoFile).use { out ->
                        dng.writeImage(out, img)
                    }
                    dng.close()
                    img.close()
                    succeedOnce(photoFile)
                } catch (e: Exception) {
                    Log.e(TAG, "DNG write failed", e)
                    try { img.close() } catch (_: Exception) {}
                    failOnce(e)
                }
            }

            imageReader.setOnImageAvailableListener({ reader ->
                try {
                    val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    pendingImage = image
                    tryWriteDng()
                } catch (e: Exception) {
                    Log.e(TAG, "Error reading RAW image", e)
                    failOnce(e)
                }
            }, cameraHandler)

            cameraManager.openCamera(logicalCameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    camera = device
                    try {
                        val sessionCallback = object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                runPrecaptureThenStill(
                                    session = s,
                                    camera = device,
                                    characteristics = characteristics,
                                    readerSurface = imageReader!!.surface,
                                    flashMode = flashMode,
                                    handler = cameraHandler,
                                    onStillResult = { result ->
                                        pendingResult = result
                                        tryWriteDng()
                                    },
                                    onFailure = { reason ->
                                        failOnce(RuntimeException("RAW capture failed: $reason"))
                                    }
                                )
                            }

                            override fun onConfigureFailed(s: CameraCaptureSession) {
                                failOnce(RuntimeException("RAW session configure failed"))
                            }
                        }

                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                            logicalCameraId != physicalCameraId
                        ) {
                            val outputConfig = OutputConfiguration(imageReader!!.surface)
                            outputConfig.setPhysicalCameraId(physicalCameraId)
                            val executor = java.util.concurrent.Executor { cmd -> cameraHandler.post(cmd) }
                            val sessionConfig = SessionConfiguration(
                                SessionConfiguration.SESSION_REGULAR,
                                listOf(outputConfig),
                                executor,
                                sessionCallback
                            )
                            device.createCaptureSession(sessionConfig)
                        } else {
                            device.createCaptureSession(
                                listOf(imageReader!!.surface), sessionCallback, cameraHandler)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "RAW session setup failed", e)
                        failOnce(e)
                    }
                }

                override fun onDisconnected(d: CameraDevice) {
                    failOnce(RuntimeException("Camera disconnected"))
                }

                override fun onError(d: CameraDevice, error: Int) {
                    failOnce(RuntimeException("Camera open error: $error"))
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "RAW capture threw", e)
            failOnce(e)
        }
    }

    /**
     * Reports whether the lens can actually emit RAW frames. Capability check
     * is the cheap path; the real gate is whether a RAW_SENSOR output size
     * exists for the *physical* camera when the device is a logical multi-cam.
     */
    private fun isRawSupported(
        cameraManager: CameraManager,
        logicalCameraId: String,
        physicalCameraId: String
    ): Boolean {
        return try {
            val chars = cameraManager.getCameraCharacteristics(physicalCameraId)
            val capabilities = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?: return false
            if (capabilities.none { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW }) {
                // Physical sub-camera may not list RAW even though logical does;
                // fall back to the logical capabilities before giving up.
                val logicalCaps = cameraManager.getCameraCharacteristics(logicalCameraId)
                    .get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: return false
                if (logicalCaps.none { it == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW }) {
                    return false
                }
            }
            val configMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?: return false
            val sizes = configMap.getOutputSizes(ImageFormat.RAW_SENSOR)
            !sizes.isNullOrEmpty()
        } catch (e: Exception) {
            Log.w(TAG, "isRawSupported probe failed", e)
            false
        }
    }

    /**
     * Runs a precapture 3A convergence sequence, then submits the actual
     * TEMPLATE_STILL_CAPTURE request. We hold AE/AF in AUTO and trigger a
     * precapture metering pass, waiting until CONTROL_AE_STATE becomes
     * CONVERGED or FLASH_REQUIRED before firing the still. This mirrors what
     * a JPEG TEMPLATE_STILL_CAPTURE would normally do internally.
     */
    private fun runPrecaptureThenStill(
        session: CameraCaptureSession,
        camera: CameraDevice,
        characteristics: CameraCharacteristics,
        readerSurface: Surface,
        flashMode: Int,
        handler: Handler,
        onStillResult: (TotalCaptureResult) -> Unit,
        onFailure: (Int) -> Unit
    ) {
        val aeAvailable = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        val canAePrecapture = aeAvailable?.contains(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START) == true

        // Apply the requested flash policy to both the precapture repeating and
        // the final still.
        fun applyAeFlash(req: CaptureRequest.Builder) {
            when (flashMode) {
                0 -> req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
                1 -> req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH)
                else -> {
                    req.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                    req.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }
        }

        fun buildStill(): CaptureRequest {
            val req = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            req.addTarget(readerSurface)
            req.set(CaptureRequest.CONTROL_AE_LOCK, false)
            req.set(CaptureRequest.CONTROL_AWB_LOCK, false)
            applyAeFlash(req)
            // Apply the same HQ ISP keys we use for JPEG stills when supported.
            applyRawQualityKeys(req, characteristics)
            return req.build()
        }

        fun captureStill() {
            try {
                session.stopRepeating()
                session.capture(buildStill(), object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureCompleted(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        result: TotalCaptureResult
                    ) {
                        onStillResult(result)
                    }

                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest, failure: CaptureFailure
                    ) {
                        onFailure(failure.reason)
                    }
                }, handler)
            } catch (e: Exception) {
                Log.e(TAG, "Still capture failed", e)
                onFailure(-1)
            }
        }

        if (!canAePrecapture) {
            captureStill()
            return
        }

        // Repeating preview-ish request so AE can converge before precapture.
        val repeating = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        repeating.addTarget(readerSurface)
        applyAeFlash(repeating)
        repeating.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE)
        session.setRepeatingRequest(repeating.build(), object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult
            ) {
                // Trigger precapture once AE reaches a known state.
                triggerPrecapture(session, camera, handler) { converged ->
                    if (converged) captureStill() else onFailure(-2)
                }
            }
        }, handler)
    }

    /**
     * Fire the AE precapture trigger and wait for CONVERGED/FLASH_REQUIRED.
     * Resolves [onDone] exactly once.
     */
    private fun triggerPrecapture(
        session: CameraCaptureSession,
        camera: CameraDevice,
        handler: Handler,
        onDone: (Boolean) -> Unit
    ) {
        val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
        fun done(ok: Boolean) { if (resolved.compareAndSet(false, true)) onDone(ok) }

        val trigger = camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        // We don't have a preview surface here; reuse the reader surface.
        // Some HALs reject a precapture with no preview target, so guard it.
        try {
            trigger.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
            session.capture(trigger.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    when (aeState) {
                        CaptureResult.CONTROL_AE_STATE_CONVERGED,
                        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> done(true)
                        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> {
                            // wait for next callback
                        }
                        else -> {
                            // Keep waiting; HAL will report CONVERGED eventually.
                        }
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest, failure: CaptureFailure
                ) { done(false) }
            }, handler)

            // Bounded wait: if convergence doesn't arrive in 2.5s, proceed anyway.
            handler.postDelayed({ done(true) }, 2500)
        } catch (e: Exception) {
            Log.w(TAG, "precapture trigger failed; firing still directly", e)
            done(true)
        }
    }

    /**
     * Best-effort: push HIGH_QUALITY noise/edge/hot-pixel keys onto the still
     * request when the RAW pipeline can use them. RAW bypasses most ISP steps
     * by design, but the keys are harmless on HALs that ignore them for RAW.
     */
    private fun applyRawQualityKeys(
        request: CaptureRequest.Builder,
        characteristics: CameraCharacteristics
    ) {
        try {
            val hpModes = characteristics.get(CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES)
            if (hpModes != null && hpModes.contains(CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)) {
                request.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_HIGH_QUALITY)
            }
            val oisModes = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            if (oisModes != null && oisModes.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
                request.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                    CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
            }
        } catch (_: Exception) {
            // Best-effort; ignore.
        }
    }

    @Suppress("unused")
    private fun ByteBuffer.toBytes(): ByteArray {
        val out = ByteArray(remaining())
        get(out)
        return out
    }
}
