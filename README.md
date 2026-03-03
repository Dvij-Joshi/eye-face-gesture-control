# Eye and Face Gesture Phone Control

An Android accessibility application that allows device navigation and control using facial gestures and head movements. Built via MediaPipe's Face Landmarker integration, this application translates physical movements into on-screen cursor actions and clicks.

## Overview

This project provides an alternative, hands-free input method for Android devices, designed specifically to assist individuals with motor disabilities, paralysis, or other physical impairments. By leveraging the device's front-facing camera, it maps head tilt (yaw/pitch) to cursor movement and specific facial blendshapes to system-level actions like clicking and scrolling, enabling full device control without requiring physical touch.

Currently, this represents an early V1/V2 implementation. The application is functional but actively being refined. Users may encounter edge-case bugs or calibration inconsistencies depending on lighting and camera hardware. We are aware there is significant room for improvement, and optimizations to the smoothing algorithms and gesture recognition state machines are ongoing.

## Features

- **Head Tracking Cursor**: Uses the facial transformation matrix to map head yaw and pitch directly to screen coordinates.
- **Wink to Click**: Left and right winks map to corresponding screen clicks.
- **Brow Scrolling**: Eyebrow raises trigger upward scrolling, while furrowing/squinting triggers downward scrolling.
- **Gesture Exclusion**: Active winking temporarily suppresses brow-based scroll detection to prevent cross-triggering.
- **Double Smoothing**: Implements a 1D Kalman filter combined with an Exponential Moving Average (EMA) layer to stabilize cursor movement and reduce sensor micro-jitter.
- **Distance Adaptive Sensitivity**: Adjusts movement sensitivity dynamically based on the user's distance from the camera (face scale).
- **Custom Calibration Wizard**: A 5-step interactive calibration process to establish neutral baselines and gesture thresholds tailored to the individual user.

## Requirements

- Android device running Android 7.0 (API level 24) or higher.
- Front-facing camera.
- The following permissions are required for operation:
  - Camera (for face tracking)
  - Display over other apps (for the cursor overlay)
  - Accessibility Service (to perform clicks and scrolls on the system)

## Build Instructions

This project uses Gradle. To build and install a debug APK via the command line, run:

```bash
./gradlew assembleDebug
```

Alternatively, you can open the project in Android Studio and run it directly on a connected device or emulator.

## Known Limitations

- High resource usage during continuous background operation.
- Cursor accuracy decreases under poor lighting conditions.
- System-level accessibility clicks may occasionally be blocked by certain secure Android UI elements or overlays.

As we continue development, the primary focus will be on reducing battery consumption, improving the robustness of the gesture state machines, and refining the user calibration experience.
