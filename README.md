# Soundpeats Mini Pro HS Controller

An open-source Android application for connecting and controlling Soundpeats Mini Pro HS earbuds. Built with Kotlin and Jetpack Compose, this app communicates directly via Bluetooth SPP (Serial Port Profile) to provide features beyond the stock application, including a fully customizable 7-band Parametric EQ (PEQ) for the WuQi WQ7033 chipset.

## ✨ Features

* **Automatic Connection:** Skips the manual device selection process by directly polling and verifying Bluetooth connections to the Soundpeats earbuds on launch.
* **Real-time State Synchronization:** Instantly queries and syncs the hardware's active state (ANC mode, Game mode, battery level) upon connection so the UI is always accurate.
* **Advanced Parametric EQ (PEQ):** 
  * Full 7-band configuration with precise 16-bit signed gain calculations.
  * Direct 17-byte command packet transmission per frequency band (0x71-0x77).
  * Automatically handles sequence execution, master EQ switch, and commit commands to prevent memory buffer overflows or earbud reboots.
* **Device Control Toggles:** Full read/write functionality for:
  * ANC (Active Noise Cancellation) Modes: ANC On, ANC Off, Transparency
  * Game Mode (Low Latency)
  * Touch Controls (Enable/Disable)
* **Modern UI:** Built entirely with Jetpack Compose featuring a sleek, Material 3-inspired design.

## 🛠️ Technical Details

* **Language:** Kotlin
* **UI Framework:** Jetpack Compose (Material 3)
* **Minimum SDK:** 26 (Android 8.0)
* **Target SDK:** 34 (Android 14)
* **Communication Protocol:** Bluetooth RFCOMM / SPP (Hex packet transmission)
* **Target Chipset:** WuQi WQ7033 (Found in Soundpeats Mini Pro HS)

## 🚀 Getting Started

### Prerequisites
* Android Studio (latest recommended)
* An Android device running Android 8.0+
* Soundpeats Mini Pro HS earbuds (paired to your device)

### Building the Project
1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/soundpeats-controller.git
   ```
2. Open the project in Android Studio.
3. Sync the Gradle files.
4. Build and run the app on your physical Android device. (Bluetooth features cannot be tested on an emulator).

### Permissions
The app requires the following permissions to discover, connect, and communicate with your earbuds:
* `BLUETOOTH` and `BLUETOOTH_ADMIN` (Legacy)
* `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` (Android 12+)

## ⚠️ Disclaimer

This is an unofficial, open-source application and is not affiliated with, maintained, authorized, endorsed, or sponsored by Soundpeats. It uses reverse-engineered Bluetooth commands specific to the WuQi chipset. Use it at your own risk. Incorrect hex packet sequences (especially related to PEQ) can theoretically cause temporary earbud reboots.

## 🤝 Contributing

Contributions, issues, and feature requests are welcome! If you have different Soundpeats models (e.g., Air3, Engine4) and want to help expand compatibility, please feel free to submit a pull request with the mapped hex commands for those devices.
