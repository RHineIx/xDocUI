# xDocUI

An Xposed module for the Android DocumentsUI file picker.

## Features

- Storage-picker customization on the existing scoped providers.
- Persistent file sorting through DocumentsUI's native sorting transitions, with a resource-entry label and direction rather than a numeric ID or array position.

## Requirements

- A framework implementing modern libxposed API 102.
- Android 11+ (minSdk 30); Android 17 is the build target, not a claim of verified runtime compatibility.
- Restart the scoped processes after installing an update. Automatic hot reload is disabled until hook replacement is implemented.
- After upgrading from v2.2.0 (or an earlier 2.3.0 test build), choose a sort option once to replace the old numeric preference. If a saved label is unavailable, hidden, ambiguous or incompatible, the stock default is retained.

## Build

- JDK 21
- AGP 9.5.3 (built-in Kotlin)
- Gradle 9.9.2 (wrapper distribution checksum pinned)
- Android SDK platform 37
- `io.github.libxposed:api:102.0.0` (compileOnly)

```bash
./gradlew clean assembleRelease
```

The pull-request CI builds and uploads a test APK without release credentials.
Production publication requires the existing release signing secrets.

## Compatibility notes

The Android 17 Files APK supplied for inspection exposes `sortByUser(int,int)`, `sortOnDefault()`, `sortByDimension(SortDimension,int)` and the expected accessor methods. Both cursor overloads and provider query sorting use the model's state. This is a signature-checked reflection implementation, not a general obfuscation-resistant resolver. Runtime testing is still required.
