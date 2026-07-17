# ZoomBox Camera — LLM Handoff

## Project Overview

**ZoomBox Camera** (`com.aistudio.zoomboxcamera.qvtkpd`) is an Android camera app built with Jetpack Compose + CameraX that simulates a vintage film camera experience. It features a "zoom box" framing overlay, lens preset simulations (35mm/50mm/85mm/135mm), manual exposure/temperature controls, and retro film filters.

**Architecture:** MVVM — `CameraViewModel` owns all state, `CameraPreviewView` manages the CameraX lifecycle and gesture input, `CameraUi` composes the full UI, and `zoom/*` modules handle lens discovery, FOV math, and capture orchestration.

---

## Project Structure

```
app/src/main/java/com/example/
├── MainActivity.kt              # Single Activity entry point
├── CameraViewModel.kt            # State management, image processing, retro filters
├── CameraPreviewView.kt          # PreviewView, CameraX binding, gesture zoom, capture functions
├── CameraUi.kt                   # Full Compose UI: zoom box overlay, controls, photo viewer
├── zoom/
│   ├── LensProfile.kt            # LensRole enum + LensProfile data class
│   ├── LensCatalog.kt            # Runtime discovery of physical cameras via Camera2
│   ├── FovMapper.kt              # Pure math: box scale, preview/capture lens selection, crop factor
│   ├── PreviewSessionManager.kt  # Preview binding to physical cameras
│   └── CaptureController.kt      # Shutter tap orchestration with lens swap logic
└── ui/theme/
    ├── Color.kt
    ├── Type.kt
    └── Theme.kt

app/src/test/java/com/example/
├── zoom/FovMapperTest.kt          # Unit tests for FovMapper pure math
├── GreetingScreenshotTest.kt      # Roborazzi screenshot tests
├── ExampleUnitTest.kt
├── ExampleRobolectricTest.kt
app/src/androidTest/
└── ExampleInstrumentedTest.kt
```

---

## Key Dependencies

| Library | Usage |
|---|---|
| CameraX `core`, `camera2`, `lifecycle`, `view` | Camera preview, capture, lifecycle binding |
| Jetpack Compose (BOM) | UI framework |
| Material3 | UI components and theming |
| Coil | Async image loading in gallery |
| Accompanist Permissions | Camera permission flow |
| Firebase (AI, AppCheck) | AI features, app attestation |
| Room (KSP) | Local persistence (unused?) |
| Moshi (KSP) | JSON serialization |
| Retrofit + OkHttp + LoggingInterceptor | Networking |
| Roborazzi + Robolectric | Screenshot + unit tests |

**Min SDK:** 24 | **Target SDK:** 36 | **Kotlin Compose**

---

## Component Responsibilities

### `MainActivity.kt` (19 lines)
Single activity, edge-to-edge, sets `CameraUi()` content inside `MyApplicationTheme`.

### `CameraViewModel.kt` (564 lines)
- **State flows:** `focalLength`, `baseFocalLength`, `zoomRatio`, `boxScale`, `previewLensRole`, `captureLensRole`, `exposure`, `temperature`, `flashMode`, `isFrontCamera`, `isCapturing`, `capturedPhotos`, etc.
- **`applyLensSelection(targetFocalLength)`** — Core lens algorithm: finds best base physical lens (largest f ≤ target), computes preview lens (FovMapper), capture lens with hysteresis, box scale, and digital zoom factor.
- **`setZoom(ratio)`** — Maps gesture zoom to focal length target.
- **`processAndSavePhoto()`** — EXIF orientation fix, bitmap crop to zoom box, retro filter (exposure/temp/vignette), save with EXIF metadata.
- **`cropBitmapToZoomBox()`** — Maps screen-space zoom box coordinates to captured bitmap using FILL_CENTER scaling math.
- **`applyRetroFilter()`** — Three passes: brightness offset, temperature tint (amber/cyan), radial vignette.

### `CameraPreviewView.kt` (435 lines) — KEY FILE
- **Composable `CameraPreviewView()`** — Manages PreviewView, CameraX binding, gesture zoom.
- **`activeImageCapture` tracking** (line 231) — Mutable state that always points to the lifecycle-bound `ImageCapture` instance. Updated after every camera bind operation. This fixes the critical bug where `triggerImageCapture()` used an unbound ImageCapture.
- **Camera binding** (lines 252–296) — `LaunchedEffect(isFrontCamera, cameraProviderFuture)`:
  - Front camera: binds directly with `CameraSelector.DEFAULT_FRONT_CAMERA`
  - Back camera: attempts physical lens targeting via `PreviewSessionManager.bindPreview()`, falls back to `DEFAULT_BACK_CAMERA`
  - Updates `activeImageCapture` after each bind
