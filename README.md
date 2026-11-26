# xDocUI

An Xposed module for the Android DocumentsUI file picker.

## Features

- Storage-picker customization on the existing scoped providers.
- Persistent file sorting using the sort dimension ID and direction.

## Requirements

- A framework implementing modern libxposed API 102.
- Android 11+ (minSdk 30); Android 17 is the build target, not a claim of verified runtime compatibility.
- Restart the scoped processes after installing an update. Automatic hot reload is disabled until hook replacement is implemented.
- After upgrading from v2.2.0, choose a sort option once to replace the old position-based preference.

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
