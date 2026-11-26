# xDocUI

A compact modern Xposed module for Android DocumentsUI.

## Features

- Existing storage-picker customization for Android/data and Android/obb.
- Persistent sorting through native DocumentsUI transitions; saves the resource label and direction rather than numeric IDs.
- Signature-checked direct and structural bindings, with lazy DexKit fallback for renamed sorting transitions.
- Version-aware DEX binding cache and guarded API 102 hook replacement on hot reload.
- Bounded diagnostic logs and stock fallback for unsupported sorting contracts.

## Requirements

- ARM64 Android device and Android 11+ (minSdk 30).
- Modern Xposed framework implementing libxposed API 102.
- Official release APKs contain only arm64-v8a native libraries.
- Restart scoped processes once when updating from a generation that rejects hot reload. Native-library reload behavior depends on the framework and runtime.
- After upgrading from the old position-based sorting implementation, select a sort option once.

## Build

JDK 21, Android SDK 37, AGP 9.5.3, Gradle 9.9.2 (checksum-pinned wrapper), libxposed 102.0.0 and DexKit 2.3.0.

```bash
./gradlew clean testDebugUnitTest lintDebug assembleRelease
python3 .github/scripts/verify_release.py app/build/outputs/apk/release
```

PR CI builds a test APK without production signing credentials. The release workflow runs automatically when a new `v*` tag is pushed on a commit from main. The tag sets the APK version automatically: `v2.3.0` produces versionName `2.3.0` and versionCode `20300` (`major * 10000 + minor * 100 + patch`). Minor and patch must be 0–99; Android versionCode limits apply. A `-rc` suffix selects a pre-release with the same numeric APK version. No Gradle version edit is needed. Local/PR builds default to `v2.3.0`; override with `-PreleaseTag=v2.4.0` if needed. It requires the existing signing secrets, verifies the single ARM64 APK and its signature, and publishes `xDocUI-v<version>-arm64-v8a.apk`. Debug builds are not ABI-restricted by this project.

To release the current version, after merging and verifying main:

```bash
git tag v2.3.0
git push origin v2.3.0
```

## Compatibility and design

Native sorting was confirmed by the maintainer on OnePlus 15 / ColorOS 17. The DexKit fallback checks literal fingerprints, method signatures, uniqueness, declaring class and call relationships; renamed accessors/fields or changed literals may still require updates. The cache includes package/version/update time, OS fingerprint, resolver revision and SHA-256 of base/split DEX contents, and validates the reflection contract on hits. API 102 hot reload replaces matching handles and removes obsolete ones; a restart may be needed if JNI cannot reload. Full native fallback and hot reload require device testing; future system updates are not guaranteed compatible.

Unit tests cover semantic sorting, structural binding and renamed/overloaded transitions. CI verifies the packaged ABI, native DexKit library and Xposed entry metadata.