- **Flash mode handling** (lines 227, 320–329) — Initial flash mode set via `Builder.setFlashMode(FLASH_MODE_AUTO)`. Changes propagate to `activeImageCapture.flashMode`.
- **Gesture zoom** (lines 343–389) — Pinch-to-zoom + vertical drag zoom with exponential mapping and haptic notches.
- **`captureWithBestLens()`** (top-level, lines 109–169) — Lens-swap capture: unbinds all, binds target physical lens, captures at full quality, then rebinds default camera.
- **`rebindDefaultCamera()`** (top-level, lines 175–190) — Rebinds the default camera after a lens-swapped capture.
- **`triggerImageCapture()`** (top-level, lines 400–416) — Simple capture with current active ImageCapture.

### `CameraUi.kt` (1010 lines)
- **`CameraUi()`** — Entry point, checks camera permission, delegates to `CameraActiveScreen()` or `CameraPermissionOnboarding()`.
- **`CameraActiveScreen()`** — Observes ViewModel state, composes the full UI:
  - `CameraPreviewView` as the viewfinder background
  - Temperature tint overlay
  - Zoom box Canvas mask (darkens outside the box)
  - Focal length label + zoom box border
  - Control capsule (temperature, focal length preset, exposure)
  - White flash burst on capture
  - Bottom deck: gallery thumbnail, shutter button, flash toggle, camera flip
  - `PhotoViewerOverlay` (EXIF data, share, delete, filmstrip thumbnails)
- **Shutter click** (lines 633–702) — Uses `activeImageCapture` (via `imageCaptureProvider` callback) for capture. Tries lens-swap capture via `captureWithBestLens()` if a `LensCaptureHandle` is available, otherwise falls back to `triggerImageCapture()`.

### `PreviewSessionManager.kt` (186 lines)
- **`BindResult`** (lines 27–30) — Data class holding `Camera` + `ImageCapture` returned by `bindPreview()`.
- **`bindPreview()`** (lines 65–105) — Creates a new Preview + ImageCapture, binds to physical camera via `CameraSelector` targeting `physicalCameraId`. Returns `BindResult?`.
- **`buildSelectorForPhysicalCamera()`** (lines 45–53) — CameraSelector filter by physical camera ID.
- **`bindDefaultCamera()`** (lines 110–154) — Unused fallback.

### `CaptureController.kt` (275 lines)
- **`capture()`** (lines 65–158) — Determines if lens swap is needed via `FovMapper.captureLens()`. Delegates to `captureWithLensSwap()` or `captureWithCurrentLens()`.
- **`captureWithLensSwap()`** (lines 194–263) — Unbinds all, binds target physical lens with a temporary ImageCapture, takes picture.
- **`captureWithCurrentLens()`** (lines 163–187) — Uses the currently bound ImageCapture directly.

### `LensCatalog.kt` (204 lines)
- **`enumerate()`** — Iterates camera IDs, checks `LENS_FACING_BACK`, expands logical multi-cameras via `getPhysicalCameraIds()`, computes crop factor, estimates megapixels, detects OIS, classifies lens roles by equiv focal length.
- Uses thresholds: UW < 20mm, Tele > 70mm.

### `FovMapper.kt` (129 lines) — PURE MATH, fully unit-tested
- **`boxScale()`** — `previewFocalMm / targetFocalMm`, clamped to [0, 1].
- **`previewLens()`** — UW if target < primary, else Primary (Tele never previewed).
- **`captureLens()`** — Primary/UW below Tele threshold, Tele above threshold, hysteresis band (±8mm) prevents flickering.
- **`captureCropFactor()`** — `target / captureLensFocal`, minimum 1.
- **`outputMegapixels()`** — `nativeMp / (cropFactor²)`.

---

## Data Flow

### Normal Preview
```
CameraPreviewView composable
  → remember { ProcessCameraProvider.getInstance(context) }
  → LaunchedEffect(isFrontCamera, cameraProviderFuture)
    → addListener → cp.bindToLifecycle() or bindPreview()
    → camera = result.camera, activeImageCapture = result.imageCapture
    → imageCaptureProvider(activeImageCapture) → CameraUi.activeImageCapture
```

### Shutter Tap (No Lens Swap)
```
CameraUi shutter click
  → activeImageCapture.takePicture()
  → CameraViewModel.processAndSavePhoto()
    → BitmapFactory.decodeFile → EXIF orientation fix → cropToZoomBox → applyRetroFilter → save
```

### Shutter Tap (Lens Swap)
```
CameraUi shutter click
  → captureWithBestLens()
    → cp.unbindAll() → bindToLifecycle(targetLens, tempImageCapture)
    → tempImageCapture.takePicture()
    → callback: rebindPreview() → rebindDefaultCamera(activeImageCapture)
    → onCaptured → ViewModel.processAndSavePhoto()
```

---

## Bugs Fixed

