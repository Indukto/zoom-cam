package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.activity.compose.BackHandler
import kotlinx.coroutines.delay
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FlashAuto
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Thermostat
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.rememberAsyncImagePainter
import com.example.zoom.LensCatalog
import com.example.zoom.LensRole
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import java.io.File

private const val FLASH_BURST_DURATION_MS = 120L

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraUi(
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val cameraPermissionState = rememberPermissionState(android.Manifest.permission.CAMERA)

    LaunchedEffect(Unit) {
        viewModel.loadPhotos(context)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF121212)
    ) {
        if (cameraPermissionState.status.isGranted) {
            CameraActiveScreen(viewModel = viewModel)
        } else {
            CameraPermissionOnboarding(
                onRequestPermission = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    cameraPermissionState.launchPermissionRequest()
                }
            )
        }
    }
}

@Composable
fun CameraPermissionOnboarding(
    onRequestPermission: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(100.dp)
                .background(Color(0xFF232323), CircleShape)
                .border(2.dp, Color(0xFFF59E0B), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.CameraAlt,
                contentDescription = "Retro Camera Icon",
                tint = Color(0xFFF59E0B),
                modifier = Modifier.size(48.dp)
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        Text(
            text = "ZOOM CAMERA",
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            letterSpacing = 2.sp,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = "To start capturing vintage film styled photos with our signature zoom box and warm retro filters, please grant access to the camera.",
            fontSize = 15.sp,
            color = Color(0xFF9CA3AF),
            textAlign = TextAlign.Center,
            lineHeight = 22.sp,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onRequestPermission,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFFF59E0B),
                contentColor = Color.Black
            ),
            shape = RoundedCornerShape(30.dp),
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(54.dp)
                .testTag("enable_camera_button")
        ) {
            Text(
                text = "ENABLE CAMERA",
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                letterSpacing = 1.sp
            )
        }
    }
}

