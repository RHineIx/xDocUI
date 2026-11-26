# xDocUI

An Xposed module for the Android DocumentsUI file picker.

## Features

- Storage-picker customization on the existing scoped providers.
- Persistent file sorting through DocumentsUI's native sorting transitions, with a resource-entry label and direction rather than a numeric ID or array position.

## Requirements

- A framework implementing modern libxposed API 102.
- Android 11+ (minSdk 30); Android 17 is the build target, not a claim of verified runtime compatibility.
- Restart scoped processes once when upgrading from an older generation that rejects hot reload. API 102 reload then replays target initialization and replaces matching hook handles; runtime support is framework-dependent.
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

The Android 17 Files APK supplied for inspection exposes `sortByUser(int,int)`, `sortOnDefault()`, `sortByDimension(SortDimension,int)` and the expected accessor methods. Both cursor overloads and provider query sorting use the model's state. Direct reflection is preferred; the DexKit fallback can recover renamed sorting transitions with retained literal fingerprints and compatible accessors. The v2.3.0 native sorting flow was confirmed by the maintainer on OnePlus 15 / ColorOS 17. Subsequent changes still require runtime testing.

## Diagnostics and tests

The module emits bounded, once-per-process messages for context initialization, preference save/restore and fallback conditions. No document names or paths are logged by these diagnostics. The pure sort-selection policy is covered by JVM tests for resource-ID changes, duplicate/missing/hidden labels, capability masks and invalid input. Run `./gradlew testDebugUnitTest`.

## Sorting hook resilience

- Resolve the Application class from package metadata, with the known DocumentsUI name as the missing-metadata fallback.
- Resolve the dimension type from the model's actual return type and validate all methods before hooking.
- If the known model class is absent, require two or more matching field-type anchors in State, DirectoryLoader and SortListFragment. Conflicting or incomplete anchors leave stock sorting untouched. This does not handle arbitrary method/field obfuscation.
- Install save/restore as a pair with API 102 hook IDs. If registration fails, both callbacks remain inactive and any installed handles are removed. A failed deoptimization is reported separately, not treated as proof of a working hook.
- Four additional JVM tests cover class-name independence, anchor agreement and rejection of incompatible bindings. Storage hooks and scope are unchanged.

## DexKit and hot reload

- DexKit 2.3.0 is loaded only when direct/structural sorting resolution fails and an application context is available. Three APK-derived literal fingerprints plus owner, signature, uniqueness and call-graph checks validate renamed save/default/transition methods. Renamed accessor methods and fields are not yet covered; changes in literals or inlining can require new fingerprints.
- The cache holds only validated class/method names, scoped to package, version, update time, OS fingerprint, resolver revision and SHA-256 of DEX content from base and split APKs. It is consulted only on the fallback path and the entire reflection contract is checked again on a hit.
- API 102 reload passes host-owned objects only, rebinds matching hook handles by stable ID, and removes obsolete handles. Native bridges are closed synchronously. Native library reloading is runtime-dependent; if DexKit cannot load after a reload/cache miss, sorting falls back to stock behavior. A restart can restore it. No hot reload claim is made until device testing.
- Storage behavior and package scope are unchanged; IDs are assigned to existing storage hooks for generation replacement. The four new JVM tests cover renamed/overloaded transition binding and invalid cache mappings, not native scanning or on-device hot reload.
