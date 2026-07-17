# Fix: Error capturing with best lens - IllegalArgumentException: No available camera can be found

The application crashes with `java.lang.IllegalArgumentException: No available camera can be found` when attempting to switch lenses for high-quality capture. This is because `CameraSelector` is being configured to filter for a `physicalCameraId`, but CameraX's `ProcessCameraProvider` typically only exposes **logical** camera IDs (e.g., "0" for back, "1" for front). When the filter tries to match a physical ID (like "2" or "3") against the logical IDs, it finds no matches and throws the exception.

## Proposed Changes

### [Component] zoom

#### [MODIFY] [LensProfile.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/zoom/LensProfile.kt)
- Add `logicalCameraId: String` property to the `LensProfile` data class. This will store the ID of the logical camera that "owns" the physical lens.

#### [MODIFY] [LensCatalog.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/zoom/LensCatalog.kt)
- In the `enumerate()` function, populate the new `logicalCameraId` field using the `cameraId` from the `cameraManager.cameraIdList` iteration.

---

### [Component] app

#### [MODIFY] [CameraPreviewView.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/CameraPreviewView.kt)
- **`buildCameraSelectorForLens`**: Update the camera filter to use `targetLens.logicalCameraId` instead of `physicalCameraId`. This ensures that CameraX can find and bind to the correct logical camera.
- **`captureWithBestLens`**:
    - Re-acquire the `targetLens` profile to access both logical and physical IDs.
    - Use `Camera2Interop.Extender` on both `ImageCapture.Builder` and `Preview.Builder` to set the `physicalCameraId`. This tells the logical camera to specifically use the target physical lens for these use cases.

## Verification Plan

### Manual Verification
- Deploy the app to a device with multiple back cameras (e.g., Ultra-wide, Wide, Tele).
- Trigger a "best lens" capture (likely by zooming and taking a photo, or a specific UI action that calls `captureWithBestLens`).
- Verify that the app no longer crashes and successfully captures the photo using the correct lens.
- Check logs for "Error capturing with best lens" to ensure it's not being swallowed.
