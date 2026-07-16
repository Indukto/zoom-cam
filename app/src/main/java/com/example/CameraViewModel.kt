package com.example

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaActionSound
import android.os.Environment
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class CameraViewModel : ViewModel() {

    private val _zoomRatio = MutableStateFlow(1.0f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _focalLength = MutableStateFlow(35) // 35mm, 50mm, 85mm
    val focalLength: StateFlow<Int> = _focalLength.asStateFlow()

    private val _exposure = MutableStateFlow(0f) // -3f to +3f
    val exposure: StateFlow<Float> = _exposure.asStateFlow()

    private val _temperature = MutableStateFlow(0f) // -2f to +2f (Warm/Cool)
    val temperature: StateFlow<Float> = _temperature.asStateFlow()

    private val _flashMode = MutableStateFlow(0) // 0 = Auto, 1 = On, 2 = Off
    val flashMode: StateFlow<Int> = _flashMode.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _capturedPhotos = MutableStateFlow<List<File>>(emptyList())
    val capturedPhotos: StateFlow<List<File>> = _capturedPhotos.asStateFlow()

    private val _selectedPhoto = MutableStateFlow<File?>(null)
    val selectedPhoto: StateFlow<File?> = _selectedPhoto.asStateFlow()

    private val _showTemperatureSlider = MutableStateFlow(false)
    val showTemperatureSlider: StateFlow<Boolean> = _showTemperatureSlider.asStateFlow()

    private val _showExposureSlider = MutableStateFlow(false)
    val showExposureSlider: StateFlow<Boolean> = _showExposureSlider.asStateFlow()

    private val _isCapturing = MutableStateFlow(false)
    val isCapturing: StateFlow<Boolean> = _isCapturing.asStateFlow()

    private val shutterSound = MediaActionSound()

    init {
        // Pre-load shutter sound
        try {
            shutterSound.load(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error loading shutter sound", e)
        }
    }

    fun loadPhotos(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val directory = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            val files = directory?.listFiles { file ->
                file.isFile && (file.extension.lowercase() == "jpg" || file.extension.lowercase() == "jpeg")
            }?.sortedByDescending { it.lastModified() } ?: emptyList()
            _capturedPhotos.value = files
        }
    }

    fun setZoom(ratio: Float) {
        _zoomRatio.value = ratio.coerceIn(1.0f, 10.0f)
        // Dynamically calculate focal length label to look cool and professional
        val computedFocal = (35 + (ratio - 1.0f) * 20).toInt()
        _focalLength.value = computedFocal
    }

    fun selectLensPreset(lens: Int) {
        _focalLength.value = lens
        _zoomRatio.value = when (lens) {
            35 -> 1.0f
            50 -> 1.5f
            85 -> 2.5f
            else -> 1.0f
        }
    }

    fun setExposure(value: Float) {
        _exposure.value = value.coerceIn(-3.0f, 3.0f)
    }

    fun setTemperature(value: Float) {
        _temperature.value = value.coerceIn(-2.0f, 2.0f)
    }

    fun toggleFlash() {
        _flashMode.value = (_flashMode.value + 1) % 3
    }

    fun toggleCamera() {
        _isFrontCamera.value = !_isFrontCamera.value
    }

    fun setSelectedPhoto(file: File?) {
        _selectedPhoto.value = file
    }

    fun toggleTemperatureSlider() {
        _showTemperatureSlider.value = !_showTemperatureSlider.value
        if (_showTemperatureSlider.value) {
            _showExposureSlider.value = false
        }
    }

    fun toggleExposureSlider() {
        _showExposureSlider.value = !_showExposureSlider.value
        if (_showExposureSlider.value) {
            _showTemperatureSlider.value = false
        }
    }

    fun closeSliders() {
        _showTemperatureSlider.value = false
        _showExposureSlider.value = false
    }

    fun playShutterSound() {
        try {
            shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        } catch (e: Exception) {
            Log.e("CameraViewModel", "Error playing shutter sound", e)
        }
    }

    fun processAndSavePhoto(context: Context, rawFile: File) {
        _isCapturing.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // Decode captured image
                val options = BitmapFactory.Options().apply {
                    inMutable = true
                }
                var bitmap = BitmapFactory.decodeFile(rawFile.absolutePath, options)

                if (bitmap != null) {
                    // Check if we need to rotate/flip the image based on camera selection
                    val matrix = Matrix()
                    
                    // Front camera images are usually mirrored, we can mirror it here if desired
                    if (_isFrontCamera.value) {
                        matrix.postScale(-1f, 1f)
                    }

                    if (matrix.isIdentity.not()) {
                        val rotatedBitmap = Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )
                        bitmap.recycle()
                        bitmap = rotatedBitmap
                    }

                    // Apply the retro adjustments (exposure, temperature, vignette)
                    val processedBitmap = bitmap.applyRetroFilter(_temperature.value, _exposure.value)

                    // Overwrite the original file with processed retro photo
                    FileOutputStream(rawFile).use { out ->
                        processedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
                    }
                    processedBitmap.recycle()
                    if (bitmap != processedBitmap) {
                        bitmap.recycle()
                    }
                }

                // Delete raw temporary file if needed, but since rawFile is where we saved, we just reload
                loadPhotos(context)
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error processing captured photo", e)
            } finally {
                _isCapturing.value = false
            }
        }
    }

    fun deletePhoto(context: Context, file: File) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (file.exists()) {
                    file.delete()
                }
                if (_selectedPhoto.value == file) {
                    _selectedPhoto.value = null
                }
                loadPhotos(context)
            } catch (e: Exception) {
                Log.e("CameraViewModel", "Error deleting photo", e)
            }
        }
    }

    // Helper: Extension on Bitmap to apply retro/vintage film filter in memory
    private fun Bitmap.applyRetroFilter(tempVal: Float, expVal: Float): Bitmap {
        val width = this.width
        val height = this.height
        val output = Bitmap.createBitmap(width, height, this.config ?: Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(output)
        val paint = android.graphics.Paint()
        canvas.drawBitmap(this, 0f, 0f, paint)

        // 1. Apply Exposure (brightness/contrast offset)
        if (expVal != 0f) {
            val offset = expVal * 20f
            // Brighten/darken RGB channels
            val brightnessMatrix = android.graphics.ColorMatrix(floatArrayOf(
                1f, 0f, 0f, 0f, offset,
                0f, 1f, 0f, 0f, offset,
                0f, 0f, 1f, 0f, offset,
                0f, 0f, 0f, 1f, 0f
            ))
            paint.colorFilter = android.graphics.ColorMatrixColorFilter(brightnessMatrix)
            canvas.drawBitmap(output, 0f, 0f, paint)
        }

        // 2. Apply Temperature Tint
        if (tempVal != 0f) {
            // Calculate a warm or cool amber/cyan tint
            val tintColor = if (tempVal > 0f) {
                // Retro Amber/Orange Warmth
                val alpha = (tempVal * 25f).toInt().coerceIn(0, 80)
                android.graphics.Color.argb(alpha, 245, 158, 11)
            } else {
                // Retro Cool Blue/Teal
                val alpha = (-tempVal * 25f).toInt().coerceIn(0, 80)
                android.graphics.Color.argb(alpha, 14, 165, 233)
            }
            val tintPaint = android.graphics.Paint().apply {
                colorFilter = android.graphics.PorterDuffColorFilter(tintColor, android.graphics.PorterDuff.Mode.SRC_ATOP)
            }
            canvas.drawBitmap(output, 0f, 0f, tintPaint)
        }

        // 3. Apply Retro Vignette
        val vignettePaint = android.graphics.Paint().apply {
            isAntiAlias = true
            shader = android.graphics.RadialGradient(
                width / 2f, height / 2f,
                kotlin.math.max(width, height) * 0.72f,
                intArrayOf(android.graphics.Color.TRANSPARENT, android.graphics.Color.argb(135, 12, 12, 12)),
                floatArrayOf(0.55f, 1.0f),
                android.graphics.Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), vignettePaint)

        return output
    }

    override fun onCleared() {
        super.onCleared()
        try {
            shutterSound.release()
        } catch (e: Exception) {
            // ignore
        }
    }
}
