# Walkthrough - Fixing Unresolved Reference 'cameraInfo'

I have fixed the build error `Unresolved reference 'cameraInfo'` in `CameraPreviewView.kt`.

## Changes

### [CameraPreviewView.kt](file:///F:/#an/Bhig/app/src/main/java/com/example/CameraPreviewView.kt)

The issue was caused by attempting to access `cameraInfo` on a `CameraInfo` object within the `addCameraFilter` lambda. In CameraX, to access the Camera2 ID from `CameraInfo`, `Camera2CameraInfo.from(it)` should be used.

I also added the required opt-in for the experimental Camera2 interop API.

```diff
+@file:OptIn(ExperimentalCamera2Interop::class)
 package com.example

 import android.content.Context
...
+import androidx.camera.camera2.interop.Camera2CameraInfo
+import androidx.camera.camera2.interop.ExperimentalCamera2Interop
 import androidx.camera.core.Camera
...
-                cameras.filter { it.cameraInfo.cameraId == bestCameraId }
+                cameras.filter { Camera2CameraInfo.from(it).cameraId == bestCameraId }
```

## Verification Results

### Automated Tests
- Executed `./gradlew :app:compileDebugKotlin` and confirmed the build finished successfully.

```
{
  "status": "Build finished successfully."
}
```
