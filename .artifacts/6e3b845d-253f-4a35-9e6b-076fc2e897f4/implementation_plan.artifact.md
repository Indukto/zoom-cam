# Fix RAW Capture Failure and Gallery Visibility

The user reports that RAW pictures are not being saved. Analysis of the logs and code suggests two main issues:
1. **Camera Resource Contention**: `RawCapture.kt` attempts to open the camera via Camera2 while CameraX is still holding it for the preview. This causes `onDisconnected` events and `CAMERA_ERROR (3)`.
2. **Gallery Visibility**: The app's internal gallery (`loadPhotos`) only looks for `.jpg` and `.jpeg` files, so even if a `.dng` is saved, it doesn't appear in the app.

## User Review Required

> [!IMPORTANT]
> To fix the RAW capture, the preview will now be temporarily unbound (going black) while the RAW frame is being acquired. This is necessary to allow `RawCapture` to have exclusive access to the camera hardware, as CameraX and manual Camera2 calls to `openCamera` conflict on most Android devices.

## Proposed Changes

### Camera Pipeline

#### [MODIFY] [CameraPreviewView.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/CameraPreviewView.kt)
- Add `isCapturing` and `rawModeEnabled` to the `LaunchedEffect` that binds the camera.
- If a RAW capture is in progress, call `cp.unbindAll()` to release the camera for `RawCapture`.

#### [MODIFY] [RawCapture.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/zoom/RawCapture.kt)
- Add logging to track the arrival of the `Image` and the `TotalCaptureResult`.
- Gracefully handle the `Function not implemented (-38)` error during session closure by ignoring it specifically if the capture has already succeeded.
- Ensure `imageReader` and `camera` are closed in the correct order to avoid HAL crashes.

### Gallery & ViewModel

#### [MODIFY] [CameraViewModel.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/CameraViewModel.kt)
- Update `loadPhotos` to include `.dng` files in the list.
- Ensure `captureAndSaveRaw` properly manages the `isCapturing` state and handles errors.

## Verification Plan

### Automated Tests
- Check Logcat for "RAW image arrived" and "RAW result arrived" logs.
- Verify that the `CAMERA_ERROR (3)` no longer appears during the start of capture.

### Manual Verification
- Deploy to device.
- Enable RAW mode and take a photo.
- Verify that the preview goes black briefly, then the capture completes.
- Verify that the RAW photo appears in the app's filmstrip/gallery.
- Check the device gallery (Google Photos) for the DNG file.
