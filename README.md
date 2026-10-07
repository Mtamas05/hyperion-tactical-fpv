# Hyperion Tactical FPV Station & Autonomous Edge AI Tracker

![Kotlin](https://img.shields.io/badge/Kotlin-2.0-blue?logo=kotlin)
![Jetpack Compose](https://img.shields.io/badge/Jetpack_Compose-latest-4285F4?logo=android)
![TensorFlow Lite](https://img.shields.io/badge/TensorFlow_Lite-Edge_AI-FF6F00?logo=tensorflow)
![MediaCodec](https://img.shields.io/badge/MediaCodec-H.264_HW_Decoding-green?logo=android)
![UDP Networking](https://img.shields.io/badge/Networking-UDP/Socket-lightgrey)

## Overview

**Hyperion Tactical FPV** is a professional-grade, low-latency Android flight control and ground station application originally engineered for DJI Tello micro-UAVs. It serves as an autonomous FPV (First Person View) station, integrating real-time hardware-accelerated video decoding, an immersive glassmorphism tactical HUD, and an on-device Edge AI tracking pipeline. 

Designed for robust cyber-tactical operations, this project bridges the gap between raw UDP microcontroller interfaces and modern reactive Android architectures.

---

## Key Architectural Highlights

*   **Hardware-Accelerated Video Pipeline:**
    *   Zero-buffer UDP H.264 NAL unit parsing.
    *   Low-latency direct `Surface` rendering using Android's native `MediaCodec`.
    *   Live recording to standard `.mp4` files via `MediaMuxer` with dynamic SPS/PPS extraction and clean `finally` block teardowns to prevent file corruption.
*   **Edge AI ActiveTrack (Closed-Loop PID):**
    *   On-device object and human detection powered by **TensorFlow Lite (YOLO)**.
    *   Closed-loop Proportional-Integral-Derivative (PID) control algorithm converting visual bounding boxes into dynamic pitch, yaw, and throttle vectors. 
    *   Failsafe integral-reset mechanism to auto-hover (`stopAndHover`) immediately upon visual target loss.
*   **Cyber-Tactical HUD (Jetpack Compose):**
    *   Fully reactive, 60 FPS `Canvas`-drawn military-style interface.
    *   Features an Artificial Horizon, Pitch Ladder, Speed/Altitude tapes, and dynamic warning capsules.
    *   State-driven telemetry binding via Kotlin `StateFlow` ensures uncoupled UI updates.
*   **Resilient Networking & Hardware Interface:**
    *   Advanced Wi-Fi routing constraints: Forces network binding (`bindProcessToNetwork`) to the drone's Wi-Fi, bypassing mobile data dropouts.
    *   Asynchronous 15-second heartbeat/watchdog loops (`keep-alive`).
    *   Native Bluetooth gamepad integration with exponential (Expo) curve mapping and custom deadzone filtering.
*   **Flight Blackbox:**
    *   10 Hz telemetry logging streaming structured flight data directly to `.csv` format.

---

## System Architecture

```mermaid
flowchart TD
    subgraph Drone[UAV Hardware (Tello)]
        Cam(H.264 Camera)
        FC(Flight Controller)
    end

    subgraph Net[Networking Layer]
        UDP_Video[UDP Video Socket :11111]
        UDP_Cmd[UDP Command/Telemetry :8889 / :8890]
    end

    subgraph App[Android App]
        Decoder[TelloVideoDecoder\nNAL Parser -> MediaCodec]
        Muxer[TelloStreamRecorder\nMediaMuxer MP4]
        
        Session[TelloFlightSession\nCoroutines & Sockets]
        State[StateFlow\nTelemetry & Status]
        
        Yolo[YoloDetector\nTensorFlow Lite]
        PID[TelloActiveTracker\nPID Controller]
        
        HUD[Jetpack Compose HUD\nCanvas & Overlays]
    end

    Cam -->|Raw NAL Units| UDP_Video
    FC <-->|Commands & Telemetry| UDP_Cmd

    UDP_Video --> Decoder
    Decoder -->|Surface| HUD
    Decoder -->|Keyframes| Muxer

    UDP_Cmd <--> Session
    Session --> State
    State --> HUD

    Decoder -.->|Bitmaps| Yolo
    Yolo -->|Bounding Box| PID
    PID -->|RC Vectors| Session
```

---

## Tech Stack

| Component | Technology / Version |
| :--- | :--- |
| **Language** | Kotlin 2.0 |
| **UI Toolkit** | Jetpack Compose (BOM 2024+) |
| **Concurrency** | Kotlin Coroutines (`Dispatchers.IO`, `Flow`, `StateFlow`) |
| **Machine Learning** | TensorFlow Lite Task Vision |
| **Video Processing** | `MediaCodec` (HW Decoding), `MediaMuxer` (MP4 Multiplexing) |
| **Networking** | `java.net.DatagramSocket`, Android `ConnectivityManager` |
| **Architecture** | Unidirectional Data Flow, State-Driven UI |

---

## Project Setup & Installation

### Prerequisites
*   Android Studio Ladybug (or latest stable)
*   Android SDK 34+
*   JDK 17+

### Build Instructions

1.  **Clone the repository** (or open the project directory):
    ```bash
    git clone <repository_url>
    cd tellofpv
    ```

2.  **Build the APK using Gradle:**
    ```bash
    ./gradlew assembleDebug
    ```
    *On Windows:*
    ```powershell
    .\gradlew assembleDebug
    ```

3.  **Install on device:**
    ```bash
    ./gradlew installDebug
    ```

### Permissions Required
The application actively requests the following upon launch:
*   `CAMERA` (for Edge AI processing and pixel mapping)
*   `ACCESS_FINE_LOCATION` / `ACCESS_NETWORK_STATE` (for forced Wi-Fi binding to the UAV)
*   `BLUETOOTH_CONNECT` (for controller inputs)
*   `WRITE_EXTERNAL_STORAGE` (for saving MP4 videos and CSV Blackbox logs)

---

## Author & License

**Copyright © 2026 Hyperion Tech SRL**  
**Developed by Major Tamás**

This project is licensed under the [MIT License](LICENSE).

