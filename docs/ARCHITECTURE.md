# Architecture

## Design constraints

Lumen Sync is one Kotlin Multiplatform application, not three separate clients. `commonMain` owns the data model, invite format, REST adapter, state machine, and Compose UI. The Android and desktop source sets supply small platform adapters. Syncthing is a supervised child process and is the only synchronization engine.

This keeps the application monolithic from a product and build perspective while preserving native lifecycle behavior on each platform. No web runtime or Electron-style embedded browser is involved.

## State and configuration

Lumen Sync stores only UI-level settings: device name, local folder path, folder ID, onboarding state, and desktop autostart preference. Syncthing stores its peer configuration, certificates, index, and synchronization state under an app-private directory.

The REST server binds to a random loopback port. A cryptographically random API key is generated once and stored in app-private configuration. The embedded browser GUI is disabled/hardened, automatic core upgrades are disabled, and the API never binds to a LAN interface.

The versioned invite URI is JSON encoded as unpadded base64url:

```text
lumensync://invite/<payload>
```

Version 1 contains `version`, `folderId`, `deviceId`, and `deviceName`. Decoding is strict about supported versions and Syncthing's device-ID shape so future migrations can fail safely.

## Lifecycle

### Desktop

Only one process is allowed. The window may be hidden while a tray icon keeps the engine running. The process supervisor restarts an unexpectedly terminated core at most three times, then reports a visible failure instead of looping forever. Quit asks Syncthing to shut down through REST before using a bounded process termination fallback.

### Android

The UI can briefly start the core for onboarding and status. A real sync session is a user-started `dataSync` foreground service with a persistent notification. The service stops when the folder has remained idle and up to date, when no peer appears, after a fatal error, when the user stops it, or when Android reports the platform time limit.

There is intentionally no boot receiver, periodic worker, always-on VPN, accessibility service, or hidden daemon.

## Synchronization policy

Folders use Syncthing's `sendreceive` mode, watcher, and periodic rescan. The UI's states are derived from Syncthing's folder status and connection status:

- `Error` when the runtime, folder, or monitor reports a failure.
- `Scanning` while the core scans.
- `Syncing` while bytes remain or the folder is synchronizing.
- `Waiting for devices` when the folder is otherwise idle but no peer is connected.
- `Up to date` when the folder is idle, no bytes remain, and at least one peer is connected.

Deletion is part of the mirror and propagates. Users who need recovery should use an independent backup system.

## Native core supply chain

`SYNCTHING_VERSION` pins the core and `GO_VERSION` pins its compiler. `tools/build_syncthing.go` checks out the exact Syncthing tag, verifies it with `git describe`, disables built-in upgrades, and builds a native binary for the host desktop or Android arm64. Generated binaries and fetched source are ignored by Git. CI repeats the builds from source rather than accepting checked-in binaries.

The Android build passes `-checklinkname=0` because Syncthing v2.1.1's pinned `github.com/wlynxg/anet` dependency documents that requirement for Go 1.23 and newer. The exception is scoped to the Android linker invocation.
