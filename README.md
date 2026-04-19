# xDocUI

A simple and modern Xposed module built to enhance the default Android file picker (DocumentsUI).

## Features
* **Bypass Restrictions:** Unlocks access to `Android/data` and `Android/obb` folders within the native file picker.
* **Persistent Sorting:** Forces the system's file picker to remember and apply your preferred file sorting method globally.

## Requirements
* A modern Xposed framework manager supporting **API 101**.
* Android 12 - Android 16.

## Dependencies
* `io.github.libxposed:api:101.0.0` (compileOnly)

## Build Instructions
* **JDK:** 21
* **Kotlin:** 2.1
* **Gradle:** 8.9+

Run the following command to build the release APK:
```bash
./gradlew assembleRelease
```