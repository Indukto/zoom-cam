# Walkthrough - RAW Capture Fix and Privacy Compliance

I have implemented the fixes for the RAW capture failure and addressed the `attributionTag` manifest warning.

## Changes Made

### 1. Privacy & Compliance
- **AndroidManifest.xml**: Added `android:attributionTag="CameraX"` and the `<attribution>` element. This resolves the `AppOps attributionTag not declared` error on Android 12+.
- **strings.xml**: Added `camerax_attribution_label` for the manifest attribution declaration.

### 2. RAW Capture Pipeline
- **CameraPreviewView.kt**: Updated the camera binding logic to call `unbindAll()` when a RAW capture is initiated. This releases the camera hardware from CameraX (Preview) so that the manual Camera2 `RawCapture` logic can take over without hardware contention.
- **RawCapture.kt**:
    - Added detailed logging for `Image` and `TotalCaptureResult` arrival to help verify the pipeline state.
    - Improved session cleanup to gracefully handle HAL-level errors (`cancelRequest` not implemented) that occur when closing a session without a preview.

### 3. Gallery & UX
- **CameraViewModel.kt**:
    - Updated `loadPhotos` to include `.dng` files in the app's internal photo list.
    - Added a success Toast when a RAW image is successfully captured and saved.
    - Ensured `isCapturing` state is reset on both success and failure.

## Verification

- **Logcat**: You should now see logs like `RAW image arrived` and `RAW TotalCaptureResult arrived` during capture.
- **App Gallery**: Captured `.dng` files should now appear in the thumbnail roll at the bottom of the screen.

> [!NOTE]
> During RAW capture, the viewfinder will go black for about 1 second. This is expected as the camera is released by the preview to allow the high-quality RAW sensor readout.
