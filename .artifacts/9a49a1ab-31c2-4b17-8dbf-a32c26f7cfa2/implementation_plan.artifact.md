# Fix "No Pictures Saved" and Camera Targeting

The user reports that no pictures are saved when pressing the shutter button. Investigation reveals that the camera targeting logic is likely failing on devices where physical camera IDs (e.g., "2", "3") are part of a logical multi-camera (e.g., "0"). The current code incorrectly tries to use these physical IDs in `CameraSelector`, which fails to match any logical camera exposed by CameraX.

## User Review Required

> [!IMPORTANT]
> The fix involves changing how physical cameras are targeted. This will improve compatibility with multi-camera devices like Pixels and Samsungs.
> I will also change the filename logic to prevent double `_XXmm` suffixes.

## Proposed Changes

### [Zoom Box Camera](file:///F:/#an/Bhig)

#### [MODIFY] [LensProfile.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/zoom/LensProfile.kt)
- Add `logicalCameraId` property to `LensProfile`.

#### [MODIFY] [LensCatalog.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/zoom/LensCatalog.kt)
- Populate `logicalCameraId` during camera enumeration in `enumerate()`.

#### [MODIFY] [PreviewSessionManager.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/zoom/PreviewSessionManager.kt)
- Update `bindPreview` to:
    - Use `logicalCameraId` for the `CameraSelector`.
    - Use `Camera2Interop.Extender` on both `Preview.Builder` and `ImageCapture.Builder` to set the physical camera ID.

#### [MODIFY] [CameraPreviewView.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/CameraPreviewView.kt)
- Update `captureWithBestLens` to:
    - Use the `logicalCameraId` for the `CameraSelector`.
    - Use `Camera2Interop.Extender` to set the physical camera ID on `tempImageCapture` and `capturePreview`.
- Remove the focal length suffix from the initial filename in `captureWithBestLens` to avoid double suffixes (`..._50mm_50mm.jpg`).

#### [MODIFY] [CameraViewModel.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/CameraViewModel.kt)
- In `processAndSavePhoto`, add a check if the photo file exists before attempting to process it.
- Add logging to track the capture and processing flow.

#### [MODIFY] [CameraUi.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/CameraUi.kt)
- Set `viewModel.isCapturing = true` immediately in the shutter button's `onClick` to prevent multiple clicks and provide immediate feedback.
- Ensure `isCapturing` is set back to `false` if `captureWithBestLens` or `triggerImageCapture` fails.

---

## Verification Plan

### Automated Tests
- Run `gradle build` to ensure no regressions in compilation.

### Manual Verification
- Deploy to a device with multiple cameras (if available) and verify that pressing the shutter captures and saves a photo.
- Check the app's gallery (bottom-left button) to see if the captured photo appears.
- Verify the filename in the logs or by inspecting the files on the device.
