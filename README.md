

# 📸 ZoomBox Camera

**ZoomBox Camera** is a professional, retro-themed camera application built for Android. It combines the aesthetic charm of vintage film photography with modern mobile camera technology. 

Featuring a unique "Zoom Box" framing interface, it simulates the experience of shooting with classic prime lenses (35mm, 50mm, 85mm) while applying real-time retro filters, exposure adjustments, and analog-style date stamps.

---

[![Screenshot-20260718-192853.png](https://i.postimg.cc/JzLvjMzg/Screenshot-20260718-192853.png)](https://postimg.cc/qz11TfnX) [![Screenshot-20260718-192912.png](https://i.postimg.cc/0ysF7PyV/Screenshot-20260718-192912.png)](https://postimg.cc/ZCwwSk3N) 


## ✨ Key Features

*   **Cinematic Zoom Box**: A signature 4:3 framing overlay that guides your composition and defines the final crop area.
*   **Lens Preset Simulations**:
    *   **35mm**: Wide-angle "street photography" field of view.
    *   **50mm**: The classic "nifty fifty" natural perspective.
    *   **85mm**: Tight portrait framing with compressed depth.
*   **Manual Exposure & Tint**: Tactile sliders to adjust exposure compensation (-3 to +3) and color temperature (Warm Amber to Cool Teal).
*   **Retro Film Processing**:
    *   Real-time warming/cooling tints.
    *   Dynamic vignette effect for focused subjects.
    *   **Analog Date Stamp**: Iconic orange-glow timestamp (e.g., JUL 16 2026) embedded directly in the shot.
*   **Minimalist Interface**: High-contrast, slate-dark UI designed for professional focus and ease of use.
*   **Gallery Integration**: Instantly view and manage your captured film-style photos.

---

## 🛠 Tech Stack

*   **Jetpack Compose**: 100% declarative UI for a modern, fluid user experience.
*   **CameraX**: Robust and reliable camera integration for preview, image capture, and lens control.
*   **MVVM Architecture**: Clean separation of concerns for maintainability and scalability.
*   **Kotlin Coroutines & Flow**: Efficient asynchronous data handling and state management.
*   **Coil**: Optimized image loading for the gallery view.
*   **Material 3**: Utilizing modern design components with a custom "Retro Slate" theme.

---

## 🚀 Getting Started

### Prerequisites

*   **Android Studio**: Ladybug (2024.2.1) or newer recommended.
*   **Android Device**: Running Android 7.0 (API 24) or higher.

### Setup Instructions

1.  **Clone the Repository**:
    ```bash
    git clone https://github.com/your-repo/zoombox-camera.git
    cd zoombox-camera
    ```

2.  **Configure API Keys**:
    This app uses Gemini AI for advanced features. Create a `.env` file in the root directory and add your API key:
    ```env
    GEMINI_API_KEY=your_actual_key_here
    ```
    *(See `.env.example` for reference)*

3.  **Open in Android Studio**:
    *   Select **File > Open** and choose the project directory.
    *   Allow Android Studio to fix any incompatibilities as it imports the project.

4.  **Build and Run**:
    *   Select your device/emulator.
    *   Click the **Run** (green triangle) button.

---

## 🏗 Project Structure

```text
app/
├── src/main/java/com/example/
│   ├── ui/theme/         # Custom Retro Slate theme definitions
│   ├── CameraUi.kt       # Main UI entry point and components
│   ├── CameraViewModel.kt # Business logic and state management
│   ├── CameraPreviewView.kt # CameraX integration and viewfinder logic
```

---

## 📜 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

> [!TIP]
> For the best "ZoomBox" experience, try shooting in well-lit environments to capture the fine details of the film grain and color tints.
