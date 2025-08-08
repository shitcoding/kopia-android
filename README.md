# Kopia Android

This is a small Android app that runs the Kopia server locally on your phone and shows Kopia’s Web UI in a WebView. It’s meant for quick browsing of snapshots and restoring files on-device, without needing a separate computer.

I built this to make digging into old backups on Android less painful. If it helps you too—great.

## Highlights

- Bundled Kopia (v0.21.1, ARM64 PIE) as `libkopia.so` and executed from `nativeLibraryDir`
- Runs Kopia server on `127.0.0.1:51515` and loads the UI inside the app
- Simple local WebView auth: username `admin`, password `admin`
- Connect to an existing filesystem repository or create a new one
- Browse snapshots, preview contents, and restore files
- Integrates with Android’s Storage Access Framework (SAF) where needed

## Requirements

- Android 8.0+ (API 26+)
- ARM64 device or emulator
- On Android 13+, allow Notifications (foreground service)

## Quick start (APK)

1) Install the APK (Debug or Release).

2) First launch starts the Kopia server and opens the Web UI.
   - Local UI credentials: `admin` / `admin`.

3) Connect or create a repository:
   - New repo: create a filesystem repo in app storage via the UI.
   - Existing repo: place your repo under app storage or select it via SAF. Make sure the password matches.

4) Restore files:
   - Select files in the UI, choose a destination.
   - Common public path: `/storage/emulated/0/Download` (aka `/sdcard/Download`).
   - On newer Android versions, a system picker (SAF) may appear for write access.

Tips:
- If your repo came from macOS, remove AppleDouble files (`._*`) before connecting.
- Password must be correct. If you manually copied a repo into app storage, ensure you use the same password when opening it.

## Build from source

### Prereqs
- Android Studio (or just Gradle) with SDK 33
- Android NDK for bundling the Kopia binary

### How the binary is bundled
- Kopia is built from source as a static ARM64 PIE and shipped as `app/src/main/jniLibs/arm64-v8a/libkopia.so`.
- The app prefers running this binary from `nativeLibraryDir` (SELinux-friendly) so it can execute on modern Android.

### Build

 ```bash
 # Debug build
 ./gradlew assembleDebug

 # Release build (requires signing setup)
 ./gradlew assembleRelease
 ```

Install the debug APK:

 ```bash
 adb install -r app/build/outputs/apk/debug/app-debug.apk
 ```

### Release / signing

1) Configure signing in `keystore.properties`.
2) Optionally use `./publish_release.sh` to help with packaging.

See `docs/publishing_guide.md` for more details.

## Usage

1. Launch the app — the server starts and the UI loads.
2. Use the menu for settings and diagnostics.
3. UI credentials for local access are `admin`/`admin`.

### Diagnostics

- Dev builds can capture logs/screenshots during testing under `tmp_screenshots/`.
- Handy for verifying repos or server startup.

### Creating a Repository

1. Go to Settings
2. Tap "Select Repository Location"
3. Choose a directory on your device
4. Tap "Create Repository"

### Connecting to a Repository

1. Go to Settings
2. Tap "Select Repository Location"
3. Choose the directory containing your Kopia repository
4. The app will automatically connect to the repository

### Restoring Files

1. Browse snapshots in the Kopia Web UI
2. Select files/folders to restore
3. Choose the restore location
   - Common public path: `/storage/emulated/0/Download` (aka `/sdcard/Download`)
   - On Android 10+, SAF may prompt to grant write access

## Architecture

- Java/Kotlin Android application with Coroutines for asynchronous operations
- Embedded Kopia binary (ARM64 PIE) as `libkopia.so`
- Foreground service for running the Kopia server
- WebView for displaying the Kopia Web UI
- Storage Access Framework for file system access
- Utility components:
  - **OptimizationUtility**: Dynamically adjusts WebView settings and server parameters based on device capabilities
  - **ErrorHandlingUtility**: Provides centralized error logging, display, and recovery strategies
  - **PerformanceMonitor**: Tracks memory, storage, and CPU usage for diagnostics
  - **TestUtility**: Validates Kopia functionality through scripted tests
  - **WorkflowValidator**: Ensures all user workflows function correctly across Android versions

## Troubleshooting

- Server not starting? On Android 13+, ensure notification permission is granted.
- Repo won’t open? Double-check the password and make sure the repo path is correct (absolute if using CLI).
- Coming from macOS? Remove `._*` AppleDouble files from the repo before connecting.
- UI shows 401/404? Use the in-app reload; the app clears stale cache and sets basic auth automatically.

## License

This project is licensed under the Apache License 2.0 - see the LICENSE file for details.

## Acknowledgments

- [Kopia](https://kopia.io) - The backup tool embedded in this application