### Critical: `triggerImageCapture()` used unbound `ImageCapture`
**Root cause:** `PreviewSessionManager.bindPreview()` created a new `ImageCapture` internally but returned only the `Camera`. The caller's `imageCapture` reference pointed to the original unbound instance. `triggerImageCapture()` called `takePicture()` on the unbound instance, causing silent capture failure on back cameras using physical lens targeting.

**Fix:** 
- Added `BindResult` data class holding `Camera` + `ImageCapture`.
- `bindPreview()` now returns `BindResult?`.
- `CameraPreviewView` tracks `activeImageCapture` as mutable state, updated after every bind (front, back-default, physical).
- `imageCaptureProvider` passes `activeImageCapture`.
- **Files changed:** `PreviewSessionManager.kt` (lines 27–30, 65–105), `CameraPreviewView.kt` (lines 221, 231, 233, 266, 286, 289, 324).

### Medium: Flash mode not propagated to actively-bound `ImageCapture`
**Root cause:** The `LaunchedEffect(flashMode, camera)` was writing to the original `imageCapture` instance while `bindPreview()` had created a different bound instance.

**Fix:**
- Initial flash mode set via `Builder.setFlashMode(FLASH_MODE_AUTO)`.
- `LaunchedEffect(flashMode, camera)` now writes to `activeImageCapture.flashMode`.
- **File changed:** `CameraPreviewView.kt` (lines 227, 324).

---

## Remaining Issues / Observations

### 1. `ConsumerBase is abandoned!` (framework log spam)
CameraX's `ImageReader` surfaces are abandoned when `unbindAll()` is called during the rapid close/reopen cycle of lens-swap capture. This is expected framework behavior and is non-fatal. Requires restructuring the capture pipeline to avoid the double unbind/bind cycle.

### 2. `AppOps attributionTag` warning in manifest
Android 12+ requires `android:attributionTag` on the `<application>` element. CameraX internally calls AppOpsManager APIs, causing 4 log lines per session. Fix: add `android:attributionTag="CameraX"` to the `<application>` tag in `AndroidManifest.xml`.

### 3. Stale `SurfaceProvider` in `LensCaptureHandle`
`LensCaptureHandle` captures `previewView.surfaceProvider` once when `cameraProviderRef` is set. If the PreviewView is recreated (configuration change), this handle points to an orphaned surface. The handle is only used during capture, so in practice this is rare, but could cause a blank capture preview on config changes.

### 4. Dead code: `PreviewSessionManager.bindDefaultCamera()` (lines 110–154)
This method is defined but never called anywhere in the codebase.

### 5. Haptic feedback called on every slider drag
`CameraUi.kt` calls `haptic.performHapticFeedback(HapticFeedbackType.LongPress)` on every `onValueChange` of temperature and exposure sliders (lines 431, 470). This produces continuous vibration during slider drag. Consider debouncing or using a lighter feedback type.

### 6. Torch enabled when flash mode is ON
`CameraPreviewView.kt` line 322: `c.cameraControl.enableTorch(flashMode == 1)`. This turns on the torch (continuous light) when flash mode is set to "On", which differs from typical camera behavior (flash fires only on capture). The flash mode enum is: 0=Auto, 1=On, 2=Off.

---

## Build & Test Commands

```powershell
# Build debug APK
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-18.0.2.1'
gradlew assembleDebug

# Run unit tests
gradlew testDebugUnitTest

# Run screenshot tests
gradlew testDebugUnitTest --tests "*GreetingScreenshotTest*"

# Run specific FovMapper tests
gradlew testDebugUnitTest --tests "*FovMapperTest*"
```

**Test files:**
- `app/src/test/java/com/example/zoom/FovMapperTest.kt` — 36 tests for pure math module (no Android dependencies)
- `app/src/test/java/com/example/GreetingScreenshotTest.kt` — Roborazzi screenshot tests
- `app/src/test/java/com/example/ExampleRobolectricTest.kt` — ViewModel state + UI interaction tests
- `app/src/test/java/com/example/ExampleUnitTest.kt` — Placeholder
- `app/src/androidTest/java/com/example/ExampleInstrumentedTest.kt` — Device test

---

## Key Architecture Decisions

1. **Preview vs Capture lens independence** — The preview always shows a wider context (UW or Primary, never Tele), while capture uses the best lens for the target focal length. This means boxScale shows a framing box on a wider preview.
2. **Physical camera targeting** — Uses `Camera2CameraInfo` + `CameraFilter` to target specific physical camera IDs, not `CameraMetadata` or deprecated `setPhysicalCameraId()` on the extender.
3. **Lens-swap capture** — When the capture lens differs from the preview lens, `captureWithBestLens()` temporarily unbinds the preview, binds the capture lens, takes a picture, and rebinds the preview.
4. **All state in ViewModel** — No state in composables except camera lifecycle references (`camera`, `activeImageCapture`, `cameraProviderRef`).
