package com.example.zoom

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.view.WindowManager
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner

/**
 * Manages the live preview session, handling lens transitions.
 *
 * Uses Camera2Interop.Extender.setPhysicalCameraId() to route Preview and
 * ImageCapture use cases to the specific physical camera (ultra-wide, primary,
 * or tele) within the logical multi-camera device.
 */
data class BindResult(
    val camera: Camera,
    val imageCapture: ImageCapture
)

@OptIn(ExperimentalCamera2Interop::class)
class PreviewSessionManager(
    private val context: Context,
    private val lifecycleOwner: LifecycleOwner
) {

    companion object {
        private const val TAG = "PreviewSessionManager"
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
     * @return A BindResult containing the bound Camera and ImageCapture, or null on failure
     */
    fun bindPreview(
        cameraProvider: ProcessCameraProvider,
        logicalCameraId: String,
        physicalCameraId: String,
        surfaceProvider: Preview.SurfaceProvider,
        flashMode: Int = 0
    ): BindResult? {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val rotation = windowManager.defaultDisplay.rotation

            val preview = Preview.Builder()
                .apply {
                    Camera2Interop.Extender(this).setPhysicalCameraId(physicalCameraId)
                }
                .build()
                .apply { setSurfaceProvider(surfaceProvider) }

            val newImageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(rotation)
                .apply {
                    Camera2Interop.Extender(this).setPhysicalCameraId(physicalCameraId)
                    when (flashMode) {
                        0 -> setFlashMode(ImageCapture.FLASH_MODE_AUTO)
                        1 -> setFlashMode(ImageCapture.FLASH_MODE_ON)
                        else -> setFlashMode(ImageCapture.FLASH_MODE_OFF)
                    }
                }
                .build()

            val selector = buildSelectorForLogicalCamera(logicalCameraId)

            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                selector,
                preview,
                newImageCapture
            )
            BindResult(camera, newImageCapture)
        } catch (e: Exception) {
            Log.e(TAG, "Error binding preview to physical=$physicalCameraId logical=$logicalCameraId", e)
            null
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
