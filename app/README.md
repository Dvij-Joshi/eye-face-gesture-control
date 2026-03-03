# Eye Control - Accessibility Android App

A hands-free Android accessibility application that allows users to control their phone using facial gestures and eye movements. Built with MediaPipe Face Mesh, CameraX, and Android Accessibility Service.

## Features

- **Hands-Free Cursor Control**: Move the cursor by moving your head/nose.
- **Gesture Selection**:
  - **Click**: Quick squint (blink both eyes).
  - **Long Press**: Hold squint for >1 second.
  - **Scroll**: Raise/Lower eyebrows.
  - **Home**: Wink Right.
  - **Back**: Wink Left.
  - **Recent Apps**: Double blink.
- **Accessibility Service**: Performs system-wide clicks and navigation.
- **Customizable**: Adjust sensitivity and smoothing in Settings.
- **Calibration**: 5-step calibration process for accurate tracking.

## Architecture

- **MVVM Pattern**: ViewModels (not yet fully implemented), Repositories (planned).
- **Service-Based**:
  - `FaceTrackingService`: Foreground service running CameraX and MediaPipe.
  - `EyeControlAccessibilityService`: Handles system interactions and UI overlay.
  - `GestureEventBus`: Singleton using Kotlin Flows for inter-service communication.
- **Tech Stack**:
  - Kotlin
  - CameraX
  - MediaPipe Tasks Vision (Face Landmarker)
  - Android Accessibility Service
  - Coroutines & Flow
  - ViewBinding

## Setup & Installation

1.  **Clone the repository**.
2.  **Open in Android Studio** (Hedgehog or newer recommended).
3.  **Sync Gradle**.
4.  **Download Model**:
    The app requires the MediaPipe Face Landmarker model. It should be automatically downloaded to `app/src/main/assets/face_landmarker.task`.
    If missing, download from: [MediaPipe Models](https://storage.googleapis.com/mediapipe-models/face_landmarker/face_landmarker/float16/latest/face_landmarker.task)
5.  **Build & Run** on a physical device (Android 8.0+).

## Usage Guide

1.  **Onboarding**: Grant Camera and Overlay permissions. Enable "Eye Control" in Accessibility Settings.
2.  **Calibration**: Go to the Calibration screen. Follow the instructions to look at the center and edges of the screen.
3.  **Start Service**: Click "Start Service" in the main app.
4.  **Control**: A cursor will appear. Move your head to control it. Use gestures to click/navigate.

## Troubleshooting

-   **Cursor not moving?** Ensure the Accessibility Service is enabled and the background service is running.
-   **Laggy?** Adjust smoothing in Settings.
-   **False positives?** Try calibrating again or adjusting sensitivity.
