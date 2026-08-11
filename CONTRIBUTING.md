# Contributing

Use JDK 17 and format Kotlin with the official style. Keep synchronization behavior in `commonMain`; add platform code only for APIs that genuinely differ. The app intentionally supports one folder, one sync mode, and no server-side account system, so scope-expanding changes should start with a design discussion.

Before submitting a change, run:

```bash
./gradlew :composeApp:desktopTest :composeApp:compileDebugKotlinAndroid
```

Changes to the embedded core must update `SYNCTHING_VERSION`, review upstream release/security notes, rebuild all targets, and update `THIRD_PARTY_NOTICES.md` if licensing or provenance changes. Do not commit generated native binaries, SDKs, keys, or local Syncthing configuration.