@Composable
fun CameraActiveScreen(
    viewModel: CameraViewModel
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    val selectedLensRole by viewModel.selectedLensRole.collectAsState()
    val effectiveFocalLength by viewModel.effectiveFocalLength.collectAsState()
    val digitalZoomRatio by viewModel.digitalZoomRatio.collectAsState()
    val exposure by viewModel.exposure.collectAsState()
    val temperature by viewModel.temperature.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val capturedPhotos by viewModel.capturedPhotos.collectAsState()
    val selectedPhoto by viewModel.selectedPhoto.collectAsState()
    val boxScale by viewModel.boxScale.collectAsState()
    val previewLensRole by viewModel.previewLensRole.collectAsState()
    val captureLensRole by viewModel.captureLensRole.collectAsState()
    val lensSwitchTrigger by viewModel.lensSwitchTrigger.collectAsState()

    val showTempSlider by viewModel.showTemperatureSlider.collectAsState()
    val showExpSlider by viewModel.showExposureSlider.collectAsState()
    val isCapturing by viewModel.isCapturing.collectAsState()

    val mainExecutor = ContextCompat.getMainExecutor(context)
    var activeImageCapture by remember { mutableStateOf<ImageCapture?>(null) }

    var flashFlashActive by remember { mutableStateOf(false) }
    LaunchedEffect(flashFlashActive) {
        if (flashFlashActive) {
            delay(FLASH_BURST_DURATION_MS)
            flashFlashActive = false
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                viewModel.closeSliders()
            }
    ) {
        val totalWidth = maxWidth
        val totalHeight = maxHeight

        // Viewfinder bounds (4:3 portrait box at top)
        val vfWidth = totalWidth * 0.92f
        val vfHeight = vfWidth * 4f / 3f
        val vfTop = 56.dp

        // 1. Black background
        Box(modifier = Modifier.fillMaxSize().background(Color.Black))

        // 2. Camera Viewfinder — 4:3 box at top with rounded corners
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = vfTop)
                .width(vfWidth)
                .height(vfHeight)
        ) {
        key(lensSwitchTrigger) {
        CameraPreviewView(
            modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)),
            selectedLensRole = selectedLensRole,
            digitalZoomRatio = digitalZoomRatio,
            exposure = exposure,
            flashMode = flashMode,
            isFrontCamera = isFrontCamera,
            onZoomChanged = { viewModel.setZoom(it) },
            onZoomTick = {
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            },
            onAvailableFocalLengths = { viewModel.setAvailableFocalLengths(it) },
            imageCaptureProvider = { activeImageCapture = it },
            onLensCatalogReady = { result -> viewModel.setLensCatalogResult(result) }
        )
        }
        }

        // Color overlay for retro tint
        if (temperature != 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        if (temperature > 0f) {
                            Color(0xFFF59E0B).copy(alpha = (temperature * 0.08f).coerceIn(0f, 0.22f))
                        } else {
                            Color(0xFF0EA5E9).copy(alpha = (-temperature * 0.08f).coerceIn(0f, 0.22f))
                        }
                    )
            )
        }

        // Box scale animation
        val animatedBoxWidthFraction by animateFloatAsState(
            targetValue = boxScale,
            animationSpec = spring(stiffness = 200f, dampingRatio = 0.75f),
            label = "box_width_fraction"
        )

        val showZoomBox = selectedLensRole == LensRole.PRIMARY && animatedBoxWidthFraction < 0.99f

        // Zoom box mask — positioned relative to viewfinder
        val vfX = (totalWidth - vfWidth) / 2f

        if (showZoomBox) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val boxW = vfWidth.toPx() * animatedBoxWidthFraction
                val boxH = boxW * 1.35f
                val left = vfX.toPx() + (vfWidth.toPx() - boxW) / 2f
                val top = vfTop.toPx() + (vfHeight.toPx() - boxH) / 2f

                val rect = Rect(left, top, left + boxW, top + boxH)
                val path = Path().apply {
                    addRoundRect(RoundRect(rect = rect, cornerRadius = CornerRadius(20.dp.toPx())))
                }
                clipPath(path = path, clipOp = ClipOp.Difference) {
                    drawRect(color = Color.Black.copy(alpha = 0.65f))
                }
            }

            // Zoom box outline
            Box(
                modifier = Modifier
                    .offset(
                        x = vfX + (vfWidth - vfWidth * animatedBoxWidthFraction) / 2f,
                        y = vfTop + (vfHeight - vfWidth * animatedBoxWidthFraction * 1.35f) / 2f
                    )
                    .width(vfWidth * animatedBoxWidthFraction)
                    .height(vfWidth * animatedBoxWidthFraction * 1.35f)
                    .border(2.dp, Color.White.copy(alpha = 0.9f), RoundedCornerShape(20.dp))
            )
        }

        // 4. Controls capsule — positioned above bottom deck
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 140.dp)
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                // Slider popup
                AnimatedVisibility(
                    visible = showTempSlider || showExpSlider,
                    enter = fadeIn() + slideInVertically(initialOffsetY = { 20 }),
                    exit = fadeOut() + slideOutVertically(targetOffsetY = { 20 })
                ) {
                    Box(
                        modifier = Modifier
                            .padding(bottom = 12.dp)
                            .background(Color(0xE61E1E1E), RoundedCornerShape(16.dp))
                            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                            .width(240.dp)
                    ) {
                        if (showTempSlider) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Cool", color = Color(0xFF0EA5E9), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Text("Temp (${if (temperature >= 0) "+" else ""}${String.format("%.1f", temperature)})", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    Text("Warm", color = Color(0xFFF59E0B), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Slider(
                                    value = temperature,
                                    onValueChange = { viewModel.setTemperature(it); haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    valueRange = -2f..2f,
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFF59E0B), inactiveTrackColor = Color(0xFF4B5563)
                                    )
                                )
                            }
                        } else if (showExpSlider) {
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("- EV", color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                    Text("Exposure (${if (exposure >= 0) "+" else ""}${String.format("%.1f", exposure)})", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                    Text("+ EV", color = Color.LightGray, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                }
                                Slider(
                                    value = exposure,
                                    onValueChange = { viewModel.setExposure(it); haptic.performHapticFeedback(HapticFeedbackType.LongPress) },
                                    valueRange = -3f..3f,
                                    colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color(0xFFFBBF24), inactiveTrackColor = Color(0xFF4B5563)
                                    )
                                )
                            }
                        }
                    }
                }

                // Control capsule
                Row(
                    modifier = Modifier
                        .background(Color(0xFF1C1C1E), RoundedCornerShape(32.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(32.dp))
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Temperature
                    IconButton(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.toggleTemperatureSlider() },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = if (showTempSlider) Color(0xFF2C2C2E) else Color.Transparent),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Thermostat,
                            contentDescription = "Temperature",
                            tint = if (temperature > 0f) Color(0xFFF59E0B) else if (temperature < 0f) Color(0xFF0EA5E9) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Lens selector
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color(0xFF2C2C2E), CircleShape)
                            .clickable {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.cycleLens()
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        val lensLabel = "${effectiveFocalLength}"
                        Text(
                            text = lensLabel,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // Exposure
                    IconButton(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.toggleExposureSlider() },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = if (showExpSlider) Color(0xFF2C2C2E) else Color.Transparent),
                        modifier = Modifier.size(44.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.WbSunny,
                            contentDescription = "Exposure Compensation",
                            tint = if (exposure != 0f) Color(0xFFFBBF24) else Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // 5. White flash overlay
        AnimatedVisibility(
            visible = flashFlashActive,
            enter = fadeIn(animationSpec = tween(40)),
            exit = fadeOut(animationSpec = tween(150))
        ) {
            Box(modifier = Modifier.fillMaxSize().background(Color.White))
        }

        // 6. Bottom Deck Controls
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.9f))
                .padding(bottom = 36.dp, top = 20.dp)
                .padding(horizontal = 24.dp)
        ) {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // Gallery button (left)
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .size(54.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E1E))
                        .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(12.dp))
                        .clickable {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            if (capturedPhotos.isNotEmpty()) {
                                viewModel.setSelectedPhoto(capturedPhotos.first())
                            }
                        }
                        .testTag("gallery_button"),
                    contentAlignment = Alignment.Center
                ) {
                    if (capturedPhotos.isNotEmpty()) {
                        Image(
                            painter = rememberAsyncImagePainter(model = capturedPhotos.first()),
                            contentDescription = "Last captured photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Rounded.PhotoLibrary,
                            contentDescription = "Empty Gallery",
                            tint = Color.Gray,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                // Shutter Button
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(Color(0xFF2C2C2E), CircleShape)
                        .border(4.dp, Color(0xFFE5E7EB), CircleShape)
                        .padding(4.dp)
                        .testTag("shutter_button")
                        .clickable(enabled = !isCapturing) {
                            val captureDevice = activeImageCapture
                            if (captureDevice != null) {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                viewModel.playShutterSound()
                                flashFlashActive = true

                                val catalog = LensCatalog(context)
                                val result = catalog.enumerate()
                                val isDigitalZoomActive = selectedLensRole == LensRole.PRIMARY
                                val nativeFocalForCrop = if (isDigitalZoomActive) result.primary?.equivFocalMm else null

                                triggerImageCapture(
                                    context = context,
                                    imageCapture = captureDevice,
                                    executor = mainExecutor,
                                    onCaptured = { rawFile ->
                                        viewModel.processAndSavePhoto(
                                            context = context,
                                            rawFile = rawFile,
                                            boxWidthFraction = animatedBoxWidthFraction,
                                            screenWidth = totalWidth.value,
                                            screenHeight = totalHeight.value,
                                            captureFocalLength = effectiveFocalLength,
                                            captureLensNativeFocalMm = nativeFocalForCrop
                                        )
                                    },
                                    onCaptureError = { exc ->
                                        Log.e("CameraActiveScreen", "Capture failed", exc)
                                    }
                                )
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val s by animateFloatAsState(
                        targetValue = if (isCapturing) 0.85f else 1.0f,
                        animationSpec = spring(dampingRatio = 0.6f),
                        label = "shutter_scale"
                    )
                    Box(
                        modifier = Modifier.fillMaxSize().scale(s).background(Color(0xFFEF4444), CircleShape)
                    )
                }

                // Flash & Flip (right)
                Row(
                    modifier = Modifier.align(Alignment.CenterEnd),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Flash
                    IconButton(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.toggleFlash() },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E)),
                        modifier = Modifier.size(44.dp).testTag("flash_toggle_button")
                    ) {
                        Icon(
                            imageVector = when (flashMode) { 0 -> Icons.Rounded.FlashAuto; 1 -> Icons.Rounded.FlashOn; else -> Icons.Rounded.FlashOff },
                            contentDescription = "Toggle Flash",
                            tint = if (flashMode == 2) Color.Gray else Color(0xFFFBBF24),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    // Flip camera
                    IconButton(
                        onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.toggleCamera() },
                        colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E)),
                        modifier = Modifier.size(44.dp).testTag("camera_flip_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.FlipCameraAndroid,
                            contentDescription = "Flip Camera",
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        // 7. Photo Viewer Overlay
        AnimatedVisibility(
            visible = selectedPhoto != null,
            enter = fadeIn() + slideInVertically(initialOffsetY = { it }),
            exit = fadeOut() + slideOutVertically(targetOffsetY = { it })
        ) {
            val activePhoto = selectedPhoto
            if (activePhoto != null) {
                PhotoViewerOverlay(
                    photo = activePhoto,
                    allPhotos = capturedPhotos,
                    viewModel = viewModel,
                    onClose = { viewModel.setSelectedPhoto(null) },
                    onDelete = { file -> viewModel.deletePhoto(context, file) },
                    onSelectPhoto = { viewModel.setSelectedPhoto(it) }
                )
            }
        }
    }
}

@Composable
fun PhotoViewerOverlay(
    photo: File,
    allPhotos: List<File>,
    viewModel: CameraViewModel,
    onClose: () -> Unit,
    onDelete: (File) -> Unit,
    onSelectPhoto: (File) -> Unit
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var exifData by remember(photo) { mutableStateOf(ExifData()) }
    LaunchedEffect(photo) { exifData = viewModel.readExifData(photo) }

    val phoneName = Build.MODEL
    BackHandler(onBack = onClose)

    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(top = 16.dp)
    ) {
        // Upper Title Deck
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(
                onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClose() },
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E))
            ) {
                Icon(imageVector = Icons.Rounded.Close, contentDescription = "Close Viewfinder", tint = Color.White)
            }

            Text(
                text = "CPM VINTAGE ROLL",
                fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp, fontFamily = FontFamily.Monospace
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        try {
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", photo)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "image/jpeg"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Retro Photo"))
                        } catch (e: Exception) { Log.e("PhotoViewerOverlay", "Error sharing photo", e) }
                    },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF1C1C1E))
                ) {
                    Icon(imageVector = Icons.Rounded.Share, contentDescription = "Share retro capture", tint = Color.White, modifier = Modifier.size(18.dp))
                }

                IconButton(
                    onClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onDelete(photo) },
                    colors = IconButtonDefaults.iconButtonColors(containerColor = Color(0xFF2A1C1C))
                ) {
                    Icon(imageVector = Icons.Rounded.Delete, contentDescription = "Delete captured photo", tint = Color(0xFFEF4444), modifier = Modifier.size(18.dp))
                }
            }
        }

        // Photo Card
        Box(
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center
        ) {
            Card(
                modifier = Modifier.fillMaxWidth().aspectRatio(1f / 1.28f),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAF9)),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 16.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Image(
                        painter = rememberAsyncImagePainter(model = photo),
                        contentDescription = "Enlarged capture",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(8.dp))
                    )
                    Spacer(modifier = Modifier.height(14.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = phoneName, fontSize = 12.sp, fontWeight = FontWeight.Normal, color = Color.Black.copy(alpha = 0.55f), fontFamily = FontFamily.Serif, modifier = Modifier.padding(horizontal = 4.dp))
                        Text(text = "${exifData.focalLength}  ${exifData.shutterSpeed}  ${exifData.iso}", fontSize = 12.sp, fontWeight = FontWeight.Normal, color = Color.Black.copy(alpha = 0.55f), fontFamily = FontFamily.Serif, modifier = Modifier.padding(horizontal = 4.dp))
                    }
                }
            }
        }

        // Filmstrip
        Spacer(modifier = Modifier.height(16.dp))
        Box(modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(items = allPhotos, key = { it.absolutePath }) { item ->
                    val isSelected = item == photo
                    Box(
                        modifier = Modifier
                            .size(62.dp).clip(RoundedCornerShape(6.dp))
                            .border(width = if (isSelected) 3.dp else 0.dp, color = if (isSelected) Color(0xFFF59E0B) else Color.Transparent, shape = RoundedCornerShape(6.dp))
                            .clickable { haptic.performHapticFeedback(HapticFeedbackType.LongPress); onSelectPhoto(item) }
                    ) {
                        Image(painter = rememberAsyncImagePainter(model = item), contentDescription = "Filmstrip photo", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}