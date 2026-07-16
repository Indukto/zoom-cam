package com.example.zoom

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.ExifInterface
import android.util.Log
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor

/**
 * Handles the shutter tap: reconciles preview-lens vs capture-lens,
 * performs the lens swap if needed, captures the photo, applies crop,
 * and hands off to the post-processing pipeline.
 *
 * The key insight: the preview lens and capture lens are independent.
 * The preview shows a wide context, while capture uses the best lens
 * for the target focal length.
 */
@OptIn(ExperimentalCamera2Interop::class)
class CaptureController(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "CaptureController"
    }

    data class CaptureResult(
        val photoFile: File,
        val captureLensRole: LensRole,
        val cropFactor: Float,
        val outputMegapixels: Float
    )

    /**
     * Captures a photo using the best lens for the target focal length.
     *
     * This is the main entry point called when the user taps the shutter.
     *
     * @param cameraProvider The ProcessCameraProvider
     * @param surfaceProvider The Preview.SurfaceProvider (needed to rebind preview after capture)
     * @param currentImageCapture The currently bound ImageCapture (for same-lens captures)
     * @param catalog The LensCatalog result with all lens profiles
     * @param targetFocalMm The target equivalent focal length
     * @param currentCaptureLens The currently selected capture lens (for hysteresis)
     * @param flashMode Flash mode: 0 = Auto, 1 = On, 2 = Off
     * @param executor The executor for async callbacks
     * @param onResult Callback with the CaptureResult on success
     * @param onError Callback with the exception on failure
     */
    fun capture(
        cameraProvider: ProcessCameraProvider,
        surfaceProvider: Preview.SurfaceProvider,
        currentImageCapture: ImageCapture,
        catalog: LensCatalog.CatalogResult,
        targetFocalMm: Float,
        currentCaptureLens: LensRole,
        flashMode: Int,
        executor: Executor,
        onResult: (CaptureResult) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            val primary = catalog.primary ?: throw IllegalStateException("No primary camera found")
            val tele = catalog.tele
            val ultraWide = catalog.ultraWide

            // Determine which lens to use for capture
            val captureLens = FovMapper.captureLens(
                targetFocalMm = targetFocalMm,
                currentCaptureLens = currentCaptureLens,
                primaryFocalMm = primary.equivFocalMm,
                teleFocalMm = tele?.equivFocalMm ?: Float.MAX_VALUE,
                ultraWideFocalMm = ultraWide?.equivFocalMm ?: 0f
            )

            // Determine the native focal length of the capture lens
            val captureLensProfile = when (captureLens) {
                LensRole.ULTRA_WIDE -> ultraWide
                LensRole.PRIMARY -> primary
                LensRole.TELE -> tele
            }

            val captureLensFocalMm = captureLensProfile?.equivFocalMm ?: primary.equivFocalMm
            val cropFactor = FovMapper.captureCropFactor(captureLensFocalMm, targetFocalMm)
            val outputMp = captureLensProfile?.let {
                FovMapper.outputMegapixels(it.nativeMegapixels, cropFactor)
            } ?: 0f

            // Check if we need to switch lenses
            val previewLens = FovMapper.previewLens(
                targetFocalMm = targetFocalMm,
                primaryFocalMm = primary.equivFocalMm,
                ultraWideFocalMm = ultraWide?.equivFocalMm ?: 0f
            )

            val needsLensSwap = captureLens != previewLens

            if (needsLensSwap) {
                // Lens swap capture: temporarily switch to the capture lens
                captureWithLensSwap(
                    cameraProvider = cameraProvider,
                    surfaceProvider = surfaceProvider,
                    captureLensProfile = captureLensProfile ?: primary,
                    targetFocalMm = targetFocalMm,
                    flashMode = flashMode,
                    executor = executor,
                    onResult = { file ->
                        onResult(
                            CaptureResult(
                                photoFile = file,
                                captureLensRole = captureLens,
                                cropFactor = cropFactor,
                                outputMegapixels = outputMp
                            )
                        )
                    },
                    onError = onError
                )
            } else {
                // Same-lens capture: use the current ImageCapture directly
                captureWithCurrentLens(
                    imageCapture = currentImageCapture,
                    targetFocalMm = targetFocalMm,
                    flashMode = flashMode,
                    executor = executor,
                    onResult = { file ->
                        onResult(
                            CaptureResult(
                                photoFile = file,
                                captureLensRole = captureLens,
                                cropFactor = cropFactor,
                                outputMegapixels = outputMp
                            )
                        )
                    },
                    onError = onError
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error during capture", e)
            onError(e)
        }
    }

    /**
     * Captures using the currently bound lens (no lens swap needed).
     */
    private fun captureWithCurrentLens(
        imageCapture: ImageCapture,
        targetFocalMm: Float,
        flashMode: Int,
        executor: Executor,
        onResult: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val photoFile = createOutputFile(targetFocalMm)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            executor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    onResult(photoFile)
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }

    /**
     * Captures by temporarily switching to a different physical lens.
     * This is used when the capture lens differs from the preview lens
     * (e.g., preview is on Primary, capture needs Tele).
     */
    private fun captureWithLensSwap(
        cameraProvider: ProcessCameraProvider,
        surfaceProvider: Preview.SurfaceProvider,
        captureLensProfile: LensProfile,
        targetFocalMm: Float,
        flashMode: Int,
        executor: Executor,
        onResult: (File) -> Unit,
        onError: (Exception) -> Unit
    ) {
        try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val rotation = windowManager.defaultDisplay.rotation

            val photoFile = createOutputFile(targetFocalMm)

            // Build a temporary ImageCapture for the capture lens
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

            val selector = CameraSelector.Builder()
                .addCameraFilter { cameras ->
                    cameras.filter { camera ->
                        Camera2CameraInfo.from(camera).cameraId == captureLensProfile.physicalCameraId
                    }
                }
                .build()

            val capturePreview = Preview.Builder()
                .build()
                .apply { setSurfaceProvider(surfaceProvider) }

            // Unbind all and bind to the capture lens
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                capturePreview,
                tempImageCapture
            )

            val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

            tempImageCapture.takePicture(
                outputOptions,
                executor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                        onResult(photoFile)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        onError(exception)
                    }
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error during lens-swap capture", e)
            onError(e)
        }
    }

    /**
     * Creates a timestamped output file for the captured photo.
     */
    private fun createOutputFile(targetFocalMm: Float): File {
        val directory = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
            ?: context.cacheDir
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val focalInt = targetFocalMm.toInt()
        return File(directory, "RETRO_IMG_${timeStamp}_${focalInt}mm.jpg")
    }
}