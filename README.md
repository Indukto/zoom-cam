# 📸 ZoomBox Camera

**ZoomBox Camera** is a professional, retro-themed camera application for Android built with Jetpack Compose and CameraX. It features a unique "Zoom Box" framing overlay that shows the exact crop area before capture, combined with vintage film-style color processing and tactile manual controls.

---

[![Screenshot-20260718-192853.png](https://i.postimg.cc/JzLvjMzg/Screenshot-20260718-192853.png)](https://postimg.cc/qz11TfnX)  [![Screenshot-20260718-192912.png](https://i.postimg.cc/0ysF7PyV/Screenshot-20260718-192912.png)](https://postimg.cc/ZCwwSk3N) 



## ✨ Key Features

### 🎯 Zoom Box Framing
A live 4:3 overlay on the viewfinder that previews the exact crop area of the final image. When using the PRIMARY lens with digital zoom (1×–3×), the zoom box visually scales to indicate the captured region, so you frame your shot *exactly* as it will be saved.

### 🔭 Multi-Lens System
- **Ultra-Wide** (~13mm) — expansive landscape and architectural shots.
- **Primary** (≈24mm native, 1×–3× digital zoom) — the main shooter with smooth zoom-box feedback.
- **Tele** (~116mm) — compressed perspective for portraits and distant subjects.

Digital zoom on the PRIMARY lens is backed by a center crop from the full-resolution sensor, preserving maximum image quality.

### 🎛️ Manual Controls
- **Exposure Compensation** (−3 to +3 EV, ⅒-stop granularity) — brighten or darken before capture.
- **Color Temperature** (2300 K–9000 K) — from cool teal to warm amber with a live viewfinder tint overlay.
- **Flash Modes** — Auto / On / Off.
- **Front/Back Camera Toggle** — with automatic mirror-flip for selfies.

### 🎞️ Retro Film Processing
- Real-time warming/cooling tints applied to the viewfinder and final image.
- Brightness offset via exposure compensation.
- **Vignette effect** — a subtle radial darkening that pulls focus to the subject.
- All processing is applied losslessly to the final JPEG after capture, respecting the original EXIF data.

### 🖼️ In-App Gallery
- View captured photos in a film-style card layout with EXIF metadata (focal length, shutter speed, ISO).
- Scrollable filmstrip for quick navigation between shots.
- Share via system share sheet or delete unwanted captures.
- Photos are saved to device gallery (`Pictures/ZoomBoxCamera`) with rename pattern `*_<focal>mm.jpg`.

### 🧩 Clean Architecture
- **MVVM** with Kotlin StateFlows for reactive UI.
- **Modular camera domain** (`zoom/` package) for lens enumeration, FOV mapping, and capture orchestration.
- 100% **Jetpack Compose** UI — modern, declarative, and maintainable.

---

## 🛠 Tech Stack

| Layer          | Technology                                |
|----------------|-------------------------------------------|
| UI             | Jetpack Compose + Material 3              |
| Camera         | CameraX (`Preview` + `ImageCapture`)      |
| Architecture   | MVVM (ViewModel + StateFlow)              |
| Async          | Kotlin Coroutines & Flow                  |
| Image Loading  | Coil (`rememberAsyncImagePainter`)        |
| Permissions    | Accompanist Permissions API               |
| Image Effects  | `ColorMatrix`, `PorterDuff`, `RadialGradient` (custom) |
| EXIF Handling  | Android `ExifInterface`                   |
| Build          | Gradle with Kotlin DSL + Version Catalog  |

---

## 🚀 Getting Started

### Prerequisites
- **Android Studio** Ladybug (2024.2.1) or newer.
- **Android device/emulator** running API 24+ (Android 7.0).

### Setup

1. **Clone the repository:**
   ```bash
   git clone https://github.com/Indukto/Bhig.git
   cd Bhig
   ```

2. **Configure environment (optional):**
   The project supports a `.env` file at the project root for an optional API key:
   ```env
   GEMINI_API_KEY=your_key_here
   ```
   *(See `.env.example` for the template.)*

3. **Open in Android Studio:**
   - **File → Open** and select the project directory.
   - Let Gradle sync complete.

4. **Build and run:**
   - Select your target device.
   - Click **Run** (▶).

---

## 🏗 Project Structure

```
app/
└── src/main/java/com/example/
    ├── MainActivity.kt              # Single-activity entry point
    ├── CameraUi.kt                  # All Compose screens & components
    ├── CameraViewModel.kt           # Business logic, capture pipeline, EXIF
    ├── CameraPreviewView.kt         # CameraX lifecycle + preview bindings
    ├── ui/theme/                    # Material 3 theme (Retro Slate)
    └── zoom/
        ├── LensProfile.kt           # Lens metadata model
        ├── LensCatalog.kt           # Runtime lens enumeration (Camera2)
        ├── FovMapper.kt             # Field-of-view → box scale math
        ├── ZoomBoxCalculator.kt     # Pure-math zoom-box rect computation
        ├── PreviewSessionManager.kt # CameraX preview-session lifecycle
        └── CaptureController.kt     # Image capture + file handling

app/src/test/java/com/example/zoom/
    └── FovMapperTest.kt             # Unit tests for FOV mapping
```

---

## 🧠 How the Zoom Box Works

1. The viewfinder shows the **full sensor field of view**.
2. When `boxScale < 1.0`, a semi-transparent black mask is drawn over the viewfinder with a **rounded-rect cutout** — this is the zoom box.
3. The zoom box aspect ratio is fixed at **1 : 1.35** (≈4:3 portrait).
4. On capture, `cropBitmapToZoomBox()` computes the pixel region matching the box and crops the full-resolution image accordingly.
5. The crop coordinates are derived from screen dimensions, the box fraction, and the sensor-to-screen scale, so the saved image pixel-for-pixel matches what was visible inside the box.

---

## 🧪 Testing

Run the unit tests from the terminal:

```bash
./gradlew testDebugUnitTest
```

Or in Android Studio: right-click `app/src/test/` → **Run Tests**.

---

## 🎨 Design Philosophy

- **Tactile, minimal UI** — high-contrast slate theme with amber accents inspired by vintage rangefinder cameras.
- **No viewfinder chrome** — the camera preview fills the screen edge-to-edge (with rounded corners) for maximum immersion.
- **Slider controls** — custom `SpectrumSlider` with haptic feedback, gradient tracks, and double-tap-to-reset.
- **Film-card gallery** — each photo is presented inside a card resembling a print from a vintage film roll, complete with camera model and exposure data.

---

## 📜 License

Distributed under the MIT License. See `LICENSE` for more information.

---

> [!TIP]
> For the best results, shoot in well-lit environments to make the film-grain effect and color grading really pop. Try framing subjects with the zoom box to create intentional, precisely composed shots.
