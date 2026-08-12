<div align="center">
# Lumen Sync 🔁

**A multi-platform peer-to-peer folder sync app built with Kotlin and Compose Multiplatform.**

![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)
![Go](https://img.shields.io/badge/Go-00ADD8?logo=go&logoColor=white)

![Windows](https://img.shields.io/badge/Windows-0078D4?logo=windows&logoColor=white)
![Linux](https://img.shields.io/badge/Linux-FCC624?logo=linux&logoColor=black)
![Android](https://img.shields.io/badge/Android-3DDC84?logo=android&logoColor=white)

</div>

## About

**Lumen Sync** is a small, universally compatible file share and sync app.
Anything you put here shows up there, and vice-versa. It's fully open and
peer-to-peer with end-to-end encryption and does not require any accounts.

Part of the [Lumen Connection](https://lumenconnection.com.br) family.

## Build

Prerequisites:

- JDK 17
- Go 1.25.0 or a Go release that supports automatic `GOTOOLCHAIN` selection
- Android SDK 36 and an Android NDK for Android builds
- Git and a network connection the first time the pinned Syncthing source is fetched

The Gradle wrapper downloads JVM dependencies. Native build tasks read `SYNCTHING_VERSION` and `GO_VERSION`, select that Go toolchain, fetch the exact Git tag into `.cache/syncthing`, verify the checkout, and build it without automatic upgrades. Set `SYNCTHING_SOURCE_DIR` to use an already checked-out, matching Syncthing source tree.

On Windows:

```powershell
$env:JAVA_HOME = "C:\path\to\jdk-17"
.\gradlew.bat :composeApp:run
.\gradlew.bat :composeApp:packageReleaseMsi
.\gradlew.bat :composeApp:assembleDebug
```

On Linux:

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :composeApp:run
./gradlew :composeApp:packageReleaseDeb
./gradlew :composeApp:assembleDebug
```

The Android native core currently targets `arm64-v8a` and requires Android 8.0 or newer. The APK is under `composeApp/build/outputs/apk`; desktop installers are under `composeApp/build/compose/binaries`.

For a signed Android release, set:

```text
LUMEN_SYNC_KEYSTORE=/absolute/path/to/release.jks
LUMEN_SYNC_KEYSTORE_PASSWORD=...
LUMEN_SYNC_KEY_ALIAS=...
LUMEN_SYNC_KEY_PASSWORD=...
```

## Test

```bash
./gradlew :composeApp:desktopTest :composeApp:compileDebugKotlinAndroid
./gradlew :composeApp:buildSyncthingDesktop
pwsh ./tools/integration_smoke.ps1
```

Unit tests cover invite compatibility and the sync-status policy.