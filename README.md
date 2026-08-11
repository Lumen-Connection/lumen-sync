# Lumen Sync

Lumen Sync is a small Windows, Linux, and Android app for synchronizing one ordinary folder between your own devices. The UI is built once with Kotlin and Compose Multiplatform; a pinned, bundled Syncthing core does the indexing, transfer, discovery, encryption, conflict handling, and change detection.

There is no Lumen Sync account, cloud service, or central database. Devices communicate peer-to-peer using Syncthing's transport and discovery services.

## What works

- Create one sync space or join it by scanning/pasting an invite.
- Explicitly approve every joining device.
- Display device connectivity, progress, errors, and the last successful sync.
- Keep syncing continuously in the Windows/Linux tray.
- Run a user-started Android foreground session until up to date.
- Start a manual rescan and copy/show a QR invite.
- Optionally start the desktop app when the user signs in.

The folder is a **two-way mirror**. Edits and deletions made on any device propagate to the others. Lumen Sync deliberately does not add backups or file versioning in this first release.

## Architecture

```text
Compose UI (shared)
        |
   SyncEngine (shared state machine)
        |
Syncthing REST client on 127.0.0.1 + random API key
        |
platform process supervisor
        |
 pinned Syncthing v2.1.1 core <---- encrypted peer-to-peer links ----> other devices
```

Syncthing's index is the last-known synced state. Lumen Sync never walks the folder or invents a second synchronization database. Platform-specific code is limited to process lifecycle, settings, folder selection, QR/clipboard integration, desktop autostart, and Android's foreground service.

See [the architecture notes](docs/ARCHITECTURE.md) for lifecycle and security details.

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

Without those variables, `assembleRelease` intentionally produces an unsigned APK suitable for a downstream store such as F-Droid to sign.

## Test

```bash
./gradlew :composeApp:desktopTest :composeApp:compileDebugKotlinAndroid
./gradlew :composeApp:buildSyncthingDesktop
pwsh ./tools/integration_smoke.ps1
```

The unit tests cover invite compatibility and the sync-status policy. The integration smoke starts two isolated cores and verifies that create, modify, and delete operations propagate. CI compiles both application targets and independently builds the pinned native core on Windows, Linux, and Android.

## Pair devices

1. On the first device, choose **Create a sync space**, select a folder, and show its invite.
2. On the new device, choose **Join a sync space**, select its local folder, then scan or paste the invite.
3. Return to the first device and approve the pending device.
4. Keep both devices running until each reports **Up to date**.

An invite contains only the folder ID, inviter's Syncthing device ID, and display name. It does not contain the local REST API key or any account secret. Possession of an invite is not enough to join: the inviter must approve the pending device.

## Android behavior

Android sessions are manual by design. Press **Sync now** to start a visible data-sync foreground service. It stops after the folder settles, after a no-peer timeout, on an error, or when Android applies its foreground-service time limit. This avoids a permanent background daemon and keeps power use predictable.

Synchronizing an arbitrary user-selected folder requires broad file access (`MANAGE_EXTERNAL_STORAGE`) on modern Android. That permission is why the direct/F-Droid distribution path is the primary target rather than Google Play. Lumen Sync accesses only the chosen folder and stores its own configuration in app-private storage. See [Privacy](docs/PRIVACY.md).

## Current scope

- One folder and one sync space per installation.
- Two-way `sendreceive` mode only.
- Android arm64 only.
- No remote administration UI, browser GUI, accounts, telemetry, analytics, or crash uploads.
- Syncthing's normal conflict copies are preserved, but file versioning and backups are not enabled.

## License

Lumen Sync is Apache-2.0 licensed. The bundled Syncthing core remains MPL-2.0 licensed; see [Third-party notices](THIRD_PARTY_NOTICES.md) and [LICENSES](LICENSES/README.md).
